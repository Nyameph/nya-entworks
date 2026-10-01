-- ============================================================================
-- **本文件由 tools/release.py 生成，不要手改** —— 改 src/main/resources/db/nya_entworks.sql
-- 再跑 `python tools/release.py --emit-sqlite-ddl`。
--
-- 它是发出去那一份的建表脚本：只含歌曲 + 喊麦 + 通用表（漫画裁掉），随包打进 jar，
-- 首启由 spring.sql.init 自动执行（application-release.yaml 里 mode: always）。
-- 全是 CREATE ... IF NOT EXISTS，可重复执行。
--
-- 与 MySQL 那份的两处**有意**不同（其余逐字对应）：
--   一、索引名带上了表名。MySQL 的索引名在每张表里各自命名，SQLite 是全库唯一；
--       不带表名的话后建的那条会撞名，而 IF NOT EXISTS 撞名不报错、直接跳过。
--   二、唯一键写成表内的 UNIQUE (...)。SQLite 的 upsert（ON CONFLICT）只认表内约束，
--       独立 CREATE UNIQUE INDEX 匹配不上，会报 no matching constraint。
-- ============================================================================

-- ----------------------------
-- async_task
-- ----------------------------
CREATE TABLE IF NOT EXISTS async_task (
  id INTEGER PRIMARY KEY,
  task_type TEXT NOT NULL,
  module TEXT NOT NULL,
  task_name TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'PENDING',
  params_json TEXT,
  progress_done INTEGER NOT NULL DEFAULT 0,
  progress_total INTEGER NOT NULL DEFAULT 0,
  message TEXT DEFAULT NULL,
  error TEXT DEFAULT NULL,
  result_json TEXT,
  logs TEXT,
  source TEXT NOT NULL DEFAULT 'SUBMIT',
  rerun_from_id INTEGER DEFAULT NULL,
  start_time TEXT DEFAULT NULL,
  end_time TEXT DEFAULT NULL,
  create_time TEXT DEFAULT NULL,
  update_time TEXT DEFAULT NULL
);
CREATE INDEX IF NOT EXISTS async_task_idx_status ON async_task(status);
CREATE INDEX IF NOT EXISTS async_task_idx_module_status ON async_task(module, status);
CREATE INDEX IF NOT EXISTS async_task_idx_create_time ON async_task(create_time);

