/**
 * 桌面壳与页面之间**唯一**的通道。
 *
 * 只暴露四个能力，都返回 `{ok, message, ...}` 形式的结果对象而不抛异常 ——
 * 跨 contextBridge 抛出去的 Error 会被序列化成一句面目全非的话，
 * 而这个项目的规矩是「失败也给人话看」（同后端的 ApiResult）。
 *
 * 页面侧的用法是**能力探测**：`window.NyaEntworksShell` 存在 = 在壳里，
 * 不存在 = 纯浏览器打开（配置页据此决定画不画「浏览…」与「保存并重启后端」）。
 * 那是探测一个由壳注入的 bridge，不是业务判定，不违反「前端只画不判」。
 *
 * 为什么不上设计稿 §4.2 那套 HTTP + token 的 bridge：能力只有这几个、
 * main.js 的 `trusted()` 已把「别的源冒充」堵住，2026-09-24 裁决维持 preload、不实施 bridge
 * （桌面化收尾实施计划 §0.2 第 2 条）。也**不要再往上加能力**：每加一个，
 * 这条通道就更值得冒充一分 —— 加之前先想清楚它是不是刚需（拖拽取路径就是这么否掉的）。
 */
const {contextBridge, ipcRenderer} = require('electron');

contextBridge.exposeInMainWorld('NyaEntworksShell', {
    /**
     * 弹原生对话框选路径。
     *
     * @param options.kind    'directory' | 'file'
     * @param options.current 当前值，作为对话框的初始位置
     * @returns {Promise<{ok: boolean, path?: string, message?: string}>}
     *          取消也走 ok:false（message 是「已取消」），不当错误
     */
    pickPath: (options) => ipcRenderer.invoke('shell:pick-path', options || {}),

    /**
     * 弹原生对话框**多选**文件（「添加文件」/「新增原曲」弹窗用）。
     *
     * @param options.title      对话框标题（可空）
     * @param options.defaultPath 初始目录（可空，空则由主进程回落到持久化的上次目录）
     * @param options.filters    扩展名过滤（可空），形状 `[{name, extensions}]` ——
     *                           **只透传**（`invoke` 收整包），主进程那边按 Electron 原生格式校验；
     *                           清单来自后端下发的 `extRule`，这里不维护第二份
     * @returns {Promise<{ok: boolean, paths?: string[], message?: string}>}
     *          取消也走 ok:false（message 是「已取消」），不当错误；
     *          {@code paths} 是绝对路径数组
     */
    pickFiles: (options) => ipcRenderer.invoke('shell:pick-files', options || {}),

    /**
     * 重启后端（主进程负责杀进程、重选端口、重新导航）。
     * **不等它完成**：随后的导航会销毁发起方，等回包只会等到一个悬空的 promise。
     *
     * @returns {Promise<{ok: boolean, message?: string}>} 只表示「收到请求了」
     */
    restartBackend: () => ipcRenderer.invoke('shell:restart-backend'),

    /**
     * 在资源管理器中显示某个文件（打开资源管理器并选中它）。
     *
     * 放行口径是**壳侧**定的：只有本次会话里通过 {@link pickPath} / {@link pickFiles}
     * 选出来的路径才放行（页面传别的路径会得到一句人话的拒绝）。所以它只对
     * 「我刚选的那个文件」可用 —— 正是「选完文件立刻确认位置」这个刚需。
     *
     * 页面侧照这个口径**只在「来自 pickFiles 的行」上画按钮**（`songform.js` 的
     * `f.picked`）：粘贴路径进来的、以及修改 / 归档弹窗里磁盘现扫的行都不画 ——
     * 那些一定会被拒，画出来只是排排点不通的按钮。
     *
     * @param {string} path 绝对路径（通常就是 pickFiles 返回过的那一个）
     * @returns {Promise<{ok: boolean, message?: string}>} 拒绝也给人话，不静默
     */
    showItem: (path) => ipcRenderer.invoke('shell:show-item', path),
});
