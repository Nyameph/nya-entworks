/**
 * 标签共用件（歌曲与喊麦的列表页、未归档页共用，文档 8.4 / 8.2）。
 *
 * 标签挂在行的 {@code mergeKey} 上（歌曲那边是归一化合并键，喊麦那边就是主名 ——
 * 字段名沿用是为了共用这一份代码），存的是纯库表（song_tag / shout_tag），
 * 不落目录名 —— 所以这里没有「预演-提交」，直接读改。两页一致。
 *
 * 打标签输入按空白 / 顿号 / 逗号切分，每个 token 是一个标签。归档打分前要先有标签
 * （闸门在后端 SongArchiveService / ShoutArchiveService）；标签编辑区现在内嵌在修改
 * 弹窗里（{@link #editor}），不再单独弹「打标签」窗 —— 标签在修改弹窗里和评分 /
 * 改名一起保存。
 */
const SongTag = (() => {

    /** 标签 chips；没有时返回空串（调用方自己决定要不要补「无」） */
    function chips(tags) {
        tags = tags || [];
        return tags.map(t => '<span class="tag">' + UI.esc(t) + '</span>').join(' ');
    }

    /** 未打标签的警示 chip。只在需要「不打标签不能归档」的列表层用 */
    function warnChip(tags) {
        return (tags && tags.length)
            ? ''
            : '<span class="tag tag-warn" title="不打标签不能归档">未打标签</span>';
    }

    /**
     * 内嵌标签编辑区：渲染 chips + 输入框到 {@code host}，增删原地改 {@code tags} 数组。
     * 供修改弹窗（{@code SongActions.edit}）内嵌，替代原来的独立打标签弹窗 —— 标签
     * 现在和评分 / 改名在同一个弹窗里，保存由修改弹窗统一提交。
     *
     * @param host     容器元素
     * @param tags     标签数组，原地增删；初始值由调用方从行的 {@code tags} 拷贝一份
     * @param onChange 可选，标签增删、输入框残留内容变化时回调，用于刷新「保存」按钮
     * @return {@code {flush, hasPending}}：{@code flush()} 把输入框里打了还没按回车的内容
     *         收进 tags（不触发 onChange，供保存前调用）；{@code hasPending()} 判断输入框
     *         是否还有未提交的残留内容
     */
    function editor(host, tags, onChange) {
        host.innerHTML = '<div class="row" style="flex-wrap:wrap;gap:6px" id="sg-tags"></div>'
            + '<div class="row" style="margin-top:6px">'
            + '<input type="text" id="sg-new" placeholder="标签名，回车添加，可一次粘多个">'
            + '<button id="sg-add" type="button">+ 加标签</button>'
            + '</div>';

        const box = host.querySelector('#sg-tags');
        const input = host.querySelector('#sg-new');

        const draw = () => {
            box.innerHTML = tags.length
                ? tags.map((t, i) => '<span class="tag" data-i="' + i + '">' + UI.esc(t)
                    + ' <a href="#" class="sg-del" title="移除">×</a></span>').join(' ')
                : '<span class="muted small">还没有标签</span>';
            box.querySelectorAll('.sg-del').forEach(a => a.onclick = (ev) => {
                ev.preventDefault();
                tags.splice(Number(a.closest('.tag').dataset.i), 1);
                draw();
                if (onChange) {
                    onChange();
                }
            });
        };

        // 把输入框里打了还没按回车的内容收进 tags。返回是否真的新增了标签。不触发 onChange：
        // 保存前调用它只是补齐没提交的内容，不借它刷新按钮
        const flush = () => {
            const tokens = input.value.trim().split(/[\s、，,]+/).filter(Boolean);
            let changed = false;
            for (const t of tokens) {
                if (!tags.includes(t)) {
                    tags.push(t);
                    changed = true;
                }
            }
            input.value = '';
            if (changed) {
                draw();
            }
            return changed;
        };

        // 输入框里有没有打了还没提交的内容
        const hasPending = () => input.value.trim() !== '';

        const add = () => {
            flush();
            if (onChange) {
                onChange();
            }
        };
        host.querySelector('#sg-add').onclick = add;
        host.querySelector('#sg-new').onkeydown = (e) => {
            if (e.key === 'Enter') {
                e.preventDefault();
                add();
            }
        };
        // 输入框内容变化也通知外部：打了标签还没提交，也应让「保存」可点（保存前会 flush）
        input.oninput = () => {
            if (onChange) {
                onChange();
            }
        };

        draw();
        return {flush, hasPending};
    }

    return {chips, warnChip, editor};
})();
