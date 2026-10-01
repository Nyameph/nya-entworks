/**
 * 模块注册表。左侧导航由这个数组生成，新增模块只在这里加一项，不动框架代码
 * （设计概述提到「后续可能添加更多分区」）。
 *
 * render(host) 往 host 里画内容；缺 render 的项渲染成占位页。
 *
 * <b>顶层项里 manga / song / shout 那三个 id 不是随便起的</b>：后端 {@code /api/modules}
 * 按同一套 id 下发模块开关，app.js 拿它把关掉的模块整组收起来（连 hash 直接进去也只给
 * 一句「已关闭」）。改这里的 id 而没改后端，症状是**开关关掉后导航照旧挂着** ——
 * 点进去每个请求 404。tasks / settings / guide 不是业务模块，不在那份开关里，故永远显示。
 *
 * <b>needs：这一页跑起来非有不可的磁盘路径</b>。写了 needs 的页面，在那一项缺着的时候
 * 会被一张遮罩盖住（「先去配置页补上」，见 app.js 的 gateOverlay）—— 因为缺了那个根，
 * 页面扫出来的空列表与「这里确实没有内容」<b>长得一模一样</b>，不遮就等于没提示。
 * 两种写法：
 * <ul>
 *   <li>字符串：这一项必须有；</li>
 *   <li>数组：这几个是<b>并列的来源</b>，<b>全缺</b>才算缺（有一个在就行）。</li>
 * </ul>
 * 键一律写<b>全键</b>（与配置页的 data-key 同一个串，见 docs/配置页设计.md §3）；
 * 只判磁盘目录根 —— 功能依赖（NConvert / AI 端点 / 本地 eh 库）缺了是「降级但能用」，
 * 归配置页顶部的提示条管，不在这一层。<b>路径来自库行或表的页面不写</b>
 * （合并冲突 / 标签读的是 manga_archive_unit.folder_path，配置里的归档根缺了照样能用）。
 * 改这份清单后跑 {@code PageGatesTest}：它逐页比对，写歪一个键名会导致那一页永远不遮。
 */
