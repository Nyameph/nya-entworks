/**
 * 原曲统计页（文档 8.6）。
 *
 * 这一页回答一个问题：**同一首原曲被填了几个版本、都打了几分。** 填词歌曲的组织方式是
 * 「一首原曲 → 多个填词版本」（文件名里的 `（原曲名）` 就是这条线），而按分区看只能看到
 * 「这一档里有哪些歌」，看不出这个结构。
 *
 * **已归档与待打分分开计数**：待打分的还没定分，混进分数没意义 —— 一首原曲 5 个版本、
 * 其中 3 个还没打分，贝叶斯分只该按那 2 个算。
 *
 * **点击跳转**：已归档/待打分两个数字是链接，点「已归档」跳到歌曲页并筛选该原曲名，
 * 点「待打分」跳到未归档页并筛选。模板列里的「svp」标记是填词入口 —— 点它进填词页，
 * 打开这首歌最近的一份填词，一份都没有就新建（所以这里不再单独放「填词」按钮）。
 * 其他素材标记（原/词/伴/声/样）点了是播放 / 看歌词浮层。标记顺序 原 词 伴 声 样 mid svp，
 * 与「编辑」弹窗里那八个文件框一致；有文件的是 button、没文件的是 span，两者盒子尺寸必须一致。
 *
 * **每列可排序**：前端排序，作用于已加载的全量数据，切页不重扫（重新加载要几百毫秒）。
 *
 * **「搜索」与「重新扫描」是两件事**：列表的筛选全在前端（关键词边打字边筛，不发请求），
 * 「搜索」按钮是主动重拉一次列表（＝页面级刷新：走只读接口，会扫一遍歌曲目录但**不写库、
 * 不动文件**，几百毫秒），「重新扫描」才去扫模板目录并回写库。
 *
 * 这一页也是配「原曲名默认倍速」的地方（倍速三级回落的第 3 级）。在播放浮层里改倍速
 * 只影响当下那一次，要给整首原曲定基准倍速就在这里。
 *
 * **改动磁盘与改库都进同一个弹窗**（`SongOriForm`，songoriform.js；2026-09-25 合并）：
 * 工具条「新增原曲」＝ 从任意路径选文件建一条新原曲；每行「编辑」＝ 改这条原曲的八类文件
 * （指派 / 导入 / 按规范改名 / 移入冗余）。原先行内是「编辑」（只改库里的指派）与「导入」
 * （搬文件、改名）两个按钮、两个长得不一样的弹窗，同一批文件有两条入口、还各自缺一半能力；
 * 现在一个按钮进同一个表单，这一页只负责转交。
 * 用户反馈的原话与逐条裁决见 `docs/已完成/原曲导入与改名实施计划.md` 的《用户反馈修正》。
 */
