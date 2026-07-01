@echo off
setlocal EnableExtensions EnableDelayedExpansion

cd /d "%~dp0"

if "%ANDROID_HOME%"=="" set "ANDROID_HOME=D:\software\andriod\sdk"
if "%ANDROID_SDK_ROOT%"=="" set "ANDROID_SDK_ROOT=%ANDROID_HOME%"
if "%ANDROID_NDK_ROOT%"=="" set "ANDROID_NDK_ROOT=%ANDROID_HOME%\ndk\27.0.12077973"
if "%JAVA_HOME%"=="" set "JAVA_HOME=D:\Program Files\Android\Android Studio\jbr"

set "PATH=%JAVA_HOME%\bin;%PATH%"

REM gomobile bind
if not exist "android-native\app\libs" mkdir "android-native\app\libs"
echo [build] gomobile bind ...
call gomobile bind -target=android/arm64 -androidapi=21 -o android-native\app\libs\music_dl_mobile.aar github.com/guohuiyuan/go-music-dl/mobile
if errorlevel 1 (
    echo [build] gomobile bind FAILED
    exit /b 1
)

REM assemble APK
pushd android-native
echo [build] gradle assembleDebug ...
call .\gradlew.bat assembleDebug
set GRADLE_EC=%errorlevel%
popd

if not "%GRADLE_EC%"=="0" (
    echo [build] gradle build FAILED (code %GRADLE_EC%)
    exit /b %GRADLE_EC%
)

REM install
echo [build] installing APK ...
adb install -r android-native\app\build\outputs\apk\debug\app-debug.apk
if errorlevel 1 (
    echo [build] install FAILED
    exit /b 1
)

echo [build] DONE. App installed.

endlocal
