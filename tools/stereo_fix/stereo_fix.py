"""Align, colour-match and frame wide/ultrawide pairs for stereo viewing.

Approach (as in Stereo Photo Maker's auto-alignment):
  1. Match SIFT features between the main (wide) and ultrawide photos.
  2. Fit a similarity transform (scale, rotation, shift) that maps the ultrawide onto the wide.
     Vertical position, rotation and scale are solved from vertical errors only, so the natural
     horizontal parallax (the 3D information) is preserved.
  3. Place the stereo window: shift horizontally so the nearest content sits at the screen plane
     and everything else appears behind it.
  4. Match the ultrawide's colour/brightness to the wide (per-channel histogram matching in Lab).
  5. Crop both views to the area they share, then write left/right, parallel, cross-eyed and
     anaglyph images plus an HTML viewer.

Usage:
  python stereo_fix.py INPUT_DIR OUTPUT_DIR [--swap] [--window 98] [--work 1600]
INPUT_DIR holds <stem>_wide.jpg / <stem>_ultrawide.jpg pairs from the app.
"""
import argparse, base64, json, pathlib, sys
import cv2
import numpy as np


# ------------------------------------------------------------------ matching
def detect_matches(ref, mov, work):
    """SIFT matches between ref and mov; returns full-resolution point arrays (N,2)."""
    def prep(img):
        s = work / img.shape[1]
        g = cv2.cvtColor(cv2.resize(img, None, fx=s, fy=s, interpolation=cv2.INTER_AREA), cv2.COLOR_BGR2GRAY)
        g = cv2.createCLAHE(clipLimit=2.0, tileGridSize=(8, 8)).apply(g)
        return g, s
    g_r, s_r = prep(ref)
    g_m, s_m = prep(mov)
    sift = cv2.SIFT_create(nfeatures=12000)
    k_r, d_r = sift.detectAndCompute(g_r, None)
    k_m, d_m = sift.detectAndCompute(g_m, None)
    if d_r is None or d_m is None:
        return np.empty((0, 2)), np.empty((0, 2))
    matcher = cv2.FlannBasedMatcher(dict(algorithm=1, trees=5), dict(checks=64))
    pr, pm = [], []
    for pair in matcher.knnMatch(d_m, d_r, k=2):
        if len(pair) == 2 and pair[0].distance < 0.72 * pair[1].distance:
            m = pair[0]
            pm.append(k_m[m.queryIdx].pt)
            pr.append(k_r[m.trainIdx].pt)
    return np.float64(pr) / s_r, np.float64(pm) / s_m


# ------------------------------------------------------------------ model
# The ultrawide has noticeably different radial distortion from the main lens. We model it with
# OpenCV's k1 term on normalised coordinates n = (p - c) / R  (c = image centre, R = half diagonal):
#     n_distorted = n_corrected * (1 + k * |n_corrected|^2)
# so the warp can be done with cv2.initUndistortRectifyMap (same call the Android app uses).

def radial(p, k, c, R):
    """Distorted ultrawide pixels -> corrected pixels (fixed-point inverse of the k1 model)."""
    nd = (p - c) / R
    nu = nd.copy()
    for _ in range(10):
        nu = nd / (1.0 + k * (nu[:, 0] ** 2 + nu[:, 1] ** 2))[:, None]
    return c + nu * R


def _vertical_fit(pr, pm, use):
    """IRLS on  y_ref = b*x + a*y + ty  (linear in a, b, ty)."""
    for _ in range(8):
        X = np.column_stack([pm[use, 0], pm[use, 1], np.ones(use.sum())])
        sol, *_ = np.linalg.lstsq(X, pr[use, 1], rcond=None)
        b, a, ty = sol
        res = pr[:, 1] - (b * pm[:, 0] + a * pm[:, 1] + ty)
        mad = np.median(np.abs(res[use] - np.median(res[use]))) * 1.4826 + 1e-6
        use = np.abs(res) < max(3.0 * mad, 1.0)
    return a, b, ty, use, res


def fit_alignment(pr, pm, mov_shape, fit_distortion=True):
    """Distortion + similarity mov->ref, refined on vertical error only.
    Returns dict(a, b, ty, k, c, R), inlier mask, vertical residuals, corrected mov points."""
    w = mov_shape[1]
    M, inl = cv2.estimateAffinePartial2D(pm, pr, method=cv2.RANSAC,
                                         ransacReprojThreshold=0.03 * w, maxIters=5000, confidence=0.999)
    if M is None:
        raise RuntimeError("could not fit an initial transform")
    return refine_alignment(pr, pm, inl.ravel().astype(bool), mov_shape, fit_distortion)


