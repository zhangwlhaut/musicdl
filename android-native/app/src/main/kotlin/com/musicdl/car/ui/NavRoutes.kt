package com.musicdl.car.ui

/** Single source of truth for in-app navigation routes. */
object NavRoutes {
    // 顶级 Tab(TopTabBar 切换)
    const val HOME = "home"
    const val DISCOVER = "discover"
    const val MINE = "mine"
    const val SEARCH = "search"
    const val SETTINGS = "settings"

    /** 顶级 Tab 列表(用于 TopTabBar 渲染) */
    val TOP_TABS = listOf(
        HOME to "首页",
        DISCOVER to "发现",
        MINE to "我的",
        SEARCH to "搜索",
        SETTINGS to "设置",
    )

    /** 从"我的"里跳出的二级页面 */
    const val FAVORITES = "favorites"
    const val LOCAL = "local"
    const val RECENT = "recent"

    /** Local collection details — arg: collectionId (Long). */
    const val COLLECTION = "collection/{id}"
    fun collection(id: Long) = "collection/$id"
    /** Online playlist/album details — args: source, id; optional query: name, cover, creator, content_type. */
    const val REMOTE_PLAYLIST = "remote_playlist/{source}/{id}?name={name}&cover={cover}&creator={creator}&content_type={content_type}"
    fun remotePlaylist(
        source: String,
        id: String,
        name: String = "",
        cover: String? = null,
        creator: String? = null,
        contentType: String = "playlist",
    ): String {
        val enc: (String) -> String = { java.net.URLEncoder.encode(it, "UTF-8") }
        val base = "remote_playlist/${enc(source)}/${enc(id)}"
        val params = buildList {
            if (name.isNotBlank()) add("name=${enc(name)}")
            if (!cover.isNullOrBlank()) add("cover=${enc(cover)}")
            if (!creator.isNullOrBlank()) add("creator=${enc(creator)}")
            if (contentType.isNotBlank() && contentType != "playlist") add("content_type=${enc(contentType)}")
        }
        return if (params.isEmpty()) base else base + "?" + params.joinToString("&")
    }

    /** 歌单分类(按音源浏览各种标签)入口页 */
    const val CATEGORIES = "categories"

    /** 某一分类下的歌单列表 — args: source, category_id, name (display) */
    const val CATEGORY_PLAYLISTS = "category_playlists/{source}/{category_id}?name={name}"
    fun categoryPlaylists(source: String, categoryId: String, name: String = ""): String {
        val enc: (String) -> String = { java.net.URLEncoder.encode(it, "UTF-8") }
        val base = "category_playlists/${enc(source)}/${enc(categoryId)}"
        return if (name.isBlank()) base else "$base?name=${enc(name)}"
    }
}
