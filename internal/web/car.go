package web

import (
	"encoding/json"
	"errors"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/guohuiyuan/go-music-dl/core"
	"github.com/guohuiyuan/music-lib/model"
	"gorm.io/gorm"
	"gorm.io/gorm/clause"
)

// recentPlay 记录车载/手机版用户最近播放过的歌曲。
// SongID + Source 联合唯一,同一首歌只保留一条,后续播放仅更新 LastPlayedAt 与 PlayCount。
type recentPlay struct {
	ID           uint   `gorm:"primaryKey" json:"id"`
	SongID       string `gorm:"uniqueIndex:idx_recent_song_src;not null" json:"song_id"`
	Source       string `gorm:"uniqueIndex:idx_recent_song_src;not null" json:"source"`
	Name         string `json:"name"`
	Artist       string `json:"artist"`
	Album        string `json:"album"`
	Cover        string `json:"cover"`
	Duration     int    `json:"duration"`
	Extra        string `json:"extra"`
	LastPlayedAt int64  `gorm:"index;not null;default:0" json:"last_played_at"`
	PlayCount    int    `gorm:"not null;default:0" json:"play_count"`
}

const (
	favoriteCollectionName    = "我的收藏"
	recentPlayDefaultLimit    = 60
	recentPlayMaxLimit        = 200
	recentPlayRetainThreshold = 500
)

var (
	favoriteCollectionIDOnce sync.Once
	favoriteCollectionID     uint
	favoriteCollectionErr    error
)

// ensureRecentPlayTable creates the table if missing. Called by InitDB.
func ensureRecentPlayTable() error {
	if db == nil {
		return errors.New("db not initialized")
	}
	return db.AutoMigrate(&recentPlay{})
}

// ensureFavoriteCollection 查找或创建固定的 "我的收藏" 本地歌单。
// 返回该 collection 的主键 ID,供 /favorites 路由使用。
func ensureFavoriteCollection() (uint, error) {
	favoriteCollectionIDOnce.Do(func() {
		if db == nil {
			favoriteCollectionErr = errors.New("db not initialized")
			return
		}
		var col Collection
		err := db.Where("name = ? AND kind = ?", favoriteCollectionName, collectionKindManual).
			First(&col).Error
		if err == nil {
			favoriteCollectionID = col.ID
			return
		}
		if !errors.Is(err, gorm.ErrRecordNotFound) {
			favoriteCollectionErr = err
			return
		}
		col = Collection{
			Name:        favoriteCollectionName,
			Description: "车载模式收藏的歌曲",
			Kind:        collectionKindManual,
			ContentType: collectionContentPlaylist,
			Source:      "local",
		}
		if err := db.Create(&col).Error; err != nil {
			favoriteCollectionErr = err
			return
		}
		favoriteCollectionID = col.ID
	})
	return favoriteCollectionID, favoriteCollectionErr
}

// RegisterCarRoutes 在 /music 下注册车载页相关的所有路由(HTML + JSON)。
func RegisterCarRoutes(api *gin.RouterGroup) {
	api.GET("/car", carPageHandler)

	api.GET("/recent", listRecentHandler)
	api.POST("/recent", addRecentHandler)
	api.DELETE("/recent", clearRecentHandler)

	api.GET("/recommend.json", recommendJSONHandler)
	api.GET("/user_playlists.json", userPlaylistsJSONHandler)
	api.GET("/playlist_categories.json", playlistCategoriesJSONHandler)
	api.GET("/category_playlists.json", categoryPlaylistsJSONHandler)
	api.GET("/search.json", searchJSONHandler)
	api.GET("/playlist.json", playlistDetailJSONHandler)
	api.GET("/album.json", albumDetailJSONHandler)

	api.GET("/favorites", listFavoritesHandler)
	api.GET("/favorites/contains", containsFavoriteHandler)
	api.POST("/favorites/toggle", toggleFavoriteHandler)
}

