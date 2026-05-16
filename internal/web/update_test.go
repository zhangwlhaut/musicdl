package web

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"

	"github.com/gin-gonic/gin"
)

func TestGitHubRepoFromURLAndProxyURL(t *testing.T) {
	owner, repo, err := githubRepoFromURL("https://github.com/guohuiyuan/go-music-dl.git")
	if err != nil {
		t.Fatalf("githubRepoFromURL returned error: %v", err)
	}
	if owner != "guohuiyuan" || repo != "go-music-dl" {
		t.Fatalf("repo parse mismatch: owner=%q repo=%q", owner, repo)
	}

	got := proxiedGitHubURL("https://github.com/guohuiyuan/go-music-dl/releases", "https://gh-proxy.com/", true)
	want := "https://gh-proxy.com/https://github.com/guohuiyuan/go-music-dl/releases"
	if got != want {
		t.Fatalf("proxiedGitHubURL = %q, want %q", got, want)
	}
	if compareVersions("1.4.0", "1.3.1") <= 0 {
		t.Fatal("compareVersions should detect newer version")
	}
}

func TestUpdateCheckRouteUsesGitHubReleaseResponse(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/repos/guohuiyuan/go-music-dl/releases/latest" {
			t.Fatalf("unexpected path: %s", r.URL.Path)
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{
			"tag_name": "v9.9.9",
			"name": "v9.9.9",
			"html_url": "https://github.com/guohuiyuan/go-music-dl/releases/tag/v9.9.9",
			"body": "release notes",
			"assets": [
				{"name": "music-dl-windows-amd64.zip", "browser_download_url": "https://github.com/guohuiyuan/go-music-dl/releases/download/v9.9.9/music-dl-windows-amd64.zip", "size": 1024, "content_type": "application/zip"}
			]
		}`))
	}))
	defer server.Close()

	originalBase := githubAPIBaseURL
	githubAPIBaseURL = server.URL
	t.Cleanup(func() { githubAPIBaseURL = originalBase })

	gin.SetMode(gin.TestMode)
	router := gin.New()
	RegisterUpdateRoutes(router.Group(RoutePrefix))

	req := httptest.NewRequest(http.MethodGet, RoutePrefix+"/app_update/check?repo=https://github.com/guohuiyuan/go-music-dl", nil)
	rec := httptest.NewRecorder()
	router.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200, body=%s", rec.Code, rec.Body.String())
	}
	body := rec.Body.String()
	for _, want := range []string{
		`"latest_version":"9.9.9"`,
		`"update_available":true`,
		`music-dl-windows-amd64.zip`,
	} {
		if !strings.Contains(body, want) {
			t.Fatalf("update response missing %q: %s", want, body)
		}
	}
}

func TestAppUpdateOpenRouteCallsBrowser(t *testing.T) {
	originalOpener := openBrowserForUpdate
	t.Cleanup(func() { openBrowserForUpdate = originalOpener })

	var (
		mu       sync.Mutex
		captured []string
	)
	openBrowserForUpdate = func(url string) {
		mu.Lock()
		defer mu.Unlock()
		captured = append(captured, url)
	}

	gin.SetMode(gin.TestMode)
	router := gin.New()
	RegisterUpdateRoutes(router.Group(RoutePrefix))

	target := "https://github.com/guohuiyuan/go-music-dl/releases/latest"
	req := httptest.NewRequest(http.MethodGet, RoutePrefix+"/app_update/open?url="+target, nil)
	rec := httptest.NewRecorder()
	router.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200, body=%s", rec.Code, rec.Body.String())
	}
	if !strings.Contains(rec.Body.String(), `"ok":true`) {
		t.Fatalf("open response missing ok=true: %s", rec.Body.String())
	}
	mu.Lock()
	defer mu.Unlock()
	if len(captured) != 1 || captured[0] != target {
		t.Fatalf("captured open URLs = %v, want [%q]", captured, target)
	}
}

func TestAppUpdateOpenRouteRejectsInvalidURL(t *testing.T) {
	originalOpener := openBrowserForUpdate
	t.Cleanup(func() { openBrowserForUpdate = originalOpener })

	called := false
	openBrowserForUpdate = func(string) { called = true }

	gin.SetMode(gin.TestMode)
	router := gin.New()
	RegisterUpdateRoutes(router.Group(RoutePrefix))

	for _, raw := range []string{"", "javascript:alert(1)", "ftp://example.com/file"} {
		req := httptest.NewRequest(http.MethodGet, RoutePrefix+"/app_update/open?url="+raw, nil)
		rec := httptest.NewRecorder()
		router.ServeHTTP(rec, req)
		if rec.Code == http.StatusOK {
			t.Fatalf("expected non-200 for %q, got %d", raw, rec.Code)
		}
	}
	if called {
		t.Fatal("openBrowserForUpdate should not be called for invalid URLs")
	}
}