def refine_alignment(pr, pm, base, mov_shape, fit_distortion=True):
    """Deterministic part of the fit (mirrored by StereoAlignMath.kt in the Android app)."""
    h, w = mov_shape[:2]
    c = np.array([w / 2.0, h / 2.0]); R = float(np.hypot(w, h) / 2)

    def score(k):
        pc = radial(pm, k, c, R)
        a, b, ty, use, res = _vertical_fit(pr, pc, base.copy())
        return np.median(np.abs(res[use])), (a, b, ty, use, res, pc)

    k = 0.0
    if fit_distortion:
        k = best_k(score)
    _, (a, b, ty, use, res, pc) = score(k)
    return dict(a=a, b=b, ty=ty, k=k, c=c, R=R), use, res, pc


K_COARSE = [round(-0.15 + 0.0125 * i, 4) for i in range(25)]   # -0.15 .. +0.15


def best_k(score):
    """Coarse grid then a fine step around the best value (same schedule as the Android app)."""
    best = min(K_COARSE, key=lambda k: score(k)[0])
    fine = [best + 0.00125 * (i - 10) for i in range(21)]
    return min(fine, key=lambda k: score(k)[0])


def warp_mov(mov, model, tx, out_shape):
    """Render mov into ref geometry with one initUndistortRectifyMap + remap.
    Output pixel = A * (c + R * n_corrected) + t, so the 'new camera matrix' is R*A with offset A*c + t."""
    a, b, ty, k, c, R = (model[n] for n in ("a", "b", "ty", "k", "c", "R"))
    H, W = out_shape[:2]
    K = np.float64([[R, 0, c[0]], [0, R, c[1]], [0, 0, 1]])
    newK = np.float64([[R * a, -R * b, a * c[0] - b * c[1] + tx],
                       [R * b, R * a, b * c[0] + a * c[1] + ty],
                       [0, 0, 1]])
    dist = np.float64([k, 0, 0, 0, 0])
    mapx, mapy = cv2.initUndistortRectifyMap(K, dist, np.eye(3), newK, (W, H), cv2.CV_32FC1)
    img = cv2.remap(mov, mapx, mapy, interpolation=cv2.INTER_LANCZOS4, borderMode=cv2.BORDER_CONSTANT)
    valid = (mapx >= 1) & (mapx < mov.shape[1] - 2) & (mapy >= 1) & (mapy < mov.shape[0] - 2)
    return img, valid


def horizontal_offsets(pr, pm, a, b):
    """x_ref - (a*x_mov - b*y_mov): shift plus per-point parallax."""
    return pr[:, 0] - (a * pm[:, 0] - b * pm[:, 1])


# Eye order: on the Redmi Note 12 Pro (lenses in a column, main on top) held in landscape the way
# the app expects (upright JPEGs), the main lens is the LEFT eye. Verified on a scene with known
# depths: near objects show larger x_wide - x_ultra than far ones. Use --swap if a phone differs.


# ------------------------------------------------------------------ colour
def symmetric_plan(model, tx, wide_shape):
    """Split the correction between both views instead of fitting the ultrawide to the wide.

    The ultrawide->wide similarity M = [s*R(theta) | t] (t includes the stereo window) becomes
      wide  -> canvas:  Hw = s^-1/2 R(-theta/2) p + o
      ultra -> canvas:  Hu = s^+1/2 R(+theta/2) u + (Lw t + o)      (so Hu = Hw o M: still aligned)
    i.e. the bigger-scale view is shrunk and the smaller one enlarged by the same factor, each turns
    half the angle, and o centres the midpoint of the two image centres so the vertical and
    horizontal shifts are shared equally. Ultrawide distortion stays fully on the ultrawide (the
    main image is already distortion-corrected by the camera, so it is the straight reference).

    Returns (Hw 2x3, newK 3x3 for initUndistortRectifyMap of the ultrawide, (Wc, Hc)).
    Mirrors StereoAlignMath.symmetricPlan in the Android app.
    """
    a, b, ty, c, R = model["a"], model["b"], model["ty"], model["c"], model["R"]
    s = float(np.hypot(a, b)); th = float(np.arctan2(b, a))
    h = s ** -0.5
    Lw = h * np.float64([[np.cos(-th / 2), -np.sin(-th / 2)], [np.sin(-th / 2), np.cos(-th / 2)]])
    A = np.float64([[a, -b], [b, a]])
    Lu = Lw @ A
    t = np.float64([tx, ty])
    H, W = wide_shape[:2]
    Wc, Hc = int(round(W * h)) & ~1, int(round(H * h)) & ~1
    cw = Lw @ np.float64([W / 2.0, H / 2.0])
    cu = Lw @ (A @ np.float64(c) + t)
    o = np.float64([Wc / 2.0, Hc / 2.0]) - (cw + cu) / 2.0
    Hw = np.hstack([Lw, o[:, None]])
    off_u = Lu @ np.float64(c) + Lw @ t + o
    newK = np.float64([[R * Lu[0, 0], R * Lu[0, 1], off_u[0]],
                       [R * Lu[1, 0], R * Lu[1, 1], off_u[1]],
                       [0, 0, 1]])
    return Hw, newK, (Wc, Hc)