func carPageHandler(c *gin.Context) {
	c.HTML(http.StatusOK, "car.html", gin.H{
		"Root": RoutePrefix,
	})
}

// --- /recent ---

type recentPlayPayload struct {
	SongID   string      `json:"id"`
	Source   string      `json:"source"`
	Name     string      `json:"name"`
	Artist   string      `json:"artist"`
	Album    string      `json:"album"`
	Cover    string      `json:"cover"`
	Duration int         `json:"duration"`
	Extra    any `json:"extra"`
}

func addRecentHandler(c *gin.Context) {
	if db == nil {
		c.JSON(http.StatusServiceUnavailable, gin.H{"error": "db not ready"})
		return
	}

	var req recentPlayPayload
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid payload"})
		return
	}

	req.SongID = strings.TrimSpace(req.SongID)
	req.Source = strings.TrimSpace(req.Source)
	if req.SongID == "" || req.Source == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "missing id or source"})
		return
	}

	extraStr := ""
	if req.Extra != nil {
		if b, err := json.Marshal(req.Extra); err == nil {
			extraStr = string(b)
		}
	}

	now := time.Now().Unix()
	entry := recentPlay{
		SongID:       req.SongID,
		Source:       req.Source,
		Name:         strings.TrimSpace(req.Name),
		Artist:       strings.TrimSpace(req.Artist),
		Album:        strings.TrimSpace(req.Album),
		Cover:        strings.TrimSpace(req.Cover),
		Duration:     req.Duration,
		Extra:        extraStr,
		LastPlayedAt: now,
		PlayCount:    1,
	}

	// upsert: 命中唯一索引则更新 LastPlayedAt 并 PlayCount++
	err := db.Clauses(clause.OnConflict{
		Columns: []clause.Column{{Name: "song_id"}, {Name: "source"}},
		DoUpdates: clause.Assignments(map[string]interface{}{
			"name":           entry.Name,
			"artist":         entry.Artist,
			"album":          entry.Album,
			"cover":          entry.Cover,
			"duration":       entry.Duration,
			"extra":          entry.Extra,
			"last_played_at": entry.LastPlayedAt,
			"play_count":     gorm.Expr("play_count + 1"),
		}),
	}).Create(&entry).Error
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	// 异步触发表清理:超过 retain 阈值时,保留最近 N 条
	go pruneRecentPlays()

	c.JSON(http.StatusOK, gin.H{"status": "ok"})
}

func pruneRecentPlays() {
	if db == nil {
		return
	}
	var count int64
	if err := db.Model(&recentPlay{}).Count(&count).Error; err != nil {
		return
	}
	if count <= recentPlayRetainThreshold {
		return
	}
	// 找到第 recentPlayRetainThreshold 新的那条,删除更老的
	var cutoff recentPlay
	if err := db.Order("last_played_at DESC").
		Offset(recentPlayRetainThreshold - 1).
		Limit(1).
		Find(&cutoff).Error; err != nil {
		return
	}
	if cutoff.LastPlayedAt > 0 {
		db.Where("last_played_at < ?", cutoff.LastPlayedAt).Delete(&recentPlay{})
	}
}

func listRecentHandler(c *gin.Context) {
	if db == nil {
		c.JSON(http.StatusServiceUnavailable, gin.H{"error": "db not ready"})
		return
	}

	limit := recentPlayDefaultLimit
	if raw := strings.TrimSpace(c.Query("limit")); raw != "" {
		if n, err := strconv.Atoi(raw); err == nil && n > 0 {
			limit = n
		}
	}
	if limit > recentPlayMaxLimit {
		limit = recentPlayMaxLimit
	}

	var rows []recentPlay
	if err := db.Order("last_played_at DESC").Limit(limit).Find(&rows).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	resp := make([]gin.H, 0, len(rows))
	for _, r := range rows {
		resp = append(resp, gin.H{
			"id":             r.SongID,
			"source":         r.Source,
			"name":           r.Name,
			"artist":         r.Artist,
			"album":          r.Album,
			"cover":          r.Cover,
			"duration":       r.Duration,
			"extra":          decodeSongExtraObject(r.Extra),
			"last_played_at": r.LastPlayedAt,
			"play_count":     r.PlayCount,
		})
	}
	c.JSON(http.StatusOK, resp)
}

