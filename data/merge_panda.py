# -*- coding: utf-8 -*-
"""
用 pandabrowser（UBTL/pandabrowser）的 2025-08-04 dump 重建 eh-gallery.db。

来源：Release-v3.9 的 exhentai_database_dump_2025_08_04.zip（解压出单个 SQLite，2.3GB），
     是目前能找到的最新 exhentai 元数据快照（posted 覆盖到 2025-08-05，比 ccloli 2023-12 新 1.5 年）。

源表 gallery 单表、namespace 已拆成独立列（比 ccloli 三表关联直接）：
  artist / group / parody / character / female / male / language / mixed / other / cosplayer / rest
  每列是 Python list 字面量字符串（如 "['koari']"、"['a', 'b']"），NULL 表示无。

与 merge_eh.py / merge_ccloli.py 相同的产出表 gallery（schema 一致，MangaEhLocalDb 无需改）：
  title_norm / title / title_jpn / category / artist / group_name / parody / character /
  language / tags / gid / token

去重 key 仍为 title_norm（expunged=1 跳过）。female/male/mixed/other/cosplayer/rest 落 content tags。
用法：python merge_panda.py。源 SQLite 路径由环境变量 PANDA_DB 给，**没有内置的本机路径** ——
     不给时取**当前目录**下的 exhentai_database_dump_2025_08_04.sqlite；EH_LIMIT 只处理前 N 条（自测用）。
"""
import ast
import json
import os
import sqlite3
import sys
import unicodedata

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

SRC = os.environ.get('PANDA_DB', 'exhentai_database_dump_2025_08_04.sqlite')
OUT = os.path.join(os.path.dirname(__file__), 'eh-gallery.db')

LIMIT = int(os.environ.get('EH_LIMIT', '0')) or None

# 元数据列：列名 → 目标列名（group 撞 SQL 关键字，用 group_name）
META_COLS = {
    'artist': 'artist',
    'group': 'group_name',
    'parody': 'parody',
    'character': 'character',
    'language': 'language',
}
# 内容类列：落 tags（'ns:name'）
CONTENT_COLS = ('female', 'male', 'mixed', 'other', 'cosplayer', 'rest')

COLUMNS = ('title_norm', 'title', 'title_jpn', 'category', 'artist', 'group_name',
           'parody', 'character', 'language', 'tags', 'gid', 'token')


def title_norm(title):
    """等价 MangaTextUtil.normalizeNameKey + stripSeparators：NFC+trim+大写，只留字母数字（含 CJK）。"""
    k = unicodedata.normalize('NFC', title).strip().upper()
    return ''.join(ch for ch in k if ch.isalnum())


def norm_category(c):
    """'Doujinshi'/'Artist CG'/'Image Set'/'Non-H'/'Asian Porn' → 'doujinshi'/'artistcg'/'imageset'/'non-h'/'asianporn'。"""
    if not c:
        return ''
    return c.strip().lower().replace(' ', '')


def parse_list(s):
    """把 "['a', 'b']" 之类的 Python list 字面量解析成 list；空/非法返回 []。"""
    if not s:
        return []
    try:
        v = ast.literal_eval(s)
        return v if isinstance(v, list) else []
    except Exception:
        return []


def main():
    src = sqlite3.connect(SRC)
    src.row_factory = sqlite3.Row

    out = sqlite3.connect(OUT)
    cur = out.cursor()
    cur.execute('DROP TABLE IF EXISTS gallery')
    cur.execute(f'''CREATE TABLE gallery (
        title_norm TEXT PRIMARY KEY,
        title TEXT,
        title_jpn TEXT,
        category TEXT,
        artist TEXT,
        group_name TEXT,
        parody TEXT,
        character TEXT,
        language TEXT,
        tags TEXT,
        gid INTEGER,
        token TEXT
    )''')

    batch = []
    BATCH = 50000

    def flush():
        if batch:
            cur.executemany('INSERT OR IGNORE INTO gallery VALUES (%s)' % ','.join('?' * len(COLUMNS)), batch)
            out.commit()
            batch.clear()

    kept = 0
    skipped_expunged = 0
    rows = src.execute('SELECT * FROM gallery')
    for row in rows:
        if row['expunged']:
            skipped_expunged += 1
            continue
        title = row['title'] or ''
        tn = title_norm(title)
        if not tn:
            continue
        meta = {v: parse_list(row[k]) for k, v in META_COLS.items()}
        content = []
        for ns in CONTENT_COLS:
            for name in parse_list(row[ns]):
                content.append(f'{ns}:{name}')
        batch.append((tn, title, row['title_jpn'] or '', norm_category(row['category']),
                      ';'.join(meta['artist']), ';'.join(meta['group_name']),
                      ';'.join(meta['parody']), ';'.join(meta['character']),
                      ';'.join(meta['language']), json.dumps(content, ensure_ascii=False),
                      row['gid'], row['token'] or ''))
        kept += 1
        if len(batch) >= BATCH:
            flush()
        if kept % 200000 == 0:
            print(f'gallery {kept}', flush=True)
        if LIMIT and kept >= LIMIT:
            break
    flush()

    cur.execute('CREATE INDEX IF NOT EXISTS idx_gallery_title ON gallery(title)')
    cur.execute('CREATE INDEX IF NOT EXISTS idx_gallery_category ON gallery(category)')
    out.commit()
    cur.execute('SELECT COUNT(*) FROM gallery')
    total = cur.fetchone()[0]
    print(f'完成：保留 {kept}（expunged 跳过 {skipped_expunged}），库内 {total} 条', flush=True)
    out.close()
    src.close()


if __name__ == '__main__':
    main()