def hist_stats(h):
    """Mean and standard deviation of the values in a 256-bin histogram."""
    h = np.asarray(h, np.float64); n = h.sum()
    if n <= 0:
        return 128.0, 1.0
    v = np.arange(256, dtype=np.float64)
    mean = (v * h).sum() / n
    return mean, float(np.sqrt(max((v * v * h).sum() / n - mean * mean, 0.0)))


def linear_lut(m0, s0, m1, s1):
    """Straight-line tone mapping v -> (v - m0) * g + m1, g = s1/s0 clamped to [0.5, 2]
    (Reinhard colour transfer for one channel). Linear, so it can't posterise smooth areas or
    amplify noise in narrow tone bands like histogram matching. Mirrors StereoAlignMath.linearLut."""
    g = float(np.clip(s1 / s0 if s0 > 1e-6 else 1.0, 0.5, 2.0))
    return np.clip(np.floor((np.arange(256) - m0) * g + m1 + 0.5), 0, 255).astype(np.uint8)


def average_luts(ha, hb):
    """Linear LUTs taking both channels to their average mean and contrast (neither is the reference)."""
    (ma, sa), (mb, sb) = hist_stats(ha), hist_stats(hb)
    mt, st = (ma + mb) / 2, (sa + sb) / 2
    return linear_lut(ma, sa, mt, st), linear_lut(mb, sb, mt, st)


def histogram_target_luts(img_a, img_b, mask):
    """Per-Lab-channel linear LUTs that bring both images to their average colour and contrast."""
    la, lb = cv2.cvtColor(img_a, cv2.COLOR_BGR2LAB), cv2.cvtColor(img_b, cv2.COLOR_BGR2LAB)
    m = mask.astype(bool)
    luts_a, luts_b = [], []
    for ch in range(3):
        ha = np.bincount(la[..., ch][m], minlength=256)
        hb = np.bincount(lb[..., ch][m], minlength=256)
        A, B = average_luts(ha, hb)
        luts_a.append(A); luts_b.append(B)
    return la, lb, luts_a, luts_b


def apply_lab_luts(lab, luts):
    out = np.empty_like(lab)
    for ch in range(3):
        out[..., ch] = luts[ch][lab[..., ch]]
    return cv2.cvtColor(out, cv2.COLOR_LAB2BGR)


def match_colour(src, ref, mask):
    """Histogram-match src to ref per Lab channel using pixels where mask is set."""
    src_lab = cv2.cvtColor(src, cv2.COLOR_BGR2LAB)
    ref_lab = cv2.cvtColor(ref, cv2.COLOR_BGR2LAB)
    m = mask.astype(bool)
    out = np.empty_like(src_lab)
    for c in range(3):
        s_vals = src_lab[..., c][m]
        r_vals = ref_lab[..., c][m]
        s_hist = np.bincount(s_vals, minlength=256).astype(np.float64)
        r_hist = np.bincount(r_vals, minlength=256).astype(np.float64)
        (ms, ss), (mr, sr) = hist_stats(s_hist), hist_stats(r_hist)
        lut = linear_lut(ms, ss, mr, sr)
        out[..., c] = lut[src_lab[..., c]]
    return cv2.cvtColor(out, cv2.COLOR_LAB2BGR)