func clearRecentHandler(c *gin.Context) {
	if db == nil {
		c.JSON(http.StatusServiceUnavailable, gin.H{"error": "db not ready"})
		return
	}
	if err := db.Where("1 = 1").Delete(&recentPlay{}).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"status": "ok"})
}

// --- /favorites ---

func listFavoritesHandler(c *gin.Context) {
	id, err := ensureFavoriteCollection()
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	collection, err := loadCollection(strconv.FormatUint(uint64(id), 10))
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	resp, err := collectionSongsJSON(collection)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{
		"collection_id": id,
		"songs":         resp,
	})
}

// containsFavoriteHandler 判断给定的 id+source 是否已在收藏中。
// 支持单查询: ?id=xxx&source=yyy
func containsFavoriteHandler(c *gin.Context) {
	songID := strings.TrimSpace(c.Query("id"))
	source := strings.TrimSpace(c.Query("source"))
	if songID == "" || source == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "missing id or source"})
		return
	}
	id, err := ensureFavoriteCollection()
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	var count int64
	if err := db.Model(&SavedSong{}).
		Where("collection_id = ? AND song_id = ? AND source = ?", id, songID, source).
		Count(&count).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"favorited": count > 0})
}

func toggleFavoriteHandler(c *gin.Context) {
	var req struct {
		SongID   string      `json:"id"`
		Source   string      `json:"source"`
		Name     string      `json:"name"`
		Artist   string      `json:"artist"`
		Cover    string      `json:"cover"`
		Duration int         `json:"duration"`
		Extra    any `json:"extra"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid payload"})
		return
	}
	req.SongID = strings.TrimSpace(req.SongID)
	req.Source = strings.TrimSpace(req.Source)
	if req.SongID == "" || req.Source == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "missing id or source"})
		return
	}

	id, err := ensureFavoriteCollection()
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	var existing SavedSong
	err = db.Where("collection_id = ? AND song_id = ? AND source = ?", id, req.SongID, req.Source).
		First(&existing).Error
	if err == nil {
		if err := db.Delete(&existing).Error; err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
			return
		}
		c.JSON(http.StatusOK, gin.H{"favorited": false})
		return
	}
	if !errors.Is(err, gorm.ErrRecordNotFound) {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	extraStr := ""
	if req.Extra != nil {
		if b, jerr := json.Marshal(req.Extra); jerr == nil {
			extraStr = string(b)
		}
	}
	song := SavedSong{
		CollectionID: id,
		SongID:       req.SongID,
		Source:       req.Source,
		Name:         req.Name,
		Artist:       req.Artist,
		Cover:        req.Cover,
		Duration:     req.Duration,
		Extra:        extraStr,
	}
	if err := db.Clauses(clause.OnConflict{DoNothing: true}).Create(&song).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"favorited": true})
}

// --- /recommend.json ---

func recommendJSONHandler(c *gin.Context) {
	sources := filterAvailableSources(c.QueryArray("sources"), core.GetRecommendSourceNames())
	tabs, errMsg := loadPlaylistSourceTabs(sources, func(src string) ([]model.Playlist, error) {
		fn := core.GetRecommendFunc(src)
		if fn == nil {
			return nil, errors.New("该源不支持推荐歌单")
		}
		return fn()
	})

	type tabPayload struct {
		Source     string           `json:"source"`
		SourceName string           `json:"source_name"`
		Count      int              `json:"count"`
		Playlists  []model.Playlist `json:"playlists"`
		Error      string           `json:"error,omitempty"`
	}
	out := make([]tabPayload, 0, len(tabs))
	for _, t := range tabs {
		out = append(out, tabPayload{
			Source:     t.Source,
			SourceName: t.Name,
			Count:      t.Count,
			Playlists:  t.Playlists,
			Error:      t.Error,
		})
	}

	c.JSON(http.StatusOK, gin.H{
		"sources": sources,
		"tabs":    out,
		"error":   errMsg,
	})
}

// --- /user_playlists.json ---
//
// 返回已登录用户在各音源(netease/qq/kugou/soda)下创建/收藏的歌单。
// cookies 在后端按 source 取存,前端无需传 uid。未登录的源 tab.error 非空。
func userPlaylistsJSONHandler(c *gin.Context) {
	sources := filterAvailableSources(c.QueryArray("sources"), core.GetUserPlaylistSourceNames())
	tabs, errMsg := loadPlaylistSourceTabs(sources, func(src string) ([]model.Playlist, error) {
		fn := core.GetUserPlaylistsFunc(src)
		if fn == nil {
			return nil, errors.New("该源不支持个人歌单")
		}
		return fn(1, 50)
	})

	type tabPayload struct {
		Source     string           `json:"source"`
		SourceName string           `json:"source_name"`
		Count      int              `json:"count"`
		Playlists  []model.Playlist `json:"playlists"`
		Error      string           `json:"error,omitempty"`
	}
	out := make([]tabPayload, 0, len(tabs))
	for _, t := range tabs {
		out = append(out, tabPayload{
			Source:     t.Source,
			SourceName: t.Name,
			Count:      t.Count,
			Playlists:  t.Playlists,
			Error:      t.Error,
		})
	}

	c.JSON(http.StatusOK, gin.H{
		"sources": sources,
		"tabs":    out,
		"error":   errMsg,
	})
}

// --- /playlist_categories.json ---
//
// 返回各音源的歌单分类。形态:
//   tabs: [{source, source_name, count, groups: [{name, categories: [{id, name, hot}]}]}]
func playlistCategoriesJSONHandler(c *gin.Context) {
	sources := playlistCategorySourcesFromQuery(c)
	pageSources, errMsg := loadPlaylistCategoryPageSources(sources)

	type catPayload struct {
		ID   string `json:"id"`
		Name string `json:"name"`
		Hot  bool   `json:"hot,omitempty"`
	}
	type groupPayload struct {
		Name       string       `json:"name"`
		Categories []catPayload `json:"categories"`
	}
	type tabPayload struct {
		Source     string         `json:"source"`
		SourceName string         `json:"source_name"`
		Count      int            `json:"count"`
		Groups     []groupPayload `json:"groups"`
	}

	out := make([]tabPayload, 0, len(pageSources))
	for _, src := range pageSources {
		groups := make([]groupPayload, 0, len(src.Groups))
		for _, g := range src.Groups {
			cats := make([]catPayload, 0, len(g.Categories))
			for _, cat := range g.Categories {
				cats = append(cats, catPayload{ID: cat.ID, Name: cat.Name, Hot: cat.Hot})
			}
			groups = append(groups, groupPayload{Name: g.Name, Categories: cats})
		}
		out = append(out, tabPayload{
			Source:     src.Source,
			SourceName: src.Name,
			Count:      src.Count,
			Groups:     groups,
		})
	}

	c.JSON(http.StatusOK, gin.H{
		"sources": sources,
		"tabs":    out,
		"error":   errMsg,
	})
}

// --- /category_playlists.json ---
//
// 返回指定音源 + 分类下的歌单列表。?source=&category_id=&page=&limit=
// page 默认 1, limit 默认 120(与 HTML 版一致)。
func categoryPlaylistsJSONHandler(c *gin.Context) {
	source := strings.TrimSpace(c.Query("source"))
	categoryID := strings.TrimSpace(c.Query("category_id"))
	page, _ := strconv.Atoi(strings.TrimSpace(c.Query("page")))
	if page <= 0 {
		page = 1
	}
	limit, _ := strconv.Atoi(strings.TrimSpace(c.Query("limit")))
	if limit <= 0 || limit > 200 {
		limit = 120
	}

	fn := core.GetCategoryPlaylistsFunc(source)
	if source == "" || fn == nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "该源不支持歌单分类"})
		return
	}

	playlists, err := fn(categoryID, page, limit)
	for i := range playlists {
		playlists[i].Source = source
	}
	errMsg := ""
	if err != nil {
		errMsg = err.Error()
	}

	c.JSON(http.StatusOK, gin.H{
		"source":    source,
		"playlists": playlists,
		"error":     errMsg,
	})
}

// --- /search.json ---

func searchJSONHandler(c *gin.Context) {
	keyword := strings.TrimSpace(c.Query("q"))
	searchType := c.DefaultQuery("type", "song")
	sources := c.QueryArray("sources")
	if len(sources) == 0 {
		sources = defaultSourcesForSearchType(searchType)
	}

	if keyword == "" {
		c.JSON(http.StatusOK, gin.H{"songs": []model.Song{}, "playlists": []model.Playlist{}})
		return
	}

	var allSongs []model.Song
	var allPlaylists []model.Playlist
	var wg sync.WaitGroup
	var mu sync.Mutex

	for _, src := range sources {
		wg.Add(1)
		go func(s string) {
			defer wg.Done()
			switch searchType {
			case "playlist":
				fn := core.GetPlaylistSearchFunc(s)
				if fn == nil {
					return
				}
				res, err := fn(keyword)
				if err != nil {
					return
				}
				for i := range res {
					res[i].Source = s
				}
				mu.Lock()
				allPlaylists = append(allPlaylists, res...)
				mu.Unlock()
			case "album":
				fn := core.GetAlbumSearchFunc(s)
				if fn == nil {
					return
				}
				res, err := fn(keyword)
				if err != nil {
					return
				}
				for i := range res {
					res[i].Source = s
				}
				mu.Lock()
				allPlaylists = append(allPlaylists, res...)
				mu.Unlock()
			default:
				fn := core.GetSearchFunc(s)
				if fn == nil {
					return
				}
				res, err := fn(keyword)
				if err != nil {
					return
				}
				for i := range res {
					res[i].Source = s
				}
				mu.Lock()
				allSongs = append(allSongs, res...)
				mu.Unlock()
			}
		}(src)
	}
	wg.Wait()

	c.JSON(http.StatusOK, gin.H{
		"keyword":   keyword,
		"type":      searchType,
		"sources":   sources,
		"songs":     allSongs,
		"playlists": allPlaylists,
	})
}

// --- /playlist.json & /album.json (在线歌单/专辑详情,供车载页内联展示) ---

func playlistDetailJSONHandler(c *gin.Context) {
	id := strings.TrimSpace(c.Query("id"))
	src := strings.TrimSpace(c.Query("source"))
	if id == "" || src == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "缺少 id 或 source"})
		return
	}
	fn := core.GetPlaylistDetailFunc(src)
	if fn == nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "该源不支持查看歌单详情"})
		return
	}
	songs, err := fn(id)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error(), "songs": []model.Song{}})
		return
	}
	for i := range songs {
		if strings.TrimSpace(songs[i].Source) == "" {
			songs[i].Source = src
		}
	}
	c.JSON(http.StatusOK, gin.H{
		"id":     id,
		"source": src,
		"songs":  songs,
	})
}

func albumDetailJSONHandler(c *gin.Context) {
	id := strings.TrimSpace(c.Query("id"))
	src := strings.TrimSpace(c.Query("source"))
	if id == "" || src == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "缺少 id 或 source"})
		return
	}
	fn := core.GetAlbumDetailFunc(src)
	if fn == nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "该源不支持查看专辑详情"})
		return
	}
	songs, err := fn(id)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error(), "songs": []model.Song{}})
		return
	}
	for i := range songs {
		if strings.TrimSpace(songs[i].Source) == "" {
			songs[i].Source = src
		}
	}
	c.JSON(http.StatusOK, gin.H{
		"id":     id,
		"source": src,
		"songs":  songs,
	})
}
