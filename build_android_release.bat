@echo off
setlocal EnableExtensions EnableDelayedExpansion

cd /d "%~dp0"

if "%ANDROID_HOME%"=="" set "ANDROID_HOME=D:\software\andriod\sdk"
if "%ANDROID_SDK_ROOT%"=="" set "ANDROID_SDK_ROOT=%ANDROID_HOME%"
if "%ANDROID_NDK_ROOT%"=="" set "ANDROID_NDK_ROOT=%ANDROID_HOME%\ndk\27.0.12077973"
if "%JAVA_HOME%"=="" set "JAVA_HOME=D:\Program Files\Android\Android Studio\jbr"

set "PATH=%JAVA_HOME%\bin;%PATH%"

REM ---- gomobile bind ----
where gomobile >nul 2>&1
if errorlevel 1 (
    echo [release] installing gomobile ...
    call go install golang.org/x/mobile/cmd/gomobile@v0.0.0-20231127183840-76ac6878050a
)
echo [release] gomobile init ...
call gomobile init
if errorlevel 1 ( echo [release] gomobile init failed & exit /b 1 )

if not exist "android-native\app\libs" mkdir "android-native\app\libs"
echo [release] gomobile bind ...
call gomobile bind -target=android/arm64 -androidapi=21 ^
    -o android-native\app\libs\music_dl_mobile.aar ^
    github.com/guohuiyuan/go-music-dl/mobile
if errorlevel 1 ( echo [release] gomobile bind failed & exit /b 1 )

REM ---- assembleRelease ----
pushd android-native
echo [release] gradlew assembleRelease ...
call .\gradlew.bat assembleRelease
set GRADLE_EC=%errorlevel%
popd
if not "%GRADLE_EC%"=="0" (
    echo [release] gradle build failed (code %GRADLE_EC%)
    exit /b %GRADLE_EC%
)

echo.
echo [release] DONE.
echo Release APK: android-native\app\build\outputs\apk\release\app-release.apk
echo.

endlocal