/**
 * 列表式歌曲表单（添加歌曲设计文档阶段 5）：三框 + 文件列表 + 逐文件编号框 +
 * 评分 + 标签 + 按钮集合，三个入口共用这一份：
 *   ① 未归档页「添加文件」弹窗（两个按钮：迁移到未归档 / 归档）；
 *   ② 未归档页行内「归档」（列表来自现扫的 variants）；
 *   ③ 歌曲页行内「修改」（列表来自 /group-files，与现扫逐文件对齐）。
 *
 * **判定只为实时，认定仍在后端**：按钮可点不代表一定过 —— plan 那一关永远在。
 * 预演与确认页都在本组件里画：/import-plan 与 /edit-form-plan 返回的是 ImportPlan{plan,
 * crossVolume}，包了一层，songaction.js 里那个共用预演件（runPlan，未导出）读的是里面
 * 那层，直接喂会拿到 undefined，所以不复用它 —— runPlan 继续服务 /edit-batch-plan 等
 * 既有调用点，一行不动。
 *
 * 按钮 `need` 的三个预设（闸门全在 evaluate 里，本组件不写第四种）：
 *   'changed'         ②③：名字/评分/标签任一变了 + 编号合法 + 批内无重名 + 无「磁盘上已不在」的行
 *   'import-migrate'  ①：三框填满（= 按三框重排，原勾选框已取消）+ 编号合法 + 批内无重名
 *                     + 至少一个媒体文件
 *   'import-archive'  ①：上面全部 + 选了评分 +（requireTags 时）有标签
 * 每个按钮不满足的原因写在它的 title 上，同时在提示区按按钮分行显示（最多三行）。
 *
 * 调用方通过 buttons[].run / .submit 注入接口：run(values) → 预演响应（ImportPlan），
 * submit(values) → taskId。确认页、跨卷标记、轮询与「按钮保持禁用到底」都在这里做。
 */
