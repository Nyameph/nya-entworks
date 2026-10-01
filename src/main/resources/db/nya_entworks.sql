-- ============================================================================
-- nya_entworks —— 全量建表脚本（本仓库**唯一**的一份）
--
-- 2026-09-15 由本机库整份导出后整理、2026-09-23 补 file_op_log：共 20 张表，
-- 全部 CREATE TABLE IF NOT EXISTS，可重复执行。**不含任何 DROP / ALTER / UPDATE**。
--
-- 【怎么用】全新安装 / 重建库：
--   1. 先建库：
--        CREATE DATABASE nya_entworks
--          DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
--   2. 跑本文件。整份没有镜像表 ALTER、也没有 UPDATE，tools/mysql.py 的护栏放行：
--        python tools/mysql.py -f src/main/resources/db/nya_entworks.sql
--      也可以走裸客户端，命令见 docs/sql/README.md。
--   3. 库里建好之后还缺「数据」的那几步（e-hentai 标签 CSV 导入等）见 CLAUDE.md
--      的「启动前提」，界面上「环境自检」缺什么就报什么。
--
-- 【表结构变了怎么办】（2026-09-23 起，旧规矩「别追回本文件」作废）
--   **本文件是「当前结构」的快照 —— 结构变了就直接改下面 CREATE TABLE 的正文。**
--   但**不往本文件追加 ALTER**：它是给全新安装用的，对已存在的表 IF NOT EXISTS 是 no-op，
--   追加 ALTER 既不会生效、又破坏了「整份都能 -f 跑」这条性质。
--   这些仍走 docs/sql/待执行/YYYY-MM-DD_用途.sql（AI 写、人执行、执行完直接删）：
--     ① 老库升级 —— 已经建好的库重跑本文件**补不上列**，只能靠 ALTER；
--     ② 一次性数据修正（回填 / UPDATE）、删列删索引（DROP）。
--   判据：**改的是「表长什么样」→ 改本文件；改的是「某台库里的数据和列的去留」→ 待执行通道。**
--
-- 【镜像表】内容由磁盘扫描重建、手写行会被下次同步覆盖，**只改结构、不改行**：
--   manga_data / manga_archive_unit / manga_eh_scan / manga_tag_ref /
--   song_group / song_file / shout_group / shout_file / async_task /
--   lyric_corpus_line / lyric_corpus_pair —— 共 11 张，
--   以 tools/mysql.py 的 MIRROR_TABLES 为准（那里是权威定义）。
-- ============================================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- 持久化异步任务。
--
-- 耗时的写操作（NConvert 压缩、批量搬目录、归档同步、e-hentai 网络扫描）脱离请求线程执行：
-- 提交返回任务 id，前端轮询 /api/tasks/{id}。
--
-- 这张表推翻了 docs/实现说明.md 原 2.4 节「用内存任务而不是落库任务表」的结论。改的理由：
--   1. 任务历史本身有价值 —— 哪些目录被改过名、哪一本压缩失败过，跑完还要能翻回去核对；
--   2. 分钟级的网络任务（eh-scan 带限流 + AI 推理）不该一直占着请求线程；
--   3. 参数落库后「重新执行」才成立 —— 进程被杀掉的任务，重启后还能原样再跑一遍。
--
-- 进程重启时残留的 PENDING / RUNNING 一律由 AsyncTaskRecoveryRunner 置成 INTERRUPTED，
-- **不自动重跑**：这些任务大多在动磁盘，被杀时停在哪一步无从得知，自动重跑可能在半成品
-- 状态上二次搬运。要不要重来由人在任务页上决定，同「有一条 blockedReason 就整批不执行」。
--
-- 运行中的进度活在内存里、按节流间隔刷库（见 AsyncTaskService），所以库里的 progress_done
-- 最多滞后一个刷新周期 —— 丢的只是进度数字，工作本身的结果在磁盘和库上。

