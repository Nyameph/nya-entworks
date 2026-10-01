# tools/

与 Maven 构建无关的小工具,手动跑。下面十二个各管一件事:

- `release.py` — **一键发版**(见下方正文):跑完得到一个可以直接 zip 发给别人的文件夹。
  它把最新前后端打成 portable exe、按最新结构生成一份空 SQLite 库、放好清理过的配置、
  与上一发版比对出「结构更新」说明。`--selftest` 跑建表转换与结构 diff 的回归用例。
- `publish.py` — **发布到公开仓库**(见下方正文):把 `docs/` / `CLAUDE.md` / 两份标签 CSV
  排除在外,只把剩下的树推成线上 `main`。`--list` 看排除项、`--dry-run` 不推、
  `--selftest` 跑过滤与泄露扫描的用例。
- `bump_version.py` — 版本号 bump(见下方正文)。`pom.xml` 是唯一真源,
  `desktop/package.json` 与 `desktop/package-lock.json` 跟着同步。**别手工改版本号**,
  会漏掉另外两处。
  发版(`minor`)时还顺带留两份存档:库结构进 `.local-backup/schema/`、
  本机覆盖层进 `.local-backup/config/`(两份都不进 git)。
  `--selftest` 跑它自己的回归用例,**改正则之后先跑**。
- `mysql.py` — 数据库直连入口(见下方正文)。**AI 改库只能走它**,它带删改护栏。
- `ncm_decrypt.py` — 网易云 `.ncm` 解密(见下方正文)。
- `ncm_decrypt.sh` — `ncm_decrypt.py` 的批处理包装脚本(一行,里面写死了本机待解密目录 `E:\Music\VipSongsDownload`,换目录要改它)。
- `songfill-reflow-check.js` — 填词页批量填词(`songfill.js` 的 `applyReflow` / `applyByLine` /
  `alignByLine` / `batchUnits`)的行为核对。零依赖,`node tools/songfill-reflow-check.js` 即可跑
  (全绿打印 `ALL OK`)。口径见 `docs/填词工具设计.md` §13 第 43 条(重新分行)、第 46 条(「-」占位)、
  第 50 条(一句里多个声部)与第 58 条(按句拆 / 并),改那段逻辑前先跑一遍。
- `page-util-check.js` — 前端公共件(`js/api.js` 的 `Util` / `UI.pagerBar` / `UI.th` / `UI.toast`)
  的行为核对。`node tools/page-util-check.js` 跑。**改 `api.js` 里那几个公共件、或改任何页面对它们的
  调用之前先跑它** —— 前端没有测试框架,这几件又是从五六个页面合并来的同一个实现,合并的前提是行为
  不变,而页面得点开才看得出坏没坏。`UI.toast` 那几条盯的是「成功提示自己消失、错误提示不主动消失
  (要人点掉)」这条规矩,做法是喂一个假 `#toast` 元素与只记账的假定时器 —— 有没有排定时器就是
  「会不会自己溜走」的判据。它同时会扫各页面 js,确认没有残留的旧本地实现、没有漏改的调用点、
  没有引用到不存在的导出成员。
  **最后一节管 `index.html`**(2026-09-29 加):注释是不是真的用 `-->` 收的尾、`app.js` 启动时
  按 id 取的元素在不在、有没有重复 id、引的本地 js/css 存不存在。加它的起因是一次**真实的整站故障** ——
  新写的一条 HTML 注释顺手用 JS 的 `*/` 收了尾,浏览器于是**从那行起到下一个 `-->` 为止的整段标记
  全当注释吞掉**(被吞的是新按钮、`</nav>` 和整个 `<main>`),症状不是「这一页坏了」而是
  **每一页都刷不出来**:`app.js` 在最后几行挂 `onclick` 时抛 TypeError,`boot()` 里的首次路由也一起挂。
  要命的是静态资源照常 200、`curl` 出来的 HTML 一个字都不缺 —— **grep 字符串也照样能匹配到那几个 id**
  (它们就在被吞掉的那段里),所以只能靠一份懂 HTML 注释规则的解析来拦。**改 `index.html` 后跑它。**
