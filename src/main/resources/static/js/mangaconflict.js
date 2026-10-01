/**
 * 合并冲突页（文档 4.6）。
 *
 * 重名冲突 = 同一个社团/作者名落在多个归档目录上，归档匹配时会按归档根优先级取其一。
 * 这一页做左右并排比对：两侧漫画现扫列出（评分继承/单独标记可见），
 * 勾选后跨目录移动（评分继承随目标目录分变动、单独分不动），单本删除/改名/评分走阅读页，
 * 一侧清空后「删除空目录并完成合并」把磁盘目录与库行一起删掉。
 *
 * 每侧下半部分是**归档目录根下的「其他文件」**（2026-09-15 补，文档 4.6）：不是漫画、
 * 也不在库里的散文件 / 目录。按裁决只做**列出 + 批量移动**两件，不做删除 / 改名 ——
 * 要删就去资源管理器删。搬动走 plan / apply 两步（先看清单，确认才动盘），
 * 任一条被拦就整批不搬。
 *
 * 「删除空目录并完成合并」要求**真的空**：还有其他文件时后端会拒（它不是只删漫画，
 * 是递归删整个目录）。所以按钮的可用性同时看漫画数与其他文件数。
 *
 * 立场仍是「文件系统是权威，库是镜像」：移动先动磁盘、库跟着走；
 * 合并完成的判定（目录是否清空）由后端现扫裁决，前端只画不判。
 */
