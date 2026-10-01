#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
网易云音乐 .ncm 解密 → 直接写出原音频(flac/mp3) 的离线脚本。

原理完全来自 magic-akari/ncmc-web 的 worker.js(前端同步解密核心),本地复刻、
去掉浏览器 Blob 下载那层,直接把解出的字节写盘。算法(标准 NCM 格式):

  1. 魔数校验  文件头 8 字节 = "CTENFDAM"
  2. key      偏移 10 处 4 字节小端 keyLen → 读 keyLen 字节,逐字节 ^ 0x64,
              用 CORE_KEY=AES-128-ECB 解密,结果头部 17 字节丢弃,余下为 RC4 种子
  3. keyBox   用种子做 RC4 KSA + 一趟 PRGA,得到 256 字节查表 keybox
  4. metadata 读 4 字节小端 metaLen → 读 metaLen 字节逐字节 ^ 0x63,
              取 [22:] 那截作为 base64 串,base64 解码得 AES 密文,
              用 META_KEY=AES-128-ECB 解密,UTF-8 串头部 6 字节 ("music:") 丢弃,
              余下是 JSON(歌名/歌手/专辑/格式/专辑封)
  5. 跳过    (meta 之后)5 字节 gap + 4 字节 imageSize + 4 字节 crc + imageSize 字节内嵌封面
  6. audio   剩余全部字节,逐字节 ^ keybox[i & 0xff],即得原音频(flac/mp3)

零第三方依赖(pycryptodome 都没有):内置纯 Python AES-128-ECB 解密。
用法(默认 dry-run 只打印):
    python ncm_decrypt.py "F:\\歌曲\\*.ncm"                # 单文件/通配符
    python ncm_decrypt.py -batch "F:\\歌曲\\ncm"          # 递归整目录
    python ncm_decrypt.py -a -o "F:\\output" "F:\\song.ncm"  # 真实写盘 + 导出封面
输出文件名默认沿用输入文件名(去掉 .ncm 与盘上可能多带的媒体扩展名,如 song.mp3.ncm→song);
仅当输入名是无意义的 uid(纯数字 / 32 位 hex)时才回退为元数据拼的「歌名 - 歌手」。
开关:
    -a / --apply     写入文件(默认 dry-run,只打印不落盘)
    -o / --out       输出目录(默认写回源目录)
    --cover          同时导出封面为 <输出名>.jpg
    -batch <目录>    递归处理目录下所有 .ncm