- `guide-check.js` — 使用说明页(`static/guide/*.md` 的正文 + `js/md.js` 的渲染器 + `js/guide.js`
  的页面)的守门。`node tools/guide-check.js` 跑(全绿打印 `ALL OK`)。**改 `modules.js` 的顶层项 /
  `children` / 页面清单,增删 `static/guide/` 下的 md,或改 `guide-data.js` / `md.js` / `guide.js` 之后先跑它** ——
  说明页要防的错全是静默的,一件也不会自己冒出来:注册表加了页面而说明页漏了它(那一页整节不出现,
  没人发现)、md 文件名拼错一个字母(那一章永远显示「没读出来」)、文案顺手把 `modules.js` 那句 `desc`
  抄了一份(第二个会漂移的权威)、面向用户的正文里漏进了列名 / 阈值 / 类名、渲染器里图省事写死一句中文
  (文案从此有两个来源)。正文是**一个章一个文件** `static/guide/<章 id>.md`(5 个:`intro` /
  三个模块 id / `standalone`),章里按 `## <标题>` 分块、**一章里的每一块就是一节** —— **结构、
  顺序、左侧目录三样都以 md 为准**(2026-09-30 用户定);块的 id 仍在 `modules.js`,所以块与页面
  **按标题对名**(逐字相同才算对上,对不上的块照画成一节、没有「打开这一页」按钮)。于是
  「每个页面都讲到」由**每个页面在它那一章里都有一块同名**钉住(少了就红、点名);反过来
  「对不上任何页面的块」**只 WARN** —— 多半是某个页面标题被改了字(那一页因此丢了锚点与按钮),
  得点名;页面标题以 md 为准是有意的,标题那几行因此**豁免反抄写**。十一组断言:覆盖度(章文件非空 +
  **每个页面都有同名块** + 页标题里没有 md 标记)、
  反向(不许有这 5 个之外的孤儿 md)、反抄写(逐行比,含「以 `desc` 前 20 字开头」)、md 体例
  (不许有一级标题、不许手打图号)、禁词表(规格 + `对象.成员` 形态,扫前先剥掉图片 / 链接的地址)、
  **合同句**(填词页那个折叠速查与说明页必须一字不差的那几句,改一边忘另一边就红)、图(引用唯一 /
  都写成相对 md 文件的 `../img/guide/…` / 是真 PNG / **目录里没有没被引用的图**,豁免名单
  `UNREFERENCED_OK` 之外的都判红、名单条目失效也判红)、**渲染器零文案**(`guide.js` 与
  `md.js` 的字面量里不许有成句中文)、**`md.js` 冒烟**(把一段「什么语法都用上」的样本真渲染一遍:
  HTML 必须被转义、`javascript:` 必须被挡、孤零零的 `*` 不许吞字、标题 id 要去重)、**渲染冒烟**
  (给 `guide.js` 的 `render()` 搭一套最小假 DOM、在 `vm` 里**真跑一遍**:五章 / 23 节的顺序、
  每节的 id 与标题、左侧目录逐条与正文一致、按钮的条数、**带 `?sec=` 进来滚的是正文那一节**
  (不是目录里同 id 的那条链接;不存在的锚点则一次都不滚),外加五条退化分支 —— 整章 md 读不到 /
  少一块 / 标题被改一个字 / 缺磁盘根 / 模块关着 / 后端没起;那套「按标题对名 + 合成 id」的规则
  **只活在运行时**,静态那半看不见)、
  体量(`guide-data.js` ≤ 5 KB,长回成说明书就红)。做法是在 `vm` 里直接加载 `modules.js` /
  `guide-data.js` / `md.js`(一个是纯注册表、一个是纯数据、一个不碰 `document`);`guide.js` 只用得到
  `host.innerHTML` 与 `firstElementChild` 上那几个方法,所以假 DOM 只补五样东西(`querySelector`
  那一份照真实文档顺序命中 —— 目录排在正文前面,2026-09-30 「点目录不跳」正是栽在这上)——
  这是「正文、清单、渲染分开」的附带好处。起因与全部分工见 `docs/已完成/使用说明页实施计划.md`。
- `guide-shot.js` — 使用说明页那 18 张图的批量截图(**零依赖 node + Chrome CDP**,不装 Playwright /
  Puppeteer)。`node tools/guide-shot.js [名字片段…]` 跑(不给参数就全跑),产物直接写进
  `static/img/guide/`,**放进去即生效**(md 里早就引着那个名字);`--list` 列全部镜头,
  `--rect <页面> <选择器…>` 是调裁剪框与标注位置用的量尺(只量不截)。**跑之前后端要起着**,
  它打开的是真页面。三条底线在文件头:填词页**不许派发任何键鼠到填写格**(那一页每 5 秒自动保存,
  驱动输入就是真写库、还会盖掉用户正在编的那一份)、只点「纯展示」的(开浮层 / 切页签 / hover)、
  绝不点保存 / 归档 / 删除 / 重启 / 同步这类会真动东西的。脱敏与漏检自检是配套的:换完文字 /
  图片 / 路径之后 `gs.checkLeaks` 把整页再扫一遍,图上还看得见真内容就**指名报错**(它抓出过
  `title` 属性里藏着的真目录名、textarea 子文本节点里那份原文)。口径与踩过的坑见
  `docs/已完成/使用说明页实施计划.md`《实施记录 · 排版与截图(2026-09-30)》。
- `settings-toggle-check.js` — 配置页开关(`js/settings.js` 的 `toggleBtn` 与它下面那段点击处理)的
  行为核对。`node tools/settings-toggle-check.js` 跑。**改那两个地方之前先跑它** —— 它盯的是「画」:
  开关是仿 iOS 的 UISwitch,圆钮是按钮里那个 `<span class="set-toggle-knob">`,而点击处理里一句
  `btn.textContent = ...` 会把它顶掉、开关变成一个空胶囊;要命的是 `data-bool` 改了、保存也真的存
  下去了,只有 DOM 坏掉,不用眼睛看看不出来。做法是在 `vm` 里加载真的 `settings.js`、喂一个假 `view`
  画出页面,再对一个假按钮真跑一次 `bind()` 挂上的点击处理(不是从源码里抠字符串)。详见
  `docs/实现说明.md` §7.38。
