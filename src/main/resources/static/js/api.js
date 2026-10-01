/**
 * 后端调用与几个共用的小工具。
 *
 * 所有接口返回 ApiResult{success, message, data}：失败时后端已经把原因写进 message
 * （多半是环境问题），所以这里统一抛出 message，由调用方决定是 toast 还是画在页面上。
 */
const Api = (() => {

    async function request(url, options) {
        // 拼 URL 最容易犯的错：前一段少了 `?`，后面的 `&k=v` 全被当成路径，后端回一个空体的
        // 404，到这儿只剩「响应不是 JSON（HTTP 404）」这句没头没脑的话（2026-09-13 歌曲页踩过）。
        // 参数只可能出现在 `?` 之后，所以这样一条就是拼错了 —— 在这里说清楚，别让人去猜。
        if (url.indexOf('&') >= 0 && url.indexOf('?') < 0) {
            throw new Error('接口地址拼错了（有 & 却没有 ?，参数被当成了路径）：' + url);
        }
        let res;
        try {
            res = await fetch(url, options);
        } catch (e) {
            // 连不上后端与后端报错要分清楚：前者多半是应用没起
            throw new Error('请求发不出去，确认应用还在运行：' + e.message);
        }
        let body;
        try {
            body = await res.json();
        } catch {
            throw new Error('响应不是 JSON（HTTP ' + res.status + '）');
        }
        if (!body.success) {
            throw new Error(body.message || '操作失败');
        }
        return body.data;
    }

    const get = (url) => request(url, {method: 'GET'});

    const post = (url, data) => request(url, {
        method: 'POST',
        headers: {'Content-Type': 'application/json'},
        body: data === undefined ? undefined : JSON.stringify(data)
    });

    const put = (url, data) => request(url, {
        method: 'PUT',
        headers: {'Content-Type': 'application/json'},
        body: data === undefined ? undefined : JSON.stringify(data)
    });

    /** data 传了就带 JSON 请求体（韵脚词典的「按 ids / 按来源删除」要用，删除语句也允许带体） */
    const del = (url, data) => request(url, {
        method: 'DELETE',
        headers: data === undefined ? undefined : {'Content-Type': 'application/json'},
        body: data === undefined ? undefined : JSON.stringify(data)
    });

    return {get, post, put, del};
})();