const Modules = [
    {
        // 模块 id，与 /api/modules 的键、配置页大标题同一套
        id: 'manga',
        title: '漫画',
        desc: '本地漫画文件夹名解析与归档。',
        children: [
            {
                id: 'manga-new', title: '新漫画',
                desc: '#待看 / #待看合集 下的新漫画：评分、规范化命名、压缩、归档。'
                    + '这一层不入库，每次现扫；存储那一刻才入库。',
                needs: ['nya-entworks.manga.new-dir', 'nya-entworks.manga.collection-dir'],
                render: (host) => MangaNewPage.render(host)
            },
            {
                id: 'manga-unarchived', title: '未归档',
                desc: '未归档根（见左下角「系统配置」）下已入库的漫画：分页浏览、搜索、重新扫描（含自动归档）、'
                    + '单本改名/评分/删除/重新归档。数据从 manga_data 读，磁盘是权威。',
                needs: ['nya-entworks.manga.unarchived-dir'],
                render: (host) => MangaUnarchivedPage.render(host)
            },
            {
                id: 'manga-archive', title: '归档作者',
                desc: '归档根（见左下角「系统配置」）下的归档目录：从磁盘同步、查看标签、'
                    + '处理重名冲突与失踪目录。',
                needs: ['nya-entworks.manga.archive-dir'],
                render: (host, params) => MangaArchivePage.render(host, params)
            },
            {
                // 看着像依赖归档根，其实不依赖：路径取自 manga_archive_unit.folder_path
                // 这个库里的行，配置里那个归档根缺了照样能比对、能改名。别照直觉补 needs
                id: 'manga-conflict', title: '合并冲突',
                desc: '重名冲突的并排比对：左右两侧漫画现扫列出，勾选跨目录移动，清空一侧后删空目录完成合并。',
                render: (host) => MangaConflictPage.render(host)
            },
            {
                id: 'manga-dict', title: '词典',
                desc: '展会/原作/杂志/汉化组等八类词条的补录与诊断。保存即生效，不必重启。',
                render: (host) => MangaDictPage.render(host)
            },
            {
                // 同「合并冲突」：路径来自库行，不看配置里的归档根
                id: 'manga-tag', title: '标签',
                desc: '跨实体通用标签的重命名、合并、清理未使用项。改标签会连带改归档目录名。',
                render: (host) => MangaTagPage.render(host)
            },
            {
                id: 'manga-random', title: '随机抽选',
                desc: '已归档 / 未归档漫画按分数加权随机抽 50 部，高分比例更高，优先抽没有独立标签的。',
                render: (host) => MangaRandomPage.render(host)
            }
        ]
    },
    {
        // 模块 id，同上
        id: 'song',
        title: '填词歌曲',
        desc: '本地填词歌曲的播放、打分、命名规范化。',
        children: [
            {
                id: 'song-score', title: '未归档',
                desc: '#已压缩歌曲\\歌曲 下待打分的歌，列表形式。打分就是把整组文件搬进对应的'
                    + '评分分区目录。这一层不入库，每次现扫。',
                // 打分＝在这两个根之间搬文件，缺哪个都不成立
                needs: ['nya-entworks.song.song-dir', 'nya-entworks.song.staging-dir'],
                render: (host, params) => SongScorePage.render(host, params)
            },
            {
                id: 'song-list', title: '歌曲',
                desc: '成品-歌曲 下已归档的填词歌曲：同作者+曲名+原曲的不同版本合并成一行，'
                    + '播放时切换。筛选、改评分、规范化命名、删除、随机播放。',
                // 已归档 + 待打分合并成一行 ×N 版本，两边的根都要扫
                needs: ['nya-entworks.song.song-dir', 'nya-entworks.song.staging-dir'],
                render: (host, params) => SongPage.render(host, params)
            },
            {
                id: 'song-stat', title: '原曲',
                desc: '同一首原曲被填了几个版本、都打了几分（已归档与待打分分开计数）。'
                    + '也是配「原曲名默认倍速」的地方。',
                // 前两个根算「已归档 / 待打分各几版」的计数；后两个是并列的原曲来源
                // （svp 在任一个里找到即可，见 SongTemplateService#sourceRoots），
                // 写成数组＝全缺才算缺 —— 当 AND 的话这一页会永远挂着遮罩
                needs: ['nya-entworks.song.song-dir', 'nya-entworks.song.staging-dir',
                    ['nya-entworks.song.template-dir', 'nya-entworks.song.only-original-dir']],
                render: (host, params) => SongStatPage.render(host, params)
            },
            {
                id: 'song-fill', title: '填词',
                desc: '一首原曲可以有多份填词：先看到项目列表，点每行末的「编辑」打开那一份 → '
                    + '分句 → 上排原词、下排逐格填新词 → 导出回填模板文本（回 SynthV）或 lrc。'
                    + '这里列出全部填词；从原曲页每行模板列的「svp」标记进来则只看那一首，'
                    + '且能「新建填词」（新建要先有 svp）。',
                // 「新建填词」与「重新解析」要去这两个根找 svp —— 并列来源，全缺才算缺。
                // 已固化的填词打开/编辑/保存/导出全读库、与磁盘无关，但按「核心入口不能用了
                // 就整页遮」的口径，这一页也遮；嫌太严就把这一行整个删掉，其余不受影响
                needs: [['nya-entworks.song.template-dir', 'nya-entworks.song.only-original-dir']],
                render: (host, params) => SongFillPage.render(host, params)
            },
            {
                id: 'song-rhyme', title: '韵脚词典',
                desc: '按韵部 / 韵母查字与词，反查某个字押什么韵；粘贴导入押韵词表，'
                    + '人工补录与语料回填的词条都在这里。',
                render: (host) => SongRhymePage.render(host)
            },
            {
                id: 'song-artist', title: '作者',
                desc: '按作者统计产出：作者一首原曲填了几个版本、都打了几分。'
                    + '多作者歌每位作者各算一次。',
                // 与原曲页同源：扫已归档 / 待打分两边算计数
                needs: ['nya-entworks.song.song-dir', 'nya-entworks.song.staging-dir'],
                render: (host, params) => SongArtistPage.render(host, params)
            }
        ]
    },
    {
        // 模块 id，同上
        id: 'shout',
        title: '喊麦',
        desc: '本地喊麦的播放、打分与标签。',
        children: [
            {
                id: 'shout-staging', title: '未归档',
                desc: '#已压缩歌曲\\喊麦 下待打分的喊麦，列表形式。打分就是把整组文件搬进'
                    + '对应的评分分区目录；标签可选，归档前是否强制打标签见左下角「系统配置」'
                    + '（喊麦 · 归档）。这一层不入库，每次现扫。',
                needs: ['nya-entworks.shout.archived-dir', 'nya-entworks.shout.staging-dir'],
                render: (host) => ShoutScorePage.render(host)
            },
            {
                // 标题与歌曲模块那一页对称：歌曲模块的列表页叫「歌曲」，这里就叫「喊麦」
                // （2026-09-29 起，原先叫「已归档」）
                id: 'shout-list', title: '喊麦',
                desc: '成品-喊麦 下已归档的喊麦。文件名没有规律所以不解析，也不归并 —— '
                    + '一个文件组就是一行。列表每次现扫磁盘，库里另有一份归档镜像'
                    + '（存默认倍速、判失踪，列表不读它）。',
                needs: ['nya-entworks.shout.archived-dir', 'nya-entworks.shout.staging-dir'],
                render: (host) => ShoutListPage.render(host)
            }
        ]
    },
    {
        // 顶层项（不属任何业务模块）：跨三个模块的磁盘改动流水。
        // foot: true —— 钉到左下角「系统工具」那一栏（与「任务记录」同列，2026-09-23 用户定），
        // 不占主导航；renderNav 对 foot 项本来就有现成的渲染分支。
        // icon 是那一栏每个入口前头的单色小图示（≡ 一行一行的流水），见 app.js renderNav。
        // 刻意不作数组第一项（defaultId 会把第一个有 render 的顶层项当默认落地页），
        // 也不进 /api/modules（它不是「可被裁掉的业务模块」—— 全裁掉也要能看）
        id: 'file-ops',
        title: '文件记录',
        icon: '≡',
        desc: '三个模块的磁盘改动流水：谁在什么时候把哪个路径改成了哪个路径。'
            + '只记不判，记录本身不会反过来改任何东西。',
        foot: true,
        render: (host) => FileOpLogPage.render(host)
    },
    {
        id: 'tasks',
        title: '任务记录',
        icon: '↻',
        desc: '各模块异步任务的执行状态、进度与日志。中断/失败的任务可重新执行。',
        // 不跟业务模块一起排在上方，而是钉到侧栏左下角（「系统工具」那一栏），见 app.js renderNav
        foot: true,
        render: (host) => TaskPage.render(host)
    },
    {
        id: 'settings',
        title: '系统配置',
        desc: '本机路径与常用开关。写进 config/nya-entworks.yaml（覆盖层），'
            + '改完要重启后端才生效 —— 密钥不在这一页。',
        // 有路由、没导航项：入口是左下角「系统工具」里的齿轮按钮，见 app.js renderNav 与 config-btn
        hidden: true,
        render: (host) => SettingsPage.render(host)
    },
    {
        id: 'guide',
        title: '使用说明',
        desc: '每个页面怎么用：界面截图 + 标注 + 逐步说明，第一次用先看这里。'
            + '这一页跟着模块走 —— 关着的模块在章首挂一条提醒，被裁掉的模块整章不出现。',
        // 与 settings 同理：有路由、没导航项，入口是左下角「系统工具」里的「使用说明」按钮（见 app.js）。
        // 这一页**一个字都不许写 needs**：它不扫任何磁盘根，而且 PageGatesTest 的
        // needs_exactlyTheExpectedPages 逐页盯着「有 needs 的页面恰好是那十页」，
        // 多写一页就红（原因见 app.js 的 gateMisses —— 说明页遮上就是死锁：
        // 用户正是要看说明去补配置）。说明页的章节结构由它自己从 Modules 推，见 guide.js
        hidden: true,
        render: (host, params) => GuidePage.render(host, params)
    }
];

/** 把注册表拍平成 id → 页面配置，供路由查表 */
const ModuleIndex = (() => {
    const index = {};
    for (const mod of Modules) {
        index[mod.id] = mod;
        for (const child of mod.children || []) {
            index[child.id] = child;
        }
    }
    return index;
})();