- `shell-trusted-check.js` — 桌面壳那道 **IPC 安全闸门**(`desktop/trusted.js` 的 `isTrustedSender`,
  `desktop/main.js` 的 `trusted()` 与它每个 `ipcMain.handle` 的第一行)的行为核对。
  `node tools/shell-trusted-check.js` 跑(全绿打印 `12 过 / 0 败`)。**改 `trusted.js`、或改 `main.js`
  里那个 `trusted()` 之前先跑它** —— 判错两边都静默:太松,任意网页都能拿到原生对话框;太紧,
  自己页面点「浏览…」全被拒。判定本体抽在 `desktop/trusted.js` 这个**纯函数**里(它不 `require`
  `electron`,所以 node 能直接加载),脚本与 `main.js` require 的是**同一份实现**,不存在两份代码
  各改各的漂移。`desktop/` 下的脚本不进 Maven 构建、没有别的自动化测试,这里是它唯一的回归网。
  口径与起因见 `docs/已完成/桌面壳实施计划.md`《桌面化收尾（2026-09-25 实施）》阶段 3。

<br>

# 版本号

`pom.xml` 的 `<version>` 是**唯一真源**,`desktop/` 下**两份**由 `bump_version.py` 同步成
同一个值: `package.json`(后面 `electron-builder` 打包要用它)与 `package-lock.json`
(顶层与 `packages[""]` 各一行 —— 只有这两行带包名,依赖自己的 `version` 一个都不能碰,
所以脚本**用包名锚定**、而且包名从 `package.json` 现读,不是数「前两处」)。**改版本号
一律走这个脚本** —— 手工改 pom 会漏掉另外两份,三处从此不一致;其中 lockfile 那份最阴:
它要等下次 `npm install` 才现形,表现是 diff 里冒出一行「莫名其妙改过的版本号」。

页面上显示的那个版本号也来自这里(Maven 的 `build-info` goal → `BuildProperties` →
`/api/version` → 左下角品牌区),所以**静态页里没有第二份写死的版本号**。

## 什么时候涨哪一位

| 位 | 什么时候 | 谁做 |
| --- | --- | --- |
| 第三组 patch(`1.0.1 → 1.0.2`) | **每次提交自动 +1**,不看提交类型(`feat`/`fix`/`chore` 一视同仁) | git 的 `post-commit` 钩子,自动生成 `chore: bump version to X` 提交 |
| 第二组 minor(`1.0.1 → 1.1.0`) | **手动** —— 一批小改动攒够了、要发一个较大版本时 | `python tools/bump_version.py minor` |
| 第一组 major | 不自动,也不打算用 | —— |

**为什么不按提交类型判「大 / 小」**:「这个提交是不是新功能」与「该不该发一个较大版本」
是两件事。日常绝大多数提交都带 feat 语义,但都+1 小版本号是错的;而较大版本本质是
**发版决策**,该由人挑时机。所以两种触发方式刻意分开:patch 全自动、minor 手动。

## 装钩子(每台机器 / 每次新 clone 各做一次)

```bash
git config core.hooksPath .githooks
```

`.git/config` 不进版本库,所以这条命令不会跟着仓库走。**忘了装的症状是「版本号一直不动」**,
提交、推送全都正常 —— 正是这种静默才要每次都提一遍。

钩子按顺序跳过这四种提交(判定在 `bump_version.py` 里,不在 `.githooks/post-commit`):

1. 提交信息含 `bump version` —— 防递归,钩子自己生成的那个提交靠这条终止;
2. `Merge` / `Revert` 开头 —— 不是普通提交;
3. **本次提交自己已经改过 `pom.xml` 的版本行** —— 手动 bump 的那次提交、以及首次引入
   版本号的初始化提交都属这类,不跳过就会「改一次涨两次」;
4. 取不到上一个提交(仓库的第一个提交)—— 没有旧版本号可比。

## 手动用法

```bash
python tools/bump_version.py patch       # 1.0.1 -> 1.0.2(只改文件,自己提交)
python tools/bump_version.py minor       # 1.0.1 -> 1.1.0(只改文件,自己提交)
python tools/bump_version.py --selftest  # 改正则 / 算数之后跑一遍(9 条)
```

`--selftest` 不碰盘也不连 git,盯的是**静默**的那类错:比如在 `pom.xml` 的
`<artifactId>` 与 `<version>` 之间插一句注释 —— 正则会匹配不上,症状是「每次提交钩子
都报一句错、版本号一直不动」,而那行报错很容易被提交输出淹没。**改那个脚本的正则后先跑它。**

手动跑完自己提交即可,message 建议写成 `chore: bump version to X.Y.Z` —— 钩子认得这行,
不会再叠一次(规则 1)。

脚本拒绝修改带后缀的版本号(如 `1.0.1-SNAPSHOT`):与其猜后缀留不留,不如先让人定下来。

