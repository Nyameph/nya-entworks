/**
 * 未归档页（原「归档打分」，文档 8.4）。F:\NetdiskDownload\#已压缩歌曲\歌曲 下待打分的歌。
 *
 * 列表形式（不是原来的一屏一组）：走 /merged-staging，同作者+曲名+原曲的多个版本
 * 合并成一行。打分的动作是「听一遍、给个分」，所以：
 *   - 行上「播放」打开浮层，浮层内的改评分就是归档；
 *   - 行上「归档」直接开编辑弹窗（评分 + 标签）；
 *   - 「随机播放」生成纯前端的播放队列，打分成功后自动从队列移除并切下一首。
 *
 * 「已压缩歌曲不需要入库」是明确需求，所以这一层没有任何库记录 —— 每次进页现扫。
 * 打分就是把整组文件搬进 F:\歌曲\成品-歌曲\#<评分><评语>，搬完它就不在这一层了。
 *
 * 喊麦自成一个模块（原先它是这一页的一个页签），见 shoutscore.js。
 */
const SongScorePage = (() => {

    let currentHost = null;
    let rows = [];
    let partitions = [];
    let roots = null;
    let keyword = '';
    let page = 1;
    // 随机播放队列，纯内存：打分后从队列移除并自动切下一首；切页/刷新即失
    let randomList = null;
    let randomIndex = 0;

    async function render(host, params) {
        currentHost = host;
        host.innerHTML = UI.spinner();
        // 原曲页/作者页点「待打分」跳转：把该原曲名/作者当关键词预填
        if (params && params.originalTitle) {
            keyword = params.originalTitle;
        } else if (params && params.artist) {
            keyword = params.artist;
        }
        await reload(host);
    }

    async function reload(host) {
        host.innerHTML = UI.spinner('扫磁盘…');
        try {
            roots = await Api.get('/api/song/roots');
            // 分区列的是**目标根**（成品-歌曲）的分区，不是待打分根的 —— 打分是搬过去
            partitions = await Api.get('/api/song/partitions');
            rows = await Api.get('/api/song/merged-staging'
                + '?keyword=' + encodeURIComponent(keyword));
        } catch (e) {
            host.innerHTML = '<div class="hint hint-err">' + UI.esc(e.message) + '</div>';
            return;
        }
        page = 1;
        draw(host);
    }

    function draw(host) {
        let html = '';
        if (!roots.stagingRootExists) {
            html += '<div class="hint hint-err">待打分目录 <span class="mono">'
                + UI.esc(roots.stagingRoot) + '</span> 不存在，所以下面是空的。'
                + '外挂盘没挂上，或者这个子目录还没建。</div>';
        }
        if (!roots.archivedRootExists) {
            // 目标根不在时打分必然失败，与其让人打完才报错，不如先说
            html += '<div class="hint hint-err">目标根 <span class="mono">'
                + UI.esc(roots.archivedRoot) + '</span> 不存在，<strong>现在打分会失败</strong>：'
                + '打分就是把文件搬进它下面的分区目录。</div>';
        }
        html += '<div class="card">'
            + '<div class="row" style="justify-content:space-between">'
            + '<h2 style="margin:0">未归档 <span class="muted small" id="staging-count"></span></h2>'
            + '<div class="row">'
            + '<input type="text" id="f-keyword" placeholder="搜作者 / 曲名 / 原曲名 / 文件名" value="'
            + UI.esc(keyword) + '">'
            + '<button id="f-clear" title="清空搜索条件">清空</button>'
            + '<button class="btn-primary" id="f-go">查询</button>'
            + '<button id="shuffle">随机播放</button>'
            + '<button id="nav-refresh">重新扫描</button>'
            + '<button id="import-files" class="btn-primary" title="多选（或粘贴路径）一组文件，'
            + '重排文件名后迁移到未归档或直接归档">添加文件</button>'
            + '</div></div>'
            + '<div id="staging-list">' + UI.spinner() + '</div>'
            + '<div class="pager" id="staging-pager"></div>'
            + '</div>';
        host.innerHTML = html;
        bind(host);
        host.querySelector('#import-files').onclick = () => openImportDialog(host);

        const list = host.querySelector('#staging-list');
        const pager = host.querySelector('#staging-pager');
        host.querySelector('#staging-count').textContent = rows.length + ' 条';
        if (!rows.length) {
            list.innerHTML = UI.empty(roots.stagingRootExists
                ? '没有待打分的了。压缩好的歌放进 ' + roots.stagingRoot + ' 就会出现在这里。'
                : '目录不存在，扫不到东西。');
            pager.innerHTML = '';
            return;
        }
        const pageRows = SongActions.pageRows(rows, page);
        list.innerHTML = table(pageRows);
        bindRows(host);
        SongActions.pagerBar(pager, rows.length, page, (p) => {
            page = p;
            draw(host);
        });
    }

    function table(pageRows) {
        let html = '<table><thead><tr>'
            + '<th style="width:42%">作者 / 曲名（原曲）</th>'
            + '<th style="width:13%">文件</th><th style="width:7%">倍速</th><th></th>'
            + '</tr></thead><tbody>';
        for (const r of pageRows) {
            html += row(r);
        }
        return html + '</tbody></table>';
    }

    function row(r) {
        const v0 = r.variants[0];
        let nameCell;
        if (r.parsed) {
            nameCell = '<td>' + UI.esc((r.artists || []).join(' & ')) + ' － ' + UI.esc(r.title)
                + (r.originalTitle && r.originalTitle !== r.title
                    ? '<span class="muted small">（' + UI.esc(r.originalTitle) + '）</span>' : '')
                + '</td>';
        } else {
            // 解析失败：显示原文件名 + 原因，归档后可在歌曲页用批量规范化收拾
            nameCell = '<td class="mono small">' + UI.esc(v0.mainName) + '</td>'
                + '<div class="muted small"><span class="tag tag-err">解析失败</span> '
                + UI.esc(r.parseFailedReason || '') + '</div>';
        }

        return '<tr data-key="' + UI.esc(r.mergeKey) + '">'
            + nameCell
            + '<td>' + SongActions.fileBadges(v0) + '</td>'
            + '<td class="mono small">' + (v0.defaultRate ? Number(v0.defaultRate) + '×'
                : '<span class="muted">—</span>') + '</td>'
            + '<td class="row">'
            + '<button class="btn-primary act-play">播放</button>'
            + '<button class="act-score">归档</button>'
            + '<button class="btn-danger act-delete">删除</button>'
            + '</td></tr>';
    }

    function bind(host) {
        const go = () => {
            keyword = host.querySelector('#f-keyword').value.trim();
            reload(host);
        };
        host.querySelector('#f-keyword').onkeydown = (e) => {
            if (e.key === 'Enter') {
                go();
            }
        };
        host.querySelector('#f-go').onclick = go;
        host.querySelector('#f-clear').onclick = () => {
            keyword = '';
            reload(host);
        };
        host.querySelector('#nav-refresh').onclick = () => reload(host);
        host.querySelector('#shuffle').onclick = shuffleStaging;
    }

    function bindRows(host) {
        const find = (btn) => rows.find(r => r.mergeKey === btn.closest('tr').dataset.key);
        host.querySelectorAll('.act-play').forEach(btn => btn.onclick = () => {
            const r = find(btn);
            SongPlayer.open(r, {
                list: rows,
                // 浮层内的「修改」也走编辑弹窗，归档闸门的口径跟着传下去
                requireTags: roots.requireTagsBeforeArchive,
                // 随机播放时打分成功切下一首；普通列表时从当前列表移除
                onScored: (row) => afterScored(host, row, true)
            });
        });
        host.querySelectorAll('.act-score').forEach(btn => btn.onclick = () => {
            const r = find(btn);
            SongActions.edit(r, {
                partitions,
                // 归档前是否强制打标签 —— 后端下发的口径，前端只照着画
                requireTags: roots.requireTagsBeforeArchive,
                onDone: (result, info) => {
                    // 评分了（搬走）从列表移除；只改名（行还在待打分）整页重扫
                    if (info.scored) {
                        afterScored(host, r, false);
                    } else {
                        reload(host);
                    }
                }
            });
        });
        host.querySelectorAll('.act-delete').forEach(btn => btn.onclick = () => {
            const r = find(btn);
            SongActions.remove(r, {onDone: () => afterScored(host, r, false)});
        });
    }

    /**
     * 一行处理完（打分搬走或删掉）之后。
     *
     * 从内存里摘掉这一行而不是整页重扫：重扫要几百毫秒，而打分是连着做几十组的动作，
     * 每组都等一次很难受。若正在随机播放，还要从随机队列移除，免得切到已搬走的文件 404。
     *
     * @param fromPlayer true 表示是播放浮层里打分触发的：随机播放时自动切下一首
     */
    function afterScored(host, row, fromPlayer) {
        rows = rows.filter(x => x.mergeKey !== row.mergeKey);
        if (randomList) {
            const i = randomList.findIndex(x => x.mergeKey === row.mergeKey);
            if (i >= 0) {
                randomList.splice(i, 1);
            }
        }
        if (fromPlayer && randomList) {
            if (!randomList.length) {
                randomList = null;
                SongPlayer.close(true);
                UI.ok('随机列表放完了');
                return;
            }
            randomIndex = Math.min(randomIndex, randomList.length - 1);
            openRandom();
            return;
        }
        if (page > Math.max(1, Math.ceil(rows.length / SongActions.PAGE_SIZE))) {
            page = Math.max(1, Math.ceil(rows.length / SongActions.PAGE_SIZE));
        }
        draw(host);
    }

    // ------------------------------------------------------------------
    // 随机播放（未归档层）
    // ------------------------------------------------------------------

    /**
     * 随机播放：把当前这一层（歌曲）的待打分歌洗成纯内存播放队列。
     * 打分成功后自动从队列移除并切下一首。
     */
    function shuffleStaging() {
        if (!rows.length) {
            UI.err('还没有待打分的歌');
            return;
        }
        // Fisher–Yates 洗牌。不弹窗问来源：这一页只有歌曲那一层，没什么可勾的
        const list = [];
        const src = rows.slice();
        while (src.length) {
            const j = Math.floor(Math.random() * src.length);
            list.push(src.splice(j, 1)[0]);
        }
        randomList = list;
        randomIndex = 0;
        openRandom();
    }

    function openRandom() {
        const r = randomList[randomIndex];
        SongPlayer.open(r, {
            list: randomList,
            random: true,
            requireTags: roots.requireTagsBeforeArchive,
            onScored: (row) => afterScored(currentHost, row, true)
        });
    }

    // ------------------------------------------------------------------
    // 添加文件（添加歌曲设计文档 ①：手动导入一组文件 → 未归档 / 直接归档）
    // ------------------------------------------------------------------

    /**
     * 「添加文件」直接开表单（2026-09-23 用户定：选文件的动作移进表单里，
     * 点按钮不再先弹文件框）。文件的选择 / 联动展开 / 默认目录记忆都在 SongForm 的
     * canAdd 模式里；本页只负责开表单和收尾刷新。
     *
     * <p>按钮与弹窗标题都叫「添加文件」（2026-09-29 起，原先叫「添加歌曲」）——
     * 喊麦那一份同名的功能只能这么叫（它不解析、也不是「歌」），两边对齐。
     */
    async function openImportDialog(host) {
        SongForm.open({
            files: [],
            canAdd: true,
            removable: true,
            initial: {artists: '', title: '', originalTitle: ''},
            partitions,
            // 导入侧的档位是「新文件落到哪儿」，缺档后端直接拦（不自动建）——
            // 只列真在盘上的，免得画一个按下去必然报错的按钮（songform.js 的 strictPartitions）
            strictPartitions: true,
            score: null,
            tags: [],
            requireTags: roots.requireTagsBeforeArchive,
            showTags: true,
            title: '添加文件',
            hint: '点「＋ 添加文件」选择文件；作者 / 曲名 / 原曲名填好后按两个按钮之一提交。',
            buttons: [
                {
                    id: 'migrate',
                    label: '迁移到未归档',
                    need: 'import-migrate',
                    // 与「归档」同级（同为默认样式），不设 primary
                    run: async (v) => {
                        // 评分不保留（未归档层没有评分分区）；标签是 DB-only、按合并键挂，
                        // 迁移后跟着走 —— 提醒文案如实写
                        if (v.selScore != null || (v.tagsChanged && v.tags.length)) {
                            const ok = await UI.confirm(
                                '已选的评分不会保留（未归档文件夹没有评分分区）；'
                                + '已打的标签会随歌曲保留。确定迁移吗？',
                                {title: '迁移到未归档', okText: '确定迁移', danger: false});
                            if (!ok) {
                                return null;   // SongForm 对 null 静默处理，回到表单
                            }
                        }
                        return Api.post('/api/song/import-plan', importBody(v, 'staging', null));
                    },
                    submit: (v) => Api.post('/api/song/import', importBody(v, 'staging', null))
                },
                {
                    id: 'archive',
                    label: '归档',
                    need: 'import-archive',
                    run: (v) => Api.post('/api/song/import-plan', importBody(v, v.selDir, v.selScore)),
                    submit: (v) => Api.post('/api/song/import', importBody(v, v.selDir, v.selScore))
                }
            ],
            onDone: (result, values, b) => {
                // 迁移：重扫未归档列表；归档：跳歌曲页看刚归档的那批
                if (b.id === 'archive') {
                    App.navigate('song-list');
                } else {
                    reload(host);
                }
            }
        });
    }

    /** ① 的请求体：源路径 + 逐文件编号 + 表单值；目标与评分由按钮定（staging 时 score 必须为 null） */
    function importBody(v, target, score) {
        return {
            files: v.files.map(f => ({
                sourcePath: f.path,
                version: (f.version || '').trim() || null
            })),
            artists: v.artists.trim(),
            title: v.title.trim(),
            originalTitle: v.originalTitle.trim(),
            rename: v.rename,
            target,
            score,
            tags: v.tags.length ? v.tags : null
        };
    }

    return {render};
})();