const UI = (() => {

    /** 转义后插入，文件夹名里出现 < > & 不至于把页面搞坏 */
    function esc(text) {
        if (text === null || text === undefined) {
            return '';
        }
        return String(text)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;');
    }

    let toastTimer = null;
    /** 当前是不是一条「等着人点掉」的错误提示。错误不自动消失，成功提示也不许顶掉它 */
    let toastPinned = false;

    /** 点掉提示（错误提示唯一的手动关闭出口）。只有确认看完了才收起来，别让它自己溜走 */
    function hideToast() {
        const el = document.getElementById('toast');
        clearTimeout(toastTimer);
        toastTimer = null;
        toastPinned = false;
        el.hidden = true;
    }

    /**
     * 提示条。**成功提示一扫而过（2.5 秒），错误提示不自动消失、要人点掉** ——
     * 出错的原文（后端 message 里通常带着路径与原因）往往几十个字，6 秒读不完，
     * 而它是这一刻唯一的线索，自己溜走就等于没提示。
     * 所以错误提示：不设定时器、内容右侧带一个 ×，点提示条任意处都能关掉；
     * 正压着一条错误时，后来的成功提示**不顶掉它**（成功只是「刚才那下成了」，等不得）。
     */
    function toast(message, isError) {
        if (!isError && toastPinned) {
            return;
        }
        const el = document.getElementById('toast');
        // 拼 DOM 而不是 innerHTML：message 来自后端（可能带 < > 与路径），别当 HTML 解释
        el.textContent = '';
        const text = document.createElement('span');
        text.textContent = message;
        el.appendChild(text);
        if (isError) {
            const close = document.createElement('button');
            close.type = 'button';
            close.className = 'toast-close';
            close.title = '关闭';
            close.textContent = '×';
            el.appendChild(close);
        }
        el.className = 'toast' + (isError ? ' toast-err' : '');
        el.hidden = false;
        el.onclick = hideToast;   // 点条子本身或那个 × 都算点掉（成功提示顺手点了也无妨）
        el.title = isError ? '点一下关掉' : '';
        clearTimeout(toastTimer);
        toastTimer = null;
        toastPinned = isError;
        if (!isError) {
            toastTimer = setTimeout(() => el.hidden = true, 2500);
        }
    }

    const ok = (msg) => toast(msg, false);
    const err = (msg) => toast(msg, true);

    function spinner(text) {
        return '<div class="spinner">' + esc(text || '加载中…') + '</div>';
    }

    function empty(text) {
        return '<div class="empty">' + esc(text) + '</div>';
    }

    /**
     * 画分页条。onGo(p) 由调用方刷新当前页数据。
     *
     * pageSize 由调用方给，不写死在这里 —— 各页的每页条数本来就不同（词典 / 标签 50、
     * 未归档 20、韵脚查询 30……），写死会把「本页该显示几条」变成一处猜不到来源的常量。
     */
    function pagerBar(container, total, current, pageSize, onGo) {
        const pages = Math.max(1, Math.ceil(total / pageSize));
        const btn = (pg, label) => {
            if (pg < 1 || pg > pages) {
                return '<button disabled>' + label + '</button>';
            }
            return '<button data-pg="' + pg + '" class="' + (pg === current ? 'on' : '') + '">'
                + label + '</button>';
        };
        let html = '<span class="muted small">共 ' + total + ' 条</span>';
        html += btn(1, '«') + btn(current - 1, '‹');
        const from = Math.max(1, current - 2), to = Math.min(pages, current + 2);
        if (from > 1) {
            html += '<span class="pager-gap">…</span>';
        }
        for (let p = from; p <= to; p++) {
            html += btn(p, String(p));
        }
        if (to < pages) {
            html += '<span class="pager-gap">…</span>';
        }
        html += btn(current + 1, '›') + btn(pages, '»');
        container.innerHTML = html;
        container.querySelectorAll('button[data-pg]').forEach(b => b.onclick = () => {
            const p = Number(b.dataset.pg);
            if (p !== current && p >= 1 && p <= pages) {
                onGo(p);
            }
        });
    }

    /** 排序箭头：当前排序列才显示，升序 ↑ / 降序 ↓。手写表头（如倍速列）也要用它 */
    function sortArrow(key, sortKey, sortDir) {
        return sortKey === key ? (sortDir === 1 ? ' ↑' : ' ↓') : '';
    }

    /**
     * 可排序表头单元格。排序状态（sortKey / sortDir）由调用方传入 ——
     * 它属于各页自己的模块状态，不是这个构件能持有的东西。
     */
    function th(key, label, width, sortKey, sortDir) {
        return '<th style="width:' + width + '" class="sortable" data-sort="' + key + '">'
            + label + sortArrow(key, sortKey, sortDir) + '</th>';
    }

    /** 把 async 动作包成「点了就禁用、完了恢复」，避免连点重复提交 */
    async function withBusy(btn, label, fn) {
        const old = btn.textContent;
        btn.disabled = true;
        btn.textContent = label;
        try {
            return await fn();
        } finally {
            btn.disabled = false;
            btn.textContent = old;
        }
    }

    /**
     * 通用模态容器：遮罩 + 居中面板。返回 `{box, close}`，点遮罩空白处关闭。
     *
     * @param onClose 可省。**点遮罩空白处关掉时**回调一次。
     *   给 `Promise` 型的对话框用：光把面板拿掉而 promise 不 resolve，
     *   调用方会永远停在 `await` 上（症状是「点了空白处，然后什么都没发生」）。
     *   `close()` 是程序化关闭，**不**触发它 —— 调用方自己知道自己在关什么，别再回调一遍。
     */
    function modal(html, onClose) {
        const overlay = document.createElement('div');
        overlay.className = 'modal-overlay';
        overlay.innerHTML = html;
        document.body.appendChild(overlay);
        const close = () => {
            if (overlay.parentNode) {
                overlay.remove();
            }
        };
        overlay.addEventListener('click', (e) => {
            if (e.target === overlay) {
                close();
                if (onClose) {
                    onClose();
                }
            }
        });
        return {box: overlay, close};
    }

    /**
     * 二次确认对话框，替代原生 confirm。返回 Promise<boolean>。
     * 删除这类不可逆操作统一用它：默认红色确认键，Esc / 点遮罩 / 取消都算「否」。
     */
    function confirm(message, opts = {}) {
        const {title = '确认', okText = '确定', danger = true} = opts;
        return new Promise((resolve) => {
            const m = modal(`
                <div class="modal">
                    <div class="modal-head"><span>${esc(title)}</span>
                        <button class="modal-close" type="button">×</button></div>
                    <div class="modal-body">${esc(message)}</div>
                    <div class="modal-foot">
                        <button type="button" class="btn-plain" data-act="cancel">取消</button>
                        <button type="button" class="${danger ? 'btn-danger' : 'btn-primary'}"
                            data-act="ok">${esc(okText)}</button>
                    </div>
                </div>`);
            const finish = (val) => {
                cleanup();
                m.close();
                resolve(val);
            };
            const cleanup = () => document.removeEventListener('keydown', onKey);
            const onKey = (e) => {
                if (e.key === 'Escape') {
                    finish(false);
                }
            };
            document.addEventListener('keydown', onKey);
            m.box.querySelector('.modal-close').onclick = () => finish(false);
            m.box.querySelector('[data-act="cancel"]').onclick = () => finish(false);
            m.box.querySelector('[data-act="ok"]').onclick = () => finish(true);
        });
    }

    return {esc, toast, ok, err, spinner, empty, pagerBar, sortArrow, th,
        withBusy, modal, confirm};
})();