## 发版时顺带留两份存档

`minor` 会在改版本号**之前**做这两件事 —— 都放在改号之前,所以中途失败时版本号
一个字没动,处理好重跑一次即可。`patch` 两件都不做(它每次提交都会跑)。

| 存什么 | 存到哪 | 进 git 吗 |
| --- | --- | --- |
| 库结构(只有建表语句,不含数据) | `.local-backup/schema/<新版本>.sql` | **不进** |
| 本机覆盖层 `config/nya-entworks.yaml` | `.local-backup/config/nya-entworks-<新版本>.yaml` | **不进** |

两份**都不进仓库**,去向与 `mysql.py` 的 `.local-backup/db/` 并列(同一个 gitignore)。
按版本命名是为了回答「两个版本的库差了什么」—— 拿两次发版的快照直接比即可,
不必让它们跟着代码走。

- 结构那份**转调 `tools/mysql.py --dump-schema`**,不在这里另写一份连库逻辑 ——
  凭据怎么读、`mysqldump` 在哪、密码怎么传,那些知识只该有一份。`AUTO_INCREMENT=`
  去掉(那是行数的痕迹、不是结构)。**失败即中止**(MySQL 没起、`mysqldump` 不在
  `MYSQL_BIN_DIR` 下都会命中)—— 它**没有「文件不在」这条退路**。
- 覆盖层那份里有 eh cookie 明文(配置页把 cookie 收进了页面),所以更不能进仓库。
  它同时是**模块裁剪的介质**,丢了模块会一起消失,所以值得留。
- **覆盖层不在 ≠ 出错**:新 clone / 另一台机器上本来就没有这一份,拿它去挡发版就成了
  「新机器发不了版」。这种情况只提示一句、照常发版;真复制失败(磁盘满、没权限)才中止。

## 坑:`git commit --amend` 会改到 bump 提交上

这是「版本号另起一个提交」的必然代价(实测于 2026-09-30):提交一条普通改动之后,
**HEAD 是钩子生成的那个 `chore: bump version to X`,不是你刚写的那条**。所以紧接着
`git commit --amend` 改的是 bump 提交的信息,而不是你想改的那条。

要改刚才那次提交,`--amend` 要**连做两次**(先把 bump 提交改回去、再改真正的目标),
或者干脆用 `git reset --soft HEAD~2` 把两条一起退回来重做。钩子不会因此重复涨版本:
bump 提交自己动过版本行,规则 3 会跳过它。

<br>

# 一键发版

```bash
python tools/release.py                # 全套:建表脚本 → 打包后端 → 空库 → 配置 → exe
python tools/release.py --selftest     # 建表转换 + 结构 diff 的回归用例(不碰盘、不构建)
python tools/release.py --emit-sqlite-ddl   # 只重生成 SQLite 建表脚本
python tools/release.py --emit-config DIR   # 只把三份交付配置写进 DIR/config/
python tools/release.py --no-shell     # 到「落位文件夹」为止,不做 jlink / electron-builder
python tools/release.py --no-build     # 复用 target 里现成的 jar
python tools/release.py --jar PATH     # 不构建、也不用 target 里那个,拿这一个去打包
python tools/release.py --release-dir D     # 换个落点(默认 <发版目录>)
```

**⚠️ 跑之前先关两个东西:桌面壳窗口、上一版的 exe。**

- **桌面壳窗口** —— 壳起的 java 进程占着 `target` 里的 jar,`mvnw clean package` 会死在
  「另一个程序正在使用此文件」,而那个报错埋在 mvn 一大段输出里,读起来像构建脚本坏了。
- **上一版的 exe** —— portable 单文件**一运行就占住它自己那个文件**,最后一步复制不进去。

两件都在 `main()` 开头就探(判据是**能不能改名**:Windows 上被占用的文件能覆盖写、
但改不了名),探到就直接报出**该关哪一个**再退出 —— 所以正常看不到 mvn 或 Python 的原始报错。

**`--jar` 是给「jar 拿不到手」用的**:开发壳开着 → `target/` 里的 jar 既占着又旧,
但成品还是得做出来 —— 那就换个地方编一份递进来(比如把 `pom.xml`/`mvnw`/`.mvn`/`src` 拷到
临时目录跑一次 `mvnw package`)。**它同样过 `assert_jar_carries_sqlite_ddl` 那道闸**,
所以递进来的必须是个**带 SQLite 建表脚本**的真 jar(见下面第三个坑)。

## 产出

落点在 `<发版目录>`(可用 `--release-dir` 改),整个文件夹**打包成 zip 就能发给别人**:

| 文件 | 是什么 |
| --- | --- |
| `呜啊娱乐工坊-<版本>.exe` | portable:单文件、免安装、免 UAC。**数据落在 exe 旁边** |
| `当前版本<版本>` | 版本标记文件(无扩展名) |
| `data/nya_entworks.sqlite` | 按最新结构生成的**空**库(供发版人查看/留档;首启也会自建) |
| `config/` | 清理过的配置:只有歌曲 + 喊麦、路径全空、**无任何密钥** |
| `结构更新/<旧>-><新>结构更新.md` | 只在结构或配置**真变了**时才生成 |

