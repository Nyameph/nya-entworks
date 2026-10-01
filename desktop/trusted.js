/**
 * 「这个 IPC 发送方可信吗」的纯判定。
 *
 * 独立成文件（而不是留在 main.js 里）的原因：main.js 顶部就 require('electron')，
 * node 直接 require 它会炸；而 trusted() 是<b>安全闸门</b> —— 防别人的页面冒充本地服务
 * 拿到原生能力，属于「写错了没人发现、后果很重」的那类逻辑，值得有一个不启动 Electron
 * 就能跑的核对脚本（tools/shell-trusted-check.js）。main.js 与那个脚本 require 的是
 * <b>同一份实现</b>，不存在两份代码各改各的漂移。
 *
 * 判据两条，缺一不可：
 *   1. 发送方就是主窗口的 webContents（preload 绑在窗口上，别的窗口没有这份 preload）；
 *   2. 发送方帧的 URL 源等于本次后端的源（`http://127.0.0.1:<端口>`）——
 *      窗口被导到别的源（钓鱼页、别的本地服务抢占端口）时拒绝。
 *
 * 约束：<code>backendOrigin</code> 必须<b>不带尾斜杠</b>（main.js 里是
 * `'http://127.0.0.1:' + port` 拼出来的，天然满足）。比较用
 * <code>url.startsWith(backendOrigin + '/')</code>： origin 尾部带斜杠会让
 * 一切正常页面都对不上（恒拒），不带则连 user-info 混淆
 * （`http://127.0.0.1:50721@evil.com/`）这种 URL 也骗不过 —— 它的开头是
 * `…:50721@` 而不是 `…:50721/`。
 */
'use strict';

/**
 * @param {boolean} senderIsMainWindow 发送方是不是主窗口的 webContents
 * @param {string}  frameUrl           发送方帧的完整 URL
 * @param {?string} backendOrigin      本次后端的源（不带尾斜杠）；后端没起时为 null
 * @returns {boolean}
 */
function isTrustedSender(senderIsMainWindow, frameUrl, backendOrigin) {
    if (!senderIsMainWindow) return false;
    if (typeof backendOrigin !== 'string' || !backendOrigin) return false;
    return typeof frameUrl === 'string' && frameUrl.startsWith(backendOrigin + '/');
}

module.exports = {isTrustedSender};
