// Package mobile is the gomobile bind entry point used by the native
// Android car app (android-native/). It exposes a tiny surface to start
// the existing embedded HTTP server inside the host Android process.
//
// All real business logic (music sources, search, playlists, download,
// streaming, sqlite) lives in the reused internal/web + core packages.
package mobile

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"github.com/guohuiyuan/go-music-dl/internal/appshell"
)

var (
	startOnce sync.Once
	startErr  error
	startURL  string
)

// StartServer launches the embedded HTTP server bound to 127.0.0.1.
// It blocks until the server is reachable (or until timeout / failure).
//
// dataDir:     writable directory where the SQLite settings DB and cookie
//              file should live. Pass Context.getFilesDir().getAbsolutePath().
// downloadDir: writable directory for downloaded music files. Pass
//              "/sdcard/Download/MusicDL" on Android; falls back to
//              dataDir/downloads when empty.
// port:        listening port; empty falls back to appshell.DefaultPort.
//
// Subsequent calls are idempotent and return the cached result.
//
// gomobile constraint: signature uses only string + error, which round-trip
// cleanly to Java/Kotlin (returns void on success, throws Exception on err).
func StartServer(dataDir string, downloadDir string, port string) error {
	startOnce.Do(func() {
		if dataDir != "" {
			if err := os.MkdirAll(dataDir, 0o755); err != nil {
				startErr = err
				return
			}
			// Mirror desktop_app/main.go env-var convention so the
			// internal/config layer puts its sqlite DB + cookies under
			// the app's private files dir.
			_ = os.Setenv("MUSIC_DL_CONFIG_DB", filepath.Join(dataDir, "settings.db"))
			_ = os.Setenv("MUSIC_DL_COOKIE_FILE", filepath.Join(dataDir, "cookies.json"))
		}

		// 下载目录:优先用调用方传入的(/sdcard/Download/MusicDL),否则回退到
		// dataDir/downloads。两个都失败时只 setEnv,让 core 自己 mkdir 时报错。
		effectiveDownloadDir := strings.TrimSpace(downloadDir)
		if effectiveDownloadDir == "" && dataDir != "" {
			effectiveDownloadDir = filepath.Join(dataDir, "downloads")
		}
		if effectiveDownloadDir != "" {
			if err := os.MkdirAll(effectiveDownloadDir, 0o755); err != nil {
				// 公共目录有时需要权限;失败时尝试退回 dataDir/downloads。
				if dataDir != "" {
					fallback := filepath.Join(dataDir, "downloads")
					if mkErr := os.MkdirAll(fallback, 0o755); mkErr == nil {
						effectiveDownloadDir = fallback
					}
				}
			}
			_ = os.Setenv("MUSIC_DL_DOWNLOAD_DIR", effectiveDownloadDir)
		}

		// Bounded wait so a stuck startup does not freeze the calling
		// Kotlin thread forever. 30s is well above appshell.ReadyTimeout
		// but still lets the UI fall back to an error screen.
		ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
		defer cancel()

		url, err := appshell.StartDesktopServerAndWait(ctx, port)
		if err != nil {
			startErr = err
			return
		}
		startURL = url
	})
	return startErr
}

// ServerURL returns the base URL of the running server (e.g. http://127.0.0.1:37777/music/).
// Returns empty string before StartServer succeeds.
func ServerURL() string {
	return startURL
}

// HealthURL returns the readiness endpoint for the embedded server.
// Kotlin can poll this independently of StartServer if desired.
func HealthURL(port string) string {
	return appshell.HealthURL(port)
}

// Version is bumped manually when the mobile bridge ABI changes so the
// Kotlin layer can sanity-check the .aar it loaded.
const Version = "1"

// GetVersion mirrors Version as a callable, since gomobile does not
// export package-level constants to Java.
func GetVersion() string {
	return Version
}

// Make sure errors is referenced even if future revisions stop using it
// (keeps the package import set stable across gomobile rebinds).
var _ = errors.New
