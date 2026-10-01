/**
 * 呜啊娱乐工坊 桌面壳。壳只做四件事，不承载任何业务（业务全在后端 Spring Boot 里）：
 *   1. 开一个窗口；
 *   2. 启动 / 守护 / 重启 / 停止后端子进程（动态端口）；
 *   3. 单实例；
 *   4. 退出时把后端进程树杀干净。
 *
 * 前端不算「改」也行：窗口加载的就是现有那套页面，`fetch('/api/...')` 与
 * `Util.mediaUrl()` 都是同源相对路径，所以换端口对前端透明。页面唯一多用的东西是
 * `window.NyaEntworksShell`（壳注入的能力：原生选路径（单/多选）、重启后端、
 * 在资源管理器中显示），纯浏览器打开时它不存在、相关按钮自动收起。见 preload.js。
 */
const {app, BrowserWindow, shell, ipcMain, dialog} = require('electron');
const {spawn, spawnSync} = require('child_process');
const fs = require('fs');
const net = require('net');
const path = require('path');
const {isTrustedSender} = require('./trusted');

/** 后端最多等多久（首次启动要连 MySQL，给足） */
const HEALTH_TIMEOUT_MS = 120000;
/** 探测间隔 */
const PROBE_INTERVAL_MS = 500;
/** 本平台的 java 可执行文件名（留着以后跨平台用） */
const JAVA_BIN = process.platform === 'win32' ? 'java.exe' : 'java';

/**
 * 打包态与开发态的岔口。**开发态（`npm start`）下这些分支全不生效，行为与打包前逐字一致。**
 *
 * 打包后进程是从 asar 里跑的：`__dirname` 在 asar 内部、**不可写**，
 * jar / 裁剪过的 JRE / 出厂配置都在 `process.resourcesPath` 下（extraResources，见 package.json）。
 */
const IS_PACKAGED = app.isPackaged;
/** 打包资源根（app.jar / jre / config 都在这儿）；开发态是 undefined */
const RESOURCES = process.resourcesPath;

/**
 * 工作区根：**数据与配置都落这儿**，它同时是后端的 `cwd`（见 spawnSidecar 的命脉注释）。
 *
 * ★ 打包成 portable 之后 = **exe 所在目录**，取自 `PORTABLE_EXECUTABLE_DIR`。
 *   不能用 `path.dirname(process.execPath)`：portable 是自解压到临时目录再运行的，
 *   那里退出即蒸发 —— 用户的数据库会跟着没了，而且全程不报错。
 */
let projectRoot = null;

/**
 * 日志与 PID 文件放哪：跟着工作区走。
 * 打包态 `__dirname` 在 asar 里（写不进去，症状是双击后静默失效），所以退到 userData；
 * 开发态维持原样（`desktop/` 下），不改变任何既有习惯。
 */
function stateDir() {
    if (projectRoot) return projectRoot;
    return IS_PACKAGED ? app.getPath('userData') : __dirname;
}

/** 后端日志文件（每次 spawn 截断重写） */
const logFile = () => path.join(stateDir(), 'sidecar.log');
/** 残留 PID 文件（见 killStaleSidecar） */
const pidFile = () => path.join(stateDir(), '.sidecar.pid');

/**
 * 日志文件在界面上怎么指路。开发态在 `desktop/` 下 —— 打包之后**不在那儿了**，
 * 写死路径会让用户对着一个不存在的文件找原因。
 */
const logHint = () => (IS_PACKAGED ? 'sidecar.log（在本程序所在目录）' : 'desktop/sidecar.log');

/** 启动画面文案 */
const MSG = {
    starting: '正在启动后端…',
    waiting: '后端还在启动，请稍候…（首次较慢）',
};

/** 后端子进程句柄；退出时要杀它，所以放模块级 */
let sidecar = null;
/** 后端日志的文件描述符（stdout/stderr 共用；spawn 的 stdio 只认 fd，不认未打开的 WriteStream） */
let sidecarLog = null;
/** 主窗口 */
let mainWindow = null;
/** 本次后端所在的源（`http://127.0.0.1:<端口>`）。IPC 用它挡住「窗口被导到别的源」的情况 */
let backendOrigin = null;
/** 重启是否正在进行：防连点，也防重启途中被别的调用插一脚 */
let restarting = false;

// ───────────────────────── 单实例 ─────────────────────────