/**
 * 通用小工具。都是纯函数、不碰状态，原先散在五六个页面里各抄一份。
 */
const Util = (() => {

    /** 查询串拼接：值里可能出现中文与特殊字符，编码后才进 hash */
    function qs(key, value) {
        return key + '=' + encodeURIComponent(value);
    }

    /** 排序键的数值化。null / undefined 一律沉到最后（-Infinity），不参与比较 */
    function num(v) {
        return v == null ? -Infinity : Number(v);
    }

    /** 名字是不是 cbz（大小写不敏感）。空名字不算 */
    function isCbzName(name) {
        return !!name && name.toLowerCase().endsWith('.cbz');
    }

    /**
     * 媒体流地址。这是全项目唯一不走 Api（不包 ApiResult）的端点 —— <video src> 要的是
     * 字节流本身。它必须支持 Range 请求，否则拖不动进度条，而且 moov atom 在文件尾部的
     * mp4 连播放都开始不了。
     */
    function mediaUrl(path) {
        return '/api/media/stream?path=' + encodeURIComponent(path);
    }

    /** 行 → variant 列表。歌曲与喊麦的行都是归并形态，喊麦每行恒一个 */
    function variantsOf(row) {
        return row.variants || [];
    }

    /**
     * 取一份纯文本（使用说明页的 md）。**不能走 Api** —— 它不是 ApiResult，
     * 是一整个文件本身（与 mediaUrl 同一类例外，只是那个回字节流、这个回字符串）。
     * 失败一律抛错，由调用方决定是整页报错还是只坏一节（说明页选后者）。
     */
    async function text(url) {
        let res;
        try {
            res = await fetch(url);
        } catch (e) {
            throw new Error('读不到 ' + url + '：' + e.message);
        }
        // 静态资源缺文件时后端回的是 404 页面（HTML），不是空串 —— 不判状态码就会
        // 把整张错误页当正文渲染出来
        if (!res.ok) {
            throw new Error('读不到 ' + url + '（HTTP ' + res.status + '）');
        }
        return res.text();
    }

    return {qs, num, isCbzName, mediaUrl, variantsOf, text};
})();
