# Repository Guidance

This file contains current operating guidance for Codex and other development agents. Read
`README.md` for app behavior and architecture, and `DEVELOPMENT_LOG.md` for chronological decisions
and failed approaches.

## Build environment

- Use the checked-in Gradle wrapper (`.\gradlew.bat` on Windows); do not use a system Gradle
  installation.
- Gradle requires JDK 17 or newer. The primary Windows workstation uses Android Studio's bundled
  JBR 21 through the user-level `JAVA_HOME`; the app still targets Java 17 bytecode.
- Do not commit machine-specific SDK or JDK paths. Keep `local.properties` untracked, and do not add
  `org.gradle.java.home` to the repository's `gradle.properties`.
- If a PowerShell session does not inherit a suitable `JAVA_HOME`, select the bundled runtime for
  that session before running Gradle:

```powershell
$jbr = Join-Path $env:ProgramFiles 'Android\Android Studio\jbr'
if (-not (Test-Path (Join-Path $jbr 'bin\java.exe'))) {
    throw "Android Studio JBR not found: $jbr"
}
$env:JAVA_HOME = $jbr
.\gradlew.bat --version
```

The Gradle output must report JVM 17 or newer before builds or tests.

## Build variants and device safety

- `debug` is the owner's personal installed build. Its application ID is
  `com.example.duallens3dcamera`. Do not run connected instrumentation against it because the test
  runner can uninstall the target and erase its app-private data.
- `agent` is the isolated automation build. Its application ID is
  `com.example.duallens3dcamera.agent`, its launcher label includes `(Agent)`, and its displayed
  version has an `-agent` suffix.
- Use only agent tasks for connected-device automation:

```powershell
.\gradlew.bat :app:assembleAgent :app:testAgentUnitTest :app:lintAgent
.\gradlew.bat :app:connectedAgentAndroidTest
.\gradlew.bat :app:installAgent
```

- Launch the agent build with:

```powershell
$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
if (-not (Test-Path $adb)) { throw "ADB not found: $adb" }
& $adb shell am start -W -n com.example.duallens3dcamera.agent/com.example.duallens3dcamera.MainActivity
```

The agent and personal builds have separate preferences, permissions, and app-private files. They
still share public MediaStore output locations such as `Pictures/StereoCapture`.

## Verification expectations

- For ordinary source changes, run the relevant unit tests plus `:app:assembleAgent` and
  `:app:lintAgent`.
- For camera, lifecycle, encoding, permissions, or resource changes, also install and cold-launch
  the agent build on a physical Pixel and inspect the process log for fatal exceptions.
- Camera2 behavior varies by device. Preserve best-effort guards and validate assumptions on real
  hardware rather than relying only on advertised camera characteristics.

## Project history and versioning

- Add noteworthy investigations, rejected approaches, device findings, and verification details to
  `DEVELOPMENT_LOG.md`, newest first.
- User-facing versions follow `major.minor.patch`. Android `versionCode` increases for every
  release. Agent builds do not consume a new release version code.
- Keep failed experiments reachable through normal Git history or a named tag instead of copying
  source trees into the repository.
