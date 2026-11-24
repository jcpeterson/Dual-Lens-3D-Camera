"""Export a test fixture so the Android port (StereoAlignMath.kt) can be checked against Python.

Usage: python export_fixture.py <wide.jpg> <ultrawide.jpg> <out.json>
Writes matched points, the RANSAC inlier mask, and the expected refinement / window / LUT /
crop results computed by stereo_fix.py.
"""
import json, sys
import cv2
import numpy as np
import stereo_fix as sf

wide = cv2.imread(sys.argv[1])
ultra = cv2.imread(sys.argv[2])
pr, pm = sf.detect_matches(wide, ultra, 1600)
M, inl = cv2.estimateAffinePartial2D(pm, pr, method=cv2.RANSAC, ransacReprojThreshold=0.03 * ultra.shape[1],
                                     maxIters=5000, confidence=0.999)
base = inl.ravel().astype(bool)
model, use, vres, pmc = sf.refine_alignment(pr, pm, base, ultra.shape, True)
d = sf.horizontal_offsets(pr, pmc, model["a"], model["b"])
d_in = d[use]

rng = np.random.default_rng(7)
src_hist = rng.integers(0, 5000, 256).astype(np.float64)
ref_hist = np.convolve(rng.integers(0, 5000, 256), np.ones(9) / 9, mode="same")
s_cdf = np.cumsum(src_hist) / src_hist.sum(); r_cdf = np.cumsum(ref_hist) / ref_hist.sum()
lut = np.interp(s_cdf, r_cdf, np.arange(256)).clip(0, 255).astype(np.uint8)

tx98 = float(np.percentile(d_in, 98))
Hw, newK, (Wc, Hc) = sf.symmetric_plan(model, tx98, wide.shape)
avg_a, avg_b = sf.average_luts(src_hist, ref_hist)

mask = np.ones((60, 80), bool)
mask[:7, :] = False; mask[:, :5] = False; mask[50:, 60:] = False
rect = sf.largest_valid_rect(mask.astype(np.uint8))

fixture = dict(
    mov_w=int(ultra.shape[1]), mov_h=int(ultra.shape[0]),
    ref_x=pr[:, 0].tolist(), ref_y=pr[:, 1].tolist(), mov_x=pm[:, 0].tolist(), mov_y=pm[:, 1].tolist(),
    base_inliers=base.astype(int).tolist(),
    expected=dict(a=model["a"], b=model["b"], ty=model["ty"], k=model["k"], inliers=int(use.sum()),
                  tx_wide_left_98=float(np.percentile(d_in, 98)), tx_wide_right_98=float(np.percentile(d_in, 2))),
    hist=dict(src=src_hist.tolist(), ref=ref_hist.tolist(), lut=lut.astype(int).tolist(),
              avg_a=avg_a.astype(int).tolist(), avg_b=avg_b.astype(int).tolist()),
    sym=dict(wide_w=int(wide.shape[1]), wide_h=int(wide.shape[0]), hw=Hw.ravel().tolist(), newk=newK.ravel().tolist(),
             size=[Wc, Hc]),
    rect=dict(w=80, h=60, mask=mask.astype(int).ravel().tolist(), expected=[int(v) for v in rect]),
)
# Plain "key v1 v2 ..." lines: trivially parsed in a JVM unit test without extra dependencies.
def flat(prefix, obj, out):
    if isinstance(obj, dict):
        for k, v in obj.items():
            flat(f"{prefix}.{k}" if prefix else k, v, out)
    elif isinstance(obj, (list, tuple)):
        out.append(prefix + " " + " ".join(repr(float(x)) for x in obj))
    else:
        out.append(prefix + " " + repr(float(obj)))
lines = []
flat("", fixture, lines)
open(sys.argv[3], "w").write("\n".join(lines) + "\n")
print("a", model["a"], "b", model["b"], "ty", model["ty"], "k", model["k"], "inliers", int(use.sum()), "rect", rect)