## 这套东西的口径(改它之前先读这几条)

- **开发机保持 MySQL,只有打包线换 SQLite。** 机制是 Spring profile:`application.yaml` 一个字不改,
  壳在打包态给后端多传一个 `--spring.profiles.active=release`,由工作区里那份
  `config/application-release.yaml` 把 datasource 覆盖成 SQLite。**代价要记住:方言问题在本机跑
  测试时测不出来** —— 所以 jlink 与打包之后**必须真起一次**(见下方「冒烟」)。
- **SQLite 建表脚本是转出来的,不是手写的。** 唯一真源是
  `src/main/resources/db/nya_entworks.sql`,转换器(本脚本里的纯函数)裁到 13 张表
  (歌 8 + 喊麦 3 + 通用 2,漫画整块裁掉),写成 `…sqlite.sql` **提交进仓库**(便于 review 与 diff)。
  **改 MySQL 那份之后要重跑 `--emit-sqlite-ddl`**,否则两边漂移。
  两处**有意**不同:索引名带上表名(SQLite 的索引名是**全库**唯一,撞名会被 `IF NOT EXISTS`
  **静默跳过**)、唯一键内联成表内 `UNIQUE(...)`(SQLite 的 `ON CONFLICT` 只认表内约束)。
  这两条与 `RhymeEntryMapper.xml` 的 `ON CONFLICT(text, pinyin)`、`tier` 那几处是**跨文件契约**,
  `--selftest` 两头都钉着。
- **裁剪的介质是交付的那份 `config/nya-entworks.yaml`**:留哪个模块就写哪一段的 `enabled: true`,
  不留的整段不写。漫画不在里面 → 这个构建里**根本没有**漫画模块。
- **`application.yaml` 里绝不加回 `nya-entworks:` 段**(内侧留一份等于给被裁模块留一条复活路径)。
- **交付配置里不许出现发版人本机的任何路径与密钥。** 这是唯一的底线,`build_release_config()`
  是唯一的写处,改动它之前先想清楚。

## 冒烟(jlink 与打包之后**必做**)

jlink 漏一个模块(JDK 自带的 `java.base` / `java.sql` / `jdk.unsupported` / `jdk.crypto.ec` /
`java.net.http`)是**运行期才炸**,而且报错看不出跟 jlink 有关;SQLite 方言问题同理。
最小做法:把 `desktop/build/staged/` 当成一个假的工作区(jar + jre + config + 空 `data/`),
用**随包那个 jre** 起起来:

```bash
cd <假工作区>
./jre/bin/java.exe --enable-native-access=ALL-UNNAMED -Xmx2g -jar app.jar \
    --server.port=50888 --spring.profiles.active=release
curl -s http://127.0.0.1:50888/api/env/check     # 13 张表全 OK、无红点
curl -s http://127.0.0.1:50888/api/modules       # 只有 song / shout
```

`data/*.sqlite` 会在工作目录下自己长出来(靠 `spring.sql.init` + `CREATE TABLE IF NOT EXISTS`)。

## 打包这一步的三个坑(2026-09-30 / 2026-10-01 实测)

- **工具包走镜像。** electron-builder 的 `winCodeSign` / `nsis` 都挂在 GitHub releases 上,
  直连在国内常连不上 —— 症状是卡在
  `downloading url=https://github.com/... size=5.6 MB` **反复重试**,每次都要等一轮 TCP 超时,
  看起来像卡死了。脚本默认把 `ELECTRON_BUILDER_BINARIES_MIRROR` 设成
  `https://npmmirror.com/mirrors/electron-builder-binaries/`(**值必须以 `/` 结尾**),
  想换就设同行名的环境变量,脚本不覆盖已有值。
- **解不出符号链接时会自动降级。** `winCodeSign-2.6.0.7z` 里带着 macOS 用的符号链接
  (`darwin/10.12/lib/*.dylib`),而 Windows 上创建符号链接要么是管理员、要么开了开发者模式;
  普通账户解压会报「客户端没有所需的特权」,然后**反复重下重试**。
  所以 `pack_portable()` 有两次尝试:常规一次,失败再带
  `-c.win.signAndEditExecutable=false` 重来一次。
  **代价比想象中小**:交付的 portable exe 的**图标与版本信息都是对的** —— 那些由 NSIS 自己写进去,
  不走 rcedit(2026-09-30 抽图标 + 读 `VersionInfo` 实测)。降级只让**中间产物**
  `dist/win-unpacked/呜啊娱乐工坊.exe` 保持 Electron 默认图标,**而它不交付**。
  (**别自己去改 portable exe**:拿缓存里的 rcedit 直接改会把 190MB 的 exe 截成 205KB,
  还返回 0 —— 同一个 rcedit 改 `win-unpacked/呜啊娱乐工坊.exe` 是好的,这是 NSIS 产物特有的一条。)