const gotTheLock = app.requestSingleInstanceLock();

if (!gotTheLock) {
    // 已经有实例在跑：直接退出，交给那个实例把自己提到前台
    app.quit();
} else {
    app.on('second-instance', () => {
        if (mainWindow) {
            if (mainWindow.isMinimized()) mainWindow.restore();
            mainWindow.show();
            mainWindow.focus();
        }
    });

    app.whenReady().then(() => {
        // ★ 工作区必须在**动任何文件之前**定下来：PID 文件 / 日志 / data / config
        //   全跟着它走（见 stateDir）。原先这三样写死在 `__dirname`，开发态看不出问题，
        //   打包后那个位置在 asar 内部、**不可写**。
        projectRoot = findProjectRoot();
        if (projectRoot && IS_PACKAGED) {
            initWorkspace(projectRoot);   // data/、config/、出厂配置（只补缺失）
        } else if (!projectRoot) {
            // 开发态下 pom.xml 找不到只影响日志落点，说一声就够，不必拦启动
            console.error('[shell] 找不到项目根（pom.xml），日志与 PID 退到', stateDir());
        }

        killStaleSidecar();
        createWindow();
        startBackendAndNavigate().catch((e) => {
            // 兜底：起点函数自己已经处理了常见失败，这里是最后的网
            console.error('启动流程异常:', e);
            setStatus('启动流程异常，请看终端输出与 ' + logHint());
        });
    });

    // 关掉最后一个窗口就退出（本应用不做托盘常驻）。
    // 注意：Electron 默认在 macOS 上不退出，这里显式改成三平台都退 —— 否则后端会变成孤儿。
    app.on('window-all-closed', () => app.quit());

    // 退出路径有两条，都调一次；killSidecar 幂等
    app.on('before-quit', () => killSidecar());
    app.on('will-quit', () => killSidecar());

    // 让外链走系统浏览器，而不是在应用窗口里打开
    app.on('web-contents-created', (_e, contents) => {
        contents.setWindowOpenHandler(({url}) => {
            shell.openExternal(url);
            return {action: 'deny'};
        });
    });
}

// ───────────────────────── 窗口 ─────────────────────────

function createWindow() {
    mainWindow = new BrowserWindow({
        width: 1400,
        height: 900,
        minWidth: 1000,
        minHeight: 640,
        title: '呜啊娱乐工坊',
        icon: path.join(__dirname, 'icon.png'),   // 窗口与任务栏图标（256×256，来自 static/img/logo.png）
        backgroundColor: '#1f2430',
        autoHideMenuBar: true,   // 藏掉 Electron 默认菜单栏（按 Alt 可唤出，devtools 快捷键仍在）
        webPreferences: {
            contextIsolation: true,
            nodeIntegration: false,   // 页面是本地服务提供的，不需要任何 Node 能力
            preload: path.join(__dirname, 'preload.js'),   // ★ 只给两个能力，见 preload.js
        },
    });

    mainWindow.loadFile(path.join(__dirname, 'splash.html'));
    mainWindow.on('closed', () => {
        mainWindow = null;
    });
}