const MangaConflictPage = (() => {

    let conflicts = [];                       // NameConflict 列表
    let current = null;                       // 正在比对的 conflict
    let leftMangas = [], rightMangas = [];    // 两侧现扫漫画
    let leftSelected = new Set(), rightSelected = new Set();
    let leftOthers = [], rightOthers = [];    // 两侧根目录下的其他文件（其他文件自己的勾选集）
    let leftOthersSel = new Set(), rightOthersSel = new Set();
    let leftQuery = '', rightQuery = '';      // 两侧搜索框的筛选关键字
    let leftMatched = new Set(), rightMatched = new Set();  // 全量相似判定，筛选不动它

    async function render(host) {
        host.innerHTML = UI.spinner();
        try {
            conflicts = await Api.get('/api/manga/archive/conflicts');
        } catch (e) {
            host.innerHTML = '<div class="hint hint-err">' + UI.esc(e.message) + '</div>';
            return;
        }
        current = null;
        leftSelected.clear();
        rightSelected.clear();
        leftQuery = '';
        rightQuery = '';
        renderList(host);
    }

    function renderList(host) {
        if (!conflicts.length) {
            host.innerHTML = '<div class="card">' + UI.empty(
                '没有重名冲突。同一个社团/作者名落在多个归档目录上时才会出现在这里。') + '</div>';
            return;
        }
        let html = '<div class="hint">同一个名字落在多个归档目录上，归档时系统会按归档根优先级取其一。'
            + '点「比对」进入左右并排，把漫画并到一处，删掉空目录完成合并。</div>'
            + '<div class="card"><table><thead><tr><th style="width:20%">名字</th>'
            + '<th>落在这些目录上</th><th style="width:10%"></th></tr></thead><tbody>';
        conflicts.forEach((c, i) => {
            html += '<tr><td class="mono">' + UI.esc(c.name) + '</td>'
                + '<td class="small mono">' + c.units.map(u => UI.esc(u.folderPath)).join('<br>')
                + '</td><td><button class="cf-open" data-i="' + i + '">比对</button></td></tr>';
        });
        html += '</tbody></table></div>';
        host.innerHTML = html;
        host.querySelectorAll('.cf-open').forEach(btn => btn.onclick = () => {
            current = conflicts[Number(btn.dataset.i)];
            leftSelected.clear();
            rightSelected.clear();
            leftOthersSel.clear();
            rightOthersSel.clear();
            renderCompare(host);
        });
    }

    function renderCompare(host) {
        const units = current.units;
        const left = units[0];
        const right = units[1];
        const extra = units.slice(2);
        leftQuery = '';
        rightQuery = '';

        host.innerHTML = '<div class="card"><div class="row" style="justify-content:space-between">'
            + '<h2 style="margin:0">合并冲突：' + UI.esc(current.name) + '</h2>'
            + '<button id="cf-back">返回列表</button></div>'
            + (extra.length ? '<p class="hint">这个名字还落在另外 ' + extra.length
                + ' 个目录上，这里先比前两个：<span class="mono small">'
                + extra.map(u => UI.esc(u.folderPath)).join('、') + '</span>'
                + '。合并完这对回列表再处理其余的。</p>' : '')
            + '<p class="muted small">评分「继承」的漫画移到另一侧会随目标目录改分，'
            + '「单独」的保持不动。标题相同且展会或杂志相同视为相似，排到最前、右上角带标记。</p></div>'
            + '<div class="conflict-row">'
            + renderCol('cc-left', left)
            + '<div class="conflict-ops">'
            + '<button id="cf-move-left" title="把右侧勾选的漫画移到左侧目录">←</button>'
            + '<button id="cf-delete-selected" class="btn-danger" title="删除两侧勾选的漫画">删除</button>'
            + '<button id="cf-move-right" title="把左侧勾选的漫画移到右侧目录">→</button>'
            + '</div>'
            + renderCol('cc-right', right)
            + '</div>';

        host.querySelector('#cf-back').onclick = () => render(host);
        host.querySelector('#cf-move-right').onclick = (ev) =>
            moveSelected(host, right.id, leftSelected, ev.target);
        host.querySelector('#cf-move-left').onclick = (ev) =>
            moveSelected(host, left.id, rightSelected, ev.target);
        host.querySelector('#cf-delete-selected').onclick = (ev) =>
            deleteSelected(host, ev.target);
        host.querySelectorAll('.cf-search').forEach(input => input.oninput = () => {
            if (input.dataset.side === 'left') {
                leftQuery = input.value.trim().toLowerCase();
            } else {
                rightQuery = input.value.trim().toLowerCase();
            }
            rerenderSide(host, input.dataset.side);
        });

        loadBoth(host);
    }

    function renderCol(id, unit) {
        return '<div class="conflict-col" id="' + id + '">'
            + '<div class="conflict-col-head">'
            + '<span class="tag tag-ok">' + (unit.score != null ? unit.score + ' 分' : '—')
            + '</span> <span class="mono">' + UI.esc(unit.folderName) + '</span>'
            + '<div class="small muted mono">' + UI.esc(unit.rootPath) + '</div>'
            + '</div>'
            + '<input type="text" class="cf-search" data-side="' + (id === 'cc-left' ? 'left' : 'right')
            + '" placeholder="搜索文件名筛选">'
            + '<div class="conflict-col-body"></div>'
            // 其他文件在漫画下面：它是「剩下没地方去的东西」，比漫画次要
            + '<div class="conflict-others"></div>'
            + '<div class="conflict-col-empty"></div>'
            + '</div>';
    }

    async function loadBoth(host) {
        const left = current.units[0];
        const right = current.units[1];
        const leftBody = host.querySelector('#cc-left .conflict-col-body');
        const rightBody = host.querySelector('#cc-right .conflict-col-body');
        leftBody.innerHTML = UI.spinner('加载漫画…');
        rightBody.innerHTML = UI.spinner('加载漫画…');
        try {
            [leftMangas, rightMangas, leftOthers, rightOthers] = await Promise.all([
                Api.get('/api/manga/archive/units/' + left.id + '/mangas'),
                Api.get('/api/manga/archive/units/' + right.id + '/mangas'),
                Api.get('/api/manga/archive/units/' + left.id + '/others'),
                Api.get('/api/manga/archive/units/' + right.id + '/others')
            ]);
        } catch (e) {
            leftBody.innerHTML = '<div class="hint hint-err">' + UI.esc(e.message) + '</div>';
            rightBody.innerHTML = '';
            return;
        }
        // 列出来的东西变了（搬走了 / 被别的窗口删了）→ 勾选集里那些名字已经不存在，
        // 留着会让按钮显示「移 3 项」而实际只有 1 项可搬
        pruneOthers(leftOthers, leftOthersSel);
        pruneOthers(rightOthers, rightOthersSel);
        const sets = matchSets(leftMangas, rightMangas);
        leftMatched = sets.left;
        rightMatched = sets.right;
        rerenderSide(host, 'left');
        rerenderSide(host, 'right');
        renderOthers(host, 'left');
        renderOthers(host, 'right');
        renderEmpty(host, '#cc-left .conflict-col-empty', left, leftMangas, leftOthers);
        renderEmpty(host, '#cc-right .conflict-col-empty', right, rightMangas, rightOthers);
    }

    /** 把勾选集里已经不在清单里的名字摘掉（幂等） */
    function pruneOthers(list, selected) {
        const names = new Set(list.map(o => o.name));
        for (const name of [...selected]) {
            if (!names.has(name)) {
                selected.delete(name);
            }
        }
    }

    // ==================== 其他文件（列出 + 批量移动） ====================

    /**
     * 一侧的「其他文件」块：勾选框 + 一个「移到对侧」的按钮。
     *
     * <p>搬动本身走 plan / apply 两步（见 {@link #moveOthers}）：这里只画清单与按钮，
     * 可不可搬由后端算好的 {@code blockedReason} 决定 —— 打上拦截的那些连勾选框都禁用，
     * 免得勾了才发现整批被拦（「前端只画不判」）。
     */
    function renderOthers(host, side) {
        const box = host.querySelector(side === 'left'
            ? '#cc-left .conflict-others' : '#cc-right .conflict-others');
        if (!box) {
            return;
        }
        const list = side === 'left' ? leftOthers : rightOthers;
        const selected = side === 'left' ? leftOthersSel : rightOthersSel;
        const movable = list.filter(o => !o.blockedReason).length;
        let html = '<div class="conflict-others-head">'
            + '<span class="muted small">根目录其他文件 · ' + list.length + ' 项</span>'
            + '<button type="button" class="btn-plain cf-others-move"'
            + (selected.size ? '' : ' disabled') + '>'
            + (side === 'left' ? '移到右侧 →' : '← 移到左侧')
            + (selected.size ? '（' + selected.size + '）' : '') + '</button></div>';
        if (!list.length) {
            // 「没有」和「没扫到」长得一样：目录不在磁盘上时后端也返回空，所以说明一句
            html += '<div class="muted small">没扫到别的文件（目录不在磁盘上时也是空的）。</div>';
        } else if (!movable) {
            html += '<div class="muted small">这些现在都搬不了，原因见每条的说明。</div>';
        }
        html += list.map(o => {
            const blocked = !!o.blockedReason;
            const title = o.path + (blocked ? '\n不可搬：' + o.blockedReason : '');
            return '<label class="cf-other' + (blocked ? ' cf-other-blocked' : '') + '"'
                + ' title="' + UI.esc(title) + '">'
                + '<input type="checkbox" class="cf-other-box" value="' + UI.esc(o.name) + '"'
                + (blocked ? ' disabled' : '') + (selected.has(o.name) ? ' checked' : '') + '>'
                + (o.directory ? '<span class="tag">目录</span>' : '')
                + '<span class="mono small">' + UI.esc(o.name) + '</span>'
                + (blocked ? '<span class="tag tag-err">不可搬</span>'
                    : (o.directory ? '' : '<span class="muted small">' + UI.esc(sizeText(o.size)) + '</span>'))
                + '</label>';
        }).join('');
        box.innerHTML = html;
        box.querySelectorAll('.cf-other-box').forEach(cb => cb.onchange = () => {
            if (cb.checked) {
                selected.add(cb.value);
            } else {
                selected.delete(cb.value);
            }
            renderOthers(host, side);
        });
        const btn = box.querySelector('.cf-others-move');
        if (!btn.disabled) {
            btn.onclick = () => moveOthers(host, side);
        }
    }

    /** 字节数 → 人看的大小。这一列多半是图 / 压缩包，全用 B 会是六位数 */
    function sizeText(bytes) {
        if (bytes < 1024) {
            return bytes + ' B';
        }
        if (bytes < 1024 * 1024) {
            return (bytes / 1024).toFixed(1) + ' KB';
        }
        return (bytes / 1024 / 1024).toFixed(1) + ' MB';
    }

    /**
     * 把一侧勾选的其他文件搬到另一侧：先预演、看清单、确认再搬。
     *
     * <p>预演与执行都是同一个请求体（{@code fromUnitId / toUnitId / names}），所以「看到的那份
     * 清单」与「真要搬的那批」是同一口径 —— 预演完之后目标位置被别人占了，执行会重算一遍
     * 拦截并整批拒掉，不会出现「预演说能搬、真搬时覆盖了别人」。
     */
    async function moveOthers(host, side) {
        const fromUnit = current.units[side === 'left' ? 0 : 1];
        const toUnit = current.units[side === 'left' ? 1 : 0];
        const selected = side === 'left' ? leftOthersSel : rightOthersSel;
        const body = {fromUnitId: fromUnit.id, toUnitId: toUnit.id, names: [...selected]};
        const m = UI.modal('<div class="modal">'
            + '<div class="modal-head"><span>移动其他文件</span>'
            + '<button class="modal-close" type="button">×</button></div>'
            + '<div class="modal-body">' + UI.spinner('预演中…') + '</div>'
            + '<div class="modal-foot"></div></div>');
        m.box.querySelector('.modal-close').onclick = () => m.close();
        const bodyEl = m.box.querySelector('.modal-body');
        const footEl = m.box.querySelector('.modal-foot');
        let plan;
        try {
            plan = await Api.post('/api/manga/archive/others/move-plan', body);
        } catch (e) {
            bodyEl.innerHTML = '<div class="hint hint-err">' + UI.esc(e.message) + '</div>';
            footEl.innerHTML = '<button type="button" class="btn-plain" id="cf-others-close">关闭</button>';
            footEl.querySelector('#cf-others-close').onclick = () => m.close();
            return;
        }
        let html = '<p class="mono small">' + UI.esc(fromUnit.folderName)
            + ' <span class="muted">→</span> ' + UI.esc(toUnit.folderName) + '</p>';
        if (plan.blockedReason) {
            html += '<div class="hint hint-err">' + UI.esc(plan.blockedReason) + '</div>';
        }
        html += '<table class="small"><thead><tr><th style="width:38%">文件</th><th>搬到</th>'
            + '</tr></thead><tbody>'
            + plan.moves.map(mv => '<tr><td class="mono">' + UI.esc(mv.name) + '</td><td class="mono">'
                + (mv.blockedReason ? '<span class="tag tag-err">' + UI.esc(mv.blockedReason) + '</span>'
                    : UI.esc(mv.toPath)) + '</td></tr>').join('')
            + '</tbody></table>';
        const blockedMoves = plan.moves.filter(mv => mv.blockedReason);
        if (blockedMoves.length) {
            html += '<div class="hint hint-err">有 ' + blockedMoves.length
                + ' 条动不了，整批不搬 —— 只搬一部分的话你会以为都搬好了。</div>';
        }
        bodyEl.innerHTML = html;

        footEl.innerHTML = '<button type="button" class="btn-plain" id="cf-others-close">关闭</button>';
        footEl.querySelector('#cf-others-close').onclick = () => m.close();
        const runable = !plan.blockedReason && !blockedMoves.length && plan.moves.length;
        if (runable) {
            const runBtn = document.createElement('button');
            runBtn.type = 'button';
            runBtn.className = 'btn-primary';
            runBtn.textContent = '确认移动 ' + plan.moves.length + ' 项';
            footEl.insertBefore(runBtn, footEl.firstChild);
            runBtn.onclick = () => UI.withBusy(runBtn, '移动中…', async () => {
                let moved;
                try {
                    moved = await Api.post('/api/manga/archive/others/move', body);
                } catch (e) {
                    UI.err(e.message);
                    await loadBoth(host); // 中途失败可能已经搬了几个，重拉一次让页面说真话
                    return;
                }
                UI.ok('已移动 ' + moved + ' 项');
                (side === 'left' ? leftOthersSel : rightOthersSel).clear();
                m.close();
                await loadBoth(host);
            });
        }
    }

    /** 按当前搜索关键字重画一侧的漫画列表。matched 用全量判定，筛选只影响显示 */
    function rerenderSide(host, side) {
        const query = side === 'left' ? leftQuery : rightQuery;
        const mangas = side === 'left' ? leftMangas : rightMangas;
        const matched = side === 'left' ? leftMatched : rightMatched;
        const selected = side === 'left' ? leftSelected : rightSelected;
        const body = host.querySelector(side === 'left'
            ? '#cc-left .conflict-col-body' : '#cc-right .conflict-col-body');
        const filtered = query
            ? mangas.filter(m => (m.folderName || '').toLowerCase().includes(query))
            : mangas;
        if (query && !filtered.length) {
            body.innerHTML = '<div class="empty">没有匹配「' + UI.esc(query) + '」的漫画。</div>';
            return;
        }
        renderMangaList(host, body, filtered, matched, selected, side);
    }

    /**
     * 疑似同一作品：标题相同，且展会或杂志（任一非空）相同，纯前端分组 + 标记，
     * 只辅助肉眼比对，不改后端判定。
     */
    function matchSets(leftMangas, rightMangas) {
        const titleKey = (m) => (m.title || m.folderName || '').trim().toLowerCase();
        const exhibitKey = (m) => (m.exhibit || '').trim().toLowerCase();
        const magazineKey = (m) => (m.magazine || '').trim().toLowerCase();
        const similar = (a, b) => {
            const ta = titleKey(a), tb = titleKey(b);
            if (!ta || !tb || ta !== tb) {
                return false;
            }
            const ea = exhibitKey(a), eb = exhibitKey(b);
            const ma = magazineKey(a), mb = magazineKey(b);
            return (ea && ea === eb) || (ma && ma === mb);
        };
        const left = new Set();
        const right = new Set();
        for (const l of leftMangas) {
            for (const r of rightMangas) {
                if (similar(l, r)) {
                    left.add(l.folderPath);
                    right.add(r.folderPath);
                }
            }
        }
        return {left, right};
    }

    function renderMangaList(host, box, mangas, matched, selected, side) {
        if (!mangas.length) {
            box.innerHTML = '<div class="empty">这个目录下没扫到漫画。</div>';
            return;
        }
        const sortKey = (m) => (m.normalizedName || m.folderName || '');
        const similar = mangas.filter(m => matched.has(m.folderPath));
        const others = mangas.filter(m => !matched.has(m.folderPath))
            .sort((a, b) => sortKey(a).localeCompare(sortKey(b), 'zh-CN'));
        box.innerHTML = groupBlock('相似（对侧有疑似同一作品）', similar, selected)
            + groupBlock('无相似', others, selected);
        box.querySelectorAll('.manga-card').forEach(card => {
            if (matched.has(card.dataset.path)) {
                card.classList.add('conflict-matched');
            }
        });
        MangaCard.bind(box, {
            read: (path) => {
                const manga = mangas.find(m => m.folderPath === path);
                if (manga) {
                    openReader(host, manga, side);
                }
            },
            remove: async (path, btn) => {
                if (!await UI.confirm('删除这本及其目录？\n\n' + path
                    + '\n\n目录会送进 Windows 回收站，删错了可以从那里还原。')) {
                    return;
                }
                await UI.withBusy(btn, '…', async () => {
                    try {
                        await Api.del('/api/manga/archive/mangas?folderPath='
                            + encodeURIComponent(path));
                        UI.ok('已删除');
                        selected.delete(path);
                        await loadBoth(host);
                    } catch (e) {
                        UI.err(e.message);
                    }
                });
            },
            select: (path, checked) => {
                if (checked) {
                    selected.add(path);
                } else {
                    selected.delete(path);
                }
            }
        });
    }

    /** 一组漫画 + 分组标题。空组不渲染 */
    function groupBlock(title, mangas, selected) {
        if (!mangas.length) {
            return '';
        }
        return '<div class="conflict-group-title">' + UI.esc(title) + ' · '
            + mangas.length + ' 本</div>'
            + '<div class="manga-grid">'
            + mangas.map(m => MangaCard.render(m, {
                acts: ['read', 'delete'],
                select: {can: () => true, checked: (x) => selected.has(x.folderPath)}
            })).join('') + '</div>';
    }

    /**
     * 一侧的「能不能删这个目录」提示。
     *
     * <p><b>「空」要真为空</b>：后端删的是整个目录（递归），底下还有别的文件时会拒 ——
     * 所以这里也把其他文件数算进去，否则会出现「按钮亮着、点了报错」。
     * 前端只画不判的红线在这里体现为：判据只有「两边列表是不是空的」，任何「什么算空」
     * 的细则都在后端。
     */
    function renderEmpty(host, selector, unit, mangas, others) {
        const box = host.querySelector(selector);
        const left = [];
        if (mangas.length) {
            left.push(mangas.length + ' 本漫画');
        }
        if (others.length) {
            left.push(others.length + ' 个其他文件');
        }
        if (!left.length) {
            box.innerHTML = '<button class="btn-danger cf-delete-empty">删除空目录并完成合并</button>';
            box.querySelector('.cf-delete-empty').onclick = async (ev) => {
                if (!await UI.confirm('确认合并完成，删除这个空目录？\n\n' + unit.folderName
                    + '\n\n磁盘目录会送进 Windows 回收站，可以从那里还原；'
                    + '库里的行、别名与标签关联是直接删掉的，那部分拿不回来。',
                    {title: '删除空目录', okText: '确认删除'})) {
                    return;
                }
                await UI.withBusy(ev.target, '删除中…', async () => {
                    try {
                        await Api.post('/api/manga/archive/units/' + unit.id + '/delete-empty');
                        UI.ok('已删除空目录');
                        await render(host);
                    } catch (e) {
                        UI.err(e.message);
                    }
                });
            };
        } else {
            box.innerHTML = '<p class="muted small">还有 ' + left.join('、') + '，'
                + (mangas.length ? '移走后' : '搬走或删掉后') + '可删空这个目录。</p>';
        }
    }

    /** 逐本移动，一本失败就中断（已移动的保留），重拉两侧让页面反映现状 */
    async function moveSelected(host, toUnitId, selected, btn) {
        const paths = [...selected];
        if (!paths.length) {
            UI.err('先勾选要移动的漫画');
            return;
        }
        await UI.withBusy(btn, '移动中…', async () => {
            let moved = 0;
            for (const p of paths) {
                try {
                    await Api.post('/api/manga/archive/mangas/move',
                        {folderPath: p, toUnitId: toUnitId});
                    selected.delete(p);
                    moved++;
                } catch (e) {
                    UI.err('移动失败：' + p.split('\\').pop() + ' —— ' + e.message);
                    await loadBoth(host);
                    return;
                }
            }
            UI.ok('已移动 ' + moved + ' 本');
            await loadBoth(host);
        });
    }

    /** 批量删除两侧勾选的漫画，一本失败就中断，重拉两侧让页面反映现状 */
    async function deleteSelected(host, btn) {
        const paths = [...leftSelected, ...rightSelected];
        if (!paths.length) {
            UI.err('先勾选要删除的漫画');
            return;
        }
        if (!await UI.confirm('删除勾选的 ' + paths.length + ' 本及其目录？'
            + '\n\n目录会送进 Windows 回收站，删错了可以从那里还原。')) {
            return;
        }
        await UI.withBusy(btn, '删除中…', async () => {
            let removed = 0;
            for (const p of paths) {
                try {
                    await Api.del('/api/manga/archive/mangas?folderPath='
                        + encodeURIComponent(p));
                    leftSelected.delete(p);
                    rightSelected.delete(p);
                    removed++;
                } catch (e) {
                    UI.err('删除失败：' + p.split('\\').pop() + ' —— ' + e.message);
                    await loadBoth(host);
                    return;
                }
            }
            UI.ok('已删除 ' + removed + ' 本');
            await loadBoth(host);
        });
    }


    /** 已归档漫画的「修改」弹窗：改名 + 评分（落库不动目录）+ 独立标签，没有归档动作。
     *  标签预填两步：先独立标签（mangaId），没有则父级标签（side 对应的归档目录）。 */
    async function openConflictEditModal(host, manga, side, onDone) {
        let withTags = manga;
        if (manga.mangaId != null) {
            try {
                const tags = await Api.get('/api/manga/tags/manga/' + manga.mangaId) || [];
                withTags = Object.assign({}, withTags, {ownTags: tags});
            } catch (e) {
                // 读不到独立标签就降级
            }
        }
        if ((!withTags.ownTags || !withTags.ownTags.length) && current) {
            const unit = current.units[side === 'left' ? 0 : 1];
            if (unit) {
                try {
                    const parentTags = await Api.get('/api/manga/archive/units/' + unit.id + '/tags') || [];
                    withTags = Object.assign({}, withTags, {parentTags: parentTags});
                } catch (e) {
                    // 读不到父级标签就降级，弹窗标签栏留空
                }
            }
        }
        const opts = MangaReader.archivedOpts();
        opts.reload = () => loadBoth(host);
        opts.onDone = onDone;
        MangaReader.openArchiveModal(host, withTags, opts);
    }

    /** 阅读页：改名/评分/删除照常，列表与重扫都指回当前侧。统一走「修改」弹窗 */
    async function openReader(host, manga, side) {
        // 阅读页底栏读后端注入的 manga.tags 回显；编辑弹窗自己从 ownTags/父级标签取
        const opts = MangaReader.archivedOpts();
        opts.list = () => (side === 'left' ? leftMangas : rightMangas);
        opts.rescan = () => loadBoth(host);
        opts.edit = (m, onDone) => openConflictEditModal(host, m, side, onDone);
        MangaReader.openReader(host, manga, opts);
    }

    return {render};
})();