- **旧 jar 会做出「一打开就退」的 exe。** 建表脚本是**构建之前**才写进
  `src/main/resources/db/` 的,所以只有「先生成、后 mvn」那条顺序产出的 jar 才有它;
  而 `--no-build` 复用的是 `target/` 里现成那个,可能比脚本还旧。
  少了它的症状**全在运行期**:后端起不来,`sidecar.log` 里只有一句
  `No schema scripts found at location 'classpath:db/nya_entworks.sqlite.sql'`
  —— 既不出「旧 jar」也不出「少了个文件」。
  所以 `assert_jar_carries_sqlite_ddl(jar)` 成了**用哪个 jar 都要过的一道闸**
  (只读地看一眼 zip 目录里有没有那个条目),`--selftest` 用两个临时 zip 钉着「放行 / 拦下」。

<br>

# 发布到公开仓库

`python tools/publish.py`：把当前提交**排除掉只该留在本机的东西**之后，推成线上的 `main`。

```bash
python tools/publish.py --list       # 看会排除哪些、还剩多少个文件
python tools/publish.py --dry-run    # 生成提交但不推
python tools/publish.py              # 生成并推
python tools/publish.py --selftest   # 不碰仓库：排除判定与泄露扫描的用例
```

排除清单写在脚本顶部的 `EXCLUDE_DIRS` / `EXCLUDE_FILES` 里，当前是：`docs/`（整棵）、
`CLAUDE.md`、`data/ehentai-tags.csv`、`data/manga-tag-migration-map.csv` —— 都是本机笔记，
或是**本机归档标签的迁移映射**，不适合公开。

## 两条分支的关系

```
master（全量，永不推送） ──publish.py──> origin/main（过滤后的快照）
```

git 没有「推这条分支、但排除某些文件」的开关：推送的单位是**提交**，一个提交里有什么就公开
什么，**历史里删掉的文件照样读得到**（`git show <旧提交>:docs/xxx`）。所以反过来做 —— 本地在
含全部内容的提交上开发，线上那条分支的历史**由脚本从零生成、从不合并回来**。

三条配套纪律：

- **`master` 不要设 upstream。** 这样 `git push` 会直接拒绝（`has no upstream branch`），
  而不是顺手把全量分支推上去。发布入口只有这一个脚本。
- **本地不建 `main` 分支。** 推送用 `<提交>:refs/heads/main` 直接送 —— 免得手滑
  `git checkout main` 把 `docs/` 与 `CLAUDE.md` 从磁盘上抹掉（那棵树里没有它们）。
- **别绕开脚本推 `master`。** 一次就是一次全量公开，且收不回来。

## 三道闸门（任一不过就拒绝，绝不静默放行）

1. **工作区干净** —— 发布的是**提交**，不是磁盘上的半成品。
2. **过滤后的树里一个排除项都不剩。**
3. **过滤后的树里扫不出本机标识** —— 本机用户名（环境变量）、`git config user.email`、
   `C:\Users\<真名>` 形状的绝对路径。图案是**运行时算出来的**，脚本里不写死任何一个真值；
   占位符 `C:\Users\<用户名>` 放行。

## 三个踩过的坑

- **`git ls-tree` 必须带 `-z`。** 它默认把非 ASCII 路径转义成 `"docs/\\346\\241\\214..."`（带引号 +
  八进制），于是「以 `docs/` 开头」这条判定**全部落空** —— 中文名的文档会被静默漏掉，
  闸门看着通过、东西照样推上去。加 `-z` 才是原样输出。
- **控制台代码页 936 会把 `✓` 打崩。** 脚本里那对勾会
  `UnicodeEncodeError: 'gbk' codec can't encode character '✓'` —— 只在**某些终端**复现
  （Windows Terminal 是 UTF-8 就没事），换个地方跑就炸。已在开头加一句
  `sys.stdout.reconfigure(encoding="utf-8", errors="replace")`（`data/merge_panda.py` 的既有做法）。
  同一个坑的另一面：`启动.cmd` 必须保持纯 ASCII。
- **`mysql.py` 的待执行 SQL 落点在 `docs/sql/待执行/`**，公开版里没有这个目录。它写文件前是
  `mkdir(parents=True, exist_ok=True)`，所以缺目录不会炸；但公开版里那条「写待执行 SQL」的路
  等于没有落点（开发侧用不上）。

与 `release.py` 的分工：`release.py` 出的是**给别人的成品**（exe + 空库 + 清理过的配置），
`publish.py` 出的是**给你自己看的源码仓库**。两者互不相干，可以各自单独跑。

**一次性重建 git 的步骤（可选）** 在 `docs/已完成/公开仓库发布设计.md` §5 —— 那一步**不可逆**，
照着做之前先读完那一节。**它不解决「旧作者泄露」**（`commit-tree` 造出来的提交作者永远是当前
`git config user.*`，旧历史只在本地、推不出去），只让本地这条线也从头开始。

<br>

# 数据库直连入口

AI 操作 MySQL 的**唯一通道**。存在的理由：把「哪些 SQL 可以直接跑」做成机械护栏，
而不是靠每次自觉。裸调 `mysql.exe` 能绕过护栏，所以 `.claude/settings.local.json`
只放行本脚本、**不放行裸 mysql 调用** —— 那一道是权限弹窗。

