@echo off
setlocal
cd /d "%~dp0.."

where java >nul 2>nul || (echo ERROR: Install JDK 17 and configure JAVA_HOME.& exit /b 1)
java -version 2>&1 | findstr /R /C:"version ""17" >nul || (echo ERROR: Gema Engine requires JDK 17.& exit /b 1)
if "%ANDROID_SDK_ROOT%"=="" if "%ANDROID_HOME%"=="" (echo ERROR: Set ANDROID_SDK_ROOT or ANDROID_HOME to your Android SDK directory.& exit /b 1)
where gradle >nul 2>nul || (echo ERROR: Install Gradle 8.9 and add it to PATH.& exit /b 1)
gradle --version | findstr /C:"Gradle 8.9" >nul || (echo ERROR: Expected Gradle 8.9.& exit /b 1)

if not "%ANDROID_SDK_ROOT%"=="" (set "SDK_DIR=%ANDROID_SDK_ROOT%") else (set "SDK_DIR=%ANDROID_HOME%")
if not exist "%SDK_DIR%\platforms\android-35" (echo ERROR: Android SDK Platform 35 is missing. Install it using Android Studio SDK Manager.& exit /b 1)

gradle --no-daemon --stacktrace clean assembleDebug
if errorlevel 1 exit /b %errorlevel%
if not exist "app\build\outputs\apk\debug\app-debug.apk" (echo ERROR: APK output not found.& exit /b 1)
echo.
echo BUILD SUCCESSFUL
echo APK: %CD%\app\build\outputs\apk\debug\app-debug.apk
endlocal
