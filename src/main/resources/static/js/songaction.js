/**
 * 组操作的共用件：评分 / 改名 / 删除，以及行上的文件徽标、分页条。
 *
 * 填词歌曲、喊麦、未归档页对这些事的行为**完全一样**，区别只在页面上摆哪几个按钮
 * （未归档页没有改名按钮，只有「归档」）。抄几份的话，「预演清单里 blocked
 * 要整批拦住」这条约束早晚有一份漏掉。
 *
 * 评分与改名整合成一个编辑弹窗（一个表单，评分区和改名区各自提交）；删除是独立
 * 的危险弹窗。全部是 plan + apply 两步，以**弹窗**形式呈现（原先是跳到页面底部的卡片）。
 * 理由在后端 SongArchiveService 的注释里：一组是「一个 mp4 + 一个 lrc + 可能还有 srt」，
 * 搬一半就等于这首歌裂成两半散在两个分区里 —— 列表按主名归组，裂开之后在两个分区
 * 各出现一行，看着像有两首。规范化命名不做单组表单，只在歌曲页做批量操作。
 *
 * <p><b>「修改」两路都走列表式表单</b>（2026-09-29 起；歌曲是添加歌曲设计文档阶段 5，
 * 喊麦是它的简化版）：{@code cfg.form} 决定交给哪一份组件 ——
 * 歌曲 {@code SongForm}（三框 + 文件列表 + 逐文件编号 + 确认页，提交口
 * /edit-form-plan + /edit-form）、喊麦 {@code ShoutForm}（一个可选全名框 + 文件列表，
 * 同一对提交口）。原先喊麦走本文件里的单框改名弹窗（{@code legacyEdit}），
 * 2026-09-29 删掉，形态与歌曲对齐。
 *
 * 行对象是**归并形态**（{@code variants[]}）：歌曲页、未归档页、喊麦页两页都走它。
 * 喊麦每行恒一个 variant，所以「多个版本一起改」那段提示在那边不会出现。
 *
 * **两边只差几个字段的约定**（见 {@link #SONG_CFG} / {@link #SHOUT_CFG}，由调用方通过
 * {@code opts.cfg} 传进来，不传就是歌曲那一份）：
 *   - 接口前缀 {@code base}：{@code /api/song} 与 {@code /api/shout}（拆模块前那套
 *     {@code kind} 参数已经删掉，两边都不带）；
 *   - {@code #版本号} 语义 {@code versioned}：歌曲保留后缀、改名只填基础名；喊麦的
 *     {@code #} 是普通字符，改名改的就是全名；
 *   - {@code hasOriginal}：喊麦不解析文件名、没有原曲名，倍速只有组级那一层。
 *     {@code SongPlayer} 读的是<b>同一份</b> CFG，所以这个字段两边都必须写全 ——
 *     少写一个就会走 {@code cfgOf} 里歌曲那份 {@code true} 的兜底（踩过一次）；
 *   - {@code form}：{@code 'song'} / {@code 'shout'}，决定「修改」交给哪一份表单组件。
 *     它<b>不进</b> SongPlayer 的判定，但两边同样要写全（缺了会兜底成歌曲那份）。<br>
 * 标签是 DB-only 的同一套，只是表的键从 mergeKey 换成主名（请求体里仍叫 mergeKey）。
 * 行上按钮（修改/删除）作用于**整行所有 variant**，播放浮层内同样整行一起。
 */
