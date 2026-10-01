/**
 * 播放浮层（文档 8.5）。填词歌曲、喊麦、未归档 三个页面共用这一个。
 *
 * 需求明确「加速播放无需单独页面」，所以播放是全屏浮层而不是一页，参照阅读页的模式：
 * 只占左侧导航右边，不盖住菜单栏。
 *
 * 四条实现上的关键决定，改这个文件前先看：
 *
 * 1. **只用一个 <video> 同时承担视频和音频。** <video> 播 mp3 完全正常，只是没画面。
 *    两套元素会长出两套控制逻辑（倍速、进度、歌词同步、快捷键各一份），
 *    音频模式只是加个 CSS 类，把画面区域让给歌词大面板。
 *
 * 2. **歌词同步的时间源是 video.currentTime，不自己累加时间。** 这样倍速、拖进度条、
 *    缓冲卡顿三件事全都自动对上，零额外代码。
 *
 * 3. **驱动用 requestAnimationFrame，不用 timeupdate。** timeupdate 每秒只触发约 4 次，
 *    1.6 倍速下等于每 0.4 秒的播放内容才更新一次高亮，肉眼可见地跳。
 *
 * 4. **播放中调倍速不写库**（文档 4.12）。只有点「存为默认」才调 PUT /group-rate。
 *    调倍速是个高频的试探动作，每次都存等于把默认值搞成「最后一次随手拨到哪」。
 *
 * 5. **一行可能是一首歌的多个版本（variant）。** 归并行有 {@code variants[]}，头部在
 *    版本数 >1 时给一个下拉切换；切换 = 用该 variant 的 kind/partition/mainName 重取
 *    play-info 换 src —— 每个 variant 的 defaultRate、歌词清单都是独立的，不能复用上一个的。
 *    歌词优先当前 variant，没有则回退到同组其它 variant 的任意一个歌词。喊麦每行恒一个。
 *
 * **两边只差两个约定**（调用方通过 {@code opts.cfg} 传，见 {@link #SONG_CFG}）：
 * 接口前缀 {@code base}（{@code /api/song} 与 {@code /api/shout}）、{@code hasOriginal}
 * （喊麦不解析文件名、没有原曲名，所以没有原曲名那一级倍速回落，也没有「存到原曲名上」）。
 * 喊麦行的 {@code parsed} 恒为 false，抬头直接显示主名 —— 不必再判一次有没有作者。
 */