const SongStatPage = (() => {

    let stats = [];
    let keyword = '';
    let onlyUnconfirmed = false;
    let sortKey = 'total';
    let sortDir = -1;
    let page = 1;

    async function render(host, params) {
        // 歌曲页/播放页点原曲名跳转：把该原曲名当关键词预填，筛出这一项
        if (params && params.originalTitle) {
            keyword = params.originalTitle;
        }
        host.innerHTML = UI.spinner('扫磁盘…');
        try {
            stats = await Api.get('/api/song/originals');
        } catch (e) {
            host.innerHTML = '<div class="hint hint-err">' + UI.esc(e.message) + '</div>';
            return;
        }
        draw(host);
    }

    function draw(host) {
        const needle = keyword.toLowerCase();
        const filtered = stats.filter(s =>
            (!needle || s.originalTitle.toLowerCase().includes(needle))
            && (!onlyUnconfirmed || isUnconfirmed(s)));
        // 前端排序，作用于全量（筛选后），切页不重扫
        const list = [...filtered].sort((a, b) => {
            const r = sortKey === 'title'
                ? a.originalTitle.localeCompare(b.originalTitle, 'zh-Hans-CN')
                : sortKey === 'artist'
                    ? (a.artist || '').localeCompare(b.artist || '', 'zh-Hans-CN')
                    : sortKey === 'archived'
                            ? a.archivedCount - b.archivedCount
                            : sortKey === 'staging'
                                ? a.stagingCount - b.stagingCount
                                : sortKey === 'bayesian'
                                    ? Util.num(a.bayesianScore) - Util.num(b.bayesianScore)
                                    : sortKey === 'rate'
                                        ? Util.num(a.defaultRate) - Util.num(b.defaultRate)
                                        // 初始（total）：版本总数降序，与后端默认一致
                                        : (a.archivedCount + a.stagingCount)
                                            - (b.archivedCount + b.stagingCount);
            return r * sortDir;
        });
        const versions = stats.reduce((n, s) => n + s.archivedCount + s.stagingCount, 0);
        const pages = Math.max(1, Math.ceil(list.length / SongActions.PAGE_SIZE));

        let html = '<div class="hint">只统计填词歌曲（喊麦不解析文件名，没有原曲名可统计，'
            + '自成一个模块）；文件名解析失败的那几组也不在这里 —— 它们在歌曲页勾'
            + '「只看解析失败」能找到，凑进来只会污染统计。原曲页还会列出库里配过'
            + '（如扫过模板、设过倍速）的原曲，哪怕磁盘上还没有它的歌 —— 这些行'
            + '「已归档 / 待打分」都是 0，不评分。点「已归档 / 待打分」数字会跳到对应页并筛出这一首。</div>';

        html += '<div class="card">'
            + '<div class="row" style="justify-content:space-between">'
            + '<div class="row">'
            + '<input type="text" id="f-keyword" placeholder="搜原曲名" value="'
            + UI.esc(keyword) + '">'
            + '<button id="f-search" title="按关键词搜一次，并重新拉一遍列表">搜索</button>'
            + '<label class="tpl-check"><input type="checkbox" id="f-unconfirmed"'
            + (onlyUnconfirmed ? ' checked' : '') + '> 仅待确认</label>'
            + '<button id="f-clear" title="清空搜索条件">清空</button>'
            + '<button id="f-new-original" title="从任意路径选文件，建一条新原曲'
            + '（可选按规范改名；有 svp 时会提醒它的音频引用会失效）">新增原曲</button>'
            + '<button id="f-refresh">重新扫描</button>'
            + '</div>'
            + '<h2 style="margin:0">原曲 <span class="muted small">' + stats.length + ' 首，'
            + versions + ' 个填词版本</span></h2>'
            + '</div>';

        if (!list.length) {
            html += UI.empty(stats.length ? '没有匹配的原曲。' : '扫不到已解析的填词歌曲，库里也没有模板原曲。');
        } else {
            const pageRows = list.slice((page - 1) * SongActions.PAGE_SIZE,
                page * SongActions.PAGE_SIZE);
            html += '<table><thead><tr>'
                + th('title', '原曲名', '27%')
                + th('artist', '歌手', '20%')
                + th('archived', '已归档', '8%')
                + th('staging', '待打分', '8%')
                + th('bayesian', '贝叶斯分', '9%')
                + '<th style="width:14%" class="sortable" data-sort="rate">原曲名默认倍速'
                + arrow('rate') + '</th>'
                + '<th style="width:14%">模板</th>'
                + '</tr></thead><tbody>';
            for (const s of pageRows) {
                html += row(s);
            }
            html += '</tbody></table>'
                + '<p class="muted small">贝叶斯分只按<strong>已归档</strong>的版本算 —— '
                + '待打分的还没定分；低分降权、高分升权，样本越少越向全局均值靠拢。'
                + '「原曲名默认倍速」是倍速三级回落的第 3 级：某一组自己存了默认倍速时'
                + '用它自己的，没存过才用这里的。</p>';
        }
        html += '</div>';
        html += '<div class="pager" id="stat-pager"></div>';
        host.innerHTML = html;
        bind(host);
        if (list.length) {
            SongActions.pagerBar(host.querySelector('#stat-pager'), list.length, page, (p) => {
                page = p;
                draw(host);
            });
        }
    }

    /** 表头：排序状态是本文件的模块状态，绑定后交给 UI.th */
    function th(key, label, width) {
        return UI.th(key, label, width, sortKey, sortDir);
    }

    /** 倍速列是手写表头（不参与排序），箭头单独取 */
    function arrow(key) {
        return UI.sortArrow(key, sortKey, sortDir);
    }

    /** 「仅待确认」：歌手 / 原曲 / 歌词 / svp 任一项有内容但 check=0（对应后端同一条查询） */
    function isUnconfirmed(s) {
        return (s.artist && !s.artistCheck)
            || (s.hasOriginal && !s.originalCheck)
            || (s.hasLyric && !s.lyricCheck)
            || (s.hasSvp && !s.svpCheck);
    }

    /** 七类素材的简略标记：有则高亮、无则灰。原/词/伴/声 有文件时可点（播放 / 看歌词），
     *  svp 可点（进填词页，打开这首歌最近的一份填词，没有就新建）。
     *  顺序与编辑弹窗的七类下拉一致：原 词 伴 声 样 mid svp。
     *  末位 unconfirmed = 有内容但 check=0，用警示色标出（提醒还需人工确认，原曲 / 歌词 / svp 有） */
    function tplFlags(s) {
        const items = [
            ['原', s.hasOriginal, 'original', '原曲', 'play', s.hasOriginal && !s.originalCheck],
            ['词', s.hasLyric, 'lyric', '歌词', 'lyric', s.hasLyric && !s.lyricCheck],
            ['伴', s.hasAccompaniment, 'accompaniment', '伴奏', 'play', false],
            ['声', s.hasVocals, 'vocals', '人声', 'play', false],
            ['样', s.hasDemo, 'demo', '样例', 'play', false],
            ['mid', s.hasMid, null, 'mid', null, false],
            ['svp', s.hasSvp, null, 'svp', 'fill', s.hasSvp && !s.svpCheck],
        ];
        return items.map(([label, has, kind, title, act, unconfirmed]) => {
            const base = 'tpl-flag' + (has ? ' on' : '') + (unconfirmed ? ' unconfirmed' : '');
            const suffix = unconfirmed ? '（未确认）' : '';
            if (!has || !act) {
                return '<span class="' + base + '" title="' + title + (has ? '：有' : '：无')
                    + suffix + '">' + label + '</span>';
            }
            const action = act === 'lyric' ? '查看歌词'
                : act === 'fill' ? '进填词页' : '播放' + title;
            return '<button type="button" class="' + base + ' tpl-act" data-act="' + act
                + '"' + (kind ? ' data-kind="' + kind + '"' : '')
                + ' title="' + action + suffix + '">' + label + '</button>';
        }).join('');
    }

    function row(s) {
        // 歌手列：有内容但 check=0 用警示色标出（提醒还需人工确认）；已确认 / 空都不特殊标
        const unconfirmedArtist = !!s.artist && !s.artistCheck;
        const artistCell = s.artist ? UI.esc(s.artist) : '<span class="muted">—</span>';
        return '<tr data-key="' + UI.esc(String(s.originalId)) + '">'
            + '<td>' + UI.esc(s.originalTitle) + '</td>'
            + '<td' + (unconfirmedArtist ? ' class="check-unconfirmed" title="已填未确认"' : '') + '>'
            + artistCell + '</td>'
            // 已归档/待打分数字是链接：跳到对应页并筛选这一首原曲
            + '<td><a href="#song-list?' + Util.qs('originalTitle', s.originalTitle) + '">'
            + s.archivedCount + '</a></td>'
            + '<td>' + (s.stagingCount
                ? '<a href="#song-score?' + Util.qs('originalTitle', s.originalTitle) + '">'
                + '<span class="tag tag-warn">' + s.stagingCount + '</span></a>'
                : '<span class="muted">0</span>') + '</td>'
            + '<td class="mono">' + (s.bayesianScore == null
                ? '<span class="muted">—</span>' : Number(s.bayesianScore).toFixed(1)) + '</td>'
            + '<td class="rate-cell">'
            + '<input type="number" class="rate-input" step="0.05" min="0.25" max="4"'
            + ' value="' + (s.defaultRate == null ? '' : Number(s.defaultRate)) + '"'
            + ' placeholder="未设">'
            + '<button class="act-save-rate">存</button>'
            + (s.defaultRate == null ? ''
                : '<button class="btn-plain act-clear-rate">清掉</button>')
            + '</td>'
            + '<td class="tpl-cell"><span class="tpl-flags">' + tplFlags(s)
            // **每行只剩这一个按钮**（2026-09-25 合并，用户要求）：原先「编辑」（只改库里的
            // 指派）与「导入」（搬文件 / 改名）是两个按钮、点开两个长得不一样的弹窗，
            // 结果同一批文件有两条入口、还各自缺一半能力 ——
            // 「编辑」指派不了盘上还没入库的文件，「导入」改不了已指派的那一类。
            // 现在两个弹窗合成 SongOriForm 一个：八个类别各一格，指派、改文件、改名、移入冗余
            // 都在那一格里做完
            + '</span><button class="btn-plain act-edit-tpl" title="改这条原曲的八类文件：'
            + '指派、导入、按规范改名、把文件移入冗余">编辑</button>'
            // 填词入口就是左边的 svp 标记：点它进填词页（打开这首歌最近的一份填词，
            // 没有就新建）。没有 svp 时那个标记是灰的、点不动
            + '</td></tr>';
    }

    function bind(host) {
        const input = host.querySelector('#f-keyword');
        if (input) {
            // 中文输入法：组合（拼音）期间也会触发 input，若照常重绘会把输入框 DOM 换掉、
            // 打断组合，导致只能打英文。composition 期间只存值不重绘，选完字再搜。
            const search = () => {
                keyword = input.value.trim();
                page = 1;
                // 现有数据里过滤，不重扫 —— 扫一次几百毫秒，边打字边扫会卡
                const at = input.selectionStart;
                draw(host);
                const next = host.querySelector('#f-keyword');
                next.focus();
                next.setSelectionRange(at, at);
            };
            let composing = false;
            input.addEventListener('compositionstart', () => { composing = true; });
            input.addEventListener('compositionend', () => {
                composing = false;
                setTimeout(search, 0); // 等 IME 收尾后再搜，避免焦点恢复打断组合
            });
            input.oninput = () => {
                if (!composing) {
                    search();
                }
            };
            // 「搜索」：主动重拉一次列表（只读接口，不写库、不动文件）。与输入框的即时筛选
            // 一样只认关键词，区别是这一步会重新问一次后端 —— 别处改了库（如歌曲页改了歌手）
            // 或磁盘上换了文件之后，回到这一页不必整页刷新就能看到最新的数
            host.querySelector('#f-search').onclick = async () => {
                keyword = input.value.trim();
                page = 1;
                await UI.withBusy(host.querySelector('#f-search'), '搜索中…', async () => {
                    try {
                        stats = await Api.get('/api/song/originals');
                    } catch (e) {
                        // 拉不到就保留原来那份列表（页面照旧可用），只把原因弹出来
                        UI.err(e.message);
                    }
                });
                draw(host);
            };
            host.querySelector('#f-refresh').onclick = async () => {
                page = 1;
                // 「重新扫描」合并了原「扫描模板」：先扫模板目录回写库，再重拉列表
                await UI.withBusy(host.querySelector('#f-refresh'), '扫描中…', async () => {
                    try {
                        await TaskPoll.submitAndWait(
                            () => Api.post('/api/song/template/scan'),
                            {
                                onResult: async (r) => {
                                    UI.ok('扫描了 ' + r.scannedDirs + ' 个模板目录，更新了 '
                                        + r.upserted + ' 首原曲'
                                        + (r.moved > 0 ? '，搬移 ' + r.moved + ' 个目录' : ''));
                                    return true;
                                },
                                onFail: (error) => UI.err(error)
                            });
                    } catch (e) {
                        UI.err(e.message);
                    }
                });
                render(host);
            };
            host.querySelector('#f-clear').onclick = () => {
                keyword = '';
                onlyUnconfirmed = false;
                page = 1;
                draw(host);
            };
            host.querySelector('#f-unconfirmed').onchange = (ev) => {
                onlyUnconfirmed = ev.target.checked;
                page = 1;
                draw(host);
            };
        }

        // 表头排序：作用于已加载数据，切页不重扫
        host.querySelectorAll('th[data-sort]').forEach(thEl => thEl.onclick = () => {
            const key = thEl.dataset.sort;
            if (sortKey === key) {
                sortDir = -sortDir;
            } else {
                sortKey = key;
                // 文本列默认升序，数值列默认降序（分高的在前）
                sortDir = key === 'title' ? 1 : -1;
            }
            draw(host);
        });

        const find = (btn) => {
            const key = btn.closest('tr').dataset.key;
            return stats.find(s => String(s.originalId) === key);
        };

        host.querySelectorAll('.act-save-rate').forEach(btn => btn.onclick = async (ev) => {
            const s = find(btn);
            const raw = btn.closest('tr').querySelector('.rate-input').value.trim();
            if (raw === '') {
                UI.err('填个倍速，或者点「清掉」');
                return;
            }
            await save(host, ev.target, s, Number(raw));
        });
        host.querySelectorAll('.act-clear-rate').forEach(btn => btn.onclick = async (ev) => {
            // 清掉 = 存 null，回落到 1.0
            await save(host, ev.target, find(btn), null);
        });
        // 每行「编辑」与工具条「新增原曲」进的是**同一个** SongOriForm（差别只有 stat 有没有）
        host.querySelectorAll('.act-edit-tpl').forEach(btn => btn.onclick = () => {
            openImport(host, find(btn));
        });
        host.querySelector('#f-new-original').onclick = () => openImport(host, null);
        // 模板列的「原/伴/声/词」可点：统一浮层（音频同步歌词；词按优先级挑音频）；
        // 「svp」点进去填词
        host.querySelectorAll('.tpl-act').forEach(el => el.onclick = () => {
            const s = find(el);
            if (el.dataset.act === 'fill') {
                App.navigate('song-fill',
                    {originalId: s.originalId, originalTitle: s.originalTitle});
                return;
            }
            openTemplatePlayer(s.originalId,
                el.dataset.act === 'lyric' ? 'lyric' : el.dataset.kind,
                s.originalTitle);
        });
    }

    async function save(host, btn, stat, rate) {
        await UI.withBusy(btn, '存中…', async () => {
            try {
                await Api.put('/api/song/original-rate',
                    {originalId: stat.originalId, rate});
                // 只改内存里这一行，不整页重扫：重扫要几百毫秒，而这一列的值就是刚存进去的
                stat.defaultRate = rate;
                UI.ok(rate == null
                    ? '已清掉「' + stat.originalTitle + '」的默认倍速，回落到 1.0'
                    : '「' + stat.originalTitle + '」的默认倍速存成 ' + rate + '×');
                draw(host);
            } catch (e) {
                UI.err(e.message);
            }
        });
    }

    const PLAY_LABEL = {original: '原曲', demo: '样例', accompaniment: '伴奏', vocals: '人声'};

    /**
     * 统一「模板播放 + 歌词」浮层（按 originalId 取那一套）：点原曲/样例/人声/伴奏 播对应音频
     * 并同步滚动歌词，点「词」按 原曲 → 样例 → 人声 → 伴奏 的优先级挑一个存在的音频播；
     * 四类都没有就只滚歌词。
     */
    async function openTemplatePlayer(originalId, kind, title) {
        let detail, lyric;
        try {
            [detail, lyric] = await Promise.all([
                Api.get('/api/song/template/detail?originalId=' + encodeURIComponent(originalId)),
                Api.get('/api/song/template/lyric?originalId=' + encodeURIComponent(originalId)
                    + '&kind=' + encodeURIComponent(kind || '')),
            ]);
        } catch (e) {
            UI.err(e.message);
            return;
        }

        let path = null, label = null;
        if (kind === 'lyric') {
            const prio = [['original', detail.originalPath], ['demo', detail.demoPath],
                ['vocals', detail.vocalsPath], ['accompaniment', detail.accompanimentPath]];
            for (const [k, p] of prio) {
                if (p) {
                    path = p;
                    label = PLAY_LABEL[k];
                    break;
                }
            }
        } else {
            path = {original: detail.originalPath, demo: detail.demoPath,
                accompaniment: detail.accompanimentPath, vocals: detail.vocalsPath}[kind] || null;
            label = PLAY_LABEL[kind];
        }

        showTemplatePlayer(title || detail.rawName || String(originalId),
            path ? Util.mediaUrl(path) : null, label, lyric);
    }

    /**
     * 播「音频 + 歌词」这一对（原曲表单那六格「▶ 播放」用；这一页的 openTemplatePlayer
     * 是同一件事的另一条来路 —— <b>浮层只有这一个实现</b>，两条路的观感必须一样）。
     *
     * <p>两个参数都是<b>后端算好的</b>：{@code audioUrl} 是完整的流地址（本次新选的文件在受管根
     * 之外，它的串里带着临时预览令牌），{@code lyric} 是 {@code LyricTextReader} 的解析结果。
     * 任一端缺了就只画另一端 —— 缺音频只滚歌词，缺歌词只放音频。
     *
     * @param displayName 浮层标题（原曲名）
     * @param audioUrl    音频流地址；null / 空 = 没有音频
     * @param audioLabel  那一格的中文标签（「正在播放：伴奏」里那个词）
     * @param lyric       歌词解析结果；null = 没有歌词
     */
    function playPair(displayName, audioUrl, audioLabel, lyric) {
        showTemplatePlayer(displayName, audioUrl || null, audioLabel || null,
            lyric || {lines: [], timed: false, supported: false,
                message: audioUrl ? '这一格没有配歌词' : '这一格没有可播放的音频'});
    }

    /** 画出统一浮层：顶部可选的 <audio>（audioUrl 是完整地址），下面是歌词（有时间轴就随音频滚） */
    function showTemplatePlayer(displayName, audioUrl, label, lyric) {
        const lines = lyric.lines || [];
        const hasAudio = !!audioUrl;
        const timed = hasAudio && lyric.timed && lyric.supported && lines.length > 0;

        const audioHtml = hasAudio
            ? '<div style="margin-bottom:10px">'
            + '<audio id="tpl-audio" controls autoplay src="' + UI.esc(audioUrl) + '" style="width:100%"></audio>'
            + '<div class="muted small">正在播放：' + UI.esc(label || '') + '</div>'
            + '</div>'
            : '';

        const bodyLyric = (lyric.supported && lines.length)
            ? lines.map((line, i) => '<div class="sp-line" data-i="' + i + '"'
                + (hasAudio && line.start != null ? ' data-start="' + line.start + '"' : '')
                + '>' + (UI.esc(line.text) || '&nbsp;') + '</div>').join('')
            : '<div class="hint">' + UI.esc(lyric.message || '没有可显示的歌词') + '</div>';

        const m = UI.modal(`
            <div class="modal modal-wide">
                <div class="modal-head"><span>${UI.esc(displayName)} · ${UI.esc(label || '歌词')}</span>
                    <button class="modal-close" type="button">×</button></div>
                <div class="modal-body">
                    ${audioHtml}
                    <div class="tpl-lyric tpl-player-lyric" style="padding:22vh 14px">${bodyLyric}</div>
                </div>
                <div class="modal-foot">
                    <button type="button" class="btn-plain" data-act="close">关闭</button>
                </div>
            </div>`);
        m.box.querySelector('.modal-close').onclick = m.close;
        m.box.querySelector('[data-act="close"]').onclick = m.close;

        const audio = m.box.querySelector('#tpl-audio');
        if (audio && timed) {
            syncLyric(m, audio, lines);
        }
    }

    /** rAF 循环同步歌词：读 <audio>.currentTime，二分找行、高亮 + scrollIntoView。浮层关闭即停 */
    function syncLyric(m, audio, lines) {
        const body = m.box.querySelector('.tpl-player-lyric');
        let currentLine = -1;
        let followPausedUntil = 0;

        const findLine = (t) => {
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
            return line.end != null && t >= line.end ? -1 : found;
        };

        const highlight = (index) => {
            if (index === currentLine) {
                return;
            }
            body.querySelectorAll('.sp-line.on').forEach(el => el.classList.remove('on'));
            currentLine = index;
            if (index < 0) {
                return;
            }
            const el = body.querySelector('.sp-line[data-i="' + index + '"]');
            if (el) {
                el.classList.add('on');
                if (Date.now() >= followPausedUntil) {
                    el.scrollIntoView({behavior: 'smooth', block: 'center'});
                }
            }
        };

        // 点歌词行 seek；人手动滚了就暂停自动跟随 4 秒
        body.querySelectorAll('.sp-line[data-start]').forEach(el => el.onclick = () => {
            audio.currentTime = Number(el.dataset.start);
            followPausedUntil = 0;
            if (audio.paused) {
                audio.play().catch(() => {});
            }
        });
        body.onwheel = () => { followPausedUntil = Date.now() + 4000; };

        const loop = () => {
            if (!m.box.isConnected) {
                return;
            }
            highlight(findLine(audio.currentTime));
            requestAnimationFrame(loop);
        };
        requestAnimationFrame(loop);
    }

    /**
     * 工具条「新增原曲」与行内「编辑」共用这一个入口 —— 两处的差别只有 `stat` 有没有。
     *
     * 表单本身（八类文件框、挑文件、改名预览、确认页、轮询）全在 `SongOriForm` 里，
     * 这里只负责把「这一页的状态」与「那一行的四个确认位」递进去，提交完重拉一次列表。
     * **判定不在这一层**：按钮亮不亮由 SongOriForm 按后端下发的口径算。
     */
    function openImport(host, stat) {
        SongOriForm.open({
            isNew: !stat,
            // 两条路都是「先出表单」：新增那一路也不再点开就弹文件对话框，
            // 文件由表单里八个框各自的「选择…」按需挑（2026-09-24 起）
            originalId: stat ? stat.originalId : null,
            rawName: stat ? stat.originalTitle : '',
            artist: stat && stat.artist ? stat.artist : '',
            // 四个确认位照回原值：改完不勾就是不给扫描 / 搜索覆盖（§0.2 第 3 条）
            artistCheck: !!(stat && stat.artistCheck),
            originalCheck: !!(stat && stat.originalCheck),
            lyricCheck: !!(stat && stat.lyricCheck),
            svpCheck: !!(stat && stat.svpCheck),
            rename: true,       // 裁决 3：初值 true（改不改得成由后端下发的 renameAllowed 定）
            title: stat ? '修改原曲「' + stat.originalTitle + '」' : '新增原曲',
            buttons: [{
                id: 'ok',
                label: stat ? '保存' : '新增并导入',
                need: stat ? 'ori-changed' : 'ori-ready',
                primary: true
            }],
            // 库与磁盘都可能变了：整页重拉（只读接口，不写库、不动文件）
            onDone: () => render(host)
        });
    }

    // `playPair` 导出给 songoriform.js：八个文件框里那六个「▶ 播放」用的就是这一页本来就有的
    // 那个浮层（音频同步滚动，只是音频与歌词都由那一格的数据拼出来，不再按 originalId 去取）。
    // 写第二份播放浮层是另一种错法 —— 两份的倍速、滚动、点行 seek 行为会慢慢分叉
    return {render, openTemplatePlayer, playPair};
})();