-- ----------------------------
-- Table structure for async_task
-- ----------------------------
-- DROP TABLE IF EXISTS `async_task`;
CREATE TABLE IF NOT EXISTS `async_task`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `task_type` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '任务类型（AsyncTaskHandler.type()），重新执行时靠它找回 handler',
  `module` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '归属模块 manga / song / account，决定进哪个执行队列',
  `task_name` varchar(300) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '展示用任务名，如「存储 3 本新漫画」',
  `status` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / RUNNING / DONE / FAILED / INTERRUPTED',
  `params_json` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '任务参数 JSON，重新执行时反序列化回 handler.paramType()；无参任务为 NULL',
  `progress_done` int NOT NULL DEFAULT 0 COMMENT '已完成步数',
  `progress_total` int NOT NULL DEFAULT 0 COMMENT '总步数，未知时为 0（如压缩前还没数过文件）',
  `message` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '当前在做什么，直接显示给人看',
  `error` varchar(2000) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT 'FAILED / INTERRUPTED 时的原因',
  `result_json` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT 'DONE 时的结果 JSON，前端读它接着走后续流程（如压缩失败的逐本确认）',
  `logs` mediumtext CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '逐步明细，换行分隔。失败的文件、跳过的漫画都在这里，跑完能逐条核对',
  `source` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'SUBMIT' COMMENT 'SUBMIT 正常提交 / RERUN 页面上点的重新执行',
  `rerun_from_id` bigint NULL DEFAULT NULL COMMENT '重新执行自哪个任务 id。不复用原行，原任务的日志与失败明细要留着对照',
  `start_time` datetime NULL DEFAULT NULL COMMENT '开始执行时间，PENDING 排队期间为空',
  `end_time` datetime NULL DEFAULT NULL COMMENT '进入终态的时间',
  `create_time` datetime NULL DEFAULT NULL,
  `update_time` datetime NULL DEFAULT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_status`(`status` ASC) USING BTREE,
  INDEX `idx_module_status`(`module` ASC, `status` ASC) USING BTREE,
  INDEX `idx_create_time`(`create_time` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1271 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '持久化异步任务' ROW_FORMAT = DYNAMIC;

-- 文件与目录的改动流水：谁（页面 / 异步任务 / 运维脚本）在什么时间，把哪个路径改成了哪个路径。
--
-- 【它不是镜像表】不进 tools/mysql.py 的 MIRROR_TABLES：磁盘扫描从不碰它，也没有任何
--   「同步」会来覆盖它。正相反 —— 本表**只有应用能写**，而且只追加（append-only）。
--
-- 【一条记录 = 一个被改动的路径】同一次用户动作（如「批量改名 300 首」）产生的多条记录
--   共用一个 batch_id，页面上靠它串起来。漫画「整体移动一个作者文件夹」在代码里本来就是
--   一次 Files.move，所以天然只有一条，不需要任何按目录聚合的逻辑。
--
-- 【为什么写失败也不能影响功能】它是附属品：写入整段 try/catch + log.warn，绝不往上抛。
--   文件已经搬完了还抛异常，会让调用方（和页面）以为失败、用户可能重跑一遍 —— 那才是真的坏事。
--   同理**绝不能与主操作同事务**：磁盘先动、库后动，事务回滚会把记录一起滚掉，
--   而磁盘已经改了 —— 那正是最需要这条记录的时候。
--
-- 【记不上怎么办】库里没有这张表时症状是「操作记录页空」，不是功能坏 —— 所以表名也加进了
--   EnvCheckService 的逐张检查清单，缺表会在左下角环境自检里报红。
--
-- 口径全文（记录点挂哪一层、记什么不记什么）见 docs/已完成/文件操作记录设计.md。

-- ----------------------------
-- Table structure for file_op_log
-- ----------------------------
-- DROP TABLE IF EXISTS `file_op_log`;
CREATE TABLE IF NOT EXISTS `file_op_log`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `batch_id` varchar(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '批次号（UUID）：同一次动作的多条记录共用一个',
  `module` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT 'MANGA / SONG / SHOUT',
  `op_type` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT 'MOVE / DELETE / WRITE',
  `unit_level` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT 'AUTHOR 作者目录 / COLLECTION 合集目录 / SINGLE 单本漫画 / GROUP 歌曲或喊麦的一组 / OTHER 其它',
  `action` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '人话的原因，如「改评分」「添加歌曲：迁移到未归档」，直接显示给人看',
  `source` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT 'PAGE 页面 / TASK 异步任务 / SCRIPT 运维脚本',
  `task_id` bigint NULL DEFAULT NULL COMMENT '异步任务 id，source = TASK 时填',
  `from_path` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '改前全路径；新建类操作（WRITE）为 NULL',
  `to_path` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '改后全路径；删除类操作（DELETE）为 NULL',
  `result` varchar(8) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'OK' COMMENT 'OK / FAILED',
  `detail` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '失败原因或备注',
  `op_time` datetime NOT NULL COMMENT '操作时间 LocalDateTime.now()。本表刻意不加 create_time / update_time —— 流水只追加，不需要第二个时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_op_time`(`op_time` ASC) USING BTREE,
  INDEX `idx_module_time`(`module` ASC, `op_time` ASC) USING BTREE,
  INDEX `idx_from_path`(`from_path` ASC) USING BTREE,
  INDEX `idx_batch`(`batch_id` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '文件与目录的改动流水（append-only，非镜像表）' ROW_FORMAT = DYNAMIC;

-- 语料句：从归档歌曲的歌词里逐句采出来的库，供 AI 填词做 few-shot。
-- 重扫时整批重建（镜像表，见文件头），不要手写。

-- ----------------------------
-- Table structure for lyric_corpus_line
-- ----------------------------
-- DROP TABLE IF EXISTS `lyric_corpus_line`;
CREATE TABLE IF NOT EXISTS `lyric_corpus_line`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `group_id` bigint NOT NULL COMMENT '= song_group.id = song_file.song_id（注意不是 group_id）；kind=SHOUT 时为合成负数（喊麦走磁盘现扫，无库 id，见 group_key）',
  `kind` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'SONG' COMMENT 'SONG 填词歌曲 / SHOUT 喊麦。few-shot 只读 SONG',
  `group_key` varchar(600) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '喊麦组的稳定标识（分区|主名）；kind=SONG 时为 NULL',
  `file_name` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '来源歌词文件名（一组可能有多个版本，各采一份）',
  `original_title` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '原曲名快照（song_group.original_title）',
  `score` int NOT NULL COMMENT '采集时的组评分快照（few-shot 加权随机的权重）',
  `line_index` int NOT NULL COMMENT '清洗后的行序，0 起',
  `text` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '清洗后的整句',
  `tail_char` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '句尾汉字；英文句尾为空',
  `tail_pinyin` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '句尾字带调读音（多音取最常用那个）',
  `finals` varchar(60) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '句尾字全部读音的韵母，逗号分隔',
  `yun18` varchar(60) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '同上，十八韵名',
  `create_time` datetime NULL DEFAULT NULL,
  `update_time` datetime NULL DEFAULT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_line`(`group_id` ASC, `file_name` ASC, `line_index` ASC) USING BTREE,
  INDEX `idx_kind_score`(`kind` ASC, `score` ASC) USING BTREE,
  INDEX `idx_yun18`(`yun18`(20) ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 66707 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '语料：规范化后的歌词句' ROW_FORMAT = Dynamic;

-- 原词→新词配对：同一 group_id 下原歌词句与已定稿新词的对应关系，AI 填词的 few-shot 取自它。
-- 保存钩子写入，重扫时整批重建（镜像表）。

-- ----------------------------
-- Table structure for lyric_corpus_pair
-- ----------------------------
-- DROP TABLE IF EXISTS `lyric_corpus_pair`;
CREATE TABLE IF NOT EXISTS `lyric_corpus_pair`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `fill_id` bigint NOT NULL COMMENT 'song_lyric_fill.id',
  `line_index` int NOT NULL COMMENT '句子下标（与 song_lyric_fill.lines_json 同序）',
  `original_text` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '原词句',
  `filled_text` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '新词句（整句填满才收）',
  `yun18` varchar(60) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '新词句尾韵部',
  `create_time` datetime NULL DEFAULT NULL,
  `update_time` datetime NULL DEFAULT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_pair`(`fill_id` ASC, `line_index` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 5 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '语料：原词 → 新词配对（AI few-shot 用）' ROW_FORMAT = Dynamic;

-- 归档作者/社团与通用标签。
--
-- 权威方向是文件系统：F:\MangaGroup 下的目录名是数据源，本库是可查询的镜像，
-- 同步入口见 MangaArchiveService#sync。
--
-- 社团名/作者名别名，归档匹配的查找表。**刻意不在 name 上加唯一键**：重名正是要留存的
-- 信息（原先只 println「😭 群组或作者重复」）。冲突检出：
--   SELECT name FROM manga_archive_name GROUP BY name HAVING COUNT(DISTINCT unit_id) > 1
--
-- name_source 是归档编辑表单引入的：允许「增加关联作者但不改目录名」，那种别名在目录名里
-- 没有，若仍随目录名整组重建就会在下次 sync 时被静默删掉。

-- ----------------------------
-- Table structure for manga_archive_name
-- ----------------------------
-- DROP TABLE IF EXISTS `manga_archive_name`;
CREATE TABLE IF NOT EXISTS `manga_archive_name`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `unit_id` bigint NOT NULL,
  `name` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '归一化比较键：NFC + trim + 大写',
  `raw_name` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '原文',
  `name_type` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT 'GROUP / ARTIST',
  `create_time` datetime NULL DEFAULT NULL,
  `name_source` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'FOLDER',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_unit_name_type`(`unit_id` ASC, `name` ASC, `name_type` ASC) USING BTREE,
  INDEX `idx_name`(`name` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 16236 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '归档社团名/作者名别名' ROW_FORMAT = DYNAMIC;

-- 一个归档目录，即 F:\MangaGroup\<评分分区>\[社团 (作者)]【标签…】。
-- 磁盘镜像，由 MangaArchiveService#sync 重建。

-- ----------------------------
-- Table structure for manga_archive_unit
-- ----------------------------
-- DROP TABLE IF EXISTS `manga_archive_unit`;
CREATE TABLE IF NOT EXISTS `manga_archive_unit`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `folder_path` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '归档目录全路径',
  `folder_name` varchar(300) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '目录名原文',
  `root_path` varchar(300) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '所属归档根，如 F:\\MangaGroup\\9-百读不厌',
  `score` int NOT NULL COMMENT '评分，由 root_path 推导：3/5/7/9',
  `group_name` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '社团名原文，[作者] 形态时为空',
  `artist_names` varchar(300) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '作者名原文，多作者含 、 分隔符',
  `status` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / MISSING',
  `create_time` datetime NULL DEFAULT NULL,
  `update_time` datetime NULL DEFAULT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_folder_path`(`folder_path` ASC) USING BTREE,
  INDEX `idx_root_status`(`root_path` ASC, `status` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 546 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '漫画归档目录' ROW_FORMAT = DYNAMIC;

-- 漫画本身。
--
-- 与归档目录（manga_archive_unit）的分工：那张表是「作者层」，这张是「单本层」。
-- 权威方向同样是文件系统 —— folder_path 是键，目录改了名就重新扫描认回来。
--
-- 只有「已归档」「未归档」两种状态的漫画才在这张表里。新漫画（#待看 / #待看合集 下的）
-- **刻意不入库**：它们每次都现扫，因为在这个阶段目录名、评分、标签都还在变，存一份就要
-- 维护一致性，而扫一次只要几秒。落库发生在「存储」那一刻，见 MangaNewService#store。
--
-- archive_unit_id 由归档目录那侧的同步维护：归档目录合并时
-- （MangaArchiveService#mergeUnit）它从被并掉的 unit 改指向承载 unit，
-- 否则漫画会挂在已删除的 id 上。

-- ----------------------------
-- Table structure for manga_data
-- ----------------------------
-- DROP TABLE IF EXISTS `manga_data`;
CREATE TABLE IF NOT EXISTS `manga_data`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `folder_path` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '婕?敾鐩?綍鍏ㄨ矾寰勶紝鎵?弿鏃剁殑鍖归厤閿',
  `cover_file` varchar(300) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '灏侀潰鏂囦欢鍚嶏紝鍙栫洰褰曚笅棣栧紶鍥',
  `exhibit` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '展会',
  `group_name` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '绀惧洟',
  `artist` varchar(300) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '浣滆?锛屽?浣滆?鍚?銆?鍒嗛殧绗',
  `date_tag` varchar(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '鏃堕棿鏍囩?锛堣?鍒?锛屽? 2024.05锛',
  `title` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '鏍囬?',
  `parody` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '同人作品',
  `magazine` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '杂志',
  `matched_rule` int NOT NULL DEFAULT 0 COMMENT '鍛戒腑鐨勫懡鍚嶈?鍒欙紝0 琛ㄧず鏈?尮閰',
  `remark` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `create_time` datetime NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `archive_unit_id` bigint NULL DEFAULT NULL COMMENT '鎵?睘褰掓。鐩?綍 id',
  `file_count` int NULL DEFAULT NULL COMMENT '目录下文件数（含子目录），扫描时算好落库',
  `image_count` int NULL DEFAULT NULL COMMENT '其中的图片数',
  `score` int NULL DEFAULT NULL COMMENT '璇勫垎 3/5/7/9',
  `score_source` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT 'SELF / INHERIT_ARCHIVE',
  `status` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT 'ARCHIVED / UNARCHIVED / MISSING',
  `file_type` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '文件形态：FOLDER 目录 / CBZ 压缩包',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_folder_path`(`folder_path` ASC) USING BTREE,
  INDEX `idx_archive_unit`(`archive_unit_id` ASC) USING BTREE,
  INDEX `idx_status`(`status` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 15774 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '漫画资源表' ROW_FORMAT = DYNAMIC;

-- 漫画名称解析词典。一张表，逐条生效。
--
-- dict_type 只表示用途，匹配语义全靠每条自己的 match_mode + ignore_case：
--   dict_type  = MangaDictType 的 8 个值，本列只做「这批词是干什么用的」的分类：
--                USELESS_TAG / UNCENSORED / UNBOXING / MODIFIER /
--                PARODY / EXHIBIT / MAGAZINE / IGNORE_CONTENT
--   match_mode = MangaDictMatchMode 的 5 个值：EXACT / PREFIX / SUFFIX / CONTAINS / REGEX
--                （为空时按 EXACT 处理，见 MangaDictEntry#effectiveMatchMode）
--   ignore_case = 实体侧缺省视为 true（MangaDictEntry#effectiveIgnoreCase：只有显式 false
--                才不忽略大小写）。**但本列是 NOT NULL DEFAULT 0** —— 裸 INSERT 又不填这列时
--                落进来的是 0（= 区分大小写），与实体缺省相反。手工加词条时留意。
--
-- 词条是数据不是代码：新增词条走页面（MangaDictController）或直接 INSERT，不再改 Java。
--
-- 唯一键 uk_type_value 带前缀长度（dict_value 只取前 191 字符）：dict_value 是
-- VARCHAR(1000)，整列进索引会超出 InnoDB 3072 字节的键长上限（utf8mb4 下 1000 字符
-- = 4000 字节）。191 × 4 = 764 字节，留足余量。副作用是「前 191 字符相同的两条同类型
-- 词条」会被判重 —— 实际词条都是短词，够用。

-- ----------------------------
-- Table structure for manga_dict_entry
-- ----------------------------
-- DROP TABLE IF EXISTS `manga_dict_entry`;
CREATE TABLE IF NOT EXISTS `manga_dict_entry`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `dict_type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT 'PARODY/EXHIBIT/HANHUA_SUFFIX/...',
  `match_mode` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT 'EXACT/PREFIX/SUFFIX/CONTAINS/REGEX',
  `ignore_case` tinyint(1) NOT NULL DEFAULT 0,
  `dict_value` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `remark` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '来源文件夹等备注',
  `create_time` datetime NULL DEFAULT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_type_value`(`dict_type` ASC, `dict_value`(191) ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 23110 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = DYNAMIC;

-- 单本漫画的 e-hentai 标签扫描结果。一张漫画一条最新记录（manga_id 唯一），
-- 记「匹配到了谁、多可信、建议了哪些标签」，供审计与重跑。
--
-- 标签本体不进这张表，统一走 manga_tag + manga_tag_ref（MANGA_DATA），避免两套标签存储。
-- candidates_json 存候选画廊（含各自相似度与标签）供复核；suggested_tags_json 存合并后的
-- 建议标签 [{namespace,tagEn,tagZh}]，apply 时直接读它，不必重跑合并。
--
-- match_method 是 VARCHAR(32) 而非 16：本地命中会写进 LOCAL_TITLE_EXACT（17）、
-- LOCAL_TITLE_CONTAINS（20）、LOCAL_META_STRONG（17）等更长的值，16 会截断或触发严格模式报错。
-- gallery_title_jpn 是后加的列（本地库 / 联网命中都会带回日文标题，实体 MangaEhScan.galleryTitleJpn
-- 在读它）。

-- ----------------------------
-- Table structure for manga_eh_scan
-- ----------------------------
-- DROP TABLE IF EXISTS `manga_eh_scan`;
CREATE TABLE IF NOT EXISTS `manga_eh_scan`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `manga_id` bigint NOT NULL COMMENT '→ manga_data.id',
  `status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT 'SUCCESS / NOT_FOUND / NO_MATCH / FAILED',
  `gallery_gid` bigint NULL DEFAULT NULL COMMENT '匹配到的 gallery gid',
  `gallery_token` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '匹配到的 gallery token',
  `gallery_title` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '匹配到的 gallery 标题',
  `gallery_title_jpn` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `match_method` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT 'MANUAL / TITLE_EXACT / AI',
  `match_score` double NULL DEFAULT NULL COMMENT '0~1 相似度',
  `tag_count` int NULL DEFAULT NULL COMMENT '合并后建议标签数',
  `candidates_json` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '候选列表 [{gid,token,title,similarity,tags[],weights{}}]，审计+重跑',
  `suggested_tags_json` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '合并后的建议标签 [{namespace,tagEn,tagZh}]，apply 读它',
  `error_message` varchar(1024) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT 'FAILED 时的错误信息',
  `scanned_at` datetime NULL DEFAULT NULL COMMENT '扫描时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_manga`(`manga_id` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 25058 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '漫画 e-hentai 扫描结果' ROW_FORMAT = DYNAMIC;

-- 标签本体，跨实体共用（ARCHIVE_UNIT 作者层 / MANGA_DATA 单本层，见 manga_tag_ref）。
--
-- namespace 为 NULL 的是磁盘目录标签；e-hentai 词典标签带 namespace（female / male / …），
-- 同名中文但不同 namespace 是两条，故唯一键必须带上 namespace：
-- uk_ns_tag_name(namespace, tag_name)，不是单列的 tag_name。
--
-- count_ignore = 1 表示不计入归档作者「高频标签」统计。

-- ----------------------------
-- Table structure for manga_tag
-- ----------------------------
-- DROP TABLE IF EXISTS `manga_tag`;
CREATE TABLE IF NOT EXISTS `manga_tag`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `tag_name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `remark` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `create_time` datetime NULL DEFAULT NULL,
  `en_name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '英文原标签',
  `major_category` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '语义大类',
  `category` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '细分类',
  `description` varchar(2000) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '描述',
  `source` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'FOLDER' COMMENT 'FOLDER 目录名扫出 / EHENTAI 词典导入',
  `namespace` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT 'e-hentai 命名空间；目录标签为 NULL',
  `count_ignore` tinyint(1) NULL DEFAULT 0 COMMENT '统计时忽略',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_ns_tag_name`(`namespace` ASC, `tag_name` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 3137 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '漫画标签' ROW_FORMAT = DYNAMIC;

-- 标签关联，target_type 取 ARCHIVE_UNIT / MANGA_DATA。
-- 后续新增可打标签的实体只加枚举值、不改表结构，故不设外键。

-- ----------------------------
-- Table structure for manga_tag_ref
-- ----------------------------
-- DROP TABLE IF EXISTS `manga_tag_ref`;
CREATE TABLE IF NOT EXISTS `manga_tag_ref`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `tag_id` bigint NOT NULL,
  `target_type` varchar(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT 'ARCHIVE_UNIT / MANGA_DATA',
  `target_id` bigint NOT NULL,
  `create_time` datetime NULL DEFAULT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_tag_target`(`tag_id` ASC, `target_type` ASC, `target_id` ASC) USING BTREE,
  INDEX `idx_target`(`target_type` ASC, `target_id` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 325821 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '标签关联' ROW_FORMAT = DYNAMIC;

-- 韵脚词典。程序与任务写入（韵表 TSV 导入 / AI 批量新增 / 页面维护），**不是磁盘镜像**，
-- 可以直接 INSERT。

-- ----------------------------
-- Table structure for rhyme_entry
-- ----------------------------
-- DROP TABLE IF EXISTS `rhyme_entry`;
CREATE TABLE IF NOT EXISTS `rhyme_entry`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增代理主键',
  `entry_type` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT 'CHAR 单字 / WORD 词',
  `text` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '字或词原文',
  `pinyin` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '带调主读音（多音字一音一行）。NOT NULL：NULL 会让 uk_entry 失效。必须 bin：ai_ci 下 lǜ = lù，会让 uk_entry 把同字不同调的读音当重复丢掉',
  `finals` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '韵母（含介音，舌尖元音写作 -i），如 ang / -i。必须 bin：ai_ci 下 u = ü、e = ê，会让按韵母的查询串味',
  `rhyme_body` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '韵身（去介音），18 个值之一。查询的实际键。必须 bin，理由同 finals',
  `yun18` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '十八韵名，如 十六唐（与 rhyme_body 一一对应，展示用）。bin 是为与前两列同口径',
  `word_class` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '词性，受控 8 值枚举（RhymeService.WORD_CLASSES）：名词/动词/形容词/副词/代词/数量词/虚词/其他。OPEN 行由 jieba 词性映射而来',
  `source` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT 'MODERN 现代规范字表 / XLSX / MANUAL / CORPUS / OPEN 开源词表',
  `tier` tinyint NOT NULL DEFAULT 0 COMMENT '常用度：1一级/2二级/3三级/0未知（PinyinUtil.tier；排序第一键）',
  `freq` int NOT NULL DEFAULT 0 COMMENT '词频（排序第二键）：CORPUS 行是语料出现次数，OPEN 行是 jieba 词频，其余为 0',
  `note` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '导入时与尾字读音不符等提示',
  `create_time` datetime NULL DEFAULT NULL,
  `update_time` datetime NULL DEFAULT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_body`(`rhyme_body` ASC, `entry_type` ASC, `tier` ASC) USING BTREE,
  INDEX `idx_finals`(`finals` ASC) USING BTREE,
  INDEX `idx_source`(`source` ASC) USING BTREE,
  UNIQUE INDEX `uk_text_pinyin`(`text` ASC, `pinyin` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 122329 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '韵脚词典' ROW_FORMAT = Dynamic;

-- 组下的文件清单。喊麦没有 variant 概念（主名即身份），所以只有组内顺序。

-- ----------------------------
-- Table structure for shout_file
-- ----------------------------
-- DROP TABLE IF EXISTS `shout_file`;
CREATE TABLE IF NOT EXISTS `shout_file`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `shout_id` bigint NOT NULL COMMENT '所属 shout_group.id',
  `main_name` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '去扩展名主名',
  `file_type` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT 'VIDEO / AUDIO / LYRIC',
  `suffix` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT '' COMMENT '扩展名后缀，含点、保留原文大小写（如 .MP3）；与 main_name 拼回完整文件名',
  `sort_order` int NOT NULL DEFAULT 0 COMMENT '组内顺序：video=0 audio=1 lyric=2..',
  `create_time` datetime NULL DEFAULT NULL,
  `update_time` datetime NULL DEFAULT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_file`(`shout_id` ASC, `main_name` ASC, `suffix` ASC) USING BTREE,
  INDEX `idx_shout_main`(`shout_id` ASC, `main_name` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 49 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '归档喊麦组下的文件清单' ROW_FORMAT = Dynamic;

-- 归档喊麦组（一条 = 一个主名组）。唯一键 (分区, 主名)：同一组出现在两个分区就是两行。
--
-- default_rate 是这一表里唯一的「磁盘表达不出来」的东西 —— 用户设的组默认倍速。
-- 同步只建行、不设值，也从不覆盖它（同歌曲侧）；删除时只标 MISSING 不删行，为的就是保住它。

-- ----------------------------
-- Table structure for shout_group
-- ----------------------------
-- DROP TABLE IF EXISTS `shout_group`;
CREATE TABLE IF NOT EXISTS `shout_group`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `partition_name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '评分分区目录名，如 #9超赞',
  `main_name` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '去扩展名主名，组的身份（喊麦的归并键就是它）',
  `score` int NOT NULL COMMENT '评分，由 partition_name 推导的镜像',
  `default_rate` decimal(4, 2) NULL DEFAULT NULL COMMENT '组级默认倍速，0.25~4.00（浏览器超出会静音）',
  `status` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / MISSING',
  `create_time` datetime NULL DEFAULT NULL,
  `update_time` datetime NULL DEFAULT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_main`(`partition_name` ASC, `main_name` ASC) USING BTREE,
  INDEX `idx_status`(`status` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 35 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '归档喊麦组' ROW_FORMAT = Dynamic;

-- 标签。键 = (主名, 标签名)。
--
-- 标签跨分区共用（不像倍速带分区）：待打分时打的标签，归档后原样保留 ——
-- 归档门就是「这组有没有标签」，没有标签不许打分归档。
--
-- 标签名冗余存储（不做 tag + tag_ref 两表）：喊麦标签是 DB-only，没有「改标签要连带改目录名」
-- 那层权威关系，内联成字符串更简单。
--
-- **注意它不是镜像表**：标签是磁盘表达不出来的设置，可以直接 INSERT。

-- ----------------------------
-- Table structure for shout_tag
-- ----------------------------
-- DROP TABLE IF EXISTS `shout_tag`;
CREATE TABLE IF NOT EXISTS `shout_tag`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `main_name` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '去扩展名主名，组的身份',
  `tag_name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '标签名，NFC 归一后存储',
  `create_time` datetime NULL DEFAULT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_main_tag`(`main_name` ASC, `tag_name` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 9 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '喊麦标签' ROW_FORMAT = Dynamic;

-- 归档歌曲的文件清单（磁盘镜像，由 SongSyncService 同步）。

-- ----------------------------
-- Table structure for song_file
-- ----------------------------
-- DROP TABLE IF EXISTS `song_file`;
CREATE TABLE IF NOT EXISTS `song_file`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `song_id` bigint NOT NULL COMMENT '所属 song.id',
  `main_name` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '去扩展名主名，含版本号，variant 身份',
  `file_type` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT 'VIDEO / AUDIO / LYRIC',
  `suffix` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT '' COMMENT '扩展名后缀，含点、保留原文大小写（如 .MP3）；与 main_name 拼回完整文件名',
  `variant_sort` int NOT NULL DEFAULT 0 COMMENT '跨 variant 排序（同步时固化），同 variant 各行同值',
  `sort_order` int NOT NULL DEFAULT 0 COMMENT 'variant 内顺序：video=0 audio=1 lyric=2..',
  `create_time` datetime NULL DEFAULT NULL,
  `update_time` datetime NULL DEFAULT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_file`(`song_id` ASC, `main_name` ASC, `suffix` ASC) USING BTREE,
  INDEX `idx_song_main`(`song_id` ASC, `main_name` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 2539 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '归档歌曲合并条目下的文件清单' ROW_FORMAT = DYNAMIC;

-- 归档歌曲主表（磁盘镜像，由 SongSyncService 同步）。
--
-- 权威方向仍是文件系统：分区目录名编码评分，打分 = 搬文件。这张表只是把归档结果固化下来，
-- 供列表、倍速与 MISSING 判定查询。
--
-- 归档镜像里有一条是「用户设的、磁盘表达不出来」的：default_rate（合并条目级默认倍速）。
-- 同步只建行、不覆盖它，读写见 SongSettingService。
--
-- needs_speed_up = 1 表示这个合并条目落在「需加速」分区，由 partition_name 推导（与 score 同源）。

-- ----------------------------
-- Table structure for song_group
-- ----------------------------
-- DROP TABLE IF EXISTS `song_group`;
CREATE TABLE IF NOT EXISTS `song_group`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `partition_name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '评分分区目录名，如 #9超赞-需加速',
  `author1` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '作者1原文；SHOUT/解析失败为 NULL',
  `author2` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '作者2原文',
  `author3` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '作者3原文',
  `title` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '曲名原文；SHOUT/解析失败为 NULL',
  `original_title` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '原曲名原文，隐式时=title',
  `merge_key` varchar(600) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '归一化合并键，与 SongNameParser.mergeKey 同源',
  `parsed` tinyint(1) NOT NULL DEFAULT 0 COMMENT '任一 variant 解析成功=1',
  `parse_failed_reason` varchar(300) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '全部 variant 都失败时取主 variant 的原因',
  `needs_normalize` tinyint(1) NOT NULL DEFAULT 0 COMMENT '任一 variant 需要规范化',
  `loose_separator` tinyint(1) NOT NULL DEFAULT 0 COMMENT '任一 variant 用了容错分隔符',
  `score` int NOT NULL COMMENT '评分，由 partition_name 推导的镜像',
  `needs_speed_up` tinyint(1) NOT NULL DEFAULT 0 COMMENT '是否在「需加速」分区，由 partition_name 推导',
  `default_rate` decimal(4, 2) NULL DEFAULT NULL COMMENT '合并条目级默认倍速',
  `status` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / MISSING',
  `create_time` datetime NULL DEFAULT NULL,
  `update_time` datetime NULL DEFAULT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_merge`(`partition_name` ASC, `merge_key` ASC) USING BTREE,
  INDEX `idx_status`(`status` ASC) USING BTREE,
  INDEX `idx_original`(`original_title` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1219 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '归档歌曲合并条目' ROW_FORMAT = DYNAMIC;

-- 填词项目（synthV svp 模板填词），一首歌一份。
--
-- 四个 JSON 列按层次：notes_json 是音轨×音符骨架，lines_json 是合并出来的时间线分句，
-- filled_json 是每句实际填进去的槽位值，gaps_json 是每句的视觉空位（句内空格）。都是
-- MEDIUMTEXT —— 一首歌的骨架数据轻松超过 TEXT 的 64KB。各列的精确形状见列注释。
--
-- gaps_json 是唯一可以由 NULL 表达语义的列：NULL = 用户从没在页面上编辑过空位，
-- 页面与导出都按 LyricFillAligner#gaps 现算（老行天然是 NULL，行为与加列前完全一致）；
-- 非 NULL 时形状必须与 lines_json 一致，对不上就当作没存、退回现算（见 LyricFillService#resolveGaps）。
-- 单开一列而不是塞进 lines_json：保存路径上 recompute / syncDashes / syncGroupCopies 都会
-- 重建或合并行，把空位放进 FillLine 就得让每一处都记得搬它（漏一处 = 空格静默移位）。
--
-- original_id 指向 song_original_setting.id（多对一）；name 为空时页面按序号显示「填词 N」。

-- ----------------------------
-- Table structure for song_lyric_fill
-- ----------------------------
-- DROP TABLE IF EXISTS `song_lyric_fill`;
CREATE TABLE IF NOT EXISTS `song_lyric_fill`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增代理主键',
  `original_id` bigint NULL DEFAULT NULL COMMENT '所属原曲（song_original_setting.id），多对一',
  `name` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '填词名，空则按序号显示「填词 N」',
  `svp_path` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT 'svp 模板完整路径（打开时的快照，不唯一）',
  `original_name` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '原曲名原文，供展示',
  `track_indices` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '勾选的音轨序号（逗号分隔），空表示全歌唱轨',
  `notes_json` mediumtext CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '音轨×音符骨架 [{trackIndex,trackName,notes:[{lyrics,onset,duration,slotType}]}]',
  `lines_json` mediumtext CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '合并时间线分句 [{slots:[{trackIndex,noteIndex,original,slotType}],needCount,originalText,startOnset}]',
  `filled_json` mediumtext CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '每句的槽位值 string[][]，与 lines[i].slots 等长；空串 = 没填。旧格式（一句一个稠密字符串）由 readFilled 容错读成空列表',
  `gaps_json` mediumtext CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '每句视觉空位（句内空格）bool[][]，与 lines_json 同形状；NULL = 从没在页面上编辑过，按 LyricFillAligner#gaps 现算',
  `create_time` datetime NULL DEFAULT NULL,
  `update_time` datetime NULL DEFAULT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_original_id`(`original_id` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 72 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '填词项目（synthV svp 模板填词）' ROW_FORMAT = Dynamic;

-- 原曲名级设置（唯一的「设置表」，不是磁盘镜像）。
--
-- 主键是自增代理键（surrogate key），不与 raw_name / artist 关联；唯一键是
-- (raw_name, artist) —— 同一首原曲名可被不同作者唱（同名原曲），靠作者区分。
--
-- default_rate 是这一表里唯一的「磁盘表达不出来」的东西 —— 用户设的原曲默认倍速。
-- bpm 由 SongTemplateService 扫描时三级取到后落库（svp 工程 ＞ midi 文件内容的 tempo 事件
-- ＞ midi 文件名里的 [BPM=NN]）；remark 供人备注。
-- artist / demo_file_name / demo_lrc_file_name / last_search_time / bpm / remark / *_check
-- 等列都是后加的（老库靠一次性 ALTER 补上，故不在这份全量脚本里体现）。
--
-- 早期为原曲页展示「专辑」加过的 album 列已废弃删除，这里不再出现。

-- ----------------------------
-- Table structure for song_original_setting
-- ----------------------------
-- DROP TABLE IF EXISTS `song_original_setting`;
CREATE TABLE IF NOT EXISTS `song_original_setting`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `raw_name` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '原曲名原文，供展示',
  `artist` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '歌手',
  `artist_check` tinyint(1) NULL DEFAULT 0,
  `original_file_name` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '原曲文件名（含扩展名），未识别为 null',
  `original_check` tinyint(1) NULL DEFAULT 0,
  `lyric_file_name` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '歌词文件名（含扩展名）',
  `lyric_check` tinyint(1) NULL DEFAULT 0,
  `last_search_time` datetime NULL DEFAULT NULL COMMENT '最近搜索时间',
  `demo_file_name` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '样例音频（含扩展名）',
  `demo_lrc_file_name` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '样例音频歌词（含扩展名）',
  `accompaniment_file_name` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '伴奏音频文件名（含扩展名）',
  `vocals_file_name` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '纯人声音频文件名（含扩展名）',
  `mid_file_name` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT 'mid 文件名（含扩展名）',
  `bpm` decimal(10, 4) NULL DEFAULT NULL COMMENT 'BPM',
  `svp_file_name` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT 'svp 模板文件名（含扩展名）',
  `svp_check` tinyint(1) NULL DEFAULT 0,
  `default_rate` decimal(4, 2) NULL DEFAULT NULL COMMENT '默认倍速，null 表示回落到 1.0',
  `need_manual_judge` tinyint(1) NOT NULL DEFAULT 0 COMMENT '需要手动判断',
  `template_folder_name` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '原第2层文件夹名（模板原名）',
  `has_other_file` tinyint(1) NOT NULL DEFAULT 0 COMMENT '是否有其他文件',
  `remark` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '备注',
  `create_time` datetime NULL DEFAULT NULL,
  `update_time` datetime NULL DEFAULT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_raw_artist`(`raw_name` ASC, `artist` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 923 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '原曲名级设置' ROW_FORMAT = DYNAMIC;

-- 歌曲合并条目标签（DB-only，磁盘表达不出来），可直接 INSERT。
-- merge_key 是归一化合并键，与 SongNameParser.mergeKey 同源。

-- ----------------------------
-- Table structure for song_tag
-- ----------------------------
-- DROP TABLE IF EXISTS `song_tag`;
CREATE TABLE IF NOT EXISTS `song_tag`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `merge_key` varchar(600) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '归一化合并键，与 SongNameParser.mergeKey 同源',
  `tag_name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '标签名，NFC 归一后存储',
  `create_time` datetime NULL DEFAULT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_merge_tag`(`merge_key` ASC, `tag_name` ASC) USING BTREE,
  INDEX `idx_merge`(`merge_key` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 237 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '歌曲合并条目标签（DB-only，磁盘表达不出来）' ROW_FORMAT = DYNAMIC;

SET FOREIGN_KEY_CHECKS = 1;