/** 更新启动画面的文案。页面被导航走后 __setStatus 不存在，靠 && 短路，不会报错 */
function setStatus(text) {
    if (!mainWindow || mainWindow.isDestroyed()) return;
    // JSON.stringify 负责转义，所以中文/引号/反斜杠都安全
    mainWindow.webContents
        .executeJavaScript(`window.__setStatus && window.__setStatus(${JSON.stringify(text)})`)
        .catch(() => {});
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ───────────────────────── 找路径 ─────────────────────────

/**
 * 找项目根（＝工作区根）：
 *   · 打包态 —— 直接用 exe 所在目录（见 projectRoot 的注释），**不再向上找**：
 *     发出去的文件夹里没有 `pom.xml`，找了也是白找，还会把工作区错认成别的地方。
 *   · 开发态 —— 从 exe 所在目录**向上**找 `pom.xml`。开发时 `process.execPath` 是
 *     `desktop/node_modules/electron/dist/electron.exe`，向上四层就是项目根。
 * `NYA_ENTWORKS_ROOT` 两种态都优先（排查用的逃生口）。
 */
function findProjectRoot() {
    const fromEnv = process.env.NYA_ENTWORKS_ROOT;
    if (fromEnv && fs.existsSync(path.join(fromEnv, 'pom.xml'))) return fromEnv;

    if (IS_PACKAGED) {
        const portableDir = process.env.PORTABLE_EXECUTABLE_DIR;
        if (portableDir) return portableDir;
        // 理论上到不了这里（portable 一定会设它）。真到了就退到 exe 旁边，
        // 并在日志里说清楚 —— 数据落错地方是**静默**的，必须留痕
        console.warn('[shell] 没有 PORTABLE_EXECUTABLE_DIR，工作区退到',
            path.dirname(process.execPath));
        return path.dirname(process.execPath);
    }

    const starts = [path.dirname(process.execPath)];
    if (process.resourcesPath) starts.push(process.resourcesPath);

    for (const start of starts) {
        let dir = start;
        for (let i = 0; i < 10; i++) {
            if (fs.existsSync(path.join(dir, 'pom.xml'))) return dir;
            const parent = path.dirname(dir);
            if (parent === dir) break;   // 到盘根了
            dir = parent;
        }
    }
    return null;
}

/**
 * 找 java：打包态第一候选是随包带的**裁剪 JRE**（朋友的机器上不装 Java），
 * 开发态仍是环境变量 → JAVA_HOME → PATH。
 * 打包态也保留后三者：JRE 没随包带上时还能靠本机 JDK 起来，而不是直接不启动。
 */
function findJava() {
    const candidates = [];
    if (IS_PACKAGED && RESOURCES) {
        candidates.push(path.join(RESOURCES, 'jre', 'bin', JAVA_BIN));
    }
    if (process.env.NYA_ENTWORKS_JAVA) candidates.push(process.env.NYA_ENTWORKS_JAVA);
    if (process.env.JAVA_HOME) candidates.push(path.join(process.env.JAVA_HOME, 'bin', JAVA_BIN));
    for (const dir of (process.env.PATH || '').split(path.delimiter)) {
        if (dir) candidates.push(path.join(dir, JAVA_BIN));
    }
    for (const c of candidates) {
        try {
            if (c && fs.statSync(c).isFile()) return c;
        } catch (_) {
            // 不存在就试下一个
        }
    }
    return null;
}

/**
 * 找 jar：
 *   · 打包态 —— 固定就是 `resources/app.jar`（release.py 拷进去时定死的名字），不扫目录。
 *   · 开发态 —— `<root>/target/*.jar` 里取**最新**的一个。
 *     排除 `-sources.jar` / `-javadoc.jar`；spring-boot repackage 留下的 `.jar.original`
 *     不以 `.jar` 结尾，天然被排除。
 */
function findJar(root) {
    if (IS_PACKAGED) {
        const fixed = path.join(RESOURCES, 'app.jar');
        return fs.existsSync(fixed) ? fixed : null;
    }
    let names;
    try {
        names = fs.readdirSync(path.join(root, 'target'));
    } catch (_) {
        return null;
    }
    let best = null;
    for (const name of names) {
        if (!name.endsWith('.jar')) continue;
        if (name.endsWith('-sources.jar') || name.endsWith('-javadoc.jar')) continue;
        const full = path.join(root, 'target', name);
        let mtime;
        try {
            mtime = fs.statSync(full).mtimeMs;
        } catch (_) {
            continue;
        }
        if (!best || mtime > best.mtime) best = {mtime, full};
    }
    return best ? best.full : null;
}

/**
 * 首启初始化工作区（打包态，`whenReady` 里、`killStaleSidecar` 之前调用）。
 *
 * 两件事：
 *   1. `mkdir -p data config` —— SQLite 的 `./data/xxx.sqlite` **不会**自己建父目录，
 *      目录不在时 sqlite-jdbc 直接报打开失败（那是「双击后起不来」的常见来路）。
 *   2. 把出厂配置从 `resources/config/` 拷过去 —— 用 `COPYFILE_EXCL`，**只补缺失、绝不覆盖**：
 *      用户改过的 `nya-entworks.yaml` 是**他自己填的路径**，重拷一次就全没了。
 */
function initWorkspace(root) {
    for (const sub of ['data', 'config']) {
        try {
            fs.mkdirSync(path.join(root, sub), {recursive: true});
        } catch (e) {
            console.error(`[shell] 建目录失败 ${sub}:`, e.message);
        }
    }
    const from = path.join(RESOURCES, 'config');
    let names;
    try {
        names = fs.readdirSync(from);
    } catch (_) {
        return;   // 没带出厂配置（开发态打包试验）—— 不是错误
    }
    for (const name of names) {
        const dst = path.join(root, 'config', name);
        try {
            fs.copyFileSync(path.join(from, name), dst, fs.constants.COPYFILE_EXCL);
            console.log('[shell] 铺出厂配置:', name);
        } catch (e) {
            // EEXIST 是**常态**（用户改过的那份就在这里），其余才值得说一声
            if (e.code !== 'EEXIST') console.warn('[shell] 拷配置失败', name, e.message);
        }
    }
}

// ───────────────────────── 端口与探测 ─────────────────────────

/**
 * 选一个空闲端口：绑 0 让系统分配，读到端口号后立刻释放。
 *
 * 释放到后端真正绑定之间有一个极小的竞态窗口；真撞上时表现为「后端起不来 →
 * 健康探测超时 → 启动画面报错」，看 sidecar.log 里会有端口占用。重跑一次即可。
 */
function pickFreePort() {
    return new Promise((resolve, reject) => {
        const srv = net.createServer();
        srv.once('error', reject);
        srv.listen(0, '127.0.0.1', () => {
            const {port} = srv.address();
            srv.close(() => resolve(port));
        });
    });
}

/**
 * 探后端是否就绪：连上端口 + 发一个最小 HTTP 请求 + 读得到字节。
 *
 * 为什么发 HTTP 而不是只看端口通：端口被别的东西占着时也会「连得上」，
 * 发个请求能区分这两种情况。用 `/favicon.png`（静态资源、不碰数据库），
 * 避免探测本身触发 MySQL 查询。
 */
function backendReady(port) {
    return new Promise((resolve) => {
        const socket = net.connect({host: '127.0.0.1', port});
        let settled = false;
        const done = (ok) => {
            if (settled) return;
            settled = true;
            socket.destroy();
            resolve(ok);
        };
        socket.setTimeout(1000, () => done(false));
        socket.once('error', () => done(false));
        socket.once('connect', () => {
            socket.write('GET /favicon.png HTTP/1.0\r\nHost: 127.0.0.1\r\n\r\n');
        });
        socket.once('data', () => done(true));
    });
}

// ───────────────────────── 子进程 ─────────────────────────

/**
 * 起后端进程。
 *
 * ★ `cwd: root` 是命脉：`data/eh-gallery.db` 与 `static-locations` 里的
 * `file:src/main/resources/static/` 都是**按工作目录解析**的，错了不报错、只静默降级。
 *
 * ★ `-Xmx2g`：**1g 不够，别调回去**（2026-09-16 实测后从 1g 改成 2g）。
 *
 *   本进程的**活集**（full GC 之后仍然在的对象）实测约 **924 MB**，而且几乎全是
 *   eh-gallery 字典：190 万行 `MangaEhLocalDb$Row` + 860 万个 String/byte[] +
 *   516 万个 HashMap$Node（`data/eh-gallery.db` 的四个归一标题索引）。
 *   在 `-Xmx1g` 下堆长期贴在 **98.8%**（1036463K / 1048576K），于是「新建填词模板」
 *   解析 svp 时抛 OutOfMemoryError —— 而那次分配自己只要 17 MB，**换个操作一样会炸**，
 *   报错位置只是恰好压死它的那根稻草。
 *
 *   别把 1g 当成"省内存的调优"：默认堆（物理内存 1/4）下本应用常驻 1912 MB，
 *   2g 只是把壳当初强加的那个过紧天花板还回去。教训与 `-Xmx512m` 完全相同
 *   （那时堆 522/524 打满）：**上限低于活集，症状就是随机位置 OOM**。
 *   要真正降内存，得让那本字典不常驻（`MangaEhLocalDb` 的四个索引改按需回查 SQLite），
 *   不是压堆上限 —— 见 docs/已完成/桌面壳实施计划.md 的验收记录 §6。
 */
function spawnSidecar({root, java, jar, port}) {
    sidecarLog = fs.openSync(logFile(), 'w');

    const args = [
        // 取自 pom.xml 里 spring-boot-maven-plugin 的 jvmArguments（java -jar 不会自动带上）
        '--enable-native-access=ALL-UNNAMED',
        '-Xmx2g',
        // ★ 这两行不是可有可无的，见 tee() 的注释：不加就是「一份日志两种编码」
        '-Dstdout.encoding=UTF-8',
        '-Dstderr.encoding=UTF-8',
        '-jar', jar,
        `--server.port=${port}`,
        // ★ 这一行**会改变现状**（原来绑 0.0.0.0，手机/别的电脑能访问）。
        //   如果你确实要局域网访问，把这一行删掉。
        '--server.address=127.0.0.1',
    ];

    // ★ 打包态加 release profile：`config/application-release.yaml` 把 datasource
    //   换成 SQLite（**只在打包线换，开发机仍走 MySQL**）。
    //   那个文件由发版脚本放进工作区的 config/ —— 所以它缺席时后端会**退回 MySQL**，
    //   在朋友的机器上表现为「启动就报连不上数据库」，不会静默。
    if (IS_PACKAGED) args.push('--spring.profiles.active=release');

    console.log('[shell] 后端命令:', java, args.join(' '));
    console.log('[shell] 工作目录:', root);

    // stdout/stderr 走管道，由 tee() 双写：一份到控制台、一份到 sidecar.log。
    // 原先写成 `stdio: ['ignore', sidecarLog, sidecarLog]`，后端日志一行都到不了控制台，
    // 想知道后端在干嘛只能另开窗口 tail 文件（2026-09-18 改）。
    const child = spawn(java, args, {
        cwd: root,               // ★ 见函数注释
        windowsHide: true,       // 不弹黑窗
        stdio: ['ignore', 'pipe', 'pipe'],
    });

    // spawn 自己失败时（找不到 java）这两个流可能压根不存在，判一下再挂
    if (child.stdout) tee(child.stdout, process.stdout);
    if (child.stderr) tee(child.stderr, process.stderr);

    child.on('error', (e) => {
        console.error('[shell] 起后端失败:', e.message);
    });

    try {
        fs.writeFileSync(pidFile(), String(child.pid));
    } catch (_) {
        // 写不了就算了，只影响「强杀后清理残留」这个便利功能
    }
    return child;
}

/**
 * 把子进程的一段输出（stdout / stderr）同时写进控制台与 sidecar.log。
 *
 * ★ **按 Buffer 原样转发，一个字都不解码**：后端吐的是 UTF-8 字节（见下），
 *   控制台在 `chcp 65001` 下按 UTF-8 渲染，两边正好对上。这里一旦 `toString()`
 *   再写，Node 会按它自己的口径重新编码，中文立刻变乱码。
 *
 * ★ `-Dstdout.encoding=UTF-8` 是**必须**的，不是可选调优。不加的话同一份日志里
 *   两种编码并存（2026-09-18 在这台机器上实测）：
 *     - logback 的日志行 → UTF-8（JDK 18+ 起 `file.encoding` 默认 UTF-8）
 *       例：`异步任务 handler 注册完成，共 30 类` = `\xe5\xbc\x82...`
 *     - `System.out.println` → GBK（stdout 被重定向成管道后，`stdout.encoding`
 *       落到系统 ANSI 代码页 936）
 *       例：`8月更新分 (14枚)` = `\xd4\xc2\xb8\xfc...`
 *   于是任何单一代码页的控制台都只能对上其中一半。钉成 UTF-8 后两边统一，
 *   顺带把 sidecar.log 也变成纯 UTF-8（`tail` / grep / 编辑器都正常了）。
 *
 * ★ 日志写不进去**绝不能把壳带崩**：以后打包成 exe 双击启动时没有控制台，
 *   写 stdout 会失败；控制台被关掉时管道也会断。两种情况都只吞掉异常，
 *   日志文件照旧。
 */
function tee(source, sink) {
    // 控制台被关掉时管道断开会发 'error'；没有任何监听者的 'error' 会直接崩掉主进程
    sink.on('error', () => {});

    source.on('error', () => {});
    source.on('data', (chunk) => {
        try {
            sink.write(chunk);
        } catch (_) {
            // 没有控制台 / 控制台已经关了：忽略，日志文件不受影响
        }
        try {
            // 用 writeSync 而不是 fs.createWriteStream：保持原来那个「spawn 时截断、
            // 重启时 closeSync」的口径不动，主进程多阻塞几毫秒无所谓（日志量很小）
            fs.writeSync(sidecarLog, chunk);
        } catch (_) {
            // 日志文件写不动（盘满、重启途中 fd 已关）：不因为记日志把后端带崩
        }
    });
}

/** 杀掉上次残留的后端（壳被强杀时会留下孤儿进程）。幂等，失败无害。 */
function killStaleSidecar() {
    let pid;
    try {
        pid = parseInt(fs.readFileSync(pidFile(), 'utf8').trim(), 10);
    } catch (_) {
        return;
    }
    if (!Number.isInteger(pid) || pid <= 0) return;
    // 只在我们自己的 PID 文件上动手；进程早没了的话 taskkill 会报错，忽略即可
    killTree(pid);
    try {
        fs.unlinkSync(pidFile());
    } catch (_) {
        // 忽略
    }
}

/** 杀进程树。Windows 靠 taskkill /T；其他平台先 SIGTERM。 */
function killTree(pid) {
    if (process.platform === 'win32') {
        try {
            spawnSync('taskkill', ['/PID', String(pid), '/T', '/F'], {
                stdio: 'ignore',
                windowsHide: true,
            });
            return true;
        } catch (_) {
            return false;
        }
    }
    try {
        process.kill(pid, 'SIGTERM');
        return true;
    } catch (_) {
        return false;
    }
}

/** 退出时杀掉后端及其子孙进程。幂等。 */
function killSidecar() {
    const child = sidecar;
    sidecar = null;
    if (!child || child.pid == null) return;

    const ok = killTree(child.pid);
    if (!ok) {
        try {
            child.kill();
        } catch (_) {
            // 已经死了
        }
    }
    try {
        fs.unlinkSync(pidFile());
    } catch (_) {
        // 忽略
    }
}

// ───────────────────────── 页面 → 壳的两个能力 ─────────────────────────

/**
 * 只有「本窗口 + 本窗口加载的确实是我们自己起的那个后端」才放行。
 *
 * 为什么要这道校验：preload 是绑在**窗口**上的，而窗口里加载的是本地 HTTP 服务提供的页面。
 * docs/桌面化与模块裁剪设计.md §4.2 正是冲着这条选了 bridge 方案（怕端口被冒充、页面被导到别的源）。
 * 本壳只加载自己 spawn 的 sidecar，所以用「发送方帧的源 == backendOrigin」把那条路堵住就够。
 *
 * 判定本体抽在 trusted.js（纯函数、不依赖 electron），tools/shell-trusted-check.js
 * 核对的与这里是**同一份实现** —— 改判定先跑那个脚本。
 */
function trusted(event) {
    return isTrustedSender(
        !!mainWindow && event.sender === mainWindow.webContents,
        event.senderFrame ? event.senderFrame.url : '',
        backendOrigin);
}

/**
 * 本次会话里壳亲手交出去的绝对路径（pick-path / pick-files 的返回值）。
 *
 * 「在资源管理器中显示」（shell:show-item）的放行口径就是它 —— 2026-09-24 裁决取
 * 「受管根 + 壳自己记过的路径」里的后半（前半在壳这边无从判断，业务归后端）：
 * 覆盖「我刚选的那个文件，要立刻确认位置」这个刚需，又不给页面一把
 * 「打开任意目录」的万能钥匙。壳重启即清空：用途是刚选完就显示，够了。
 *
 * 键存小写（Windows 路径大小写不敏感；别的平台原样）—— 对话框返回的盘符
 * 大小写与后端/前端传回来的写法可能不同，同一文件不该被当成两个路径。
 */
const grantedPaths = new Set();

/** 放行表的键：绝对路径 + 归一化分隔符；Windows 再折叠大小写（NTFS 不分大小写，对话框返回的盘符大小写还不一致） */
function grantKey(p) {
    const resolved = path.resolve(p);
    return process.platform === 'win32' ? resolved.toLowerCase() : resolved;
}

function grantPath(p) {
    if (typeof p === 'string' && p) grantedPaths.add(grantKey(p));
}

function isGranted(p) {
    return typeof p === 'string' && !!p && grantedPaths.has(grantKey(p));
}

ipcMain.handle('shell:pick-path', async (event, options) => {
    if (!trusted(event)) return {ok: false, message: '不是本应用的页面，已拒绝'};
    const isFile = options.kind === 'file';
    const opts = {title: isFile ? '选择文件' : '选择目录', properties: [isFile ? 'openFile' : 'openDirectory']};
    if (!isFile) opts.properties.push('createDirectory');   // 目录对话框允许顺手新建一个
    const cur = options.current;
    if (typeof cur === 'string' && cur && fs.existsSync(cur)) opts.defaultPath = cur;
    try {
        const r = await dialog.showOpenDialog(mainWindow, opts);
        if (r.canceled || !r.filePaths.length) return {ok: false, message: '已取消'};
        grantPath(r.filePaths[0]);   // 记下来：它有资格被「在资源管理器中显示」
        return {ok: true, path: r.filePaths[0]};
    } catch (e) {
        return {ok: false, message: '打不开对话框：' + e.message};
    }
});

// 「上次新增文件的目录」持久化在 userData 里（跨壳重启有效）。不放前端 localStorage：
// 页面加载自动态端口的 http origin，端口一变 localStorage 就换了个存储
function lastImportDirFile() {
    return path.join(app.getPath('userData'), 'nya-entworks-last-import-dir');
}

function readLastImportDir() {
    try {
        return fs.readFileSync(lastImportDirFile(), 'utf8').trim();
    } catch (e) {
        return '';
    }
}

function writeLastImportDir(dir) {
    if (!dir) return;
    try {
        fs.writeFileSync(lastImportDirFile(), dir);
    } catch (e) { /* 存不了就下次从头选，不影响功能 */ }
}

ipcMain.handle('shell:pick-files', async (event, options) => {
    if (!trusted(event)) return {ok: false, message: '不是本应用的页面，已拒绝'};
    const opts = {
        title: (options && options.title) || '选择文件',
        properties: ['openFile', 'multiSelections']
    };
    // 扩展名过滤（可选）：Electron 原生 filters，形状 [{name, extensions}]（extensions 不带点）。
    // 清单由后端下发（页面的 extRule），这里只透传 —— 壳不自己维护一份扩展名表。
    // 给得不合法（不是数组 / 空数组 / 项里没 extensions）就整段忽略：过滤是锦上添花，
    // 不能因为它把对话框弄成打不开
    const filters = options && options.filters;
    if (Array.isArray(filters)) {
        const clean = filters.filter(f => f && typeof f.name === 'string'
            && Array.isArray(f.extensions) && f.extensions.length);
        if (clean.length) opts.filters = clean;
    }
    // 默认目录：优先用调用方指定的（当前文件所在文件夹）；没有则回落到持久化的上次目录
    const explicit = options && options.defaultPath;
    const cur = (typeof explicit === 'string' && explicit) ? explicit : readLastImportDir();
    if (cur && fs.existsSync(cur)) opts.defaultPath = cur;
    try {
        const r = await dialog.showOpenDialog(mainWindow, opts);
        if (r.canceled || !r.filePaths.length) return {ok: false, message: '已取消'};
        for (const p of r.filePaths) grantPath(p);   // 同上：选出来的都有资格被显示
        writeLastImportDir(path.dirname(r.filePaths[0]));
        return {ok: true, paths: r.filePaths};
    } catch (e) {
        return {ok: false, message: '打不开对话框：' + e.message};
    }
});

/**
 * 「在资源管理器中显示」：打开资源管理器并选中那个文件（Electron 内置
 * shell.showItemInFolder，不必拼 explorer /select）。手动归档的刚需 ——
 * 选完文件要能立刻确认位置（设计稿 §7）。
 *
 * 放行口径（2026-09-24 裁决 b）：只放行<b>本次会话里通过 pick-path / pick-files
 * 选出来的路径</b>（见 grantedPaths）。业务后端不管 —— 这个能力纯前端 → preload → 壳，
 * 不新增后端端点。拒绝必须给一句人话，不能静默无反应。
 */
ipcMain.handle('shell:show-item', (event, targetPath) => {
    if (!trusted(event)) return {ok: false, message: '不是本应用的页面，已拒绝'};
    if (!isGranted(targetPath)) {
        return {ok: false,
            message: '只能显示本次用「浏览…」选进来的文件（壳重启后要重新选一次）'};
    }
    const resolved = path.resolve(targetPath);
    if (!fs.existsSync(resolved)) {
        return {ok: false, message: '文件已不在原处：' + resolved};
    }
    try {
        shell.showItemInFolder(resolved);
        return {ok: true};
    } catch (e) {
        return {ok: false, message: '打不开资源管理器：' + e.message};
    }
});

ipcMain.handle('shell:restart-backend', (event) => {
    if (!trusted(event)) return {ok: false, message: '不是本应用的页面，已拒绝'};
    if (restarting) return {ok: false, message: '正在重启中，请稍候'};
    restarting = true;
    // 先回话再动手：随后的导航会销毁发起方，等回包只会等到一个悬空的 promise
    restartBackend().catch((e) => {
        console.error('重启后端失败:', e);
        setStatus('重启失败，请看终端输出与 ' + logHint());
    }).finally(() => {
        restarting = false;
    });
    return {ok: true};
});

/**
 * 重启后端：回到启动画面 → 杀掉旧进程 → 等端口释放 → 走一遍原来的启动流程。
 * 复用 {@link startBackendAndNavigate}（选端口、spawn、健康探测、loadURL 全在里面），
 * 并带上 `#settings` 让用户回到刚才那一页 —— 新端口与旧端口不同，必须重新导航，
 * 否则窗口里留着的是指向旧端口的死链。
 */
async function restartBackend() {
    if (!mainWindow || mainWindow.isDestroyed()) return;
    await mainWindow.loadFile(path.join(__dirname, 'splash.html'));
    setStatus('正在重启后端…');

    // 上一轮的日志另存一份：spawnSidecar 用 'w' 打开日志，不存的话崩溃现场正好被这次重启抹掉
    try {
        const log = logFile();
        fs.copyFileSync(log, log.replace(/\.log$/, '.prev.log'));
    } catch (_) {
        // 没有就没有
    }
    killSidecar();   // 幂等
    if (sidecarLog !== null) {
        try {
            fs.closeSync(sidecarLog);
        } catch (_) {
            // 已经关了
        }
        sidecarLog = null;
    }
    // 让 taskkill /T 真正落地、端口释放掉。立刻 spawn 会撞上自己刚才占的那个端口
    await sleep(800);
    await startBackendAndNavigate('#settings');
}

// ───────────────────────── 主流程 ─────────────────────────

/**
 * @param hash 启动后要落在哪一页（`#settings` 这种，可省）。重启时用它回到用户原来那一页
 */
async function startBackendAndNavigate(hash) {
    // 工作区在 whenReady 里已经定过一次（重启时也保留着）——这里只是兜底
    const root = projectRoot || findProjectRoot();
    if (!root) {
        return setStatus('找不到项目根（向上没找到 pom.xml）。可设环境变量 NYA_ENTWORKS_ROOT。');
    }
    const java = findJava();
    if (!java) {
        return setStatus(IS_PACKAGED
            ? '随包的 JRE 不在（resources/jre）。这个包不完整，请重新解压一份。'
            : '找不到 java。可设环境变量 NYA_ENTWORKS_JAVA，或检查 PATH / JAVA_HOME。');
    }
    const jar = findJar(root);
    if (!jar) {
        return setStatus(IS_PACKAGED
            ? '随包的 app.jar 不在（resources/app.jar）。这个包不完整，请重新解压一份。'
            : 'target 下没有 jar。先在项目根跑 ./mvnw clean package -DskipTests');
    }

    const port = await pickFreePort();
    backendOrigin = `http://127.0.0.1:${port}`;
    sidecar = spawnSidecar({root, java, jar, port});
    setStatus(MSG.starting);

    const deadline = Date.now() + HEALTH_TIMEOUT_MS;
    let ticks = 0;

    for (;;) {
        if (await backendReady(port)) {
            // 带 hash 直接落在目标页；`#settings` 是配置页（重启回来时用）
            await mainWindow.loadURL(backendOrigin + '/' + (hash || ''));
            return;
        }

        // 进程自己死了就别再等（端口被占、MySQL 连不上导致启动失败等）
        if (sidecar && sidecar.exitCode !== null) {
            sidecar = null;
            return setStatus('后端进程已退出。请看 ' + logHint() + ' 的最后几十行。');
        }

        if (Date.now() > deadline) {
            killSidecar();
            return setStatus('后端启动超时。请看 ' + logHint() + '。');
        }

        if (++ticks % 6 === 0) setStatus(MSG.waiting);
        await sleep(PROBE_INTERVAL_MS);
    }
}