# ------------------------------------------------------------------ crop
def largest_valid_rect(mask):
    """Shrink a rectangle until it contains only valid pixels (mask==1)."""
    h, w = mask.shape
    x0, y0, x1, y1 = 0, 0, w, h
    while x1 - x0 > 16 and y1 - y0 > 16:
        sub = mask[y0:y1, x0:x1]
        if sub.all():
            break
        bad = {
            "l": (~sub[:, 0].astype(bool)).sum(), "r": (~sub[:, -1].astype(bool)).sum(),
            "t": (~sub[0, :].astype(bool)).sum(), "b": (~sub[-1, :].astype(bool)).sum(),
        }
        side = max(bad, key=bad.get)
        if side == "l": x0 += 1
        elif side == "r": x1 -= 1
        elif side == "t": y0 += 1
        else: y1 -= 1
    return x0, y0, x1, y1


# ------------------------------------------------------------------ outputs
def anaglyph_dubois(left, right):
    """Red-cyan Dubois anaglyph (reduces ghosting/retinal rivalry vs. naive channel swap)."""
    L = left[..., ::-1].astype(np.float64) / 255.0
    R = right[..., ::-1].astype(np.float64) / 255.0
    ml = np.array([[0.456, 0.500, 0.176], [-0.040, -0.038, -0.016], [-0.015, -0.021, -0.005]])
    mr = np.array([[-0.043, -0.088, -0.002], [0.378, 0.734, -0.018], [-0.072, -0.113, 1.226]])
    out = np.clip(L @ ml.T + R @ mr.T, 0, 1)
    return (out[..., ::-1] * 255).astype(np.uint8)


def side_by_side(a, b, gap=0):
    if gap:
        sep = np.zeros((a.shape[0], gap, 3), np.uint8)
        return np.hstack([a, sep, b])
    return np.hstack([a, b])


def write_jpg(path, img, q=93):
    cv2.imwrite(str(path), img, [cv2.IMWRITE_JPEG_QUALITY, q])


