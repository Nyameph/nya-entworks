/**
 * 桌面壳安全闸门 trusted() 的行为核对 —— 运行：node tools/shell-trusted-check.js
 *
 * 为什么需要它：main.js 的 trusted() 是那道「防别人的页面冒充本地服务拿到原生能力」的
 * 闸门（ipcMain.handle 的每个通道第一行都在问它），而 desktop/ 下的脚本不在 Maven 构建里、
 * 没有别的自动化测试 —— 它是「写错了没人发现、后果很重」的典型。写错的症状全静默：
 * 判得太松，任意网页都能拿到原生对话框；判得太紧，自己的页面点按钮全被拒。
 *
 * 判定本体在 desktop/trusted.js（纯函数、不 require electron），本脚本与 main.js
 * require 的是**同一份实现**，不存在两份代码各改各的漂移。下面逐条钉住：
 *
 *   1. 正常页面（同源 + 主窗口发出）放行 —— 带路径、带 query、恰好根路径都算同源；
 *   2. 别的源一律拒绝：不同端口、相邻端口（前缀像但端口不同）、https 换 http、
 *      user-info 混淆（`http://127.0.0.1:50721@evil.com/`，开头是 `@` 不是 `/`，骗不过）；
 *   3. 后端没起（backendOrigin = null）时全拒 —— 没有源就没有可信；
 *   4. 发送方不是主窗口的 webContents 时全拒 —— 这是与源无关的第二道独立判据。
 *
 * 改 trusted.js 或 main.js 的 trusted() 之前先跑它；改完（preload / main 必须重启壳）
 * 也请真机点一遍「浏览…」确认没把正常页面误伤。
 */
'use strict';

const {isTrustedSender} = require('../desktop/trusted');

const ORIGIN = 'http://127.0.0.1:50721';

let pass = 0;
let fail = 0;

function ok(msg) {
    pass++;
    console.log('  ok   ' + msg);
}

function bad(msg) {
    fail++;
    console.log('  FAIL ' + msg);
}

function check(cond, msg) {
    cond ? ok(msg) : bad(msg);
}

// ───────────────────────── 1. 正常页面放行 ─────────────────────────

console.log('放行（本窗口 + 同源）:');
check(isTrustedSender(true, ORIGIN + '/', ORIGIN),
    '根路径放行');
check(isTrustedSender(true, ORIGIN + '/#song', ORIGIN),
    '带 hash 的页面放行');
check(isTrustedSender(true, ORIGIN + '/js/app.js?v=1', ORIGIN),
    '带路径与 query 的资源放行');

// ───────────────────────── 2. 别的源拒绝 ─────────────────────────

console.log('拒绝（源不对）:');
check(!isTrustedSender(true, 'http://127.0.0.1:9999/#settings', ORIGIN),
    '不同端口拒绝（别的本地服务抢了窗口）');
check(!isTrustedSender(true, 'http://127.0.0.1:5072/', ORIGIN),
    '相邻端口拒绝（端口 5072 是 50721 的前缀，不能startsWith误放）');
check(!isTrustedSender(true, 'https://127.0.0.1:50721/', ORIGIN),
    'https 冒充同源拒绝');
check(!isTrustedSender(true, 'http://127.0.0.1:50721@evil.com/#settings', ORIGIN),
    'user-info 混淆拒绝（@evil.com 不是本服务）');
check(!isTrustedSender(true, 'http://localhost:50721/', ORIGIN),
    'localhost 与 127.0.0.1 字面不同，一律按源拒绝（口径从严）');

// ───────────────────────── 3. 后端没起 ─────────────────────────

console.log('拒绝（backendOrigin 未定）:');
check(!isTrustedSender(true, ORIGIN + '/', null),
    'backendOrigin 为 null 时拒绝');
check(!isTrustedSender(true, ORIGIN + '/', ''),
    'backendOrigin 为空串时拒绝');

// ───────────────────────── 4. 不是主窗口 ─────────────────────────

console.log('拒绝（发送方不是主窗口）:');
check(!isTrustedSender(false, ORIGIN + '/', ORIGIN),
    '源相同但发送方是别的 webContents，拒绝');
check(!isTrustedSender(false, 'http://evil.example.com/', ORIGIN),
    '发送方与源都不对，拒绝');

// ───────────────────────── 结果 ─────────────────────────

console.log('\n' + pass + ' 过 / ' + fail + ' 败');
process.exit(fail ? 1 : 0);