-- ----------------------------
-- file_op_log
-- ----------------------------
CREATE TABLE IF NOT EXISTS file_op_log (
  id INTEGER PRIMARY KEY,
  batch_id TEXT NOT NULL,
  module TEXT NOT NULL,
  op_type TEXT NOT NULL,
  unit_level TEXT NOT NULL,
  action TEXT DEFAULT NULL,
  source TEXT NOT NULL,
  task_id INTEGER DEFAULT NULL,
  from_path TEXT DEFAULT NULL,
  to_path TEXT DEFAULT NULL,
  result TEXT NOT NULL DEFAULT 'OK',
  detail TEXT DEFAULT NULL,
  op_time TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS file_op_log_idx_op_time ON file_op_log(op_time);
CREATE INDEX IF NOT EXISTS file_op_log_idx_module_time ON file_op_log(module, op_time);
CREATE INDEX IF NOT EXISTS file_op_log_idx_from_path ON file_op_log(from_path);
CREATE INDEX IF NOT EXISTS file_op_log_idx_batch ON file_op_log(batch_id);

-- ----------------------------
-- song_group
-- ----------------------------
CREATE TABLE IF NOT EXISTS song_group (
  id INTEGER PRIMARY KEY,
  partition_name TEXT NOT NULL,
  author1 TEXT DEFAULT NULL,
  author2 TEXT DEFAULT NULL,
  author3 TEXT DEFAULT NULL,
  title TEXT DEFAULT NULL,
  original_title TEXT DEFAULT NULL,
  merge_key TEXT NOT NULL,
  parsed INTEGER NOT NULL DEFAULT 0,
  parse_failed_reason TEXT DEFAULT NULL,
  needs_normalize INTEGER NOT NULL DEFAULT 0,
  loose_separator INTEGER NOT NULL DEFAULT 0,
  score INTEGER NOT NULL,
  needs_speed_up INTEGER NOT NULL DEFAULT 0,
  default_rate NUMERIC DEFAULT NULL,
  status TEXT NOT NULL DEFAULT 'ACTIVE',
  create_time TEXT DEFAULT NULL,
  update_time TEXT DEFAULT NULL,
  UNIQUE (partition_name, merge_key)
);
CREATE INDEX IF NOT EXISTS song_group_idx_status ON song_group(status);
CREATE INDEX IF NOT EXISTS song_group_idx_original ON song_group(original_title);

-- ----------------------------
-- song_file
-- ----------------------------
CREATE TABLE IF NOT EXISTS song_file (
  id INTEGER PRIMARY KEY,
  song_id INTEGER NOT NULL,
  main_name TEXT NOT NULL,
  file_type TEXT NOT NULL,
  suffix TEXT NOT NULL DEFAULT '',
  variant_sort INTEGER NOT NULL DEFAULT 0,
  sort_order INTEGER NOT NULL DEFAULT 0,
  create_time TEXT DEFAULT NULL,
  update_time TEXT DEFAULT NULL,
  UNIQUE (song_id, main_name, suffix)
);
CREATE INDEX IF NOT EXISTS song_file_idx_song_main ON song_file(song_id, main_name);

-- ----------------------------
-- song_tag
-- ----------------------------
CREATE TABLE IF NOT EXISTS song_tag (
  id INTEGER PRIMARY KEY,
  merge_key TEXT NOT NULL,
  tag_name TEXT NOT NULL,
  create_time TEXT DEFAULT NULL,
  UNIQUE (merge_key, tag_name)
);
CREATE INDEX IF NOT EXISTS song_tag_idx_merge ON song_tag(merge_key);

-- ----------------------------
-- song_original_setting
-- ----------------------------
CREATE TABLE IF NOT EXISTS song_original_setting (
  id INTEGER PRIMARY KEY,
  raw_name TEXT DEFAULT NULL,
  artist TEXT DEFAULT NULL,
  artist_check INTEGER DEFAULT 0,
  original_file_name TEXT DEFAULT NULL,
  original_check INTEGER DEFAULT 0,
  lyric_file_name TEXT DEFAULT NULL,
  lyric_check INTEGER DEFAULT 0,
  last_search_time TEXT DEFAULT NULL,
  demo_file_name TEXT DEFAULT NULL,
  demo_lrc_file_name TEXT DEFAULT NULL,
  accompaniment_file_name TEXT DEFAULT NULL,
  vocals_file_name TEXT DEFAULT NULL,
  mid_file_name TEXT DEFAULT NULL,
  bpm NUMERIC DEFAULT NULL,
  svp_file_name TEXT DEFAULT NULL,
  svp_check INTEGER DEFAULT 0,
  default_rate NUMERIC DEFAULT NULL,
  need_manual_judge INTEGER NOT NULL DEFAULT 0,
  template_folder_name TEXT DEFAULT NULL,
  has_other_file INTEGER NOT NULL DEFAULT 0,
  remark TEXT DEFAULT NULL,
  create_time TEXT DEFAULT NULL,
  update_time TEXT DEFAULT NULL,
  UNIQUE (raw_name, artist)
);

-- ----------------------------
-- song_lyric_fill
-- ----------------------------
CREATE TABLE IF NOT EXISTS song_lyric_fill (
  id INTEGER PRIMARY KEY,
  original_id INTEGER DEFAULT NULL,
  name TEXT DEFAULT NULL,
  svp_path TEXT NOT NULL,
  original_name TEXT DEFAULT NULL,
  track_indices TEXT DEFAULT NULL,
  notes_json TEXT,
  lines_json TEXT,
  filled_json TEXT,
  gaps_json TEXT,
  create_time TEXT DEFAULT NULL,
  update_time TEXT DEFAULT NULL
);
CREATE INDEX IF NOT EXISTS song_lyric_fill_idx_original_id ON song_lyric_fill(original_id);

-- ----------------------------
-- rhyme_entry
-- ----------------------------
CREATE TABLE IF NOT EXISTS rhyme_entry (
  id INTEGER PRIMARY KEY,
  entry_type TEXT NOT NULL,
  text TEXT NOT NULL,
  pinyin TEXT NOT NULL COLLATE BINARY,
  finals TEXT NOT NULL COLLATE BINARY,
  rhyme_body TEXT NOT NULL COLLATE BINARY,
  yun18 TEXT NOT NULL COLLATE BINARY,
  word_class TEXT DEFAULT NULL,
  source TEXT NOT NULL,
  tier INTEGER NOT NULL DEFAULT 0,
  freq INTEGER NOT NULL DEFAULT 0,
  note TEXT DEFAULT NULL,
  create_time TEXT DEFAULT NULL,
  update_time TEXT DEFAULT NULL,
  UNIQUE (text, pinyin)
);
CREATE INDEX IF NOT EXISTS rhyme_entry_idx_body ON rhyme_entry(rhyme_body, entry_type, tier);
CREATE INDEX IF NOT EXISTS rhyme_entry_idx_finals ON rhyme_entry(finals);
CREATE INDEX IF NOT EXISTS rhyme_entry_idx_source ON rhyme_entry(source);

-- ----------------------------
-- lyric_corpus_line
-- ----------------------------
CREATE TABLE IF NOT EXISTS lyric_corpus_line (
  id INTEGER PRIMARY KEY,
  group_id INTEGER NOT NULL,
  kind TEXT NOT NULL DEFAULT 'SONG',
  group_key TEXT DEFAULT NULL,
  file_name TEXT NOT NULL,
  original_title TEXT DEFAULT NULL,
  score INTEGER NOT NULL,
  line_index INTEGER NOT NULL,
  text TEXT NOT NULL,
  tail_char TEXT DEFAULT NULL,
  tail_pinyin TEXT DEFAULT NULL,
  finals TEXT DEFAULT NULL,
  yun18 TEXT DEFAULT NULL,
  create_time TEXT DEFAULT NULL,
  update_time TEXT DEFAULT NULL,
  UNIQUE (group_id, file_name, line_index)
);
CREATE INDEX IF NOT EXISTS lyric_corpus_line_idx_kind_score ON lyric_corpus_line(kind, score);
CREATE INDEX IF NOT EXISTS lyric_corpus_line_idx_yun18 ON lyric_corpus_line(yun18);

-- ----------------------------
-- lyric_corpus_pair
-- ----------------------------
CREATE TABLE IF NOT EXISTS lyric_corpus_pair (
  id INTEGER PRIMARY KEY,
  fill_id INTEGER NOT NULL,
  line_index INTEGER NOT NULL,
  original_text TEXT NOT NULL,
  filled_text TEXT NOT NULL,
  yun18 TEXT DEFAULT NULL,
  create_time TEXT DEFAULT NULL,
  update_time TEXT DEFAULT NULL,
  UNIQUE (fill_id, line_index)
);

-- ----------------------------
-- shout_group
-- ----------------------------
CREATE TABLE IF NOT EXISTS shout_group (
  id INTEGER PRIMARY KEY,
  partition_name TEXT NOT NULL,
  main_name TEXT NOT NULL,
  score INTEGER NOT NULL,
  default_rate NUMERIC DEFAULT NULL,
  status TEXT NOT NULL DEFAULT 'ACTIVE',
  create_time TEXT DEFAULT NULL,
  update_time TEXT DEFAULT NULL,
  UNIQUE (partition_name, main_name)
);
CREATE INDEX IF NOT EXISTS shout_group_idx_status ON shout_group(status);

-- ----------------------------
-- shout_file
-- ----------------------------
CREATE TABLE IF NOT EXISTS shout_file (
  id INTEGER PRIMARY KEY,
  shout_id INTEGER NOT NULL,
  main_name TEXT NOT NULL,
  file_type TEXT NOT NULL,
  suffix TEXT NOT NULL DEFAULT '',
  sort_order INTEGER NOT NULL DEFAULT 0,
  create_time TEXT DEFAULT NULL,
  update_time TEXT DEFAULT NULL,
  UNIQUE (shout_id, main_name, suffix)
);
CREATE INDEX IF NOT EXISTS shout_file_idx_shout_main ON shout_file(shout_id, main_name);

-- ----------------------------
-- shout_tag
-- ----------------------------
CREATE TABLE IF NOT EXISTS shout_tag (
  id INTEGER PRIMARY KEY,
  main_name TEXT NOT NULL,
  tag_name TEXT NOT NULL,
  create_time TEXT DEFAULT NULL,
  UNIQUE (main_name, tag_name)
);