"""

import argparse
import base64
import json
import os
import struct
import sys
from pathlib import Path

# ---- 固定密钥(来自 worker.js 的 Hex.parse) ----
CORE_KEY = bytes.fromhex("687a4852416d736f356b496e62617857")   # "hzHRAmso5kInbaxW"
META_KEY = bytes.fromhex("2331346C6A6B5F215C5D2630553C2728")   # 16 字节

MAGIC = b"CTENFDAM"
AUDIO_MIME = {"mp3": "audio/mpeg", "flac": "audio/flac"}


# =====================================================================
# 纯 Python AES-128-ECB 解密(仅解密方向,Pkcs7)
# =====================================================================
SBOX = [
    0x63, 0x7c, 0x77, 0x7b, 0xf2, 0x6b, 0x6f, 0xc5, 0x30, 0x01, 0x67, 0x2b, 0xfe, 0xd7, 0xab, 0x76,
    0xca, 0x82, 0xc9, 0x7d, 0xfa, 0x59, 0x47, 0xf0, 0xad, 0xd4, 0xa2, 0xaf, 0x9c, 0xa4, 0x72, 0xc0,
    0xb7, 0xfd, 0x93, 0x26, 0x36, 0x3f, 0xf7, 0xcc, 0x34, 0xa5, 0xe5, 0xf1, 0x71, 0xd8, 0x31, 0x15,
    0x04, 0xc7, 0x23, 0xc3, 0x18, 0x96, 0x05, 0x9a, 0x07, 0x12, 0x80, 0xe2, 0xeb, 0x27, 0xb2, 0x75,
    0x09, 0x83, 0x2c, 0x1a, 0x1b, 0x6e, 0x5a, 0xa0, 0x52, 0x3b, 0xd6, 0xb3, 0x29, 0xe3, 0x2f, 0x84,
    0x53, 0xd1, 0x00, 0xed, 0x20, 0xfc, 0xb1, 0x5b, 0x6a, 0xcb, 0xbe, 0x39, 0x4a, 0x4c, 0x58, 0xcf,
    0xd0, 0xef, 0xaa, 0xfb, 0x43, 0x4d, 0x33, 0x85, 0x45, 0xf9, 0x02, 0x7f, 0x50, 0x3c, 0x9f, 0xa8,
    0x51, 0xa3, 0x40, 0x8f, 0x92, 0x9d, 0x38, 0xf5, 0xbc, 0xb6, 0xda, 0x21, 0x10, 0xff, 0xf3, 0xd2,
    0xcd, 0x0c, 0x13, 0xec, 0x5f, 0x97, 0x44, 0x17, 0xc4, 0xa7, 0x7e, 0x3d, 0x64, 0x5d, 0x19, 0x73,
    0x60, 0x81, 0x4f, 0xdc, 0x22, 0x2a, 0x90, 0x88, 0x46, 0xee, 0xb8, 0x14, 0xde, 0x5e, 0x0b, 0xdb,
    0xe0, 0x32, 0x3a, 0x0a, 0x49, 0x06, 0x24, 0x5c, 0xc2, 0xd3, 0xac, 0x62, 0x91, 0x95, 0xe4, 0x79,
    0xe7, 0xc8, 0x37, 0x6d, 0x8d, 0xd5, 0x4e, 0xa9, 0x6c, 0x56, 0xf4, 0xea, 0x65, 0x7a, 0xae, 0x08,
    0xba, 0x78, 0x25, 0x2e, 0x1c, 0xa6, 0xb4, 0xc6, 0xe8, 0xdd, 0x74, 0x1f, 0x4b, 0xbd, 0x8b, 0x8a,
    0x70, 0x3e, 0xb5, 0x66, 0x48, 0x03, 0xf6, 0x0e, 0x61, 0x35, 0x57, 0xb9, 0x86, 0xc1, 0x1d, 0x9e,
    0xe1, 0xf8, 0x98, 0x11, 0x69, 0xd9, 0x8e, 0x94, 0x9b, 0x1e, 0x87, 0xe9, 0xce, 0x55, 0x28, 0xdf,
    0x8c, 0xa1, 0x89, 0x0d, 0xbf, 0xe6, 0x42, 0x68, 0x41, 0x99, 0x2d, 0x0f, 0xb0, 0x54, 0xbb, 0x16,
]
INV_SBOX = [0] * 256
for _i, _v in enumerate(SBOX):
    INV_SBOX[_v] = _i

RCON = [0x01, 0x02, 0x04, 0x08, 0x10, 0x20, 0x40, 0x80, 0x1b, 0x36]


def _xtime(x):
    x <<= 1
    if x & 0x100:
        x ^= 0x11b
    return x & 0xff


def _gmul(a, b):
    p = 0
    for _ in range(8):
        if b & 1:
            p ^= a
        a = _xtime(a)
        b >>= 1
    return p & 0xff


def _key_schedule(key16):
    """返回 11 个 16 字节轮密钥(bytes)列表。"""
    w = [list(key16[4 * i:4 * i + 4]) for i in range(4)]
    for i in range(4, 44):
        t = w[i - 1][:]
        if i % 4 == 0:
            t = t[1:] + t[:1]
            t = [SBOX[b] for b in t]
            t[0] ^= RCON[i // 4 - 1]
        w.append([w[i - 4][j] ^ t[j] for j in range(4)])
    return [bytes(w[r * 4 + k // 4][k % 4] for k in range(16)) for r in range(11)]


def _add_round_key(state, rk):
    return [state[i] ^ rk[i] for i in range(16)]


def _inv_sub_bytes(s):
    return [INV_SBOX[b] for b in s]


def _inv_shift_rows(s):
    # 状态按列排列(字节序 = 行 + 4*列)。加密时 ShiftRows 让第 r 行左移 r，
    # 解密要反向：第 r 行右移 r。
    m = [[s[r + 4 * c] for c in range(4)] for r in range(4)]  # m[行][列]
    m[1] = m[1][-1:] + m[1][:-1]
    m[2] = m[2][-2:] + m[2][:-2]
    m[3] = m[3][-3:] + m[3][:-3]
    out = []
    for c in range(4):
        for r in range(4):
            out.append(m[r][c])
    return out


def _inv_mix_columns(s):
    out = [0] * 16
    for c in range(4):
        i = 4 * c
        a0, a1, a2, a3 = s[i], s[i + 1], s[i + 2], s[i + 3]
        out[i] = _gmul(a0, 14) ^ _gmul(a1, 11) ^ _gmul(a2, 13) ^ _gmul(a3, 9)
        out[i + 1] = _gmul(a0, 9) ^ _gmul(a1, 14) ^ _gmul(a2, 11) ^ _gmul(a3, 13)
        out[i + 2] = _gmul(a0, 13) ^ _gmul(a1, 9) ^ _gmul(a2, 14) ^ _gmul(a3, 11)
        out[i + 3] = _gmul(a0, 11) ^ _gmul(a1, 13) ^ _gmul(a2, 9) ^ _gmul(a3, 14)
    return out


def aes_ecb_decrypt_block(block16, key16):
    """对一个 16 字节密文块做 AES-128-ECB 解密。"""
    rks = _key_schedule(key16)
    state = _add_round_key(list(block16), rks[10])
    for rnd in range(9, 0, -1):
        state = _inv_shift_rows(state)
        state = _inv_sub_bytes(state)
        state = _add_round_key(state, rks[rnd])
        state = _inv_mix_columns(state)
    state = _inv_shift_rows(state)
    state = _inv_sub_bytes(state)
    state = _add_round_key(state, rks[0])
    return bytes(state)


def aes_ecb_decrypt(data, key16):
    """AES-128-ECB 整段解密,自动去 Pkcs7 填充,返回 bytes。"""
    if len(key16) != 16:
        raise ValueError("AES key 必须 16 字节")
    if len(data) % 16 != 0:
        raise ValueError("密文长度非 16 的倍数: %d" % len(data))
    out = bytearray()
    for i in range(0, len(data), 16):
        out += aes_ecb_decrypt_block(data[i:i + 16], key16)
    pad = out[-1]
    if pad < 1 or pad > 16:
        raise ValueError("非法 Pkcs7 填充: %d" % pad)
    return bytes(out[:-pad])


# =====================================================================
# NCM 解密
# =====================================================================
def decrypt_ncm(raw):
    """输入整个 .ncm 文件字节,返回 (音频bytes, 元数据dict, 封面bytes|None, 格式)。"""
    if raw[:8] != MAGIC:
        raise ValueError("不是有效的 NCM 文件(魔数不匹配)")

    offset = 10  # 魔数8字节 + 2字节版本(gap)

    # ---- key ----
    key_len = struct.unpack_from("<I", raw, offset)[0]
    offset += 4
    key_cipher = bytes(b ^ 0x64 for b in raw[offset:offset + key_len])
    offset += key_len
    key_plain = aes_ecb_decrypt(key_cipher, CORE_KEY)
    key_date = key_plain[17:]  # 丢弃前 17 字节

    # ---- keyBox (RC4 KSA + 一趟 PRGA) ----
    box = list(range(256))
    klen = len(key_date)
    j = 0
    for i in range(256):
        j = (box[i] + j + key_date[i % klen]) & 0xff
        box[i], box[j] = box[j], box[i]
    keybox = bytearray(256)
    for i in range(256):
        i2 = (i + 1) & 0xff
        si = box[i2]
        sj = box[(i2 + si) & 0xff]
        keybox[i] = box[(si + sj) & 0xff]

    # ---- metadata ----
    meta_len = struct.unpack_from("<I", raw, offset)[0]
    offset += 4
    meta = bytearray(raw[offset:offset + meta_len])
    offset += meta_len
    for i in range(len(meta)):
        meta[i] ^= 0x63

    meta_dict = {}
    cover = None
    if meta_len > 0:
        b64str = bytes(meta[22:]).decode("utf-8", "replace")  # 去 22 字节头
        try:
            aes_cipher = base64.b64decode(b64str)
            text = aes_ecb_decrypt(aes_cipher, META_KEY).decode("utf-8", "replace")
            # 前 6 字节 "music:" 后为 JSON
            meta_dict = json.loads(text[6:])
        except Exception as e:
            print("  ⚠ 元数据解析失败:", e)

    # ---- 跳过: 5 gap + [4 imgSize][4 imgLen][image][extra] 越过, 落在音频起点 ----
    img_size = struct.unpack_from("<I", raw, offset + 5)[0]   # 头一个4字节(gap 后)
    img_len = struct.unpack_from("<I", raw, offset + 9)[0]    # 真实封面字节数
    cover_start = offset + 13
    if img_len > 0 and cover_start + img_len <= len(raw):
        cover = raw[cover_start:cover_start + img_len]
    # 音频起点恒为 postmeta + 13 + img_size(与 img_len 解耦, 见 ncmc decoder.rs)
    offset += 13 + img_size

    # ---- audio ----
    audio = raw[offset:]
    out = bytearray(len(audio))
    for i in range(len(audio)):
        out[i] = audio[i] ^ keybox[i & 0xff]
    audio = bytes(out)

    # ---- 格式(同 ncmc Audio::from, 缺省 mp3) ----
    fmt = meta_dict.get("format")
    if not fmt:
        if audio[0:4] == b"fLaC":
            fmt = "flac"
        elif audio[0:4] == b"OggS":
            fmt = "ogg"
        elif audio[0:3] == b"ID3" or audio[0:2] == b"\xff\xfb":
            fmt = "mp3"
        elif audio[0:4] == b"\x00\x00\x00\x20" and audio[4:8] == b"ftyp":
            fmt = "m4a"
        else:
            fmt = "mp3"
    return audio, meta_dict, cover, fmt


# =====================================================================
# 标签内嵌(零依赖): mp3 → ID3v2.3, flac → VORBIS_COMMENT + PICTURE
# ogg / m4a 结构不同,这里跳过(调用方在说明里提示)。
# =====================================================================


def _id3_syncsafe(n):
    return bytes([(n >> 21) & 0x7f, (n >> 14) & 0x7f, (n >> 7) & 0x7f, n & 0x7f])


def _id3_unsyncsafe(data):
    return (data[0] << 21) | (data[1] << 14) | (data[2] << 7) | data[3]


def _id3_text_frame(fid, text):
    """ID3v2.3 文本帧(编码 0x01 = UTF-16 with BOM, 供中文)。"""
    payload = b"\x01" + b"\xff\xfe" + str(text).encode("utf-16-le")
    return fid.encode() + len(payload).to_bytes(4, "big") + b"\x00\x00" + payload


def _id3_apic(cover):
    """APIC 附加图片帧。mime 按魔数判 jpeg/png; 描述为空。"""
    mime = (b"image/jpeg" if cover[:3] == b"\xff\xd8" else b"image/png") + b"\x00"
    payload = b"\x00" + mime + b"\x03" + b"\x00" + cover  # enc + mime + 封面类型 + 空描述 + 图
    return b"APIC" + len(payload).to_bytes(4, "big") + b"\x00\x00" + payload


def _meta_artists(meta):
    """归一化歌手列表为字符串数组(meta['artist'] 形如 [["name",id],...])。"""
    arts = meta.get("artist") or []
    names = []
    for a in arts:
        name = a[0] if isinstance(a, (list, tuple)) and a else a
        if name:
            names.append(str(name))
    return names


def embed_tags_mp3(audio, meta, cover):
    """去掉开头已有 ID3v2,再前置一个 ID3v2.3 标签(含标题/歌手/专辑/封面)。"""
    if audio[:3] == b"ID3":
        audio = audio[10 + _id3_unsyncsafe(audio[6:10]):]
    frames = []
    if meta.get("musicName"):
        frames.append(_id3_text_frame("TIT2", meta["musicName"]))
    names = _meta_artists(meta)
    if names:
        frames.append(_id3_text_frame("TPE1", "/".join(names)))
    if meta.get("album"):
        frames.append(_id3_text_frame("TALB", meta["album"]))
    if cover:
        frames.append(_id3_apic(cover))
    body = b"".join(frames)
    tag = b"ID3\x03\x00\x00" + _id3_syncsafe(len(body)) + body
    return tag + audio


def _flac_block(btype, data):
    return bytes([((btype) & 0x7f)]) + len(data).to_bytes(3, "big") + data


def _vorbis_comment_block(meta):
    """Vorbis Comment(FLAC metadata block type 4)。字段名大写 ASCII,值 UTF-8。"""
    vendor = b"ncm_decrypt.py"
    comments = []
    if meta.get("musicName"):
        comments.append(b"TITLE=" + str(meta["musicName"]).encode("utf-8"))
    for name in _meta_artists(meta):
        comments.append(b"ARTIST=" + name.encode("utf-8"))
    if meta.get("album"):
        comments.append(b"ALBUM=" + str(meta["album"]).encode("utf-8"))
    body = len(vendor).to_bytes(4, "little") + vendor
    body += len(comments).to_bytes(4, "little")
    for c in comments:
        body += len(c).to_bytes(4, "little") + c
    body += b"\x00"  # framing bit
    return _flac_block(4, body)


def _picture_block(cover):
    """FLAC METADATA_BLOCK_PICTURE(type 6)。类型 3=前封面,尺寸字段填 0(未知)。"""
    mime = b"image/jpeg" if cover[:3] == b"\xff\xd8" else b"image/png"
    body = (3).to_bytes(4, "big")              # picture type = front cover
    body += len(mime).to_bytes(4, "big") + mime
    body += (0).to_bytes(4, "big")             # description length(空)
    body += (0).to_bytes(4, "big")             # width
    body += (0).to_bytes(4, "big")             # height
    body += (0).to_bytes(4, "big")             # depth
    body += (0).to_bytes(4, "big")             # colors
    body += len(cover).to_bytes(4, "big") + cover
    return _flac_block(6, body)


def _flac_last(hdr, is_last):
    return bytes([((1 if is_last else 0) << 7) | (hdr[0] & 0x7f)]) + hdr[1:4]


def embed_tags_flac(audio, meta, cover):
    """重写 FLAC 元数据块: 保留 STREAMINFO + 非标签类块,替换/追加 VORBIS_COMMENT 与 PICTURE。"""
    if audio[:4] != b"fLaC":
        return audio
    idx = 4
    parsed = []  # (type, data, last)
    frames_start = None
    while idx < len(audio):
        hdr = audio[idx:idx + 4]
        if len(hdr) < 4:
            break
        btype = hdr[0] & 0x7f
        blen = int.from_bytes(hdr[1:4], "big")
        data = audio[idx + 4:idx + 4 + blen]
        parsed.append((btype, data, hdr[0] >> 7))
        idx += 4 + blen
        if hdr[0] >> 7:
            frames_start = idx
            break
    if frames_start is None:
        return audio
    # STREAMINFO 必留; 其余保留除 PADDING(1)/VORBIS_COMMENT(4)/PICTURE(6)
    kept = [(t, d) for (t, d, _) in parsed if t == 0 or t not in (1, 4, 6)]
    if meta and (meta.get("musicName") or _meta_artists(meta) or meta.get("album")):
        kept.append((4, _vorbis_comment_block(meta)[4:]))  # 去掉块头只留数据,下面统一加头
    if cover:
        kept.append((6, _picture_block(cover)[4:]))
    out = bytearray(b"fLaC")
    for i, (t, d) in enumerate(kept):
        is_last = (i == len(kept) - 1)
        out += bytes([((1 if is_last else 0) << 7) | t]) + len(d).to_bytes(3, "big") + d
    out += audio[frames_start:]
    return bytes(out)


def embed_tags(audio, meta, cover, fmt):
    """把歌曲元数据 + 封面写进音频文件。flac/mp3 内嵌;其它容器跳过。"""
    if fmt == "mp3":
        return embed_tags_mp3(audio, meta, cover)
    if fmt == "flac":
        return embed_tags_flac(audio, meta, cover)
    return audio  # ogg / m4a: 暂不内嵌


_ILLEGAL_CHARS = '<>:"/\\|?*'
# 输入名可能把真正的媒体扩展名也带进来(如 song.mp3.ncm),剥掉避免输出成 .mp3.mp3
_STEM_EXTS = {".mp3", ".flac", ".wav", ".m4a", ".m4p", ".ogg", ".opus", ".aac",
              ".wma", ".lrc", ".mid", ".svp", ".src", ".ncm"}


def _sanitize(name, fallback):
    """去掉 Windows 非法文件名字符;剥完空就用 fallback。"""
    return "".join(c for c in name if c not in _ILLEGAL_CHARS).strip() or fallback


def _input_stem(src):
    """输入文件名的主干:去掉 .ncm,并剥掉可能带入的媒体扩展名(避免 .mp3.mp3)。"""
    stem = Path(src).stem
    low = stem.lower()
    for ext in sorted(_STEM_EXTS, key=len, reverse=True):
        if low.endswith(ext) and len(stem) > len(ext):
            stem = stem[:-len(ext)]
            break
    return stem.strip()


def _is_uid_like(name):
    """纯数字(常见 ncm 下载名是 uid)或 32 位十六进制(md5 样式)→ 输入名无意义,应回退到元数据名。"""
    s = name.strip()
    if not s:
        return True
    if s.isdigit():
        return True
    if len(s) == 32 and all(c in "0123456789abcdefABCDEF" for c in s):
        return True
    return False


def _choose_filename(src, meta):
    """输出文件名:优先沿用输入文件名(去掉 .ncm),输入名无意义(uid)时才回退到元数据拼的「歌名 - 歌手」。"""
    base = _input_stem(src)
    if base and not _is_uid_like(base):
        return _sanitize(base, base)
    return safe_filename(meta, base or Path(src).stem)


def safe_filename(meta, fallback):
    """用元数据拼「歌名 - 歌手」,用于输入名无意义时的回退。"""
    name = ""
    if meta:
        name = str(meta.get("musicName") or "").strip()
        artist = meta.get("artist") or []
        if isinstance(artist, list) and artist:
            try:
                name += " - " + " & ".join(a[0] for a in artist if a)
            except (TypeError, IndexError):
                pass
    if not name:
        name = fallback
    return _sanitize(name, fallback)


def convert_file(src, apply=False, out_dir=None, cover=False, dry=True, tag=True):
    """处理单个 .ncm,返回 (成功?, 说明)。

    tag=True 时把歌名/歌手/专辑/封面写进音频(flac=内嵌, mp3=ID3v2.3)。
    """
    try:
        raw = Path(src).read_bytes()
    except OSError as e:
        return False, "读取失败: %s" % e
    size = len(raw)
    try:
        audio, meta, pic, fmt = decrypt_ncm(raw)
    except ValueError as e:
        return False, str(e)

    title = _choose_filename(src, meta)
    ext = "." + fmt
    target_dir = Path(out_dir) if out_dir else Path(src).parent
    out_path = target_dir / (title + ext)

    if not apply:
        print("  [dry-run] %s\n           → %s（%.1f MB → %.1f MB, %s, 歌「%s」%s）" % (
            Path(src).name, out_path.name, size / 1048576, len(audio) / 1048576,
            fmt, (meta.get("musicName") or ""),
            "，含内嵌标签" if tag else ""))
        if cover:
            print("            封面: %s.jpg" % title)
        return True, "dry-run"

    if tag:
        if fmt in ("mp3", "flac"):
            audio = embed_tags(audio, meta, pic, fmt)
        else:
            print("  ⚠ %s 暂不支持内嵌标签,仅输出裸音频" % fmt)
    target_dir.mkdir(parents=True, exist_ok=True)
    out_path.write_bytes(audio)
    note = "已写出 %s（%.1f MB%s）" % (out_path.name, len(audio) / 1048576,
                                         "，含标签" if tag else "")
    if cover and pic:
        cover_path = target_dir / (title + ".jpg")
        cover_path.write_bytes(pic)
        note += "，封面 %s" % cover_path.name
    print("  [apply] " + note)
    return True, note


def collect_ncm(args):
    """返回待处理的 ncm 文件路径列表。"""
    if args.batch:
        return [p for p in sorted(Path(args.batch).rglob("*.ncm"))]
    paths = []
    for pat in args.inputs:
        import glob as _glob
        for m in _glob.glob(pat if os.path.isabs(pat) else str(Path(pat))):
            p = Path(m)
            if p.is_file() and p.suffix.lower() == ".ncm":
                paths.append(p)
    # 去重保序
    seen, uniq = set(), []
    for p in paths:
        if p not in seen:
            seen.add(p)
            uniq.append(p)
    return uniq


def main():
    ap = argparse.ArgumentParser(description="NCM 解密 → 直接写出音频(离线, 无依赖)")
    ap.add_argument("inputs", nargs="*", help="NCM 文件或通配符")
    ap.add_argument("-batch", help="递归处理整个目录下的 .ncm")
    ap.add_argument("-a", "--apply", action="store_true", help="写盘(默认 dry-run 只打印)")
    ap.add_argument("-o", "--out", help="输出目录(默认写回源目录)")
    ap.add_argument("--cover", action="store_true", help="同时导出封面 jpg")
    ap.add_argument("--no-tag", action="store_true",
                    help="不内嵌标签(只输出裸音频; 默认内嵌歌名/歌手/专辑/封面到 flac/mp3)")
    args = ap.parse_args()

    if not args.inputs and not args.batch:
        ap.print_help()
        return

    files = collect_ncm(args)
    if not files:
        print("没有找到 .ncm 文件。")
        return

    print("共 %d 个 .ncm 文件,dry-run=%s\n" % (len(files), not args.apply))
    ok = fail = 0
    for f in files:
        try:
            succ, note = convert_file(f, args.apply, args.out, args.cover, tag=not args.no_tag)
            ok += 1 if succ else 0
            fail += 0 if succ else 1
        except Exception as e:
            fail += 1
            print("  😭 意外错误: %s → %s" % (f, e))

    print("\n完成: 成功 %d,失败 %d" % (ok, fail))
    if not args.apply:
        print("dry-run 未写任何文件。确认无误后加 -a 重跑。")


if __name__ == "__main__":
    main()