const SongActions = (() => {

    /** 每页条数。各列表页共用 */
    const PAGE_SIZE = 50;

    /**
     * 歌曲侧的约定。不传 {@code opts.cfg} 时用这一份 —— 歌曲那三个页面因此
     * 一行都不用改。
     * <p>{@code form: 'song'}：「修改」走列表式表单 {@code SongForm}（三框 + 文件列表 +
     * 逐文件编号，提交口 /edit-form-plan + /edit-form），见添加歌曲设计文档阶段 5。
     */
    const SONG_CFG = {base: '/api/song', versioned: true, form: 'song'};

    /** 喊麦侧的同一份约定：换前缀、改名改全名、没有原曲名那一层倍速。
     *  {@code form: 'shout'}：「修改」走 {@code ShoutForm} —— 喊麦不解析文件名，
     *  没有三框，改名就是一个可选的全名框。 */
    const SHOUT_CFG = {base: '/api/shout', versioned: false, hasOriginal: false, form: 'shout'};

    /** 调用方的约定；没传的字段按歌曲那份兜底 */
    function cfgOf(opts) {
        return Object.assign({}, SONG_CFG, (opts && opts.cfg) || {});
    }

    /**
     * 改名时预填的名字。
     * <p>歌曲只填基础名（{@code #版本号} 各自保留）；喊麦的 {@code #} 是普通字符，
     * 预填全名，改的就是全名。
     */
    function baseNameOf(mainName, cfg) {
        return cfg.versioned ? mainName.split('#')[0] : mainName;
    }


    /** 按页切片 */
    function pageRows(all, page) {
        const start = (page - 1) * PAGE_SIZE;
        return all.slice(start, start + PAGE_SIZE);
    }

    /** 分页条：每页条数用本模块的 PAGE_SIZE，画法见 UI.pagerBar */
    function pagerBar(container, total, current, onGo) {
        UI.pagerBar(container, total, current, PAGE_SIZE, onGo);
    }

    // ------------------------------------------------------------------
    // variant 定位
    // ------------------------------------------------------------------

    /** 行 → variant 列表。两边的行都是归并形态，喊麦每行恒一个（实现见 Util） */
    const variantsOf = Util.variantsOf;

    /** 组内文件数 */
    function fileCount(v) {
        return (v.videoFile ? 1 : 0) + (v.audioFile ? 1 : 0)
            + (v.lyricFiles ? v.lyricFiles.length : 0);
    }

    /** 视频/音频/歌词徽标。一眼能看出这组缺什么，比展开文件清单快 */
    function fileBadges(v) {
        let html = '';
        if (v.videoFile) {
            html += '<span class="tag tag-ok" title="' + UI.esc(v.videoFile) + '">视频</span>';
        }
        if (v.audioFile) {
            html += '<span class="tag tag-ok" title="' + UI.esc(v.audioFile) + '">音频</span>';
        }
        if (v.lyricFiles && v.lyricFiles.length) {
            html += '<span class="tag" title="' + UI.esc(v.lyricFiles.join('\n')) + '">歌词'
                + (v.lyricFiles.length > 1 ? ' ×' + v.lyricFiles.length : '') + '</span>';
        } else {
            html += '<span class="tag tag-warn">无歌词</span>';
        }
        return html;
    }

    // ------------------------------------------------------------------
    // plan 预演（弹窗内）
    // ------------------------------------------------------------------

    /**
     * 预演 → 审查 → 执行，全在弹窗里。
     *
     * @param m        UI.modal 返回的 {box, close}，清单画进 .modal-body、按钮放 .modal-foot
     * @param planUrl  预演接口
     * @param execute  确认后真执行的函数
     * @param opts.actionLabel  按钮/标题措辞；默认按 plan.action 映射
     * @param opts.danger       红色确认键并二次确认（删除用）
     * @param opts.confirmText  二次确认的话术
     * @param opts.onDone       执行成功后的回调
     */
    async function runPlan(m, planUrl, execute, opts = {}) {
        const bodyEl = m.box.querySelector('.modal-body');
        bodyEl.innerHTML = UI.spinner('预演中…');
        let plan;
        try {
            // 单组预演是 GET（query 参数）；批量预演要带 groups 列表，传 {url, body} 走 POST
            plan = typeof planUrl === 'string'
                ? await Api.get(planUrl)
                : await Api.post(planUrl.url, planUrl.body);
        } catch (e) {
            bodyEl.innerHTML = '<div class="hint hint-err">' + UI.esc(e.message) + '</div>';
            return;
        }

        // 规范化命名在后端就是改名（planNormalize 转给 planRename），plan.action 是 RENAME，
        // 所以按钮上写什么由调用方指定，不由 plan.action 决定
        const action = opts.actionLabel
            || {SCORE: '改评分', RENAME: '改名', DELETE: '删除'}[plan.action] || plan.action;
        const danger = opts.danger || plan.action === 'DELETE';

        let html = '<p class="mono small modal-plan-title">' + UI.esc(plan.mainName)
            + (plan.toMainName && plan.toMainName !== plan.mainName
                ? ' <span class="muted">→</span> ' + UI.esc(plan.toMainName) : '')
            + (plan.toPartition ? ' <span class="muted">→</span> ' + UI.esc(plan.toPartition) : '')
            + '</p>';

        // 整组级的阻塞原因（如目标分区目录不存在）先报，它比逐文件的原因更根本
        if (plan.blockedReason) {
            html += '<div class="hint hint-err">' + UI.esc(plan.blockedReason) + '</div>';
        }

        const blockedMoves = plan.moves.filter(m => m.blockedReason);
        // 整组被拦时 moves 是空的（连算都没算），这时不画空表
        if (plan.moves.length) {
            html += '<table class="small"><thead><tr><th style="width:44%">文件</th><th>'
                + (plan.action === 'DELETE' ? '会被删掉的路径' : '搬到') + '</th></tr></thead><tbody>';
            for (const m of plan.moves) {
                html += '<tr><td class="mono">' + UI.esc(m.fileName)
                    + '</td><td class="mono">'
                    + (m.blockedReason
                        ? '<span class="tag tag-err">' + UI.esc(m.blockedReason) + '</span>'
                        : (plan.action === 'DELETE' ? UI.esc(m.fromPath) : UI.esc(m.toPath)))
                    + '</td></tr>';
            }
            html += '</tbody></table>';
        }

        if (blockedMoves.length) {
            // 整批不执行，不是跳过继续：搬一半这首歌就裂成两个分区各一行了
            html += '<div class="hint hint-err">有 ' + blockedMoves.length + ' 个文件动不了，'
                + '整组不执行 —— 只搬一部分的话，这一组会裂成两半散在两个地方，'
                + '列表里看着像有两首歌。</div>';
        }
        bodyEl.innerHTML = html;

        const foot = m.box.querySelector('.modal-foot');
        foot.innerHTML = '<button type="button" class="btn-plain" id="sa-cancel">关闭</button>';
        const runable = !plan.blockedReason && !blockedMoves.length && plan.moves.length;
        if (runable) {
            const runBtn = document.createElement('button');
            runBtn.type = 'button';
            runBtn.className = danger ? 'btn-danger' : 'btn-primary';
            runBtn.textContent = '确认' + action;
            foot.insertBefore(runBtn, foot.firstChild);
            runBtn.onclick = async (ev) => {
                // 事件派发结束后 currentTarget 会被清成 null，先抓住按钮 —— 删除要等确认弹窗，
                // withBusy 在 await 之后才调用，直接拿 ev.currentTarget 会拿到 null
                const btn = ev.currentTarget;
                if (danger && opts.confirmText
                    && !await UI.confirm(opts.confirmText, {title: action, okText: '确认' + action})) {
                    return;
                }
                // 提交 → 轮询到终态期间全程禁用按钮。TaskPoll.wait 是「起轮询就返回 stop()」、
                // 不是 Promise，直接调用会让 withBusy 立刻恢复按钮 —— 任务还在跑、用户再点一次
                // 就重复提交（同一批文件动两遍）。用 Promise 接住终态，按钮保持禁用到底。
                await UI.withBusy(btn, '提交中…', () =>
                    new Promise((resolve) => {
                        const settled = () => resolve();
                        Promise.resolve()
                            .then(execute)
                            .then((taskId) => {
                                bodyEl.innerHTML = UI.spinner('执行中…');
                                TaskPoll.wait(taskId, {
                                    onResult: async (result) => {
                                        try {
                                            UI.ok('完成：' + (plan.action === 'DELETE' ? '删了 ' : '搬了 ')
                                                + result.movedFiles + ' 个文件'
                                                + (result.settingMoved ? '，设置也跟着搬了' : ''));
                                            m.close();
                                            if (opts.onDone) {
                                                await opts.onDone(result);
                                            }
                                            return true;
                                        } finally {
                                            settled();
                                        }
                                    },
                                    onFail: (error) => {
                                        UI.err(error);
                                        settled();
                                    }
                                });
                            })
                            .catch((e) => {
                                UI.err(e.message);
                                settled();
                            });
                    }));
            };
        }
        foot.querySelector('#sa-cancel').onclick = () => m.close();
    }

    // ------------------------------------------------------------------
    // 编辑弹窗（评分 + 改名，一个表单）
    // ------------------------------------------------------------------

    /**
     * 「修改」入口，按 {@code cfg.form} 分派：歌曲交给 {@code SongForm}
     * （③ 的列表来自 /group-files 与现扫对齐；② 未归档页传进来的行天然是现扫事实），
     * 喊麦交给 {@code ShoutForm}（列表直接来自行里的 variants —— 喊麦列表本就现扫、
     * 不走库，所以没有库/盘对齐那一段）。
     * **按钮的形态与数量两边一致**：都是一个「保存」，need: 'changed'。
     */
    async function edit(row, opts = {}) {
        if (cfgOf(opts).form === 'shout') {
            return editShoutForm(row, opts);
        }
        return editWithForm(row, opts);
    }

    /**
     * 列表式「修改」：组装 files（逐 variant 对齐库与磁盘，§5.5 的「画用库、做用盘」）
     * 后交给 SongForm，提交口 /edit-form-plan + /edit-form（异步任务 song.edit-form）。
     */
    async function editWithForm(row, opts = {}) {
        const cfg = cfgOf(opts);
        const variants = variantsOf(row);
        const onDone = opts.onDone;

        let partitions = opts.partitions;
        if (!partitions) {
            try {
                partitions = await Api.get(cfg.base + '/partitions');
            } catch (e) {
                UI.err(e.message);
                return;
            }
        }

        let roots = null;
        try {
            roots = await Api.get(cfg.base + '/roots');
        } catch (e) { /* 拉不到不影响开表单：只是文件没有 path，默认目录退回上次记忆 */ }

        /** variant 所在磁盘目录：待打分 = staging 根；已归档 = 归档根 \ 分区名 */
        function groupDir(v) {
            if (!roots) {
                return null;
            }
            return v.partitionName
                ? roots.archivedRoot + '\\' + v.partitionName
                : roots.stagingRoot;
        }

        // files：每个 variant 一组。待打分 variant 在库里没有行（同步只遍历已归档层），
        // 列表只能来自现扫；已归档 variant 拉 /group-files 与现扫逐文件对齐：
        //   两边都有 = 正常；只在磁盘 = 标「库里没有（未同步）」仍可提交；只在库里 =
        //   标「磁盘上已经不在」该行禁用、整批不可提交（不许悄悄少搬一个文件）
        const files = [];
        let libDiff = false;
        for (const v of variants) {
            const disk = [];
            if (v.videoFile) {
                disk.push({fileName: v.videoFile, role: 'VIDEO'});
            }
            if (v.audioFile) {
                disk.push({fileName: v.audioFile, role: 'AUDIO'});
            }
            for (const lyric of v.lyricFiles || []) {
                disk.push({fileName: lyric, role: 'LYRIC'});
            }
            // 编号框初值 = 原名 # 后原文；同一 variant 内联动靠共享 groupKey
            const version = v.mainName.indexOf('#') >= 0 ? v.mainName.slice(v.mainName.indexOf('#') + 1) : '';
            const groupKey = (v.partitionName || '') + '|' + v.mainName;
            const dir = groupDir(v);
            const fullPath = (fileName) => dir ? dir + '\\' + fileName : null;
            if (!v.partitionName) {
                for (const d of disk) {
                    files.push({fileName: d.fileName, role: d.role, mainName: v.mainName,
                        version, fromPartition: '', groupKey,
                        path: fullPath(d.fileName)});
                }
                continue;
            }
            let libRows;
            try {
                libRows = await Api.get(cfg.base + '/group-files?'
                    + Util.qs('partition', v.partitionName) + '&' + Util.qs('mainName', v.mainName));
            } catch (e) {
                UI.err(e.message);
                return;
            }
            // /group-files 按 (partition, mergeKey) 定位的是**合并条目级**的一行，返回它名下
            // 全部版本的 song_file 行 —— 必须先按本 variant 的 mainName 过滤再参与对齐，
            // 否则兄弟版本的行 / 改名前的残留行会被误判成「磁盘上已经不在」
            // （2026-09-23 实测：BAAM 条目 2 个文件显示成 4 个、2 个「不在」，就是漏了这步）
            libRows = libRows.filter(r => r.mainName === v.mainName);
            const libNames = libRows.map(r => r.fileName);
            const diskNames = disk.map(d => d.fileName);
            for (const d of disk) {
                files.push({fileName: d.fileName, role: d.role, mainName: v.mainName,
                    version, fromPartition: v.partitionName, groupKey,
                    path: fullPath(d.fileName),
                    libMissing: !libNames.includes(d.fileName)});
            }
            for (const r of libRows) {
                if (!diskNames.includes(r.fileName)) {
                    files.push({fileName: r.fileName, role: r.fileType || 'LYRIC',
                        mainName: v.mainName, version, fromPartition: v.partitionName,
                        groupKey, gone: true});
                }
            }
            if (libNames.some(n => !diskNames.includes(n))
                || diskNames.some(n => !libNames.includes(n))) {
                libDiff = true;
            }
        }

        SongForm.open({
            files,
            // 三框预填解析结果；解析失败的行留空（改名要人先补）
            initial: {
                artists: (row.artists || []).join(' & '),
                title: row.title || '',
                originalTitle: row.originalTitle || '',
                // 原曲作者（`原曲名_原唱`）交给 SongForm 折进「原曲名」框（foldOriginal）——
                // 三框装不下它，不传就等于这次保存会把名字改短，merge_key / 标签全丢
                originalArtist: row.originalArtist || ''
            },
            partitions,
            score: null,
            tags: (row.tags || []).slice(),
            requireTags: opts.requireTags,
            showTags: !!row.mergeKey,
            title: '修改',
            currentPartition: variants[0].partitionName || null,
            // 评分回显：当前分区的按钮打开即高亮，文字里也写一遍
            currentScoreText: variants[0].partitionDisplay || null,
            hint: libDiff ? '库与磁盘不一致，先点「重新扫描」同步一次。' : null,
            // ②③ 也开放添加 / 剔除文件（2026-09-23 用户定）：添加走 canAdd 入口，
            // 剔除已归档文件走「移入冗余」流程，未归档行剔除只提醒下次扫描会回来
            canAdd: true,
            removable: true,
            buttons: [{
                id: 'save',
                label: '保存',
                need: 'changed',
                primary: true,
                run: (v) => Api.post(cfg.base + '/edit-form-plan', editFormBody(v)),
                submit: (v) => Api.post(cfg.base + '/edit-form', editFormBody(v))
            }],
            onDone: (result, values) => onDone && onDone(result, {
                scored: values.selScore != null,
                renamed: values.willRename
            })
        });
    }

    /**
     * ②③ 的请求体：files 按 groupKey（分区|主名）归回 groups；
     * 剔除的已归档行带 exclude 标记留在原组里（后端据此移入「冗余」并置 song_id=0）；
     * 「＋ 添加文件」进来的外部文件（groupKey 为空、带 path）走 extraFiles；
     * 标签只在变了时送（null = 后端不动标签）。
     */
    function editFormBody(v) {
        const byGroup = new Map();
        const extraFiles = [];
        for (const f of v.files) {
            if (!f.groupKey) {
                if (!f.excluded && f.path) {
                    extraFiles.push({
                        sourcePath: f.path,
                        version: v.rename ? ((f.version || '').trim() || null) : null
                    });
                }
                continue;
            }
            if (!byGroup.has(f.groupKey)) {
                byGroup.set(f.groupKey, {
                    partition: f.fromPartition || null,
                    mainName: f.mainName,
                    files: []
                });
            }
            byGroup.get(f.groupKey).files.push(f.excluded
                ? {fileName: f.fileName, exclude: true}
                : {
                    fileName: f.fileName,
                    version: v.rename ? ((f.version || '').trim() || null) : null
                });
        }
        return {
            groups: [...byGroup.values()],
            artists: v.artists.trim(),
            title: v.title.trim(),
            originalTitle: v.originalTitle.trim(),
            rename: v.rename,
            targetPartition: null,
            score: v.selScore,
            tags: v.tagsChanged ? v.tags : null,
            extraFiles
        };
    }

    /**
     * 喊麦侧的「修改」（列表式表单 {@code ShoutForm}）。与歌曲那一路的差别只有两处，
     * 都是「喊麦不解析文件名」的直接后果：
     *   - 文件列表直接来自行里的 variants（喊麦列表本就现扫、不走库），
     *     没有 /group-files 那一段库/盘对齐，也就没有「库里有、盘上没了」那种行；
     *   - 没有三框，「手填名字」就是一个可选的全名框（留空 = 保持原文件名）。
     *
     * <p>「＋ 添加文件」照开（与歌曲一致）：给既有组补一个外部文件。
     *
     * @param opts.partitions 分区列表；不传现拉
     * @param opts.cfg       接口与命名约定，见 {@link #SHOUT_CFG}
     * @param opts.requireTags 归档前是否强制打标签（后端 {@code /roots} 下发的
     *        {@code requireTagsBeforeArchive}）。只决定提示语与按钮的提前拦截 ——
     *        **认定在后端**（预演里给 {@code blockedReason}）
     * @param opts.onDone     操作成功后的回调（通常是刷新列表），收到 (result, {scored, renamed})
     */
    async function editShoutForm(row, opts = {}) {
        const cfg = cfgOf(opts);
        const variants = variantsOf(row);
        const v = variants[0];
        const onDone = opts.onDone;

        let partitions = opts.partitions;
        if (!partitions) {
            try {
                partitions = await Api.get(cfg.base + '/partitions');
            } catch (e) {
                UI.err(e.message);
                return;
            }
        }

        let roots = null;
        try {
            roots = await Api.get(cfg.base + '/roots');
        } catch (e) { /* 拉不到不影响开表单：只是文件没有 path，默认目录退回上次记忆 */ }

        /** variant 所在磁盘目录：待打分 = staging 根；已归档 = 归档根 \ 分区名 */
        function groupDir(x) {
            if (!roots) {
                return null;
            }
            return x.partitionName
                ? roots.archivedRoot + '\\' + x.partitionName
                : roots.stagingRoot;
        }

        // files：每行一个文件。喊麦的组就是「去扩展名同名的那批」，所以同一 variant
        // 下所有文件共用 mainName 与 groupKey（分区|主名）—— 改评分 / 改名都整组一起
        const files = [];
        for (const x of variants) {
            const dir = groupDir(x);
            const groupKey = (x.partitionName || '') + '|' + x.mainName;
            const push = (fileName, role) => files.push({
                fileName, role, mainName: x.mainName,
                fromPartition: x.partitionName || '',
                groupKey,
                path: dir ? dir + '\\' + fileName : null
            });
            if (x.videoFile) {
                push(x.videoFile, 'VIDEO');
            }
            if (x.audioFile) {
                push(x.audioFile, 'AUDIO');
            }
            for (const lyric of x.lyricFiles || []) {
                push(lyric, 'LYRIC');
            }
        }

        ShoutForm.open({
            files,
            partitions,
            tags: (row.tags || []).slice(),
            requireTags: opts.requireTags,
            showTags: !!row.mergeKey,
            title: '修改',
            currentPartition: v.partitionName || null,
            // 评分回显：当前分区的按钮打开即高亮，文字里也写一遍
            currentScoreText: v.partitionDisplay || null,
            // ②③ 也开放添加 / 剔除文件（与歌曲一致）：添加走 canAdd 入口，
            // 剔除已归档文件走「移入冗余」流程
            canAdd: true,
            removable: true,
            buttons: [{
                id: 'save',
                label: '保存',
                need: 'changed',
                primary: true,
                run: (val) => Api.post(cfg.base + '/edit-form-plan', ShoutForm.editFormBody(val)),
                submit: (val) => Api.post(cfg.base + '/edit-form', ShoutForm.editFormBody(val))
            }],
            onDone: (result, values) => onDone && onDone(result, {
                scored: values.selScore != null,
                renamed: values.willRename
            })
        });
    }

    // ------------------------------------------------------------------
    // 删除弹窗（危险）
    // ------------------------------------------------------------------

    /** 删除整行：危险弹窗，先预演出所有 variant 要删的文件，红色确认 + 二次确认 */
    function remove(row, opts = {}) {
        const cfg = cfgOf(opts);
        const variants = variantsOf(row);
        const v = variants[0];
        const total = variants.reduce((n, x) => n + fileCount(x), 0);
        const groups = variants.map(x => ({
            partition: x.partitionName, mainName: x.mainName
        }));
        const body = {groups};
        const m = UI.modal('<div class="modal">'
            + '<div class="modal-head"><span>删除整组</span>'
            + '<button class="modal-close" type="button">×</button></div>'
            + '<div class="modal-body">' + UI.spinner('预演中…') + '</div>'
            + '<div class="modal-foot"></div></div>');
        m.box.querySelector('.modal-close').onclick = () => m.close();
        runPlan(m, {url: cfg.base + '/delete-batch-plan', body},
            () => Api.post(cfg.base + '/delete-batch', body),
            {
                danger: true,
                actionLabel: '删除',
                onDone: opts.onDone,
                confirmText: '删掉「' + baseNameOf(v.mainName, cfg) + '」这一行 '
                    + variants.length + ' 个版本的全部文件'
                    + '（视频、音频、歌词一共 ' + total + ' 个）？'
                    + '盘上的文件会送进 Windows 回收站，删错了可以从那里还原。'
            });
    }

    // ------------------------------------------------------------------
    // 重新同步（歌曲页与喊麦页顶部各一个按钮，走同一份实现）
    // ------------------------------------------------------------------

    /**
     * 「重新同步」：提交一次全量同步后台任务，排到终态后回调。
     *
     * <p><b>为什么需要这个按钮</b>：库里那两张镜像表（{@code song_group} / {@code song_file}
     * 与喊麦的两张）靠扫盘重建，启动时会自动跑一次 —— 于是改了磁盘上的文件名 / 分区之后，
     * 不重启就只能手工 curl。列表本身每次现扫，所以「列表没变」不代表不用同步：变的是
     * 标签、倍速、失踪标记这些读库的东西。
     *
     * <p><b>为什么按钮要一直禁用到任务终态</b>：{@code TaskPoll.wait} 是「起轮询就返回
     * stop()」而不是 Promise，直接调用会让 {@code withBusy} 立刻恢复按钮 —— 活儿还在跑、
     * 用户再点一次就又提交一个同步任务。所以这里用 Promise 接住终态
     * （与 {@code runPlan} 里那段同一处理）。
     *
     * @param cfg    调用方的约定，用它的 {@code base} 拼同步接口；不传按歌曲那份
     * @param onDone 同步完成后的回调（页面重拉列表）
     */
    async function syncNow(btn, cfg, onDone) {
        const c = cfgOf({cfg: cfg});
        await UI.withBusy(btn, '提交中…', () => new Promise((resolve) => {
            const settled = () => resolve();
            Api.post(c.base + '/sync')
                .then((taskId) => {
                    UI.ok('已提交任务 #' + taskId + '，同步中…');
                    TaskPoll.wait(taskId, {
                        onResult: async () => {
                            try {
                                UI.ok('同步完成');
                                if (onDone) {
                                    await onDone();
                                }
                                return true;
                            } finally {
                                settled();
                            }
                        },
                        onFail: (error) => {
                            UI.err(error);
                            settled();
                        }
                    });
                })
                .catch((e) => {
                    UI.err(e.message);
                    settled();
                });
        }));
    }

    return {
        PAGE_SIZE, pageRows, pagerBar,
        SONG_CFG, SHOUT_CFG,
        fileBadges, variantsOf,
        edit, remove, syncNow
    };
})();