const SongForm = (() => {

    /** 后端 role → 展示文字。角色词由后端下发（import-expand / group-files 的 fileType），前端不写扩展名表 */
    const ROLE_TEXT = {VIDEO: '视频', AUDIO: '音频', LYRIC: '歌词'};

    /** 文件集合的签名（剔除行不算）：添加 / 剔除都会让签名变化 */
    function signatureOf(files) {
        return files.filter(f => !f.excluded)
            .map(f => f.path || ((f.fromPartition || '') + '|' + f.fileName))
            .sort().join('\n');
    }

    /**
     * 拼预览主干：作者 - 曲名（原曲名）#编号。<b>只为实时预览</b> —— 落盘名字以 plan
     * 回传的 toPath 为准，这里拼错只会让预览不好看，不会改坏磁盘。
     */
    function joinMain(artists, title, original, version) {
        const a = (artists || '').trim().split('&').map(s => s.trim()).filter(Boolean).join(' & ');
        const t = (title || '').trim();
        const o = (original || '').trim();
        const v = (version || '').trim();
        if (!a || !t || !o) {
            return null;
        }
        return a + ' - ' + t + '（' + o + '）' + (v ? '#' + v : '');
    }

    /**
     * 「原曲名」框的值：把原曲作者折进来，写成 `后来_刘若英`。
     *
     * 表单只有作者 / 曲名 / 原曲名三个框，装不下 `_原唱`；而落盘时那句括号是**原样**拼进去的
     * （后端 `SongNaming.build` 的第 3 个参数就是整个括号内容），所以折进来能逐字拼回原名 ——
     * 于是「名字本来就是规范形态」的文件经这次改名文件名一个字都不变。
     *
     * **不折 = 名字变短**：`merge_key` 跟着变、标签与原曲名级默认倍速全丢，
     * 口径见 `SongName#normalizedMainName` 的注释（那里把丢 `originalArtist` 单列为错）。
     *
     * 两处入口共用这一份：②③ 的 `initial.originalTitle + initial.originalArtist`、
     * ① 的 `parsed.originalTitle + parsed.originalArtist`（都在本文件里调它）。
     */
    function foldOriginal(originalTitle, originalArtist) {
        const t = (originalTitle || '').trim();
        const a = (originalArtist || '').trim();
        return t && a ? t + '_' + a : t;
    }

    /** 完整文件名 = mainName + suffix（原文大小写），suffix 用来拼预览（口径同后端 MediaExtensions.suffix） */
    function suffixOf(f) {
        return f.fileName.startsWith(f.mainName) ? f.fileName.slice(f.mainName.length) : '';
    }

    /** 主名 # 后那段（原文），编号框初值用 */
    function versionOfMain(mainName) {
        const i = mainName.indexOf('#');
        return i >= 0 ? mainName.slice(i + 1) : '';
    }

    /** 这个文件的目标主名：没勾改名 = 原主名；勾了 = 按三框拼（拼不出返回 null） */
    function targetMainOf(state, f) {
        if (!state.rename) {
            return f.mainName;
        }
        return joinMain(state.artists, state.title, state.original, f.version);
    }

    /** 目标目录近似：改评分 = 所选分区；否则留在原分区（① 全部一起进目标目录） */
    function targetDirOf(state, f) {
        return state.selScore != null ? (state.selDir || '') : (f.fromPartition || '');
    }

    // ------------------------------------------------------------------
    // 闸门：evaluate(state, need) → {ok, reasons[]}。原因的措辞与文档逐字一致
    // ------------------------------------------------------------------

    function evaluate(state, need) {
        const reasons = [];
        let goneFile = null;
        let hashFile = null;
        let dupPair = null;
        const seen = new Map();
        for (const f of state.files) {
            if (f.excluded) {
                continue;   // 剔除行不参与编号 / 重名 / 媒体判定（它不走常规搬动）
            }
            if (f.gone && !goneFile) {
                goneFile = f;
            }
            if (state.rename && (f.version || '').trim().indexOf('#') >= 0 && !hashFile) {
                hashFile = f;
            }
            const main = targetMainOf(state, f);
            if (main != null) {
                const key = targetDirOf(state, f) + '|' + main + suffixOf(f);
                const lower = key.toLowerCase();
                if (seen.has(lower) && !dupPair) {
                    dupPair = [seen.get(lower), f];
                }
                seen.set(lower, f);
            }
        }
        const hasMedia = state.files.some(f => !f.excluded && f.role !== 'LYRIC');
        const tagsChanged = JSON.stringify(state.tags) !== JSON.stringify(state.initialTags)
            || (state.tagEditor && state.tagEditor.hasPending());
        const willRename = state.rename && state.files.some(f => {
            const m = targetMainOf(state, f);
            return m != null && m !== f.mainName;
        });
        const willScore = state.selScore != null;
        // 添加 / 剔除过文件也算「有要保存的」（集合与打开时不一样了）
        const filesChanged = signatureOf(state.files) !== state.initialSignature;
        // 只改标签也算「有要保存的」：标签**没有**自己的保存按钮（SongTag.editor 只收集，
        // 见 songtag.js 的「保存由修改弹窗统一提交」），只能跟着这次提交一起走 ——
        // 所以它必须计入。验收清单里的原话：「只改标签 → 保存可点」
        // （docs/已完成/添加歌曲设计.md 阶段 5 冒烟第 4 条、② 的「只改标签保存 → 文件一律不动」）。
        // 2026-09-23 修：原先这里写着 `!state.staging && tagsChanged`，而两处调用
        // （SongActions 里那个「修改」入口与 ① 添加歌曲）都带 canAdd ⇒ staging 恒为 true ⇒
        // 改标签这一项永远救不活保存按钮，红字还写着「标签都没变」（假话）。
        const unchanged = !(willScore || willRename || tagsChanged || filesChanged);

        if (need === 'changed') {
            if (goneFile) {
                reasons.push('「' + goneFile.fileName + '」在磁盘上已经不在这一组里了，先重新扫描');
            }
            if (unchanged) {
                reasons.push('评分、名字和标签都没变，没有要保存的');
            }
            if (hashFile) {
                reasons.push('编号里不能有 #');
            }
            if (dupPair) {
                reasons.push('两个文件会重名，给其中一个填个编号（如 2）');
            }
        } else if (need === 'import-migrate' || need === 'import-archive') {
            // 空列表最优先：表单可以直接打开，文件是进去之后加的
            if (!state.files.length) {
                reasons.push('还没有选择要添加的文件');
            }
            // 三框填满 = 重排（原「还没勾」那条闸门随勾选框一起取消）
            if (!state.rename) {
                reasons.push('作者、曲名、原曲名都要填');
            }
            if (hashFile) {
                reasons.push('编号里不能有 #');
            }
            if (dupPair) {
                reasons.push('两个文件会重名，给其中一个填个编号（如 2）');
            }
            if (!hasMedia) {
                reasons.push('至少要有一个视频或音频文件');
            }
            if (need === 'import-archive') {
                if (state.selScore == null) {
                    reasons.push('还没选评分，归档必须给一个分');
                }
                // 与后端同一口径：**提交后**这一组一个标签都没有就拦（清空标签也算没标签）。
                // 原先多一个 `&& !tagsChanged`，于是「把标签全删掉」时按钮反而是亮的，
                // 点下去才在确认页吃一条红字 —— 前端提前拦就该拦准（认定仍在后端）
                if (state.requireTags && !state.tags.length) {
                    reasons.push('归档前要先打标签');
                }
            }
        }
        return {ok: reasons.length === 0, reasons};
    }

    // ------------------------------------------------------------------
    // 入口
    // ------------------------------------------------------------------

    function open(opts) {
        const initial = opts.initial || {};
        const initArtists = initial.artists || '';
        const initTitle = initial.title || '';
        const initOriginal = foldOriginal(initial.originalTitle, initial.originalArtist);
        const state = {
            files: (opts.files || []).map(f => Object.assign({}, f)),
            artists: initArtists,
            title: initTitle,
            original: initOriginal,
            // rename 不是独立开关：三框填满 = 按三框重排（勾选框已取消，2026-09-23 用户定）
            rename: !!(initArtists.trim() && initTitle.trim() && initOriginal.trim()),
            tags: (opts.tags || []).slice(),
            initialTags: (opts.tags || []).slice(),
            selScore: null,
            selDir: null,
            requireTags: !!opts.requireTags,
            removable: !!opts.removable,
            // canAdd：表单内添加文件（仅 ① 添加歌曲用；②③ 的文件来自磁盘现扫）
            canAdd: !!opts.canAdd,
            blocked: (opts.blocked || []).slice(),
            adding: false,
            prefilled: false,
            partitions: opts.partitions || [],
            // strictPartitions：「添加文件」（导入）那条路置 true —— 只列磁盘上真有的分区。
            // 导入是在新文件落盘之前定归档目标，缺档时后端直接拒（不自动建，见实现说明 8.4），
            // 画出来就是一个按下去必然报错的按钮。打分那一路是 false：缺的那档选它会现建出来
            strictPartitions: !!opts.strictPartitions,
            currentPartition: opts.currentPartition || null,
            tagEditor: null,
            currentScoreText: opts.currentScoreText || null,
            // 这组现在在待打分层（或者这是 ① 的添加模式：文件是进表单之后才加的）。
            // **只用于「归档前要先有标签」那句提示语**，不再参与「有没有要保存的」的判定 ——
            // 标签是 DB-only、不移动文件，所以「只改标签」本来就是一次合法提交
            // （后端 plan/apply 也明说「moves 为空是合法输入」）。
            staging: opts.canAdd || (opts.files || []).some(f => !f.fromPartition),
            // 文件集合签名：添加 / 剔除过文件就算「有要保存的」（剔除行不算在集合里）
            initialSignature: signatureOf(opts.files || [])
        };

        const m = UI.modal('<div class="modal modal-wide">'
            + '<div class="modal-head"><span>' + UI.esc(opts.title || '修改') + '</span>'
            + '<button class="modal-close" type="button">×</button></div>'
            + '<div class="modal-body"></div>'
            + '<div class="modal-foot" id="sf-foot"></div>'
            + '</div>');
        m.box.querySelector('.modal-close').onclick = () => m.close();

        // ---- foot：按钮 + 关闭 + 原因区（页面底部，紧贴按钮）。**先建按钮再 renderForm** ——
        // 原先按钮在 renderForm 之后才插进 foot，初始那次 refresh() 找不到按钮、禁用被跳过，
        // 症状是「表单刚打开时不合格的按钮也能点」（2026-09-23 用户反馈修掉）----
        const foot = m.box.querySelector('#sf-foot');
        foot.style.flexWrap = 'wrap';
        const reasonsEl = document.createElement('div');
        reasonsEl.style.cssText = 'flex-basis:100%;margin-top:4px';
        const closeBtn = document.createElement('button');
        closeBtn.type = 'button';
        closeBtn.className = 'btn-plain';
        closeBtn.textContent = '关闭';
        closeBtn.onclick = () => m.close();
        foot.appendChild(closeBtn);
        for (const b of opts.buttons) {
            const btn = document.createElement('button');
            btn.type = 'button';
            btn.className = b.danger ? 'btn-danger' : (b.primary ? 'btn-primary' : '');
            btn.textContent = b.label;
            btn.dataset.btn = b.id;
            btn.onclick = (ev) => runButton(b, ev.currentTarget);
            foot.insertBefore(btn, closeBtn);
        }
        foot.appendChild(reasonsEl);

        renderForm();

        function renderForm() {
            // 回到表单 = 上一轮的预演作废：把那一轮插进来的确认按钮摘掉（连同它闭包里
            // 那份 values）。失败回退走的就是这条路（execute 的 onFail）
            foot.querySelectorAll('[data-confirm]').forEach(el => el.remove());
            const body = m.box.querySelector('.modal-body');
            let html = '';
            if (opts.hint) {
                html += '<p class="muted small">' + UI.esc(opts.hint) + '</p>';
            }
            for (const blocked of state.blocked) {
                html += '<div class="hint hint-err">' + UI.esc(blocked) + '</div>';
            }

            // ---- 三框（有表头）。三框填满 = 按三框重排文件名；没填满 = 不改名（只动评分 / 标签）。
            // 原先的「按以下信息重排文件名」勾选框已取消（2026-09-23 用户定：现在都是要重排的），
            // 勾与不勾的差别由三框是否填满自然表达 ----
            html += '<div style="display:grid;grid-template-columns:1fr 1fr 1fr;gap:6px">'
                + '<span class="muted small">作者</span>'
                + '<span class="muted small">曲名</span>'
                + '<span class="muted small">原曲名</span>'
                + '<input type="text" id="sf-artists" placeholder="多个用 & 分隔" value="' + UI.esc(state.artists) + '">'
                + '<input type="text" id="sf-title" placeholder="曲名" value="' + UI.esc(state.title) + '">'
                + '<input type="text" id="sf-original" placeholder="原曲名" value="' + UI.esc(state.original) + '">'
                + '</div>';

            // ---- 文件列表（canAdd 时带「＋ 添加文件」入口）----
            html += '<div class="row" style="justify-content:space-between;align-items:center;margin-top:10px">'
                + '<span class="muted small">文件'
                + (state.canAdd ? '（同名不同后缀自动联动，联动只在同一个源目录内）' : '')
                + '</span>'
                + (state.canAdd ? '<button id="sf-add-files">＋ 添加文件</button>' : '')
                + '</div>'
                + '<div id="sf-filebox" style="margin-top:6px">' + filesInner() + '</div>';

            // ---- 评分区 ----
            // 打分那一路把没建的档也画出来（onDisk=false，选它落盘时自动建，2026-09-30 起）；
            // 导入那一路只画真在盘上的，见 state.strictPartitions
            const scoreOptions = state.partitions.filter(
                p => !state.strictPartitions || p.onDisk);
            if (scoreOptions.length) {
                html += '<p class="muted small" style="font-weight:600;margin-top:10px">评分</p>'
                    + '<p class="muted small">'
                    + (state.currentScoreText ? '当前：' + UI.esc(state.currentScoreText) + '。' : '')
                    + '不选分值就是不改评分；再点一次已选中的分可取消。</p>'
                    + '<div class="row score-pad" id="sf-scores">'
                    + scoreOptions.map(p => '<button class="score-btn'
                        + (p.dirName === (state.selDir || state.currentPartition) ? ' on' : '')
                        + (p.dirName === state.currentPartition ? ' disabled' : '') + '"'
                        + ' data-score="' + p.score + '" data-dir="' + UI.esc(p.dirName) + '"'
                        // 还没建的那档说清楚它跟别的不一样：点了会在归档根下现建一个目录
                        + (p.onDisk ? '' : ' title="这一档还没建，选它会建出 '
                            + UI.esc(p.dirName) + '（评语回头自己加）"')
                        + '>' + UI.esc(p.display) + '</button>').join('')
                    + '</div>';
            }

            // ---- 标签区 ----
            if (opts.showTags) {
                html += '<p class="muted small" style="font-weight:600;margin-top:10px">标签</p>'
                    + (state.staging && state.requireTags
                        ? '<p class="muted small">归档前要先有标签，不打标签不能归档。</p>' : '')
                    + '<div id="sf-tags"></div>';
            }
            body.innerHTML = html;
            bindForm();
        }

        /** 文件区内容：空列表给一句引导（文件是进表单之后加的），非空画表格 */
        function filesInner() {
            if (!state.files.length) {
                return UI.empty(state.canAdd
                    ? '还没有文件，点「＋ 添加文件」选择要导入的文件。'
                    : '没有文件。');
            }
            return '<div style="overflow-x:auto"><table class="small" id="sf-files" '
                + 'style="min-width:820px">'
                + '<thead><tr>'
                + '<th style="width:72px">类型</th><th style="width:34%">文件</th>'
                + '<th style="width:90px">编号</th><th style="width:34%">改名后</th><th style="width:110px"></th>'
                + '</tr></thead><tbody>' + fileRows() + '</tbody></table></div>';
        }

        function fileRows() {
            return state.files.map((f, i) => {
                const main = targetMainOf(state, f);
                const preview = main == null ? '<span class="muted">—</span>'
                    : UI.esc(main + suffixOf(f));
                let status = '';
                if (f.gone) {
                    status = '<span class="tag tag-err">磁盘上已经不在</span>';
                } else if (f.excluded) {
                    status = '<span class="tag tag-warn">将移入冗余</span>';
                } else if (f.libMissing) {
                    status = '<span class="tag tag-warn" title="磁盘是权威，改名/搬动照做；先点「重新扫描」同步一次">库里没有（未同步）</span>';
                } else if (f.archived) {
                    status = '<span class="tag" title="这个文件来自已归档分区，提交时后端会处理好库里旧行，不会出现两条数据">已归档</span>';
                }
                const remove = state.removable
                    ? '<button class="sf-remove" data-i="' + i + '" title="' + removeTitle(f) + '">'
                        + (f.excluded ? '↩' : '×') + '</button>'
                    : '';
                // 在资源管理器中显示：选完文件立刻确认位置。壳只放行**它自己亲手交出去的**
                // 那些路径（grantedPaths），所以只对来自 pickFiles 的行画（f.picked）——
                // 修改 / 归档弹窗的行是磁盘现扫来的、粘贴路径进来的也不是壳给的，
                // 那些一律不画（画了排排都是点不通的按钮）。被拒仍弹人话，不静默
                const show = f.path && f.picked && hasShowItem()
                    ? '<button class="sf-show" data-i="' + i
                        + '" title="在资源管理器中显示这个文件">位置</button>'
                    : '';
                return '<tr' + (f.gone || f.excluded ? ' style="opacity:.55"' : '') + '>'
                    + '<td><span class="tag">' + UI.esc(ROLE_TEXT[f.role] || f.role) + '</span></td>'
                    + '<td class="mono" title="' + UI.esc(f.path || f.fileName) + '">' + UI.esc(f.fileName) + '</td>'
                    + '<td><input type="text" class="sf-version" data-i="' + i + '" value="'
                    + UI.esc(f.version || '') + '"' + (f.gone || f.excluded ? ' disabled' : '') + '></td>'
                    + '<td class="mono" data-prev="' + i + '">' + preview + '</td>'
                    + '<td>' + status + show + remove + '</td>'
                    + '</tr>';
            }).join('');
        }

        function bindForm() {
            const body = m.box.querySelector('.modal-body');

            for (const id of ['sf-artists', 'sf-title', 'sf-original']) {
                body.querySelector('#' + id).oninput = (e) => {
                    state[id === 'sf-artists' ? 'artists' : id === 'sf-title' ? 'title' : 'original'] = e.target.value;
                    // 三框填满 = 重排（原勾选框已取消，2026-09-23 用户定）
                    state.rename = !!(state.artists.trim() && state.title.trim() && state.original.trim());
                    refresh();
                };
            }

            body.querySelectorAll('#sf-scores button').forEach(btn => btn.onclick = () => {
                if (btn.disabled) {
                    return;
                }
                // 再点一次已选中的分 = 取消（回到「不改评分」，回显回落到当前分区）
                if (state.selDir === btn.dataset.dir
                    && String(state.selScore) === btn.dataset.score) {
                    state.selScore = null;
                    state.selDir = null;
                    body.querySelectorAll('#sf-scores button').forEach(b => b.classList.remove('on'));
                    refresh();
                    return;
                }
                body.querySelectorAll('#sf-scores button').forEach(b => b.classList.remove('on'));
                btn.classList.add('on');
                state.selScore = Number(btn.dataset.score);
                state.selDir = btn.dataset.dir;
                refresh();
            });

            if (opts.showTags) {
                state.tagEditor = SongTag.editor(body.querySelector('#sf-tags'), state.tags, refresh);
            }

            const addBtn = body.querySelector('#sf-add-files');
            if (addBtn) {
                addBtn.onclick = addFiles;
            }

            bindTable(body);
            refresh();
        }

        /** 只重画文件区（增删行时用），不重建三框 —— 免得输入焦点丢失 */
        function drawFiles() {
            const body = m.box.querySelector('.modal-body');
            body.querySelector('#sf-filebox').innerHTML = filesInner();
            bindTable(body);
        }

        /** × 的提示按文件来源分三种（语义见 bindTable 的 remove 处理） */
        function removeTitle(f) {
            if (f.excluded) {
                return '恢复：不剔除，照常参与本次保存';
            }
            if (f.fromPartition) {
                return '剔除：提交后文件移入「冗余」文件夹并标记冗余（song_id=0）';
            }
            if (f.groupKey) {
                return '本次不处理；未归档按文件名扫描，下次扫描时还会添加回来';
            }
            return '取消添加：只从本次列表移除，不动磁盘上的文件';
        }

        function bindTable(body) {
            body.querySelectorAll('.sf-version').forEach(input => input.oninput = () => {
                const i = Number(input.dataset.i);
                state.files[i].version = input.value;
                // ②③ 同一组（groupKey 相同）的编号联动：改一个，同组的其它文件跟着改。
                // ①（groupKey 为空）不联动 —— 手选的碎片逐文件填编号是它的正常用法
                const key = state.files[i].groupKey;
                if (key) {
                    body.querySelectorAll('.sf-version').forEach(other => {
                        const j = Number(other.dataset.i);
                        if (j !== i && state.files[j].groupKey === key) {
                            state.files[j].version = input.value;
                            other.value = input.value;
                        }
                    });
                }
                refresh();
            });
            body.querySelectorAll('.sf-show').forEach(btn => btn.onclick = async () => {
                const f = state.files[Number(btn.dataset.i)];
                const r = await window.NyaEntworksShell.showItem(f.path);
                // 取消与成功都不出声；拒绝（壳不认这个路径 / 文件已不在）给人话
                if (!r.ok && r.message && r.message !== '已取消') {
                    UI.err(r.message);
                }
            });
            body.querySelectorAll('.sf-remove').forEach(btn => btn.onclick = async () => {
                const i = Number(btn.dataset.i);
                const f = state.files[i];
                // 已归档文件：× = 标记剔除（可再点 ↩ 恢复），提交时才真正移入「冗余」
                if (f.fromPartition) {
                    if (!f.excluded && !await UI.confirm('「' + f.fileName + '」提交后将从这一组剔除：'
                            + '文件会移入「冗余」文件夹、库里标记为冗余（song_id=0），'
                            + '不再属于这一组。确定剔除？',
                        {title: '剔除已归档文件', okText: '剔除'})) {
                        return;
                    }
                    f.excluded = !f.excluded;
                    drawFiles();
                    refresh();
                    return;
                }
                // 其余（外部新选 / 未归档层）：直接从列表移除，磁盘上一个文件都不动
                state.files.splice(i, 1);
                if (f.groupKey) {
                    // 未归档层的行：提醒它下次扫描还会回来（按文件名扫描的口径）
                    UI.ok('未归档歌曲是按文件名扫描，下次扫描时还会添加回来');
                }
                drawFiles();
                refresh();
            });
        }

        /** 刷新预览、按钮可用性与按按钮分行的原因 */
        function refresh() {
            const body = m.box.querySelector('.modal-body');
            state.files.forEach((f, i) => {
                const cell = body.querySelector('[data-prev="' + i + '"]');
                if (!cell) {
                    return;
                }
                const main = targetMainOf(state, f);
                cell.textContent = main == null ? '—' : main + suffixOf(f);
            });

            let lines = '';
            for (const b of opts.buttons) {
                const gate = evaluate(state, b.need);
                const btnEl = foot.querySelector('[data-btn="' + b.id + '"]');
                if (btnEl) {
                    btnEl.disabled = !gate.ok;
                    btnEl.title = gate.reasons.join('\n');
                }
                if (!gate.ok && lines.split('<').length <= 3) {
                    lines += '<div class="muted small">［' + UI.esc(b.label) + '］'
                        + UI.esc(gate.reasons[0] || '') + '</div>';
                }
            }
            reasonsEl.innerHTML = lines;
        }

        /** 提交给 run / submit 的快照。标签先 flush（输入框里打了还没回车的内容也算数） */
        function snapshot() {
            if (state.tagEditor) {
                state.tagEditor.flush();
            }
            const tagsChanged = JSON.stringify(state.tags) !== JSON.stringify(state.initialTags);
            return {
                files: state.files.map(f => Object.assign({}, f)),
                artists: state.artists,
                title: state.title,
                originalTitle: state.original,
                rename: state.rename,
                tags: state.tags.slice(),
                tagsChanged,
                selScore: state.selScore,
                selDir: state.selDir,
                filesChanged: signatureOf(state.files) !== state.initialSignature,
                requireTags: state.requireTags,
                staging: state.staging,
                willRename: state.rename && state.files.some(f => {
                    const main = targetMainOf(state, f);
                    return main != null && main !== f.mainName;
                })
            };
        }

        // ---- 表单内添加文件（canAdd 模式）----
        // 选择对话框的默认目录（2026-09-23 用户定）：
        //   列表已有文件 → 最后一个「有磁盘路径」的文件所在文件夹（补文件大概率同目录）；
        //   列表为空（全新新增） → 不传 defaultPath，由壳的主进程回落到持久化的
        //   「上次新增目录」（记在壳的 userData 里 —— 壳加载的是动态端口的 http origin，
        //   localStorage 换端口就丢，所以记忆不放前端）。

        /** 目录部分（两种分隔符都认）；取不到返回空串 */
        function dirOf(path) {
            const i = Math.max(path.lastIndexOf('\\'), path.lastIndexOf('/'));
            return i > 0 ? path.slice(0, i) : '';
        }

        /**
         * 路径比对用的键：分隔符统一成 `\`、折叠大小写。
         *
         * 用来认「后端回显的这个 path 是不是壳刚亲手交出去的那一个」—— 两边字符串可能不同：
         * 后端 `import-expand` 回显的是 `toRealPath()`（`SongImportService#realPath`，挡符号
         * 链接 / 目录联接，Windows 上还带盘上的真实大小写），壳给的则是对话框原样。
         * 常见差异只有分隔符与盘符大小写，这两种归一化就够。
         *
         * 判错的代价只有一种：**少画**一个「位置」按钮（源路径正好是联接 / 符号链接时），
         * 不会误放行 —— 真正的放行判定在壳里（`grantedPaths`），这个标记只决定画不画。
         */
        function pathKey(p) {
            return String(p || '').replace(/\//g, '\\').toLowerCase();
        }

        function defaultImportDir() {
            // 从后往前找第一个有 path 的文件（gone 行也可能有 path，无妨）
            for (let i = state.files.length - 1; i >= 0; i--) {
                const p = state.files[i].path;
                if (p) {
                    return dirOf(p);
                }
            }
            return '';
        }

        function hasShell() {
            return !!(window.NyaEntworksShell && typeof window.NyaEntworksShell.pickFiles === 'function');
        }

        /** 壳的「在资源管理器中显示」在不在（老壳没有这个能力 —— 改过 preload 必须重启壳） */
        function hasShowItem() {
            return !!(window.NyaEntworksShell && typeof window.NyaEntworksShell.showItem === 'function');
        }

        /** 无壳时的路径录入框：每行一个绝对路径（资源管理器 Shift+右键「复制文件地址」粘进来） */
        function pastePathsModal() {
            return new Promise((resolve) => {
                const pm = UI.modal('<div class="modal">'
                    + '<div class="modal-head"><span>粘贴路径</span>'
                    + '<button class="modal-close" type="button">×</button></div>'
                    + '<div class="modal-body">'
                    + '<p class="muted small">每行一个文件的绝对路径。在资源管理器里 Shift+右键文件'
                    + ' →「复制文件地址」，一行粘一个。</p>'
                    + '<textarea id="sf-paste" rows="8" style="width:100%" '
                    + 'placeholder="F:\\NetdiskDownload\\某首歌.mp4&#10;F:\\NetdiskDownload\\某首歌.lrc"></textarea>'
                    + '</div>'
                    + '<div class="modal-foot">'
                    + '<button type="button" class="btn-plain" id="sf-paste-cancel">取消</button>'
                    + '<button type="button" class="btn-primary" id="sf-paste-ok">添加</button>'
                    + '</div></div>');
                const finish = (val) => {
                    pm.close();
                    resolve(val);
                };
                pm.box.querySelector('.modal-close').onclick = () => finish(null);
                pm.box.querySelector('#sf-paste-cancel').onclick = () => finish(null);
                pm.box.querySelector('#sf-paste-ok').onclick = () => {
                    const paths = pm.box.querySelector('#sf-paste').value.split('\n')
                        .map(s => s.trim().replace(/^"+|"+$/g, ''))
                        .filter(Boolean);
                    finish(paths.length ? paths : null);
                };
            });
        }

        /**
         * 取一批源路径（壳多选 / 粘贴），取消返回 null。
         *
         * 返回值多带一个 `fromShell`：**只有壳亲手交出来的那批**路径才有资格被
         * 「在资源管理器中显示」（壳侧 `grantedPaths` 只记它自己 pickFiles / pickPath
         * 的返回值）。粘贴进来的不算 —— 画一个点不通的按钮比不画更让人摸不着头脑。
         */
        async function pickImportPaths() {
            if (hasShell()) {
                const picked = await window.NyaEntworksShell.pickFiles({
                    title: '选择要添加的歌曲文件',
                    defaultPath: defaultImportDir()
                });
                if (!picked.ok) {
                    // 取消不算错误（ok:false + 「已取消」）
                    if (picked.message && picked.message !== '已取消') {
                        UI.err(picked.message);
                    }
                    return null;
                }
                return {paths: picked.paths, fromShell: true};
            }
            const pasted = await pastePathsModal();
            return pasted ? {paths: pasted, fromShell: false} : null;
        }

        /** 「＋ 添加文件」：选路径 → import-expand 联动展开 → 追加进列表（去重、提示累积） */
        async function addFiles() {
            if (state.adding) {
                return;
            }
            state.adding = true;
            try {
                const picked = await pickImportPaths();
                if (!picked) {
                    return;
                }
                const paths = picked.paths;
                // 壳这次交出来的那几个路径（归一化后比较）。只有命中它们的行才画「位置」
                const showable = new Set(picked.fromShell ? paths.map(pathKey) : []);
                const groups = await Api.post('/api/song/import-expand', {paths});
                for (const g of groups) {
                    // 受管根命中 / 认不出的扩展名：追加到顶部红字区（累积，不覆盖既有提示）
                    if (g.blockedReason) {
                        state.blocked.push(g.blockedReason);
                        continue;
                    }
                    for (const skipped of g.skipped || []) {
                        state.blocked.push(skipped);
                    }
                    for (const f of g.files) {
                        if (state.files.some(x => x.path === f.path)) {
                            continue;   // 按绝对路径去重：同一批反复选 / 与已有行重复都不进
                        }
                        state.files.push({
                            fileName: f.fileName,
                            role: f.role,
                            mainName: f.mainName,
                            version: (f.parsed && f.parsed.version) || '',
                            path: f.path,
                            // 后端 `import-expand` 随行下发的解析结果，三框预填要用。
                            // **判「解析成功」只能看 `parseFailedReason`** —— `parsed()` /
                            // `artistText()` 是 record 的方法，Jackson 不下发（附录 C 那条坑）
                            parsed: f.parsed || null,
                            // 「位置」按钮的资格：壳亲手选进来的才算（粘贴 / 磁盘现扫的行不画）
                            picked: showable.has(pathKey(f.path)),
                            fromPartition: '',
                            groupKey: '',      // ① 逐文件独立编号，不联动
                            archived: f.rootKind === 'PARTITION'
                        });
                    }
                }
                // 三框预填：首批文件进入且三框全空时做一次，之后用户改过就不再覆盖、
                // 再添文件也不动（2026-09-23 用户定）。
                //
                // **文件名本身就是标准的（解析成功），就按标准名的规则拆着填**
                // （2026-09-25 用户定）—— 整名塞进曲名会让规范名经一次改名反而变得不规范：
                // `甲 - 乙（丙）#2` 会被拼成 `甲 - 乙（丙）#2（甲 - 乙（丙）#2）`。
                // 编号不用管，逐文件已经填在编号框里了（上面 `parsed.version`）。
                // 解析不了的行才回落到「未知 / 首文件主名 / 同曲名」（A.6：「未知」是字面量）——
                // 拼出来即「作者 - 曲名（曲名）」的规范形态，翻唱未改词正是这个形状
                if (!state.prefilled && state.files.length
                    && !state.artists.trim() && !state.title.trim() && !state.original.trim()) {
                    state.prefilled = true;
                    // 首文件优先（它是这一组的代表）；它不是标准名时退而找第一个能解析的
                    const p = state.files.find(f => f.parsed && !f.parsed.parseFailedReason);
                    if (p) {
                        state.artists = (p.parsed.artists || []).join(' & ');
                        state.title = p.parsed.title || '';
                        state.original = foldOriginal(p.parsed.originalTitle,
                            p.parsed.originalArtist);
                    } else {
                        state.artists = '未知';
                        state.title = state.files[0].mainName;
                        state.original = state.title;
                    }
                    state.rename = true;
                }
                renderForm();   // 整体重画：三框值、文件区、累积的红字提示一起刷新
            } catch (e) {
                UI.err(e.message);
            } finally {
                state.adding = false;
            }
        }

        // ---- 预演 → 确认页 → 执行 → 轮询 ----
        async function runButton(b, btnEl) {
            const values = snapshot();
            let planResp;
            try {
                // 预演期间禁用按钮，避免连点重复发请求
                await UI.withBusy(btnEl, '预演中…', async () => {
                    planResp = await b.run(values);
                });
            } catch (e) {
                UI.err(e.message);
                return;
            }
            if (!planResp) {
                return;   // run 里主动取消（如迁移前的丢失提醒选了「否」），静默回到表单
            }
            drawConfirm(b, planResp, values);
        }

        /** 确认页：旧名 → 新名、源目录 → 目标目录、跨卷标记、blockedReason 红字（整批不执行） */
        function drawConfirm(b, planResp, values) {
            const body = m.box.querySelector('.modal-body');
            const plan = planResp.plan;
            const cross = planResp.crossVolume || [];
            let html = '<p class="mono small modal-plan-title">' + UI.esc(plan.mainName);
            if (plan.toMainName && plan.toMainName !== plan.mainName) {
                html += ' <span class="muted">→</span> ' + UI.esc(plan.toMainName);
            }
            if (plan.toPartition) {
                html += ' <span class="muted">→</span> ' + UI.esc(plan.toPartition);
            }
            html += '</p>';

            if (plan.blockedReason) {
                html += '<div class="hint hint-err">' + UI.esc(plan.blockedReason) + '</div>';
            }
            const blockedMoves = plan.moves.filter(mv => mv.blockedReason);
            if (plan.moves.length) {
                const first = plan.moves[0];
                const parentOf = p => p.replace(/[\\/][^\\/]*$/, '');
                html += '<p class="muted small mono">' + UI.esc(parentOf(first.fromPath))
                    + ' <span class="muted">→</span> ' + UI.esc(parentOf(first.toPath)) + '</p>';
                html += '<table class="small"><thead><tr><th style="width:44%">文件</th>'
                    + '<th style="width:34%">会变成</th><th></th></tr></thead><tbody>';
                for (const mv of plan.moves) {
                    html += '<tr><td class="mono">' + UI.esc(mv.fileName) + '</td><td class="mono">'
                        + (mv.blockedReason
                            ? '<span class="tag tag-err">' + UI.esc(mv.blockedReason) + '</span>'
                            : UI.esc(mv.toFileName));
                    if (!mv.blockedReason && cross.includes(mv.fileName)) {
                        html += '</td><td><span class="tag tag-warn">跨卷，需拷贝，可能较慢</span></td></tr>';
                    } else {
                        html += '</td><td></td></tr>';
                    }
                }
                html += '</tbody></table>';
            }
            if (blockedMoves.length) {
                // 整批不执行，不是跳过继续：搬一半这一组会裂成两半散在两个地方
                html += '<div class="hint hint-err">有 ' + blockedMoves.length + ' 个文件动不了，'
                    + '整批不执行 —— 只搬一部分的话，这一组会裂成两半散在两个地方。</div>';
            }
            // 0 条搬动是**合法输入**（名字与位置都没变，最典型的就是「只改标签」）。
            // 后端 apply 明说「moves 为空是合法输入：什么都不搬，只走标签与库那侧」，
            // 所以这里不能把「没清单可画」当成失败 —— 但页面上得说清这次不动文件
            if (!plan.blockedReason && !plan.moves.length) {
                html += '<div class="hint">没有文件要搬动（名字与位置都没变）'
                    + (values.tagsChanged ? '，这次只写标签。' : '。') + '</div>';
            }
            body.innerHTML = html;

            // 旧的确认按钮先摘掉：连点两个动作按钮（如先「保存」再「归档」）会重画这一页，
            // 留着上一轮那个按钮的话，它的 label 与闭包里那份 values 都是上一轮的 ——
            // 屏幕上显示的清单与点下去执行的东西不是同一件事（2026-09-23 修）
            foot.querySelectorAll('[data-confirm]').forEach(el => el.remove());

            // 0 条搬动**不算**不能提交（见上面那段提示）。真正的拦截只有「整组被拦」与「逐文件被拦」
            const runable = !plan.blockedReason && !blockedMoves.length;
            if (runable) {
                const runBtn = document.createElement('button');
                runBtn.type = 'button';
                runBtn.className = b.danger ? 'btn-danger' : 'btn-primary';
                runBtn.textContent = '确认' + b.label;
                runBtn.dataset.confirm = b.id;
                foot.insertBefore(runBtn, foot.firstChild);
                runBtn.onclick = (ev) => execute(b, values, ev.currentTarget);
            }
        }

        function execute(b, values, btnEl) {
            const body = m.box.querySelector('.modal-body');
            // 提交 → 轮询到终态期间全程禁用按钮。TaskPoll.wait 是「起轮询就返回 stop()」、
            // 不是 Promise，直接调用会让 withBusy 立刻恢复按钮 —— 任务还在跑、用户再点一次
            // 就重复提交（同一批文件动两遍）。用 Promise 接住终态，按钮保持禁用到底。
            // （照抄 songaction.js runPlan 里那段，含这段注释。）
            // 除了被点的那一个，**整条 foot 上的动作按钮一起锁**：withBusy 只管得住 btnEl，
            // 而任务是异步的（批量可能跑几分钟），期间点另一个动作按钮就会又提交一个任务、
            // 同一批文件动两遍。失败回退时 renderForm() 会按闸门重新算 disabled。
            // 「关闭」不动 —— 任务在服务端跑，关掉弹窗只是不再看轮询
            foot.querySelectorAll('[data-btn], [data-confirm]').forEach(el => { el.disabled = true; });
            UI.withBusy(btnEl, '提交中…', () => new Promise((resolve) => {
                const settled = () => resolve();
                Promise.resolve()
                    .then(() => b.submit(values))
                    .then((taskId) => {
                        body.innerHTML = UI.spinner('执行中…');
                        TaskPoll.wait(taskId, {
                            onResult: async (result) => {
                                try {
                                    UI.ok('完成');
                                    m.close();
                                    if (opts.onDone) {
                                        await opts.onDone(result, values, b);
                                    }
                                    return true;
                                } finally {
                                    settled();
                                }
                            },
                            onFail: (error) => {
                                UI.err(error);
                                settled();
                                renderForm();   // 回到表单让人改完再试
                            }
                        });
                    })
                    .catch((e) => {
                        UI.err(e.message);
                        settled();
                        renderForm();
                    });
            })).catch(() => {});   // withBusy 只透传 submit 的同步异常，上面已 UI.err
        }
    }

    return {open};
})();