## 快速开始

```bash
python tools/mysql.py --selftest                                  # 跑护栏用例，不连库
python tools/mysql.py --tables                                    # 列出所有表
python tools/mysql.py --desc song_group                           # 表结构
python tools/mysql.py -q "SELECT COUNT(*) FROM manga_data"        # 只读查询
python tools/mysql.py -e "INSERT INTO manga_tag (name) VALUES ('x')"
python tools/mysql.py -f src/main/resources/db/nya_entworks.sql   # 跑建表脚本
python tools/mysql.py --dump manga_tag                            # 备份单表
```

输出默认 tab 分隔带表头；`--tsv` 去掉表头，`--grid` 给人类看的对齐表格。

**建表脚本只有一份，整份都能 `-f` 跑**：`src/main/resources/db/nya_entworks.sql`
（20 张表，全 `CREATE TABLE IF NOT EXISTS`，**不含任何 `DROP` / `ALTER` / `UPDATE`**，可重复执行）。
历史包袱：以前按模块拆成 7 个 `*_schema.sql`，其中 3 个混着镜像表上的 `ALTER` 与 `UPDATE`，
被护栏**整份拒绝**、只能走裸客户端 —— 2026-09-15 合并时已把这些一次性动作剔掉，不再有这种文件。

**它是「当前结构」的快照，改结构就直接改里面的 `CREATE TABLE` 正文**（2026-09-23 起，旧规矩
「结构以后再变，别把 `ALTER` 追加回建表脚本」作废 —— 那条把「改正文」和「追加 ALTER」混为一谈了）。
**但不要往里追加 `ALTER`**：它对已存在的表 `IF NOT EXISTS` 是 no-op，追加了也不会生效，
还会破坏「整份都能 `-f` 跑」这条性质。下面三类**仍走**一次性通道 —— 写到
[`docs/sql/待执行/`](../docs/sql/README.md)，人工执行完**直接删掉**那个文件（不再归档）：

| 走一次性通道的 | 为什么 |
| --- | --- |
| 老库升级（给**已有**的表补列 / 改列型 / 加索引） | 重跑脚本补不上 —— `IF NOT EXISTS` 对已存在的表是 no-op |
| 一次性数据修正（回填 / `UPDATE`） | 只针对**当时那份库**，留着跑不了第二遍，也读不出当时为什么这么改 |
| 删列 / 删索引（任何 `DROP`） | 不可回滚，且护栏本来就拒 |

判据：**改的是「表长什么样」→ 改建表脚本；改的是「某台库里的数据和列的去留」→ 待执行通道。**

## 护栏

| 规则 | 行为 |
| --- | --- |
| `UPDATE` / `DELETE` / `DROP` / `TRUNCATE` / `REPLACE` / `RENAME` / `GRANT` / `REVOKE` / `LOAD` / `FLUSH` / `KILL` / `LOCK` / `UNLOCK` / `SHUTDOWN` / `RESET` / `PURGE`（共 16 个） | **拒绝**（退出码 3），提示写到 `docs/sql/待执行/` |
| `ALTER` 加列 / 改类型 / 加索引 | 放行 —— 改结构由 AI 直接做 |
| `ALTER` 里的 `DROP COLUMN` / `DROP INDEX` 等任何 DROP 子句 | **拒绝**：换层语法外壳而已，本质还是删 |
| `INSERT` / `ALTER` 的目标是**磁盘镜像表** | **拒绝** —— 库是磁盘的镜像，手写会被下次同步覆盖。副作用：建表脚本里凡有镜像表上的 `ALTER`（补列），整份文件都会跑不了，见上方 `-f` 那条 |
| `-q` 只读模式 | 只放行 `SELECT` / `SHOW` / `DESC` / `DESCRIBE` / `EXPLAIN` / `WITH` / `TABLE` / `VALUES` |
| `SET GLOBAL` / `CREATE USER` / `CREATE TRIGGER` 等 | **拒绝** —— 超出「建表 + 加数据」的范围 |

护栏方向是「宁可漏报、不能误报」：先剥掉注释与字符串字面量，所以字符串里出现 `delete`
不会误报，代价是关键字藏在字符串里也拦不住。漏报的兜底是下面的自动备份。

**写语句（INSERT / ALTER）执行前自动 `mysqldump` 目标表**到 `.local-backup/db/`，
dump 失败即中止、库不动。每表保留最近 20 份，该目录不进 git。

## 边界：哪些表能直接加数据

**能**：磁盘表达不出来的设置 / 词典类表 —— `manga_tag`、`manga_archive_name`、
`manga_dict_entry`、`song_original_setting`、`song_tag`、`song_lyric_fill`、
`shout_tag`（喊麦模块的设置表；喊麦的两张镜像表在下面那行）。

**不能**（脚本里 `MIRROR_TABLES` 硬拦，共 **11** 张）：`manga_data`、`manga_archive_unit`、
`manga_eh_scan`、`manga_tag_ref`、`song_group`、`song_file`、`shout_group`、`shout_file`、`async_task`，
外加两张**不是磁盘镜像、但由语料重扫任务整表删掉重建**的：`lyric_corpus_line`、`lyric_corpus_pair`
（手写必被下次重扫冲掉，故按镜像口径挡）。这些要改就改磁盘 / 重跑任务。

