/* car.js — 车载页面交互
   架构:复用 APlayer 作为播放引擎(只用 ap.audio + ap.list),自己实现 UI 控制。
   wake-lock 由 desktop_app/main.go 在 document 级捕获 <audio> 事件自动维护,无需主动调用。
*/

(function () {
    'use strict';

    const API = window.API_ROOT || '/music';

    // ===== APlayer 初始化(隐藏 UI) =====
    const ap = new APlayer({
        container: document.getElementById('aplayer'),
        fixed: false,
        autoplay: false,
        theme: '#10b981',
        loop: 'all',
        order: 'list',
        preload: 'metadata',
        volume: 0.85,
        listFolded: true,
        audio: []
    });
    window.ap = ap;

    // ===== 工具 =====
    function escapeHTML(s) {
        return String(s == null ? '' : s)
            .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
    }
    function fmtTime(sec) {
        if (!sec || !isFinite(sec) || sec < 0) return '00:00';
        const m = Math.floor(sec / 60);
        const s = Math.floor(sec % 60);
        return String(m).padStart(2, '0') + ':' + String(s).padStart(2, '0');
    }
    function buildStreamURL(song) {
        const params = new URLSearchParams();
        params.set('id', song.id || '');
        params.set('source', song.source || '');
        if (song.name) params.set('name', song.name);
        if (song.artist) params.set('artist', song.artist);
        if (song.album) params.set('album', song.album);
        if (song.cover) params.set('cover', song.cover);
        if (song.extra != null) {
            const ex = typeof song.extra === 'string' ? song.extra : JSON.stringify(song.extra);
            if (ex && ex !== 'null' && ex !== '""') params.set('extra', ex);
        }
        params.set('stream', '1');
        return `${API}/download?${params.toString()}`;
    }
    function coverFor(song) {
        if (song.source === 'local' && song.id) {
            return `${API}/local_music/cover?id=${encodeURIComponent(song.id)}`;
        }
        return song.cover || '';
    }
    async function fetchJSON(url, opts) {
        const resp = await fetch(url, opts);
        if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
        return resp.json();
    }
    function toast(msg) {
        const el = document.createElement('div');
        el.textContent = msg;
        el.style.cssText = 'position:fixed;left:50%;top:30px;transform:translateX(-50%);background:rgba(15,23,42,0.95);color:#f8fafc;padding:12px 22px;border-radius:12px;z-index:99999;font-size:16px;box-shadow:0 10px 30px rgba(0,0,0,0.4);border:1px solid rgba(255,255,255,0.1)';
        document.body.appendChild(el);
        setTimeout(() => el.remove(), 2000);
    }

    // ===== 队列管理 =====
    function enqueueAndPlay(songs, startIndex) {
        if (!Array.isArray(songs) || songs.length === 0) return;
        const normalized = songs.map(s => ({
            id: String(s.id || ''),
            source: String(s.source || ''),
            name: s.name || '未知歌曲',
            artist: s.artist || '',
            album: s.album || '',
            cover: coverFor(s),
            duration: s.duration || 0,
            extra: s.extra
        })).filter(s => s.id && s.source);

        if (normalized.length === 0) return;
        const idx = Math.max(0, Math.min(startIndex || 0, normalized.length - 1));

        // 构建 APlayer audio 列表
        const apAudios = normalized.map(s => ({
            name: s.name,
            artist: s.artist,
            cover: s.cover,
            url: buildStreamURL(s)
        }));

        // 替换队列
        try { ap.list.clear(); } catch (e) { /* ignore */ }
        ap.list.add(apAudios);
        ap.list.switch(idx);
        ap.play();

        window.__carQueue = normalized;
        renderQueue();
        refreshNowPlaying();
    }

    function playSingle(song) {
        enqueueAndPlay([song], 0);
    }

    // ===== Now Playing UI =====
    const els = {
        cover: document.getElementById('car-np-cover'),
        title: document.getElementById('car-np-title'),
        artist: document.getElementById('car-np-artist'),
        seek: document.getElementById('car-seek'),
        timeCur: document.getElementById('car-time-cur'),
        timeTotal: document.getElementById('car-time-total'),
        play: document.getElementById('car-play'),
        playIcon: document.getElementById('car-play-icon'),
        prev: document.getElementById('car-prev'),
        next: document.getElementById('car-next'),
        fav: document.getElementById('car-fav'),
        mode: document.getElementById('car-mode'),
        modeLabel: document.getElementById('car-mode-label'),
        queueToggle: document.getElementById('car-queue-toggle'),
        queue: document.getElementById('car-np-queue'),
        clock: document.getElementById('car-clock'),
        settings: document.getElementById('car-settings-btn'),
        content: document.getElementById('car-content')
    };

    const ICON_PLAY = '<path fill="currentColor" d="M8 5v14l11-7z"/>';
    const ICON_PAUSE = '<path fill="currentColor" d="M6 5h4v14H6zm8 0h4v14h-4z"/>';

    function currentSong() {
        const q = window.__carQueue || [];
        return q[ap.list.index] || null;
    }

    function refreshNowPlaying() {
        const song = currentSong();
        if (!song) {
            els.title.textContent = '未在播放';
            els.artist.textContent = '选择歌曲开始播放';
            els.cover.style.backgroundImage = '';
            els.cover.classList.remove('has-cover');
            els.fav.classList.remove('active');
            return;
        }
        els.title.textContent = song.name;
        els.artist.textContent = song.artist || '未知歌手';
        if (song.cover) {
            els.cover.style.backgroundImage = `url("${song.cover}")`;
            els.cover.classList.add('has-cover');
        } else {
            els.cover.style.backgroundImage = '';
            els.cover.classList.remove('has-cover');
        }
        refreshFavorite();
        // 上报最近播放
        fetch(`${API}/recent`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(song)
        }).catch(() => {});
        renderQueue();
    }

    function renderQueue() {
        const q = window.__carQueue || [];
        if (q.length === 0) {
            els.queue.innerHTML = '<div class="car-queue-empty">队列为空</div>';
            return;
        }
        const idx = ap.list.index;
        els.queue.innerHTML = q.map((s, i) => `
            <div class="car-queue-item ${i === idx ? 'playing' : ''}" data-idx="${i}">
                <div class="car-queue-item-meta">
                    <div class="car-queue-item-name">${escapeHTML(s.name)}</div>
                    <div class="car-queue-item-artist">${escapeHTML(s.artist)}</div>
                </div>
            </div>
        `).join('');
        els.queue.querySelectorAll('.car-queue-item').forEach(el => {
            el.addEventListener('click', () => {
                const i = parseInt(el.dataset.idx, 10);
                if (!isNaN(i)) ap.list.switch(i);
            });
        });
    }

    async function refreshFavorite() {
        const song = currentSong();
        if (!song) return;
        try {
            const data = await fetchJSON(`${API}/favorites/contains?id=${encodeURIComponent(song.id)}&source=${encodeURIComponent(song.source)}`);
            els.fav.classList.toggle('active', !!data.favorited);
        } catch (e) { /* ignore */ }
    }

    // ===== APlayer 事件 =====
    ap.on('play', () => {
        els.playIcon.innerHTML = ICON_PAUSE;
    });
    ap.on('pause', () => {
        els.playIcon.innerHTML = ICON_PLAY;
    });
    ap.on('listswitch', () => {
        refreshNowPlaying();
    });
    ap.on('listadd', renderQueue);
    ap.on('listremove', renderQueue);
    ap.on('listclear', () => {
        window.__carQueue = [];
        renderQueue();
    });
    ap.on('timeupdate', () => {
        const d = ap.audio.duration || 0;
        const t = ap.audio.currentTime || 0;
        els.timeCur.textContent = fmtTime(t);
        els.timeTotal.textContent = fmtTime(d);
        if (!seekDragging) {
            const pct = d > 0 ? (t / d * 1000) : 0;
            els.seek.value = pct;
            els.seek.style.backgroundSize = `${pct / 10}% 100%`;
        }
    });

    // ===== 控件绑定 =====
    let seekDragging = false;
    els.play.addEventListener('click', () => ap.toggle());
    els.prev.addEventListener('click', () => ap.skipBack());
    els.next.addEventListener('click', () => ap.skipForward());

    els.seek.addEventListener('input', () => { seekDragging = true; });
    els.seek.addEventListener('change', () => {
        const d = ap.audio.duration || 0;
        if (d > 0) ap.seek((els.seek.value / 1000) * d);
        seekDragging = false;
    });

    els.fav.addEventListener('click', async () => {
        const song = currentSong();
        if (!song) { toast('未在播放'); return; }
        try {
            const data = await fetchJSON(`${API}/favorites/toggle`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(song)
            });
            els.fav.classList.toggle('active', !!data.favorited);
            toast(data.favorited ? '已添加到收藏' : '已取消收藏');
        } catch (e) {
            toast('操作失败');
        }
    });

    const MODES = [
        { loop: 'all', order: 'list', label: '列表循环' },
        { loop: 'one', order: 'list', label: '单曲循环' },
        { loop: 'all', order: 'random', label: '随机播放' }
    ];
    let modeIdx = 0;
    els.mode.addEventListener('click', () => {
        modeIdx = (modeIdx + 1) % MODES.length;
        const m = MODES[modeIdx];
        ap.loop = m.loop;
        ap.order = m.order;
        els.modeLabel.textContent = m.label;
        toast(m.label);
    });

    els.queueToggle.addEventListener('click', () => {
        els.queue.classList.toggle('visible');
        els.queueToggle.classList.toggle('active');
    });

    els.settings.addEventListener('click', () => {
        window.location.href = `${API}/`;
    });

    // ===== 时钟 =====
    function tickClock() {
        const d = new Date();
        els.clock.textContent = String(d.getHours()).padStart(2, '0') + ':' + String(d.getMinutes()).padStart(2, '0');
    }
    tickClock();
    setInterval(tickClock, 30 * 1000);

    // ===== 视图路由 =====
    const views = {
        home: renderHome,
        favorites: renderFavorites,
        recent: renderRecent,
        local: renderLocalMusic,
        collections: renderCollections,
        recommend: renderRecommend,
        search: renderSearch
    };

    document.querySelectorAll('.car-nav-item').forEach(btn => {
        btn.addEventListener('click', () => {
            document.querySelectorAll('.car-nav-item').forEach(b => b.classList.remove('active'));
            btn.classList.add('active');
            const view = btn.dataset.view;
            if (views[view]) views[view]();
        });
    });

    function renderLoading() {
        els.content.innerHTML = '<div class="car-loading">加载中…</div>';
    }
    function renderEmpty(msg) {
        els.content.innerHTML = `<div class="car-empty">${escapeHTML(msg || '暂无内容')}</div>`;
    }
    function renderError(msg) {
        els.content.innerHTML = `<div class="car-empty" style="color:var(--car-danger)">${escapeHTML(msg)}</div>`;
    }

    function songRowHTML(song) {
        const cover = coverFor(song);
        return `
            <div class="car-song-row" data-id="${escapeHTML(song.id)}" data-source="${escapeHTML(song.source)}">
                <div class="car-song-cover" style="${cover ? `background-image:url('${cover}')` : ''}"></div>
                <div class="car-song-meta">
                    <div class="car-song-name">${escapeHTML(song.name || '未知歌曲')}</div>
                    <div class="car-song-artist">${escapeHTML(song.artist || '未知')}${song.album ? ' · ' + escapeHTML(song.album) : ''}</div>
                </div>
                <div class="car-song-duration">${fmtTime(song.duration || 0)}</div>
            </div>`;
    }

    function bindSongRows(songs) {
        els.content.querySelectorAll('.car-song-row').forEach((row, i) => {
            row.addEventListener('click', () => {
                enqueueAndPlay(songs, i);
            });
        });
    }

    function tileHTML(item, sub) {
        const cover = item.cover || '';
        return `
            <div class="car-tile" data-id="${escapeHTML(item.id)}" data-source="${escapeHTML(item.source || 'local')}">
                <div class="car-tile-cover" style="${cover ? `background-image:url('${cover}')` : ''}"></div>
                <div class="car-tile-meta">
                    <div class="car-tile-name">${escapeHTML(item.name)}</div>
                    <div class="car-tile-sub">${escapeHTML(sub || '')}</div>
                </div>
            </div>`;
    }

    // --- 首页:三块磁贴 ---
    async function renderHome() {
        renderLoading();
        try {
            const [recent, collections] = await Promise.all([
                fetchJSON(`${API}/recent?limit=12`).catch(() => []),
                fetchJSON(`${API}/collections`).catch(() => [])
            ]);
            const recentSection = recent.length > 0 ? `
                <div class="car-section-title">最近播放 <button class="car-section-action" data-act="more-recent">全部</button></div>
                <div class="car-tile-grid">
                    ${recent.slice(0, 8).map(s => `
                        <div class="car-tile" data-recent-id="${escapeHTML(s.id)}" data-recent-src="${escapeHTML(s.source)}">
                            <div class="car-tile-cover" style="${s.cover ? `background-image:url('${escapeHTML(s.cover)}')` : ''}"></div>
                            <div class="car-tile-meta">
                                <div class="car-tile-name">${escapeHTML(s.name)}</div>
                                <div class="car-tile-sub">${escapeHTML(s.artist || '')}</div>
                            </div>
                        </div>
                    `).join('')}
                </div>` : '';

            const collectionsSection = collections.length > 0 ? `
                <div class="car-section-title">本地歌单 <button class="car-section-action" data-act="more-col">全部</button></div>
                <div class="car-tile-grid">
                    ${collections.slice(0, 8).map(c => tileHTML({
                        id: c.id, name: c.name, cover: c.cover, source: 'local'
                    }, c.track_count ? c.track_count + ' 首' : '本地歌单')).join('')}
                </div>` : '';

            els.content.innerHTML = `
                ${recentSection}
                ${collectionsSection}
                <div class="car-section-title">快速入口</div>
                <div class="car-tile-grid">
                    <div class="car-tile" data-shortcut="local">
                        <div class="car-tile-cover" style="background:linear-gradient(135deg,#3b82f6,#1d4ed8);display:grid;place-items:center;color:#fff;font-size:48px">♪</div>
                        <div class="car-tile-meta"><div class="car-tile-name">本地音乐</div><div class="car-tile-sub">扫描本地文件夹</div></div>
                    </div>
                    <div class="car-tile" data-shortcut="favorites">
                        <div class="car-tile-cover" style="background:linear-gradient(135deg,#ef4444,#b91c1c);display:grid;place-items:center;color:#fff;font-size:48px">♥</div>
                        <div class="car-tile-meta"><div class="car-tile-name">我的收藏</div><div class="car-tile-sub">车载收藏歌曲</div></div>
                    </div>
                    <div class="car-tile" data-shortcut="recommend">
                        <div class="car-tile-cover" style="background:linear-gradient(135deg,#f59e0b,#b45309);display:grid;place-items:center;color:#fff;font-size:48px">★</div>
                        <div class="car-tile-meta"><div class="car-tile-name">每日推荐</div><div class="car-tile-sub">在线推荐歌单</div></div>
                    </div>
                    <div class="car-tile" data-shortcut="search">
                        <div class="car-tile-cover" style="background:linear-gradient(135deg,#10b981,#047857);display:grid;place-items:center;color:#fff;font-size:48px">🔍</div>
                        <div class="car-tile-meta"><div class="car-tile-name">搜索</div><div class="car-tile-sub">在线搜歌</div></div>
                    </div>
                </div>
            `;

            // 最近播放 tile 点击 => 直接播放该歌曲
            els.content.querySelectorAll('[data-recent-id]').forEach(el => {
                el.addEventListener('click', () => {
                    const id = el.dataset.recentId;
                    const src = el.dataset.recentSrc;
                    const song = recent.find(s => s.id === id && s.source === src);
                    if (song) playSingle(song);
                });
            });
            // 歌单 tile 点击 => 进入歌单详情
            els.content.querySelectorAll('.car-tile[data-id]').forEach(el => {
                el.addEventListener('click', () => openCollection(el.dataset.id));
            });
            // 快速入口
            els.content.querySelectorAll('[data-shortcut]').forEach(el => {
                el.addEventListener('click', () => {
                    const v = el.dataset.shortcut;
                    document.querySelector(`.car-nav-item[data-view="${v}"]`)?.click();
                });
            });
            // 全部按钮
            els.content.querySelectorAll('[data-act]').forEach(el => {
                el.addEventListener('click', (e) => {
                    e.stopPropagation();
                    const act = el.dataset.act;
                    if (act === 'more-recent') document.querySelector('.car-nav-item[data-view="recent"]')?.click();
                    if (act === 'more-col') document.querySelector('.car-nav-item[data-view="collections"]')?.click();
                });
            });

            if (recent.length === 0 && collections.length === 0) {
                // 完全没有数据时,首页只剩快速入口也 OK
            }
        } catch (e) {
            renderError('加载首页失败: ' + e.message);
        }
    }

    // --- 收藏 ---
    async function renderFavorites() {
        renderLoading();
        try {
            const data = await fetchJSON(`${API}/favorites`);
            const songs = data.songs || [];
            if (songs.length === 0) {
                renderEmpty('暂无收藏。播放歌曲后点击 ♥ 添加。');
                return;
            }
            els.content.innerHTML = `
                <div class="car-section-title">我的收藏 (${songs.length})
                    <button class="car-section-action" id="car-play-fav-all">全部播放</button>
                </div>
                <div class="car-song-list">${songs.map(songRowHTML).join('')}</div>
            `;
            bindSongRows(songs);
            document.getElementById('car-play-fav-all')?.addEventListener('click', () => enqueueAndPlay(songs, 0));
        } catch (e) {
            renderError('加载收藏失败: ' + e.message);
        }
    }

    // --- 最近 ---
    async function renderRecent() {
        renderLoading();
        try {
            const list = await fetchJSON(`${API}/recent?limit=100`);
            if (!Array.isArray(list) || list.length === 0) {
                renderEmpty('暂无播放记录');
                return;
            }
            els.content.innerHTML = `
                <div class="car-section-title">最近播放 (${list.length})
                    <button class="car-section-action" id="car-recent-clear">清空</button>
                </div>
                <div class="car-song-list">${list.map(songRowHTML).join('')}</div>
            `;
            bindSongRows(list);
            document.getElementById('car-recent-clear')?.addEventListener('click', async () => {
                if (!confirm('清空最近播放记录?')) return;
                await fetch(`${API}/recent`, { method: 'DELETE' });
                renderRecent();
            });
        } catch (e) {
            renderError('加载失败: ' + e.message);
        }
    }

    // --- 本地音乐 ---
    async function renderLocalMusic() {
        renderLoading();
        try {
            const data = await fetchJSON(`${API}/local_music?limit=500`);
            const tracks = (data.tracks || []).map(t => ({
                id: t.id,
                source: 'local',
                name: t.name || t.filename,
                artist: t.artist,
                album: t.album,
                cover: '',
                duration: t.duration,
                extra: t.extra
            }));
            if (tracks.length === 0) {
                renderEmpty(`本地目录无音乐文件 (${data.download_dir || '未设置'})`);
                return;
            }
            els.content.innerHTML = `
                <div class="car-section-title">本地音乐 (${tracks.length})
                    <button class="car-section-action" id="car-local-play-all">全部播放</button>
                </div>
                <div class="car-song-list">${tracks.map(songRowHTML).join('')}</div>
            `;
            bindSongRows(tracks);
            document.getElementById('car-local-play-all')?.addEventListener('click', () => enqueueAndPlay(tracks, 0));
        } catch (e) {
            renderError('加载本地音乐失败: ' + e.message);
        }
    }

    // --- 本地歌单 ---
    async function renderCollections() {
        renderLoading();
        try {
            const list = await fetchJSON(`${API}/collections?include_imported=1`);
            if (list.length === 0) {
                renderEmpty('暂无本地歌单。可在手机模式下创建或导入歌单。');
                return;
            }
            els.content.innerHTML = `
                <div class="car-section-title">本地歌单 (${list.length})</div>
                <div class="car-tile-grid">
                    ${list.map(c => tileHTML({
                        id: c.id, name: c.name, cover: c.cover, source: 'local'
                    }, c.track_count ? c.track_count + ' 首' : '')).join('')}
                </div>
            `;
            els.content.querySelectorAll('.car-tile[data-id]').forEach(el => {
                el.addEventListener('click', () => openCollection(el.dataset.id));
            });
        } catch (e) {
            renderError('加载歌单失败: ' + e.message);
        }
    }

    async function openCollection(id) {
        renderLoading();
        try {
            const data = await fetchJSON(`${API}/collections/${encodeURIComponent(id)}/songs`);
            const songs = (Array.isArray(data) ? data : []).map(s => ({
                id: s.id, source: s.source, name: s.name, artist: s.artist,
                album: s.album, cover: s.cover, duration: s.duration, extra: s.extra
            }));
            if (songs.length === 0) {
                renderEmpty('歌单为空');
                return;
            }
            els.content.innerHTML = `
                <div class="car-section-title">
                    <span><button class="car-section-action" id="car-back" style="margin-right:12px">← 返回</button>歌单 (${songs.length})</span>
                    <button class="car-section-action" id="car-col-play-all">全部播放</button>
                </div>
                <div class="car-song-list">${songs.map(songRowHTML).join('')}</div>
            `;
            bindSongRows(songs);
            document.getElementById('car-back')?.addEventListener('click', renderCollections);
            document.getElementById('car-col-play-all')?.addEventListener('click', () => enqueueAndPlay(songs, 0));
        } catch (e) {
            renderError('加载歌单内容失败: ' + e.message);
        }
    }

    // --- 每日推荐 ---
    async function renderRecommend() {
        renderLoading();
        try {
            const data = await fetchJSON(`${API}/recommend.json`);
            const tabs = data.tabs || [];
            const sections = tabs.map(tab => {
                if (!tab.playlists || tab.playlists.length === 0) return '';
                return `
                    <div class="car-section-title">${escapeHTML(tab.source_name || tab.source)}</div>
                    <div class="car-tile-grid">
                        ${tab.playlists.slice(0, 12).map(p => `
                            <div class="car-tile" data-recplay-id="${escapeHTML(p.id)}" data-recplay-src="${escapeHTML(p.source)}" data-recplay-name="${escapeHTML(p.name)}">
                                <div class="car-tile-cover" style="${p.cover ? `background-image:url('${escapeHTML(p.cover)}')` : ''}"></div>
                                <div class="car-tile-meta">
                                    <div class="car-tile-name">${escapeHTML(p.name)}</div>
                                    <div class="car-tile-sub">${escapeHTML(p.creator || '')}</div>
                                </div>
                            </div>`).join('')}
                    </div>`;
            }).filter(Boolean).join('');

            els.content.innerHTML = sections || '<div class="car-empty">暂无推荐内容</div>';
            els.content.querySelectorAll('[data-recplay-id]').forEach(el => {
                el.addEventListener('click', () => openRemotePlaylist(el.dataset.recplayId, el.dataset.recplaySrc, el.dataset.recplayName));
            });
        } catch (e) {
            renderError('加载推荐失败: ' + e.message);
        }
    }

    async function openRemotePlaylist(id, source, name) {
        renderLoading();
        try {
            const data = await fetchJSON(`${API}/playlist.json?id=${encodeURIComponent(id)}&source=${encodeURIComponent(source)}`);
            const songs = (data.songs || []).map(s => ({
                id: s.id, source: s.source || source, name: s.name, artist: s.artist,
                album: s.album, cover: s.cover || '', duration: s.duration, extra: s.extra
            }));
            if (songs.length === 0) {
                renderEmpty('歌单为空或加载失败');
                return;
            }
            els.content.innerHTML = `
                <div class="car-section-title">
                    <span><button class="car-section-action" id="car-back" style="margin-right:12px">← 返回</button>${escapeHTML(name || '歌单')} (${songs.length})</span>
                    <button class="car-section-action" id="car-remote-play-all">全部播放</button>
                </div>
                <div class="car-song-list">${songs.map(songRowHTML).join('')}</div>
            `;
            bindSongRows(songs);
            document.getElementById('car-back')?.addEventListener('click', renderRecommend);
            document.getElementById('car-remote-play-all')?.addEventListener('click', () => enqueueAndPlay(songs, 0));
        } catch (e) {
            renderError('加载歌单失败: ' + e.message);
        }
    }

    // --- 搜索 ---
    function renderSearch() {
        els.content.innerHTML = `
            <div class="car-search-bar">
                <input type="text" class="car-search-input" id="car-q" placeholder="搜索歌曲、歌手 …" autocomplete="off">
                <button class="car-search-submit" id="car-q-go">搜索</button>
            </div>
            <div id="car-search-results"></div>
        `;
        const q = document.getElementById('car-q');
        const go = document.getElementById('car-q-go');
        const results = document.getElementById('car-search-results');
        const doSearch = async () => {
            const kw = q.value.trim();
            if (!kw) return;
            results.innerHTML = '<div class="car-loading">搜索中…</div>';
            try {
                const data = await fetchJSON(`${API}/search.json?q=${encodeURIComponent(kw)}&type=song`);
                const songs = data.songs || [];
                if (songs.length === 0) { results.innerHTML = '<div class="car-empty">无结果</div>'; return; }
                results.innerHTML = `
                    <div class="car-section-title">搜索结果 (${songs.length})
                        <button class="car-section-action" id="car-search-play-all">全部播放</button>
                    </div>
                    <div class="car-song-list">${songs.map(songRowHTML).join('')}</div>
                `;
                results.querySelectorAll('.car-song-row').forEach((row, i) => {
                    row.addEventListener('click', () => enqueueAndPlay(songs, i));
                });
                document.getElementById('car-search-play-all')?.addEventListener('click', () => enqueueAndPlay(songs, 0));
            } catch (e) {
                results.innerHTML = `<div class="car-empty" style="color:var(--car-danger)">搜索失败: ${escapeHTML(e.message)}</div>`;
            }
        };
        go.addEventListener('click', doSearch);
        q.addEventListener('keydown', e => { if (e.key === 'Enter') doSearch(); });
        q.focus();
    }

    // ===== 启动 =====
    // 尝试锁定横屏
    try {
        if (screen.orientation && screen.orientation.lock) {
            screen.orientation.lock('landscape').catch(() => {});
        }
    } catch (e) { /* ignore */ }

    renderHome();
    refreshNowPlaying();
    autoplayRecentOnStart();

    // 启动时自动加载最近播放歌单并尝试播放第一首。
    // 浏览器可能因 autoplay policy 拒绝播放,此时静默装载队列,等待用户首次点击。
    async function autoplayRecentOnStart() {
        // 用户可在控制台 localStorage.setItem('car_autoplay','off') 关闭
        if (localStorage.getItem('car_autoplay') === 'off') return;
        try {
            const list = await fetchJSON(`${API}/recent?limit=60`);
            if (!Array.isArray(list) || list.length === 0) return;
            const songs = list.map(s => ({
                id: s.id, source: s.source, name: s.name, artist: s.artist,
                album: s.album, cover: s.cover, duration: s.duration, extra: s.extra
            })).filter(s => s.id && s.source);
            if (songs.length === 0) return;

            // 装入队列 + 切换到第一首(此时还没真正 play,因为下面才调 ap.play())
            const apAudios = songs.map(s => ({
                name: s.name, artist: s.artist, cover: coverFor(s), url: buildStreamURL(s)
            }));
            try { ap.list.clear(); } catch (e) { /* ignore */ }
            ap.list.add(apAudios);
            ap.list.switch(0);
            window.__carQueue = songs;
            renderQueue();
            refreshNowPlaying();

            // 尝试播放;若被 autoplay policy 拦截,提示用户点击屏幕授权
            try {
                const p = ap.audio.play();
                if (p && typeof p.then === 'function') {
                    await p;
                }
            } catch (err) {
                showAutoplayHint();
            }
        } catch (e) { /* ignore */ }
    }

    function showAutoplayHint() {
        toast('点击屏幕任意处开始播放最近歌单');
        const once = () => {
            document.removeEventListener('click', once, true);
            document.removeEventListener('touchstart', once, true);
            try { ap.play(); } catch (e) { /* ignore */ }
        };
        document.addEventListener('click', once, true);
        document.addEventListener('touchstart', once, true);
    }

})();
