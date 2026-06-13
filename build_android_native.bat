@echo off
setlocal EnableExtensions EnableDelayedExpansion

REM ============================================================
REM  build_android_native.bat
REM  Builds the native-Kotlin car APK in android-native\ by:
REM    1) running `gomobile bind` to produce
REM       android-native\app\libs\music_dl_mobile.aar
REM    2) running gradlew (auto-generated on first run) to assemble
REM       the APK.
REM
REM  Required env (set them ahead of time, or edit defaults here):
REM    ANDROID_HOME       e.g. D:\software\andriod\sdk
REM    ANDROID_NDK_ROOT   e.g. %ANDROID_HOME%\ndk\27.0.12077973
REM    JAVA_HOME          a JDK 17+ install (Android Studio JBR works)
REM ============================================================

cd /d "%~dp0"

REM ---- 0. Defaults (edit if you don't want to set env vars globally) ----
if "%ANDROID_HOME%"=="" set "ANDROID_HOME=D:\software\andriod\sdk"
if "%ANDROID_SDK_ROOT%"=="" set "ANDROID_SDK_ROOT=%ANDROID_HOME%"
if "%ANDROID_NDK_ROOT%"=="" set "ANDROID_NDK_ROOT=%ANDROID_HOME%\ndk\27.0.12077973"
if "%JAVA_HOME%"=="" set "JAVA_HOME=D:\Program Files\Android\Android Studio\jbr"

set "PATH=%JAVA_HOME%\bin;%PATH%"

REM ---- 1. Ensure gomobile is on PATH ----
where gomobile >nul 2>&1
if errorlevel 1 (
    echo [build_android_native] installing gomobile ...
    call go install golang.org/x/mobile/cmd/gomobile@v0.0.0-20231127183840-76ac6878050a
    if errorlevel 1 (
        echo [build_android_native] failed to install gomobile
        exit /b 1
    )
)

REM gomobile init is idempotent enough; skip if NDK already validated.
echo [build_android_native] running gomobile init ...
call gomobile init
if errorlevel 1 (
    echo [build_android_native] gomobile init failed
    exit /b 1
)

REM ---- 2. Produce the .aar ----
if not exist "android-native\app\libs" mkdir "android-native\app\libs"
echo [build_android_native] running gomobile bind ...
call gomobile bind -target=android/arm64 -androidapi=21 ^
    -o android-native\app\libs\music_dl_mobile.aar ^
    github.com/guohuiyuan/go-music-dl/mobile
if errorlevel 1 (
    echo [build_android_native] gomobile bind failed
    exit /b 1
)

REM ---- 3. Gradle wrapper is vendored under android-native\gradle\wrapper\ ----
if not exist "android-native\gradle\wrapper\gradle-wrapper.jar" (
    echo [build_android_native] missing android-native\gradle\wrapper\gradle-wrapper.jar
    echo [build_android_native] expected the wrapper to be checked in; aborting.
    exit /b 1
)

REM ---- 4. Assemble APK ----
pushd android-native
echo [build_android_native] running gradlew assembleDebug ...
call .\gradlew.bat assembleDebug
set GRADLE_EC=%errorlevel%
popd
if not "%GRADLE_EC%"=="0" (
    echo [build_android_native] gradle build failed (code %GRADLE_EC%)
    exit /b %GRADLE_EC%
)

echo.
echo [build_android_native] DONE.
echo Debug APK: android-native\app\build\outputs\apk\debug\app-debug.apk
echo.

endlocal