const SongPlayer = (() => {

    /** HEVC 解码探测结果。不依赖具体文件，只依赖浏览器与显卡，所以整个会话缓存一次 */
    let hevcSupport = null;

    /** 歌曲侧的约定（与 SongActions.SONG_CFG 同源）。喊麦传 SongActions.SHOUT_CFG */
    const SONG_CFG = {base: '/api/song', hasOriginal: true};

    /** 调用方的约定；没传的字段按歌曲那份兜底 */
    function cfgOf(opts) {
        return Object.assign({}, SONG_CFG, (opts && opts.cfg) || {});
    }

    let state = null;

    /**
     * 探一次 HEVC 解码能力（文档 8.0）。
     *
     * 浏览器不自带 HEVC 解码器，是调系统硬解 —— 显卡不支持就彻底不播，装不装
     * Microsoft Store 的「HEVC 视频扩展」都一样。所以探不过要直说，别让人对着黑屏猜。
     *
     * 两种 fourcc 都探：hev1 与 hvc1。实测这批文件全是 hev1，只有 Safari 不认它。
     */
    function probeHevc() {
        if (hevcSupport !== null) {
            return hevcSupport;
        }
        const types = [
            'video/mp4; codecs="hev1.2.4.L120.b0"',   // Main 10，实测这批就是它
            'video/mp4; codecs="hvc1.2.4.L120.b0"',
            'video/mp4; codecs="hev1.1.6.L93.b0"',    // 8-bit Main，门槛低一点
            'video/mp4; codecs="hvc1.1.6.L93.b0"'
        ];
        const probe = document.createElement('video');
        let canPlay = false;
        for (const type of types) {
            if (probe.canPlayType(type) === 'probably') {
                canPlay = true;
                break;
            }
            if (window.MediaSource && MediaSource.isTypeSupported(type)) {
                canPlay = true;
                break;
            }
        }
        hevcSupport = canPlay;
        return hevcSupport;
    }

    /** 行的身份：归并行用 mergeKey（喊麦那边就是主名），供上一首/下一首在 list 里定位 */
    function idOf(row) {
        return row.mergeKey;
    }

    /**
     * variant 的定位参数拼成 query（play-info 与 lyric 共用）。
     * <p>分区为空（待打分区）时不带这个参数 —— 后端把「没有分区」当成待打分区。
     */
    function locateQuery(v) {
        const parts = [];
        if (v.partitionName) {
            parts.push('partition=' + encodeURIComponent(v.partitionName));
        }
        parts.push('mainName=' + encodeURIComponent(v.mainName));
        return parts.join('&');
    }

    /** 作者名 → 各自可点击的链接，跳作者统计页对应项。合并行用 artists，旧行用 artistText */
    function artistLinks(row) {
        const artists = row.artists || (row.artistText ? row.artistText.split(' & ') : []);
        return artists.map(a =>
            '<a href="#song-artist?' + Util.qs('artist', a) + '">' + UI.esc(a) + '</a>').join(' & ');
    }

    /**
     * 打开浮层播一行。
     *
     * @param row       归并行（{@code variants[]}）
     * @param opts.list     同一批行，供上一首/下一首
     * @param opts.cfg      接口与命名约定，见 {@link #SONG_CFG}；不传送歌曲那份
     * @param opts.onClose  关闭后的回调
     * @param opts.onScored 打分成功后的回调（未归档随机播放用它从队列移除并切下一首）
     * @param opts.random   是否随机播放队列：改名切下一首时走 onScored（维护随机索引），
     *                      而不是按 list 顺序取下一项
     * @param opts.onRateSaved 存默认倍速成功后的回调（让外侧列表刷新倍速列）
     * @param opts.requireTags 归档前是否强制打标签（后端 {@code /roots} 下发的那一项）。
     *                      浮层里的「修改」也开编辑弹窗，原样转交，见 {@code SongActions.edit}
     */
    async function open(row, opts = {}) {
        // silent：换首歌时不触发 onClose，否则打分页会以为「看完了」直接推进
        close(true);
        const variants = Util.variantsOf(row);
        state = {
            row, opts, cfg: cfgOf(opts), variants, vi: 0, lyricVi: 0,
            lines: [], timed: false, currentLine: -1, lyricFile: null,
            followPausedUntil: 0, rafId: null,
            lyricVisible: true
        };
        await mount();
    }

    /** 取当前 variant 的 play-info、建浮层、开播。open 与 variant 切换共用 */
    async function mount() {
        const v = state.variants[state.vi];
        state.partition = v.partitionName;
        state.mainName = v.mainName;

        let info;
        try {
            info = await Api.get(state.cfg.base + '/play-info?' + locateQuery(v));
        } catch (e) {
            UI.err(e.message);
            close(true);
            return;
        }
        state.info = info;
        // 当前倍速：优先级最高的那一级（文档 4.12）。开场取后端算好的回落值
        state.rate = Number(info.rate);
        state.rateSource = info.rateSource;
        // 来源的人话由后端给（rateSourceLabel），前端只补一个后端没有的「手动」——
        // 手动只存在于播放这一次，后端不知道
        state.rateLabel = info.rateSourceLabel;
        // 歌词优先当前 variant；没有则回退到同组其它 variant 的第一个歌词
        let lyricVi = state.vi;
        let lyricFile = info.lyricFiles.length ? info.lyricFiles[0] : null;
        if (!lyricFile) {
            for (let i = 0; i < state.variants.length; i++) {
                const vf = state.variants[i].lyricFiles;
                if (i !== state.vi && vf && vf.length) {
                    lyricVi = i;
                    lyricFile = vf[0];
                    break;
                }
            }
        }
        state.lyricVi = lyricVi;
        state.lyricFile = lyricFile;
        state.currentLine = -1;

        document.body.appendChild(buildOverlay());
        // 浮层挂在 body 上，切页时不会随 page-body 一起被清掉，所以自己关
        // （同阅读页的做法）。不关的话它会浮在新页面上继续放。
        window.addEventListener('hashchange', closeOnRoute);
        bind();
        await loadLyric();
        startPlayback();
    }

    /** 切 variant：停当前、按新 variant 重挂载 */
    function switchVariant(vi) {
        if (vi === state.vi) {
            return;
        }
        teardown();
        state.vi = vi;
        mount();
    }

    function close(silent) {
        if (!state) {
            return;
        }
        teardown();
        const onClose = state.opts.onClose;
        state = null;
        if (onClose && !silent) {
            onClose();
        }
    }

    /** 停 rAF/定时器、解绑事件、清浮层。close 与 variant 切换共用 */
    function teardown() {
        if (state.rafId) {
            cancelAnimationFrame(state.rafId);
        }
        const video = document.getElementById('sp-video');
        if (video) {
            // 先暂停再清 src：不清的话 Chrome 会继续下载剩下的字节
            video.pause();
            video.removeAttribute('src');
            video.load();
        }
        const overlay = document.getElementById('song-player');
        if (overlay) {
            overlay.remove();
        }
        document.removeEventListener('keydown', onKey);
        window.removeEventListener('hashchange', closeOnRoute);
    }

    /**
     * 切页时关闭。silent 传 true：人是要去别的页，不是「这一组看完了」，
     * 触发 onClose 会让打分页在背后推进到下一组。
     */
    function closeOnRoute() {
        close(true);
    }

    function buildOverlay() {
        const {info, row} = state;
        const v = state.variants[state.vi];
        const overlay = document.createElement('div');
        overlay.id = 'song-player';
        // 没有视频（只有 mp3）时加 sp-audio 类，画面区域让给歌词大面板
        overlay.className = 'song-player' + (info.hasVideo ? '' : ' sp-audio');

        const title = row.parsed
            ? (artistLinks(row)
                + ' － ' + UI.esc(row.title)
                // 曲名 == 原曲名（原曲没改词、直接翻唱）时也显示原曲名，不隐藏 ——
                // 否则抬头看着像缺了一块
                + (row.originalTitle
                    ? '<span class="muted small">（原曲：'
                    + '<a href="#song-stat?' + Util.qs('originalTitle', row.originalTitle) + '">'
                    + UI.esc(row.originalTitle) + '</a>）</span>' : ''))
            : UI.esc(v.mainName);

        overlay.innerHTML = '<div class="sp-head">'
            + '<div class="sp-title">' + title + '</div>'
            + '<div class="row">'
            + variantPicker()
            + '<span class="muted small">' + UI.esc(v.partitionDisplay || '待打分') + '</span>'
            + '<button class="btn-plain" id="sp-lyric-toggle" type="button">隐藏歌词</button>'
            // 按钮不只是改评分，改名也在这个弹窗里 —— 就叫「修改」
            + '<button class="btn-plain" id="sp-score" type="button">修改</button>'
            + '<button class="btn-plain" id="sp-close" type="button">关闭 (Esc)</button>'
            + '</div>'
            + '</div>'
            + banner()
            + '<div class="sp-main">'
            + '<div class="sp-stage">'
            + '<video id="sp-video" preload="metadata"></video>'
            + '<div class="sp-bigwords" id="sp-bigwords"></div>'
            + '</div>'
            // 歌词显隐按钮在 sp-head 里（不在本面板内），否则隐藏面板时按钮一起被藏、点不回来
            + '<div class="sp-lyric" id="sp-lyric">'
            + '<div class="sp-lyric-head" id="sp-lyric-head"></div>'
            + '<div class="sp-lyric-body" id="sp-lyric-body">' + UI.spinner('读歌词…') + '</div>'
            + '</div>'
            + '</div>'
            + buildBar();
        return overlay;
    }

    /** 版本切换下拉。同一首歌只有 1 个 variant（单组）时不显示 */
    function variantPicker() {
        const variants = state.variants;
        if (variants.length <= 1) {
            return '';
        }
        return '<select id="sp-variant" title="同一首歌的不同版本 / 文件类型">'
            + variants.map((v, i) => '<option value="' + i + '"' + (i === state.vi ? ' selected' : '')
                + '>' + UI.esc(v.mainName) + '</option>').join('')
            + '</select>';
    }

    /** 解码探不过时的横幅。探测失败不阻止播放 —— 音频和歌词照常工作，只是画面黑 */
    function banner() {
        if (!state.info.hasVideo || probeHevc()) {
            return '';
        }
        return '<div class="hint hint-err sp-banner">这台机器或这个浏览器解不了 HEVC，'
            + '<strong>视频会黑屏，音频和歌词正常</strong>。'
            + 'HEVC 是纯硬解、没有软解兜底，装 Microsoft Store 的「HEVC 视频扩展」也不管用'
            + '（那个包是 Edge 软解路径和「电影和电视」用的）。'
            + '想看画面就点下面的「用本机播放器打开」。</div>';
    }

    function buildBar() {
        let html = '<div class="sp-bar">'
            + '<div class="sp-bar-row">'
            + '<button id="sp-play" class="sp-icon" type="button">▶</button>'
            + '<span class="mono small" id="sp-time">0:00 / 0:00</span>'
            + '<input type="range" id="sp-seek" min="0" max="1000" value="0" class="sp-seek">'
            // 音量在进度条右侧。不持久化：每次打开浮层重建 <video>，回到 100
            // 左边那个「喇叭」是画的：文本呈现的 ◀（U+25C0，同播放键那个 ▶）加两道波纹，
            // 不挑 🔊 —— 那类默认 Emoji 在 Windows 上会变彩色方块、字号也对不齐
            // （与左侧导航那五个小图示同一套规矩）。百分比由 bind 里的 applyVol 填，不另写一份
            + '<span class="small muted sp-vol-label" title="音量">◀)) '
            + '<span id="sp-vol-num">100%</span></span>'
            + '<input type="range" id="sp-vol" min="0" max="100" value="100"'
            + ' class="sp-vol" title="音量（拖到 0 静音）">'
            + '</div>'
            + '<div class="sp-bar-row">'
            + '<span class="small muted">倍速</span>'
            // 0.5 ~ 1.6，步长 0.05。播放中调不写库，来源标成「手动」
            + '<input type="range" id="sp-rate-range" min="0.5" max="1.6" step="0.05"'
            + ' value="' + state.rate + '">'
            + '<span class="mono small" id="sp-rate-val"></span>'
            + '<span class="small muted" id="sp-rate-src"></span>'
            + '<button id="sp-save-rate" type="button">存为默认</button>'
            + (state.partition ? '' : '<span class="muted small">'
                + (state.cfg.hasOriginal
                    ? '待打分的歌存不了组默认倍速，只能存到原曲名上'
                    : '待打分区的喊麦存不了默认倍速，先打分归档再存') + '</span>')
            + '</div>'
            + '<div class="sp-bar-row">'
            + '<button id="sp-prev" type="button">上一首</button>'
            + '<button id="sp-next" type="button">下一首</button>'
            + '<button id="sp-open-local" type="button">用本机播放器打开</button>'
            + '<span class="muted small">空格 播放/暂停　← → 退/进 5 秒　↑ ↓ 上/下一句</span>'
            + '</div></div>';
        return html;
    }

    // ------------------------------------------------------------------
    // 播放
    // ------------------------------------------------------------------

    function startPlayback() {
        const video = document.getElementById('sp-video');
        video.src = Util.mediaUrl(state.info.playPath);
        // 变调补偿。标准属性、默认就是 true，显式写一遍表明意图：
        // 1.6 倍下人声不会变尖也不会被静音
        video.preservesPitch = true;
        video.playbackRate = state.rate;
        video.controls = false;

        video.onloadedmetadata = () => {
            // src 换过之后 playbackRate 会被重置，这里再设一次
            video.playbackRate = state.rate;
            updateTime();
        };
        video.onplay = () => document.getElementById('sp-play').textContent = '⏸';
        video.onpause = () => document.getElementById('sp-play').textContent = '▶';
        video.onerror = () => {
            const stage = document.querySelector('.sp-stage');
            if (stage) {
                stage.insertAdjacentHTML('beforeend',
                    '<div class="sp-error">播放失败。'
                    + (state.info.hasVideo && !probeHevc()
                        ? '多半就是上面说的 HEVC 解不了。'
                        : '文件可能已经移走或改名了，刷新列表再试。')
                    + '</div>');
            }
        };

        video.play().catch(() => {
            // 自动播放被拦（浏览器策略）不是错误，等人点播放键
        });

        renderRate();
        loop();
    }

    /** rAF 循环：读 currentTime、同步歌词高亮与进度条 */
    function loop() {
        if (!state) {
            return;
        }
        const video = document.getElementById('sp-video');
        if (video) {
            updateTime();
            if (state.timed) {
                highlight(findLine(video.currentTime));
            }
        }
        state.rafId = requestAnimationFrame(loop);
    }

    function updateTime() {
        const video = document.getElementById('sp-video');
        const timeEl = document.getElementById('sp-time');
        const seek = document.getElementById('sp-seek');
        if (!video || !timeEl || !seek) {
            return;
        }
        timeEl.textContent = fmt(video.currentTime) + ' / ' + fmt(video.duration);
        if (!seek.dataset.dragging && video.duration) {
            seek.value = String(Math.round(video.currentTime / video.duration * 1000));
        }
    }

    function fmt(seconds) {
        if (!isFinite(seconds)) {
            return '0:00';
        }
        const m = Math.floor(seconds / 60);
        const s = Math.floor(seconds % 60);
        return m + ':' + String(s).padStart(2, '0');
    }

    // ------------------------------------------------------------------
    // 歌词
    // ------------------------------------------------------------------

    async function loadLyric() {
        const body = document.getElementById('sp-lyric-body');
        const head = document.getElementById('sp-lyric-head');
        // 歌词来源 variant：优先当前 variant，无歌词时回退到同组其它 variant
        const lv = state.variants[state.lyricVi];
        const files = lv.lyricFiles;

        if (!files.length) {
            head.innerHTML = '<span class="muted small">这一组没有歌词文件</span>';
            body.innerHTML = UI.empty('没有歌词。');
            state.lines = [];
            state.timed = false;
            setBigWords(state.mainName);
            return;
        }

        // 一组里有多个歌词文件（同时有 lrc 与 srt）时给个切换
        head.innerHTML = files.length > 1
            ? '<select id="sp-lyric-pick">' + files.map(f => '<option value="' + UI.esc(f) + '"'
                + (f === state.lyricFile ? ' selected' : '') + '>' + UI.esc(f) + '</option>').join('')
                + '</select>'
            : '<span class="muted small mono">' + UI.esc(files[0]) + '</span>';
        if (files.length > 1) {
            head.querySelector('#sp-lyric-pick').onchange = async (ev) => {
                state.lyricFile = ev.target.value;
                await loadLyric();
            };
        }

        body.innerHTML = UI.spinner('读歌词…');
        let lyric;
        try {
            // 用歌词来源 variant 的定位（回退时 mainName/partition 与播放 variant 不同）
            lyric = await Api.get(state.cfg.base + '/lyric?' + locateQuery(lv)
                + '&fileName=' + encodeURIComponent(state.lyricFile));
        } catch (e) {
            body.innerHTML = '<div class="hint hint-err">' + UI.esc(e.message) + '</div>';
            return;
        }

        state.lines = lyric.lines || [];
        state.timed = lyric.timed;
        state.currentLine = -1;
        // 没有时间轴时 highlight 永远不跑，大字区会一直空着 —— 摆上组名，
        // 音频模式下不至于是一整片黑
        setBigWords(state.timed ? '' : state.mainName);

        if (!lyric.supported || !state.lines.length) {
            head.insertAdjacentHTML('beforeend',
                '<span class="tag tag-warn">' + (lyric.supported ? '空歌词' : '格式不支持') + '</span>');
            body.innerHTML = '<div class="hint">' + UI.esc(lyric.message || '没有可显示的歌词') + '</div>';
            setBigWords(state.mainName);
            return;
        }
        if (!state.timed) {
            // txt 没有时间轴。不假装能同步：明确标出来，降级成可滚动的纯文本
            head.insertAdjacentHTML('beforeend',
                '<span class="tag tag-warn">无时间轴，不滚动</span>');
        }

        body.innerHTML = state.lines.map((line, i) =>
            '<div class="sp-line" data-i="' + i + '"'
            + (line.start == null ? '' : ' data-start="' + line.start + '"')
            + '>' + (UI.esc(line.text) || '&nbsp;') + '</div>').join('');

        // 点歌词行跳到那个时间点。行→时间的映射本来就有，顺手就做
        body.querySelectorAll('.sp-line[data-start]').forEach(el => el.onclick = () => {
            const video = document.getElementById('sp-video');
            video.currentTime = Number(el.dataset.start);
            state.followPausedUntil = 0;
            if (video.paused) {
                video.play().catch(() => {
                });
            }
        });

        // 人手动滚了就暂停自动跟随 4 秒
        body.onwheel = () => state.followPausedUntil = Date.now() + 4000;
    }

    /** 显隐歌词栏。音频模式的大字区在画面区里，不受影响 */
    function toggleLyric() {
        state.lyricVisible = !state.lyricVisible;
        const lyric = document.getElementById('sp-lyric');
        const btn = document.getElementById('sp-lyric-toggle');
        if (lyric) {
            lyric.classList.toggle('hidden', !state.lyricVisible);
        }
        if (btn) {
            btn.textContent = state.lyricVisible ? '隐藏歌词' : '显示歌词';
        }
    }

    /**
     * 二分找当前行。
     *
     * SRT 有 gap —— gap 期间没有当前行，这是它与 LRC 的唯一行为差异，
     * 所以这里不能只找「start <= t 的最后一行」，还要判 end。
     */
    function findLine(t) {
        const lines = state.lines;
        let lo = 0, hi = lines.length - 1, found = -1;
        while (lo <= hi) {
            const mid = (lo + hi) >> 1;
            if (lines[mid].start <= t) {
                found = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        if (found < 0) {
            return -1;
        }
        const line = lines[found];
        // end 为 null 表示「到文件结束」（LRC 最后一行）
        return line.end != null && t >= line.end ? -1 : found;
    }

    /** 音频模式下画面区域的大字。textContent 而不是 innerHTML —— 歌词里可能有 < > */
    function setBigWords(text) {
        const big = document.getElementById('sp-bigwords');
        if (big) {
            big.textContent = text || '';
        }
    }

    function highlight(index) {
        if (index === state.currentLine) {
            return;
        }
        const body = document.getElementById('sp-lyric-body');
        if (!body) {
            return;
        }
        body.querySelectorAll('.sp-line.on').forEach(el => el.classList.remove('on'));
        state.currentLine = index;
        if (index < 0) {
            // SRT 的 gap 里没有当前行，这时清空而不是留着上一句挂着
            setBigWords('');
            return;
        }
        const el = body.querySelector('.sp-line[data-i="' + index + '"]');
        if (el) {
            el.classList.add('on');
            if (Date.now() >= state.followPausedUntil) {
                el.scrollIntoView({behavior: 'smooth', block: 'center'});
            }
        }
        setBigWords(state.lines[index].text);
    }

    // ------------------------------------------------------------------
    // 交互
    // ------------------------------------------------------------------

    function renderRate() {
        const range = document.getElementById('sp-rate-range');
        const val = document.getElementById('sp-rate-val');
        if (range) {
            range.value = String(state.rate);
        }
        if (val) {
            val.textContent = Number(state.rate).toFixed(2).replace(/0$/, '') + '×';
        }
        const src = document.getElementById('sp-rate-src');
        if (src) {
            src.textContent = '（' + state.rateLabel + '）';
        }
    }

    function setRate(rate) {
        state.rate = rate;
        // 播放中调倍速**不写库**（文档 4.12）：只改当前这一次，来源标成「手动」
        state.rateSource = 'MANUAL';
        state.rateLabel = '手动';
        const video = document.getElementById('sp-video');
        if (video) {
            video.playbackRate = rate;
            video.preservesPitch = true;
        }
        renderRate();
    }

    function bind() {
        const overlay = document.getElementById('song-player');
        // 包一层：直接挂 close 会把 MouseEvent 当成 silent 传进去，onClose 就不触发了
        overlay.querySelector('#sp-close').onclick = () => close();
        document.addEventListener('keydown', onKey);

        overlay.querySelector('#sp-play').onclick = togglePlay;
        // 点击画面（视频 / 音频模式的大字区）启停播放
        overlay.querySelector('.sp-stage').onclick = togglePlay;

        // 倍速拖动条
        const rateRange = overlay.querySelector('#sp-rate-range');
        rateRange.oninput = () => setRate(Number(rateRange.value));

        // variant 切换
        const variantSel = overlay.querySelector('#sp-variant');
        if (variantSel) {
            variantSel.onchange = () => switchVariant(Number(variantSel.value));
        }

        // 歌词显隐
        overlay.querySelector('#sp-lyric-toggle').onclick = toggleLyric;

        const seek = overlay.querySelector('#sp-seek');
        seek.oninput = () => {
            seek.dataset.dragging = '1';
            const video = document.getElementById('sp-video');
            if (video.duration) {
                video.currentTime = Number(seek.value) / 1000 * video.duration;
            }
        };
        seek.onchange = () => delete seek.dataset.dragging;

        // 音量。拖到 0 顺手静音（拖到 0 时浏览器标签页上那个喇叭图标也跟着变）。
        // 左侧那串「喇叭 + 百分比」与 slider 共用这一个函数：两处都读同一个 vol.value，
        // 不会出现「条在 60%、数字还写着 100%」。bind 每次挂载只跑一次、且配着一个全新的
        // <video>（切版本走 teardown + mount），所以开场调用一次就是「同步成 100%」而不是覆盖用户的选择
        const vol = overlay.querySelector('#sp-vol');
        const volNum = overlay.querySelector('#sp-vol-num');
        const applyVol = () => {
            const video = document.getElementById('sp-video');
            const v = Number(vol.value) / 100;
            video.volume = v;
            video.muted = v === 0;
            volNum.textContent = Math.round(v * 100) + '%';
        };
        vol.oninput = applyVol;
        applyVol();

        overlay.querySelector('#sp-save-rate').onclick = (ev) => saveRate(ev.target);
        overlay.querySelector('#sp-open-local').onclick = async (ev) => {
            await UI.withBusy(ev.target, '打开中…', async () => {
                try {
                    await Api.post('/api/media/open-local', {path: state.info.playPath});
                    UI.ok('已交给本机播放器');
                } catch (e) {
                    UI.err(e.message);
                }
            });
        };

        // 修改（评分/改名，整行所有版本一起）。apply 成功后文件搬走了，切下一首
        overlay.querySelector('#sp-score').onclick = () => openScore();

        const list = state.opts.list || [];
        const at = list.findIndex(r => idOf(r) === idOf(state.row));
        const prev = overlay.querySelector('#sp-prev');
        const next = overlay.querySelector('#sp-next');
        prev.disabled = at <= 0;
        next.disabled = at < 0 || at >= list.length - 1;
        prev.onclick = () => open(list[at - 1], state.opts);
        next.onclick = () => open(list[at + 1], state.opts);
    }

    /** 播放浮层内的改评分 / 归档 */
    function openScore() {
        // 改评分/改名要搬文件：先把正在播放的媒体暂停、放掉文件句柄。Windows 下
        // 文件正被流媒体占着时 Files.move 会报「文件被占用」搬不动视频。保存成功后
        // 文件路径变了没法续播，就切下一首（没有下一首才关）；取消则停在暂停态。
        const video = document.getElementById('sp-video');
        if (video) {
            video.pause();
        }
        SongActions.edit(state.row, {
            // 浮层里的「修改」也走调用方的约定：喊麦这一份要落到 /api/shout
            cfg: state.cfg,
            // 归档闸门的口径一路从页面传下来（未归档随机播放时这里就是待打分的行）
            requireTags: state.opts.requireTags,
            onDone: async (result, info) => {
                if (!state) {
                    return;
                }
                const row = state.row;
                const opts = state.opts;
                // 评分或改名都会移动文件（换分区/改名），当前这首没法在原路径继续。
                // 有下一首就切下一首，没有才关掉。
                if (opts.onScored && opts.random) {
                    // 随机播放：交给调用方从随机队列移除并切下一首（openRandom）
                    await opts.onScored(row);
                    return;
                }
                // 评分把这组搬走了，让调用方从列表移除；改名只是路径变了，列表行留着下次重扫
                if (opts.onScored && info.scored) {
                    await opts.onScored(row);
                }
                const list = opts.list || [];
                const at = list.findIndex(r => idOf(r) === idOf(row));
                if (at >= 0 && at < list.length - 1) {
                    open(list[at + 1], opts);
                } else {
                    close();
                }
            }
        });
    }

    function togglePlay() {
        const video = document.getElementById('sp-video');
        if (video.paused) {
            video.play().catch(() => {
            });
        } else {
            video.pause();
        }
    }

    /**
     * 存默认倍速。倍速三级回落里，组级（这一组）与原曲名级（同一首原曲所有填词版本）
     * 是两层独立的默认值 —— 组级优先，组级没存才回落到原曲名级。所以两处都能存时
     * 让用户选存到哪一层，而不是擅自决定。
     * <p>喊麦只有组级那一层（不解析文件名，没有原曲名可言），一条路走到底。
     */
    async function saveRate(btn) {
        const rate = state.rate;
        const original = state.info.originalTitle;
        const originalId = state.info.originalId;
        const hasGroup = !!state.partition;

        if (!state.cfg.hasOriginal) {
            if (hasGroup) {
                await saveGroupRate(btn, rate);
            } else {
                UI.err('待打分区的喊麦还没有评分分区，存不了默认倍速。先打分归档再存');
            }
            return;
        }

        if (!hasGroup && !original) {
            UI.err('待打分的歌存不了组默认倍速，而这首解析不出原曲名，也存不到原曲名上');
            return;
        }

        // 只有一层可存时直接存；待打分存原曲名前提醒一句（人可能以为存的是「这一组」）
        if (hasGroup && !original) {
            await saveGroupRate(btn, rate);
            return;
        }
        if (!hasGroup && original) {
            if (!await UI.confirm('这首还没归档，存不了「这一组」的默认倍速。'
                + '存到原曲名「' + original + '」上？同一首原曲的所有填词版本都会用这个倍速。',
                {title: '存为默认倍速', okText: '存到原曲名', danger: false})) {
                return;
            }
            await saveOriginalRate(btn, rate, originalId, original);
            return;
        }

        // 两层都能存：让用户选
        const m = UI.modal('<div class="modal">'
            + '<div class="modal-head"><span>存为默认倍速</span>'
            + '<button class="modal-close" type="button">×</button></div>'
            + '<div class="modal-body">'
            + '<p class="muted small">把当前倍速 ' + Number(rate) + '× 存到哪一层？</p>'
            + '<p class="muted small">「组默认」只对这一组生效；「原曲名默认」同一首原曲'
            + '的所有填词版本都共享。播放时组级优先，没存组级才用原曲名级。</p>'
            + '</div>'
            + '<div class="modal-foot">'
            + '<button type="button" class="btn-plain" id="sr-cancel">取消</button>'
            + '<button type="button" class="btn-primary" id="sr-group">存为组默认</button>'
            + '<button type="button" class="btn-primary" id="sr-original">存为原曲名默认</button>'
            + '</div></div>');
        m.box.querySelector('.modal-close').onclick = () => m.close();
        m.box.querySelector('#sr-cancel').onclick = () => m.close();
        m.box.querySelector('#sr-group').onclick = () => {
            m.close();
            saveGroupRate(btn, rate);
        };
        m.box.querySelector('#sr-original').onclick = () => {
            m.close();
            saveOriginalRate(btn, rate, originalId, original);
        };
    }

    async function saveGroupRate(btn, rate) {
        await UI.withBusy(btn, '存中…', async () => {
            try {
                const body = {
                    partition: state.partition, mainName: state.mainName, rate
                };
                await Api.put(state.cfg.base + '/group-rate', body);
                state.rateSource = 'GROUP';
                state.rateLabel = '组默认';
                renderRate();
                UI.ok('已存为这一组的默认倍速 ' + rate + '×');
                if (state.opts.onRateSaved) {
                    state.opts.onRateSaved();
                }
            } catch (e) {
                UI.err(e.message);
            }
        });
    }

    async function saveOriginalRate(btn, rate, originalId, original) {
        await UI.withBusy(btn, '存中…', async () => {
            try {
                await Api.put('/api/song/original-rate', {originalId, rate});
                state.rateSource = 'ORIGINAL';
                state.rateLabel = '原曲名默认';
                renderRate();
                UI.ok('已存为「' + original + '」的默认倍速');
                if (state.opts.onRateSaved) {
                    state.opts.onRateSaved();
                }
            } catch (e) {
                UI.err(e.message);
            }
        });
    }

    function onKey(e) {
        if (!state) {
            return;
        }
        // 在输入框里按空格不该暂停
        const tag = (e.target.tagName || '').toLowerCase();
        if (tag === 'input' || tag === 'select' || tag === 'textarea') {
            return;
        }
        const video = document.getElementById('sp-video');
        switch (e.key) {
            case 'Escape':
                e.preventDefault();
                close();
                break;
            case ' ':
                e.preventDefault();
                togglePlay();
                break;
            case 'ArrowLeft':
                e.preventDefault();
                video.currentTime = Math.max(0, video.currentTime - 5);
                break;
            case 'ArrowRight':
                e.preventDefault();
                video.currentTime = Math.min(video.duration || 0, video.currentTime + 5);
                break;
            case 'ArrowUp':
                e.preventDefault();
                jumpLine(-1);
                break;
            case 'ArrowDown':
                e.preventDefault();
                jumpLine(1);
                break;
            default:
                break;
        }
    }

    /** 上/下一句：听不清时最常用的动作，比拖进度条准 */
    function jumpLine(delta) {
        if (!state.timed || !state.lines.length) {
            return;
        }
        const video = document.getElementById('sp-video');
        const now = findLine(video.currentTime);
        let target;
        if (now >= 0) {
            target = now + delta;
        } else {
            // 在 gap 里或还没到第一句：找下一句的下标
            const next = state.lines.findIndex(l => l.start > video.currentTime);
            // findIndex 返回 -1 表示已经过了最后一句，此时「上一句」就是最后那句
            target = next < 0 ? state.lines.length - 1 : (delta > 0 ? next : next - 1);
        }
        target = Math.max(0, Math.min(state.lines.length - 1, target));
        video.currentTime = state.lines[target].start;
        state.followPausedUntil = 0;
    }

    return {open, close, probeHevc};
})();
