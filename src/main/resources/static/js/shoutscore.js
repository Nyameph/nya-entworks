/**
 * 喊麦 · 未归档页（打分）。F:\NetdiskDownload\#已压缩歌曲\喊麦 下待打分的喊麦。
 *
 * 形式与歌曲的未归档页一致：列表 + 行上「播放 / 归档 / 删除」+ 随机播放队列。
 * 打分的动作就是「听一遍、给个分」，分数落地 = 把整组文件搬进
 * F:\歌曲\成品-喊麦\#<分数><评语>，搬完它就不在这一层了。
 *
 * 与歌曲未归档页的三处不同，都是喊麦的性质决定的：
 *   - **没有页签**：歌曲那一页只管它自己那个待打分根，喊麦自成模块，这一页只有它自己；
 *   - **随机播放不问来源**：只有这一层可抽，弹窗里勾「歌曲 / 喊麦」的选项也就没有意义了；
 *   - **没有倍速列**：待打分区的组不入库（没有分区就没有稳定身份），存不了组默认倍速，
 *     这一列恒为「—」。归档后在已归档页看倍速。
 *
 * 「已压缩歌曲不需要入库」是明确需求，所以这一层没有任何库记录 —— 每次进页现扫。
 */
const ShoutScorePage = (() => {

    let currentHost = null;
    let rows = [];
    let partitions = [];
    let roots = null;
    let keyword = '';
    let page = 1;
    // 随机播放队列，纯内存：打分后从队列移除并自动切下一首；切页/刷新即失
    let randomList = null;
    let randomIndex = 0;

    const CFG = SongActions.SHOUT_CFG;

    async function render(host) {
        currentHost = host;
        host.innerHTML = UI.spinner();
        await reload(host);
    }

    async function reload(host) {
        host.innerHTML = UI.spinner('扫磁盘…');
        try {
            roots = await Api.get('/api/shout/roots');
            // 分区列的是**目标根**（成品-喊麦）的分区，不是待打分根的 —— 打分是搬过去
            partitions = await Api.get('/api/shout/partitions');
            rows = await Api.get('/api/shout/merged-staging'
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
            + '<input type="text" id="f-keyword" placeholder="搜文件名" value="'
            + UI.esc(keyword) + '">'
            + '<button id="f-clear" title="清空搜索条件">清空</button>'
            + '<button class="btn-primary" id="f-go">查询</button>'
            + '<button id="shuffle">随机播放</button>'
            + '<button id="nav-refresh">重新扫描</button>'
            + '<button id="import-files" class="btn-primary" title="多选（或粘贴路径）一组文件，'
            + '搬到待打分文件夹或直接归档">添加文件</button>'
            + '</div></div>'
            + '<div class="hint">打分就是把这组文件（视频 / 音频 / 歌词一起）搬进 '
            + '<span class="mono">' + UI.esc(roots.archivedRoot) + '</span> 下的评分分区。'
            // 这半句只在配置里开了「归档前强制打标签」时才成立（nya-entworks.shout.require-tags-before-archive）
            + (roots.requireTagsBeforeArchive
                ? '<strong>不打标签不能归档</strong>：归档前先在「归档」弹窗里给它打上标签。'
                : '标签可选：在「归档」弹窗里顺手打上，事后也能在列表页补。')
            + '</div>'
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
                ? '没有待打分的了。写好的喊麦放进 ' + roots.stagingRoot + ' 就会出现在这里。'
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
            + '<th style="width:52%">文件名</th><th style="width:14%">文件</th>'
            + '<th style="width:18%">标签</th><th></th>'
            + '</tr></thead><tbody>';
        for (const r of pageRows) {
            html += row(r);
        }
        return html + '</tbody></table>';
    }

    function row(r) {
        const v0 = r.variants[0];
        return '<tr data-key="' + UI.esc(r.mergeKey) + '">'
            + '<td class="mono small">' + UI.esc(v0.mainName) + '</td>'
            + '<td>' + SongActions.fileBadges(v0) + '</td>'
            + '<td>' + SongTag.warnChip(r.tags) + SongTag.chips(r.tags) + '</td>'
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
                list: rows, cfg: CFG,
                // 浮层内的「修改」也走编辑弹窗，归档闸门的口径跟着传下去
                requireTags: roots.requireTagsBeforeArchive,
                // 随机播放时打分成功切下一首；普通列表时从当前列表移除
                onScored: (row) => afterScored(host, row, true)
            });
        });
        host.querySelectorAll('.act-score').forEach(btn => btn.onclick = () => {
            const r = find(btn);
            SongActions.edit(r, {
                partitions, cfg: CFG,
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
            SongActions.remove(r, {cfg: CFG, onDone: () => afterScored(host, r, false)});
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
    // 添加文件（喊麦版：手动导入一组文件 → 未归档 / 直接归档）
    // ------------------------------------------------------------------

    /**
     * 「添加文件」直接开表单（与歌曲一致：选文件的动作移进表单里，点按钮不再先弹文件框）。
     * 文件的选择 / 联动展开 / 默认目录记忆都在 ShoutForm 的 canAdd 模式里；
     * 本页只负责开表单和收尾刷新。
     *
     * <p>喊麦比歌曲少了「三框 + 逐文件编号」：改名是一个可选的全名框，留空就保持原名；
     * 而且一次只处理一组（跨主名又没填新名时按钮灰着，原因写在按钮下面）。
     */
    async function openImportDialog(host) {
        ShoutForm.open({
            files: [],
            canAdd: true,
            // 与歌曲的「添加文件」同口径：新选进来的行上也有 ×（含义是「本次不处理」，
            // 只从这次列表移除，磁盘一个文件都不动 —— 见 shoutform.js 的 removeTitle）
            removable: true,
            partitions,
            // 导入侧的档位是「新文件落到哪儿」，缺档后端直接拦（不自动建）——
            // 只列真在盘上的，免得画一个按下去必然报错的按钮（shoutform.js 的 strictPartitions）
            strictPartitions: true,
            tags: [],
            requireTags: roots.requireTagsBeforeArchive,
            showTags: true,
            title: '添加文件',
            hint: '点「＋ 添加文件」选择文件；名字可以留空（保持原文件名），'
                + '也可以填一个新名把这一批合成一组，然后按两个按钮之一提交。',
            buttons: [
                {
                    id: 'migrate',
                    label: '迁移到未归档',
                    need: 'import-migrate',
                    // 与「归档」同级（同为默认样式），不设 primary
                    run: async (v) => {
                        // 评分不保留（未归档层没有评分分区）；标签是 DB-only、按主名挂，
                        // 搬过去也跟着走 —— 提醒文案如实写
                        if (v.selScore != null || (v.tagsChanged && v.tags.length)) {
                            const ok = await UI.confirm(
                                '已选的评分不会保留（待打分文件夹没有评分分区）；'
                                + '已打的标签会跟着保留。确定迁移吗？',
                                {title: '迁移到未归档', okText: '确定迁移'});
                            if (!ok) {
                                return null;   // ShoutForm 对 null 静默处理，回到表单
                            }
                        }
                        return Api.post('/api/shout/import-plan',
                            ShoutForm.importBody(v, 'staging', null));
                    },
                    submit: (v) => Api.post('/api/shout/import',
                        ShoutForm.importBody(v, 'staging', null))
                },
                {
                    id: 'archive',
                    label: '归档',
                    need: 'import-archive',
                    run: (v) => Api.post('/api/shout/import-plan',
                        ShoutForm.importBody(v, v.selDir, v.selScore)),
                    submit: (v) => Api.post('/api/shout/import',
                        ShoutForm.importBody(v, v.selDir, v.selScore))
                }
            ],
            onDone: (result, values, b) => {
                // 迁移：重扫未归档列表；归档：跳已归档页看刚归档的那批
                if (b.id === 'archive') {
                    App.navigate('shout-list');
                } else {
                    reload(host);
                }
            }
        });
    }

    // ------------------------------------------------------------------
    // 随机播放（喊麦未归档层）
    // ------------------------------------------------------------------

    /** 随机播放：把当前这一层的待打分喊麦洗成纯内存队列。打分成功后移除并切下一首 */
    function shuffleStaging() {
        if (!rows.length) {
            UI.err('还没有待打分的喊麦');
            return;
        }
        // Fisher–Yates 洗牌。不弹窗问来源：喊麦只有这一层，没什么可勾的
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
            list: randomList, cfg: CFG,
            random: true,
            requireTags: roots.requireTagsBeforeArchive,
            onScored: (row) => afterScored(currentHost, row, true)
        });
    }

    return {render};
})();