def process_pair(stem, wide_p, ultra_p, out_dir, args):
    wide = cv2.imread(str(wide_p))
    ultra = cv2.imread(str(ultra_p))
    h, w = wide.shape[:2]

    pr, pm = detect_matches(wide, ultra, args.work)
    if len(pr) < 40:
        raise RuntimeError(f"only {len(pr)} feature matches")
    model, use, vres, pmc = fit_alignment(pr, pm, ultra.shape, fit_distortion=not args.no_distortion)
    a, b, ty = model["a"], model["b"], model["ty"]
    d = horizontal_offsets(pr, pmc, a, b)
    d_in = d[use]
    wide_left = not args.swap

    # Stereo window: nearest content (percentile) at zero parallax, the rest behind the screen.
    if wide_left:
        tx = float(np.percentile(d_in, args.window))
    else:
        tx = float(np.percentile(d_in, 100 - args.window))
    parallax = (d_in - tx) if wide_left else (tx - d_in)        # x_L - x_R after alignment
    if args.anchor == "wide":
        # Original behaviour: fit the ultrawide to the wide.
        warped, valid = warp_mov(ultra, model, tx, wide.shape)
        valid = cv2.erode(valid.astype(np.uint8), np.ones((5, 5), np.uint8)) > 0
        warped = match_colour(warped, wide, valid)
        wide_w = wide
    else:
        # Symmetric: both views move half way (scale, angle, shift) and meet in colour.
        Hw, newK, (Wc, Hc) = symmetric_plan(model, tx, wide.shape)
        wide_w = cv2.warpAffine(wide, Hw, (Wc, Hc), flags=cv2.INTER_AREA if Hw[0, 0] < 1 else cv2.INTER_CUBIC)
        v_w = cv2.warpAffine(np.full(wide.shape[:2], 255, np.uint8), Hw, (Wc, Hc), flags=cv2.INTER_NEAREST)
        c, R = model["c"], model["R"]
        K = np.float64([[R, 0, c[0]], [0, R, c[1]], [0, 0, 1]])
        mapx, mapy = cv2.initUndistortRectifyMap(K, np.float64([model["k"], 0, 0, 0, 0]), np.eye(3), newK, (Wc, Hc), cv2.CV_32FC1)
        warped = cv2.remap(ultra, mapx, mapy, interpolation=cv2.INTER_LANCZOS4, borderMode=cv2.BORDER_CONSTANT)
        v_u = (mapx >= 1) & (mapx < ultra.shape[1] - 2) & (mapy >= 1) & (mapy < ultra.shape[0] - 2)
        valid = cv2.erode(((v_w == 255) & v_u).astype(np.uint8), np.ones((5, 5), np.uint8)) > 0
        lw, lu, luts_w, luts_u = histogram_target_luts(wide_w, warped, valid)
        wide_w = apply_lab_luts(lw, luts_w)
        warped = apply_lab_luts(lu, luts_u)

    x0, y0, x1, y1 = largest_valid_rect(valid)
    wide_c = wide_w[y0:y1, x0:x1]
    ultra_c = warped[y0:y1, x0:x1]
    left, right = (wide_c, ultra_c) if wide_left else (ultra_c, wide_c)

    write_jpg(out_dir / f"{stem}_L.jpg", left)
    write_jpg(out_dir / f"{stem}_R.jpg", right)
    write_jpg(out_dir / f"{stem}_parallel.jpg", side_by_side(left, right))
    write_jpg(out_dir / f"{stem}_cross.jpg", side_by_side(right, left))
    write_jpg(out_dir / f"{stem}_anaglyph.jpg", anaglyph_dubois(left, right))
    # Small previews for the viewer.
    pw = 1600
    s = pw / (2 * left.shape[1])
    small = lambda im: cv2.resize(im, None, fx=s, fy=s, interpolation=cv2.INTER_AREA)
    sl, sr = small(left), small(right)
    write_jpg(out_dir / f"{stem}_prev_cross.jpg", side_by_side(sr, sl, gap=8), 88)
    write_jpg(out_dir / f"{stem}_prev_parallel.jpg", side_by_side(sl, sr, gap=8), 88)
    write_jpg(out_dir / f"{stem}_prev_anaglyph.jpg", anaglyph_dubois(sl, sr), 88)
    write_jpg(out_dir / f"{stem}_prev_L.jpg", sl, 88)
    write_jpg(out_dir / f"{stem}_prev_R.jpg", sr, 88)

    report = dict(
        stem=stem, matches=int(len(pr)), inliers=int(use.sum()),
        scale=float(np.hypot(a, b)), rotation_deg=float(np.degrees(np.arctan2(b, a))),
        shift_x=tx, shift_y=float(ty),
        vertical_error_px_median=float(np.median(np.abs(vres[use]))),
        vertical_error_px_p95=float(np.percentile(np.abs(vres[use]), 95)),
        distortion_k=float(model["k"]), wide_is_left=wide_left,
        parallax_px=dict(p2=float(np.percentile(parallax, 2)), p50=float(np.median(parallax)),
                         p98=float(np.percentile(parallax, 98))),
        # Parallax is measured in main-image pixels; the symmetric canvas is scaled by s^-1/2.
        parallax_pct_of_width=float((np.percentile(parallax, 98) - np.percentile(parallax, 2))
                                    * (1.0 if args.anchor == "wide" else np.hypot(a, b) ** -0.5) / (x1 - x0) * 100),
        crop=[int(x0), int(y0), int(x1), int(y1)], size=[int(x1 - x0), int(y1 - y0)],
    )
    return report