## 凭据

从 `src/main/resources/application.yaml` 的 `datasource:` 段读，不在这儿存第二份。
密码走临时 cnf 文件传给客户端，不进命令行、不进进程列表。
客户端路径默认 `C:\Program Files\MySQL\MySQL Server 8.0\bin`，可用环境变量 `MYSQL_BIN_DIR` 覆盖；
连接信息可用 `NYA_DB_HOST` / `NYA_DB_PORT` / `NYA_DB_NAME` / `NYA_DB_USER` /
`NYA_DB_PASSWORD` 覆盖。

## 改护栏之后

改的是 `guard()` 或 `MIRROR_TABLES`，**先跑 `--selftest`**。那 34 条用例是护栏本身的回归，
新增拒绝规则时同步加用例。

<br>

# NCM 解密工具

把网易云音乐加密的 `.ncm` 文件离线还原为原始音频(flac / mp3),并直接写出文件、自动内嵌标签(歌名 / 歌手 / 专辑 / 封面)。
改自 https://github.com/magic-akari/ncmc
<p><b>零依赖</b>——纯 Python 标准库实现,连 pycryptodome 都没用,拷到任意一台带 Python 3 的机器即可运行。

<br>

## 前置

- Python 3.10+(只用标准库,无需 `pip install`)

## 快速开始

```bash
# 查看用法
python ncm_decrypt.py --help

# 干跑:只打印将改哪些文件,不写盘(默认就是 dry-run,安全)
python ncm_decrypt.py -batch "F:\歌曲\ncm"

# 真解:递归整目录,解密 + 内嵌标签 + 写盘到指定输出目录
python ncm_decrypt.py -batch "F:\歌曲\ncm" -a -o "F:\output"

# 单文件 / 通配符
python ncm_decrypt.py "F:\song.ncm" -a
python ncm_decrypt.py "F:\歌曲\*.ncm" -a

# 额外导出独立封面为 <歌名>.jpg
python ncm_decrypt.py -batch "F:\歌曲\ncm" -a --cover

# 只输出裸音频,不内嵌标签
python ncm_decrypt.py -batch "F:\歌曲\ncm" -a --no-tag
```

<br>

## 参数

| 参数 | 作用 |
|---|---|
| `inputs` | 单个 `.ncm` 文件或通配符(如 `*.ncm`) |
| `-batch <目录>` | 递归处理整个目录下的所有 `.ncm`;与 `inputs` 二选一 |
| `-a` / `--apply` | 真正写盘。**默认关闭,只打印不落盘** |
| `-o <目录>` | 输出目录。默认写回源文件所在目录 |
| `--cover` | 额外导出一次独立封面 jpg(除内嵌外) |
| `--no-tag` | 不内嵌标签,只输出裸音频 |

<br>

## 说明

- **标签内嵌**默认开启:mp3 写 ID3v2.3,flac 写 Vorbis Comment + 封面图。未经清空、歌词等其它字段不写。
- **内嵌封面始终保留**(即使加了 `--no-tag`,封面也已内嵌在音频里;`--cover` 只是再单独导出一份)。
- **输出文件名默认沿用输入文件名**(去掉 `.ncm` 及盘上多带的媒体扩展名,如 `song.mp3.ncm` → `song`);只有当输入名是无意义的 uid(纯数字 / 32 位 hex)时才回退为元数据拼的 `歌名 - 歌手`。简体/繁体中文名都能正常显示。
- 原 NCM 文件不会被删除、不会修改,只是读取。

<br>

## 原理(简要)

`.ncm` 文件结构:AES 加密的 key + AES 加密的 JSON 元数据 + RC4 一次性异或的音频流。

1. 校验文件头 `CTENFDAM`;
2. key 段:逐字节 `^0x64` → AES-128-ECB 解密 → 取前 17 字节后的部分作为 RC4 种子;
3. 用种子做 RC4 KSA + PRGA,得到 256 字节查表;
4. 元数据段:逐字节 `^0x63` → base64 解码 → AES-128-ECB 解密 → 丢掉前 6 字节(`music:`)得 JSON;
5. 跳过固定头(内嵌封面),剩余音频段逐字节 `^查表` 即得原音频。

对该格式的算法实现与开源项目 [magic-akari/ncmc](https://github.com/magic-akari/ncmc)(Rust)逐项对照一致。

<br>

## 兼容性

- 输出容器:`flac` / `mp3` 会内嵌标签;**`ogg` / `m4a` 结构不同暂不内嵌**,只输出裸音频(控制台会提示)。
- 下载的 `.ncm` 多数为 flac / mp3,覆盖绝大多数场景。

<br>

## 用途声明

仅用于把你自己合法下载 / 持有的网易云 `.ncm` 文件转换为本地可播放格式。请遵守当地法律法规与平台服务条款,勿用于侵权或商业用途。
