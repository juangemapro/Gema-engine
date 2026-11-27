# Building Gema Engine for Android

This guide builds the **debug APK** from the checked-out source. It does not publish a GitHub release.

## Requirements

- JDK 17 (set `JAVA_HOME`)
- Gradle 8.9 available on `PATH`
- Android SDK with **Android SDK Platform 35** installed
- `ANDROID_SDK_ROOT` (recommended) or `ANDROID_HOME` pointing to the SDK directory
- Internet access on the first build so Gradle can download Android Gradle Plugin and dependencies

The project currently has no Gradle Wrapper files, so use an installed Gradle 8.9 executable.

## Get the intended source

In a terminal:

```bash
git clone https://github.com/juangemapro/Gema-engine.git
cd Gema-engine
git switch prisma-parity-dev
```

If you already cloned it:

```bash
git fetch origin
git switch prisma-parity-dev
git pull --ff-only origin prisma-parity-dev
```

## Build on Windows

Open Command Prompt or PowerShell in the repository directory and run:

```bat
scripts\build-debug.bat
```

## Build on Linux or macOS

From the repository directory:

```bash
chmod +x scripts/build-debug.sh
./scripts/build-debug.sh
```

Or run the underlying Gradle command directly:

```bash
gradle --no-daemon clean assembleDebug
```

## APK location

After a successful build:

```
app/build/outputs/apk/debug/app-debug.apk
```

Copy that APK to the Android device and install it. Android may ask you to allow installation from the file manager you use. Only install builds you compiled yourself or obtained from a trusted source.

## Common setup checks

- `java -version` should report Java 17.
- `gradle --version` should report Gradle 8.9.
- Confirm `$ANDROID_SDK_ROOT/platforms/android-35` exists (on Windows, use `%ANDROID_SDK_ROOT%\platforms\android-35`).
- If Gradle reports that SDK licenses are not accepted, open Android Studio > SDK Manager and accept the requested licenses.
- If a build fails, rerun `gradle --no-daemon --stacktrace clean assembleDebug` and inspect the first actual compiler or dependency error.

## Important

This produces a **debug build**, not a signed production release. The source branch has not been certified through a real-device test cycle. A successful compile confirms buildability, not that every editor feature is production-ready.