VIEWER = """<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Stereo Preview</title>
<style>
:root{--bg:#111;--fg:#eee;--muted:#999;--card:#1c1c1c;--accent:#5aa9ff}
body{margin:0;background:var(--bg);color:var(--fg);font:15px/1.4 system-ui,sans-serif}
header{position:sticky;top:0;background:#000c;backdrop-filter:blur(6px);padding:10px 16px;display:flex;gap:8px;flex-wrap:wrap;align-items:center;z-index:2}
header b{margin-right:8px}
button{background:var(--card);color:var(--fg);border:1px solid #333;border-radius:6px;padding:6px 12px;cursor:pointer}
button.on{border-color:var(--accent);color:var(--accent)}
main{padding:16px;display:grid;gap:24px}
figure{margin:0;background:var(--card);border-radius:8px;padding:12px}
figure img{width:100%;height:auto;display:block;border-radius:4px}
figcaption{color:var(--muted);font-size:13px;margin-top:8px}
.tip{color:var(--muted);font-size:13px}
</style></head><body>
<header><b>Stereo Preview</b>
<button data-m="cross" class="on">Cross-eyed</button><button data-m="parallel">Parallel</button>
<button data-m="anaglyph">Anaglyph (red-cyan)</button><button data-m="wiggle">Wiggle</button>
<span class="tip" id="tip"></span></header>
<main id="list"></main>
<script>
const items = __ITEMS__;
const tips = {cross:"Cross your eyes until the two pictures merge into a third one in the middle.",
 parallel:"Look 'through' the screen (or use a VR/stereo viewer) so the two pictures merge.",
 anaglyph:"Use red-cyan glasses, red over the left eye.", wiggle:"Alternates left/right views; depth shows as motion."};
let mode = "cross", timer = null, flip = false;
function render(){
  document.getElementById("tip").textContent = tips[mode];
  document.querySelectorAll("button").forEach(b=>b.classList.toggle("on", b.dataset.m===mode));
  const list = document.getElementById("list"); list.innerHTML = "";
  for (const it of items){
    const f = document.createElement("figure"); const img = document.createElement("img");
    img.loading = "lazy"; img.alt = it.stem; img.dataset.stem = it.stem;
    img.src = it.img[mode==="wiggle" ? "L" : mode];
    if (mode==="wiggle") img.style.maxWidth = "800px";
    const c = document.createElement("figcaption");
    c.textContent = it.stem+" | scale "+it.scale.toFixed(3)+", rotation "+it.rotation_deg.toFixed(2)+
      " deg, vertical error "+it.vertical_error_px_median.toFixed(1)+" px, depth range "+it.parallax_pct_of_width.toFixed(1)+"% of width"+
      (it.wide_is_left ? " | main lens = left" : " | main lens = right");
    f.append(img, c); list.append(f);
  }
  clearInterval(timer);
  if (mode==="wiggle") timer = setInterval(()=>{flip=!flip;
    document.querySelectorAll("img").forEach(i=>{const it=items.find(x=>x.stem===i.dataset.stem); i.src=it.img[flip?"R":"L"];});}, 180);
}
document.querySelectorAll("button").forEach(b=>b.onclick=()=>{mode=b.dataset.m;render();});
render();
</script></body></html>
"""


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("input_dir", type=pathlib.Path)
    ap.add_argument("output_dir", type=pathlib.Path)
    ap.add_argument("--swap", action="store_true",
                    help="ultrawide is the left eye (default: main/wide is left)")
    ap.add_argument("--anchor", choices=["both", "wide"], default="both",
                    help="both: split scale/angle/shift/colour between the views (default); "
                         "wide: fit the ultrawide to the main image")
    ap.add_argument("--no-distortion", action="store_true",
                    help="skip the radial distortion fit (plain similarity alignment)")
    ap.add_argument("--window", type=float, default=98.0,
                    help="percentile of nearest content placed at the screen plane (default 98)")
    ap.add_argument("--work", type=int, default=1600, help="feature-matching width in pixels")
    args = ap.parse_args()

    args.output_dir.mkdir(parents=True, exist_ok=True)
    pairs = []
    for wp in sorted(args.input_dir.glob("*_wide.jpg")):
        stem = wp.name[: -len("_wide.jpg")]
        up = wp.with_name(stem + "_ultrawide.jpg")
        if up.exists():
            pairs.append((stem, wp, up))
    if not pairs:
        sys.exit(f"no *_wide.jpg / *_ultrawide.jpg pairs in {args.input_dir}")

    reports = []
    for stem, wp, up in pairs:
        try:
            r = process_pair(stem, wp, up, args.output_dir, args)
            reports.append(r)
            print(f"{stem}: scale {r['scale']:.3f} rot {r['rotation_deg']:+.2f} deg  "
                  f"vErr med {r['vertical_error_px_median']:.1f}px p95 {r['vertical_error_px_p95']:.1f}px  "
                  f"k {r['distortion_k']:+.3f}  inliers {r['inliers']}/{r['matches']}  "
                  f"main={'L' if r['wide_is_left'] else 'R'}  depth {r['parallax_pct_of_width']:.1f}%  size {r['size']}")
        except Exception as e:
            print(f"{stem}: FAILED {e}")
    (args.output_dir / "report.json").write_text(json.dumps(reports, indent=2))
    # Self-contained viewer: previews embedded as data URIs so the HTML works on its own.
    items = []
    for r in reports:
        imgs = {}
        for key, suffix in (("cross", "cross"), ("parallel", "parallel"), ("anaglyph", "anaglyph"), ("L", "L"), ("R", "R")):
            data = (args.output_dir / f"{r['stem']}_prev_{suffix}.jpg").read_bytes()
            imgs[key] = "data:image/jpeg;base64," + base64.b64encode(data).decode("ascii")
        items.append(dict(r, img=imgs))
    (args.output_dir / "index.html").write_text(VIEWER.replace("__ITEMS__", json.dumps(items)), encoding="utf-8")
    print(f"wrote {len(reports)} pairs + index.html to {args.output_dir}")


if __name__ == "__main__":
    main()
