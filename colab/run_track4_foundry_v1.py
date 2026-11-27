#!/usr/bin/env python3
"""MoneyLens Track 4 — Multilingual Semantic Search & Quick-Entry Teacher-Student Foundry (MLSS v1).

100% offline pure-JDK student architecture (FNV-1a weighted BoW + 1-block LayerNorm-GELU residual MLP
+ 74 taxonomy prototypes), INT8 QAT quantized into < 512 KB binary `moneylens_search.bin.gz`.
"""
import argparse
import gc
import gzip
import hashlib
import json
import math
import os
import random
import re
import struct
import sys
import time
import unicodedata
from collections import Counter, defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import run_foundry_v4 as f4

OUT_DIR = Path("/content/outputs_track4_v1")
DATASET_V5_GZ_PATH = Path("/content/dataset_malaysian_v5.jsonl.gz")

STATUS_PATH = OUT_DIR / "track4_foundry_v1_status.json"
REPORT_PATH = OUT_DIR / "track4_search_report.json"
MODEL_BIN_GZ_PATH = OUT_DIR / "moneylens_search.bin.gz"
SEMANTIC_BLIND_PATH = OUT_DIR / "semantic_search_blind_test.json"
QUICK_BLIND_PATH = OUT_DIR / "quick_entry_blind_test.json"
PARITY_VECTORS_PATH = OUT_DIR / "search_parity_vectors.json"
GEN_CACHE_PATH = OUT_DIR / "track4_llm_synth_cache.json"

MAX_BIN_GZ_BYTES = 524_288  # strict < 512 KB
MAGIC = b"MLSS"
MODEL_VERSION = 2
MAX_VOCAB = 5400
EMB_DIM = 64
HIDDEN_DIM = 160
DOC_SURF_WEIGHT = 0.35
DOC_PROTO_WEIGHT = 0.65
GATE_C = 0.24
COV_GATE_C = 0.14
TRAD_SLANG_ZH_RE = re.compile(r"[壽單費車電醫藥學開門書買賣網銀轉帳還貸險稅購館飲廳機飛]|健保|卡奴|快食")

E5_TEACHER_ID = "intfloat/multilingual-e5-base"
QWEN_CANDIDATES = [
    "Qwen/Qwen2.5-7B-Instruct",
    "Qwen/Qwen2.5-3B-Instruct",
]

PAIRS_74 = []
for _cat, _subs in sorted(f4.CANONICAL_TAXONOMY.items()):
    for _sub in sorted(_subs):
        PAIRS_74.append((_cat, _sub))
assert len(PAIRS_74) == 74
NON_EXPENSE_PAIRS = [
    ("Salary", ""),
    ("Other income", ""),
    ("Miscellaneous", ""),
]
PAIRS_77 = PAIRS_74 + NON_EXPENSE_PAIRS
assert len(PAIRS_77) == 77
PAIR_INDEX = {p: i for i, p in enumerate(PAIRS_77)}

FORBIDDEN_SCRIPT_RE = re.compile(
    r"[\u0400-\u04FF\u0600-\u06FF\u0900-\u097F\u0E00-\u0E7F\u3040-\u30FF\uAC00-\uD7AF\u1100-\u11FF]"
)
INDON_BLOCKLIST_RE = re.compile(
    r"(?i)\b(?:biaya|telat|tagihan|pulsa|karta|rehab|kesukaran|tangga\s+duit|terlambat|tagih|"
    r"konser|musik|poli|spesialis|sepatu|anggaran|pelunasan|krlbis|rfidpas|servicemohon|kasur|rayah)\b"
)
VOCAB_STOPWORDS = frozenset({
    "for", "to", "of", "in", "on", "at", "by", "the", "and", "or", "a", "an",
    "is", "are", "was", "were", "be", "with", "from", "how", "my", "your",
    "this", "that", "self", "full", "global", "new", "all", "one", "two",
    "cara", "di", "ke", "dan", "yang", "untuk", "ini", "itu", "dari", "pada",
    "dengan", "buat", "atau", "ada", "saya", "kami", "kita", "apa", "penuh",
    "malaysia", "ipoh", "perak", "melaka", "pahang", "kedah", "perlis", "sabah",
    "sarawak", "johor", "selangor", "kelantan", "terengganu", "klang", "subang",
    "puchong", "cheras", "kajang", "setapak", "kepong", "bangsar", "damansara",
    "putrajaya", "cyberjaya",
    "的", "了", "和", "在", "是", "我", "有", "个", "这", "那", "也", "吗", "呢", "把",
})


def update_status(stage: str, **extra):
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    payload = {"stage": stage, "ts": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()), **extra}
    STATUS_PATH.write_text(json.dumps(payload, indent=2, ensure_ascii=False), encoding="utf-8")
    print(f"[STATUS] {stage} {extra}", flush=True)


def fnv1a63(s: str) -> int:
    """Exact bit-for-bit parity with Java SemanticSearch.fnv1a63()."""
    h = 0xCBF29CE484222325
    for b in s.encode("utf-8"):
        h ^= b
        h = (h * 0x100000001B3) & 0xFFFFFFFFFFFFFFFF
    return h & 0x7FFFFFFFFFFFFFFF


def slugify_tag(raw: str) -> str:
    s = unicodedata.normalize("NFKD", (raw or "").lower())
    s = "".join(ch for ch in s if unicodedata.category(ch) != "Mn")
    out = []
    prev_us = True
    for ch in s:
        if ("a" <= ch <= "z") or ("0" <= ch <= "9"):
            out.append(ch)
            prev_us = False
        elif not prev_us:
            out.append("_")
            prev_us = True
    res = "".join(out).strip("_")
    return res[:40]


def normalize_for_features(raw: str) -> str:
    """Exact bit-for-bit parity with Java SemanticSearch.normalizeForFeatures()."""
    if raw is None:
        return ""
    s = unicodedata.normalize("NFKD", str(raw)).lower()
    return "".join(ch for ch in s if unicodedata.category(ch) != "Mn")


def _is_cjk_char(ch: str) -> bool:
    cp = ord(ch)
    return (0x3400 <= cp <= 0x4DBF) or (0x4E00 <= cp <= 0x9FFF)


def extract_features(raw: str, field_tags=None):
    """Exact bit-for-bit parity with Java SemanticSearch.extractFeatures().

    Returns an insertion-ordered list of (feature_key, weight) with unique feature keys.
    """
    feats = {}  # key -> weight (first insertion wins, preserving deterministic order)

    def add_feat(k: str, w: float):
        if k not in feats:
            feats[k] = float(w)

    norm = normalize_for_features(raw)
    latin_tokens = []
    cjk_runs = []
    latin_buf = []
    cjk_buf = []

    def flush_latin():
        if latin_buf:
            tok = "".join(latin_buf)
            latin_buf.clear()
            if len(latin_tokens) < 48:
                latin_tokens.append(tok)

    def flush_cjk():
        if cjk_buf:
            run = "".join(cjk_buf)
            cjk_buf.clear()
            if len(cjk_runs) < 32:
                cjk_runs.append(run[:48])

    for ch in norm:
        if ("a" <= ch <= "z") or ("0" <= ch <= "9"):
            flush_cjk()
            latin_buf.append(ch)
        elif _is_cjk_char(ch):
            flush_latin()
            cjk_buf.append(ch)
        else:
            flush_latin()
            flush_cjk()
    flush_latin()
    flush_cjk()

    for i, tok in enumerate(latin_tokens):
        add_feat("w:" + tok, 3.0)
        if i > 0:
            add_feat("wb:" + latin_tokens[i - 1] + "_" + tok, 1.8)
        wtrim = tok[:18]
        if len(wtrim) >= 4:
            padded = "^" + wtrim + "$"
            Lp = len(padded)
            for st in range(Lp - 3):
                add_feat("c4:" + padded[st:st + 4], 0.55)

    for run in cjk_runs:
        Lr = len(run)
        for i in range(Lr):
            add_feat("zh1:" + run[i], 0.50)
        for i in range(Lr - 1):
            add_feat("zh2:" + run[i:i + 2], 2.6)
        for i in range(Lr - 2):
            add_feat("zh3:" + run[i:i + 3], 1.8)

    if field_tags:
        for fk, fw in field_tags:
            add_feat(str(fk), float(fw))

    return list(feats.items())


def build_exact_vocab(train_texts, max_vocab=MAX_VOCAB):
    """Selects up to max_vocab high-signal features strictly from training texts and sorts by 63-bit FNV-1a hash."""
    import numpy as np

    feat_cnt = Counter()
    for txt in train_texts:
        for fk, _ in extract_features(txt):
            feat_cnt[fk] += 1

    for c, s in PAIRS_77:
        feat_cnt["cat:" + slugify_tag(c)] += 100
        if s:
            feat_cnt["sub:" + slugify_tag(s)] += 100
    for k in ("expense", "income", "transfer", "refund", "shared"):
        feat_cnt["kind:" + k] += 100
    for b in (
        "tax_relief", "medical_health", "sports_gym", "education",
        "lifestyle_tech_books_wifi", "medical_edu_insurance", "life_insurance_takaful",
        "sspn_prs_ev", "zakat_fitrah_rebate", "lhdn_pcb_paid",
    ):
        feat_cnt["lhdn:" + b] += 100

    ranked = []
    for fk, cnt in feat_cnt.items():
        pref, _, body = fk.partition(":")
        if pref in ("w", "zh1") and body in VOCAB_STOPWORDS:
            continue
        if pref == "c4" and body in VOCAB_STOPWORDS:
            continue
        if pref in ("cat", "sub", "kind", "lhdn"):
            pri = (5, cnt)
        elif pref in ("w", "zh2"):
            pri = (4, cnt)
        elif pref == "zh1" and cnt >= 2:
            pri = (3, cnt)
        elif pref in ("wb", "zh3") and cnt >= 2:
            pri = (2, cnt)
        elif pref == "c4" and cnt >= 2:
            pri = (1, cnt)
        else:
            continue
        ranked.append((pri, fk))
    ranked.sort(key=lambda x: (x[0][0], x[0][1], x[1]), reverse=True)

    hash_to_fk = {}
    for _, fk in ranked:
        if len(hash_to_fk) >= max_vocab:
            break
        h = fnv1a63(fk)
        if h not in hash_to_fk:
            hash_to_fk[h] = fk

    sorted_items = sorted(hash_to_fk.items(), key=lambda x: x[0])
    vocab_hashes = np.array([h for h, _ in sorted_items], dtype=np.int64)
    vocab_keys = [fk for _, fk in sorted_items]
    hash_to_idx = {int(h): i for i, h in enumerate(vocab_hashes)}
    assert len(vocab_hashes) == len(vocab_keys) <= max_vocab
    return vocab_hashes, vocab_keys, hash_to_idx


def encode_inputs_to_csr(texts, tag_lists=None, hash_to_idx=None):
    """Exact-vocab weighted-mean CSR matrix per text (total_w sums ALL extracted features so OOD dilutes cleanly)."""
    import numpy as np
    from scipy.sparse import csr_matrix

    assert hash_to_idx is not None and len(hash_to_idx) > 0
    V = len(hash_to_idx)
    indptr, indices, data = [0], [], []
    for i, txt in enumerate(texts):
        tags = tag_lists[i] if tag_lists is not None else None
        items = extract_features(txt, tags)
        total_w = sum(w for _, w in items)
        bmap = {}
        if total_w > 1e-6:
            inv_w = 1.0 / total_w
            for fk, fw in items:
                row = hash_to_idx.get(fnv1a63(fk))
                if row is not None:
                    bmap[row] = bmap.get(row, 0.0) + fw * inv_w
        for b in sorted(bmap.keys()):
            v = bmap[b]
            if v != 0.0:
                indices.append(b)
                data.append(v)
        indptr.append(len(indices))
    return csr_matrix(
        (np.array(data, dtype=np.float32), np.array(indices, dtype=np.int32), np.array(indptr, dtype=np.int32)),
        shape=(len(texts), V),
        dtype=np.float32,
    )


def quantize_int8_per_row(mat):
    import numpy as np

    w = np.asarray(mat, dtype=np.float32)
    max_abs = np.max(np.abs(w), axis=1)
    scales = np.where(max_abs > 1e-8, max_abs / 127.0, 1.0).astype(np.float32)
    q = np.clip(np.rint(w / scales[:, None]), -127, 127).astype(np.int8)
    return scales, q


def dequantize_int8_per_row(scales, q):
    import numpy as np

    return q.astype(np.float32) * scales[:, None]


def _pack_utf8(s: str) -> bytes:
    b = s.encode("utf-8")
    return struct.pack(">H", len(b)) + b


def _pack_qmat(scales, q) -> bytes:
    import numpy as np

    rows, cols = q.shape
    return struct.pack(">2i", rows, cols) + np.asarray(scales, dtype=">f4").tobytes() + np.asarray(q, dtype=np.int8).tobytes()


def serialize_search_bin_gz(
    weights,
    vocab_hashes,
    proto_pairs,
    proto_embs,
    sem_th: float,
    sem_margin: float,
    cls_th: float,
    cls_margin: float,
) -> bytes:
    """Packs exact MLSS v2 big-endian binary (with sorted 63-bit vocab hashes) and returns gzip level-9 bytes."""
    import numpy as np

    vh = np.asarray(vocab_hashes, dtype=">i8")
    buf = bytearray()
    buf.extend(
        struct.pack(
            ">4s5i4f",
            MAGIC,
            MODEL_VERSION,
            len(vh),
            EMB_DIM,
            HIDDEN_DIM,
            len(proto_pairs),
            float(sem_th),
            float(sem_margin),
            float(cls_th),
            float(cls_margin),
        )
    )
    buf.extend(vh.tobytes())
    for name in ("E", "W1", "W2"):
        sc, q = quantize_int8_per_row(weights[name])
        buf.extend(_pack_qmat(sc, q))
    for name in ("b1", "ln1_w", "ln1_b", "b2"):
        buf.extend(np.asarray(weights[name], dtype=">f4").tobytes())
    for p_cat, _ in proto_pairs:
        buf.extend(_pack_utf8(p_cat))
    for _, p_sub in proto_pairs:
        buf.extend(_pack_utf8(p_sub))
    p_sc, p_q = quantize_int8_per_row(proto_embs)
    buf.extend(_pack_qmat(p_sc, p_q))
    return gzip.compress(bytes(buf), compresslevel=9)


def load_search_bin_gz(gz_bytes: bytes):
    import numpy as np

    raw = gzip.decompress(gz_bytes)
    pos = 0
    hdr_sz = struct.calcsize(">4s5i4f")
    magic, ver, nb, ed, hd, np_cnt, sem_th, sem_mg, cls_th, cls_mg = struct.unpack_from(">4s5i4f", raw, pos)
    pos += hdr_sz
    assert magic == MAGIC and ver == MODEL_VERSION
    vocab_hashes = np.frombuffer(raw, dtype=">i8", count=nb, offset=pos).astype(np.int64).copy()
    pos += nb * 8
    hash_to_idx = {int(h): i for i, h in enumerate(vocab_hashes)}

    def read_qmat():
        nonlocal pos
        rows, cols = struct.unpack_from(">2i", raw, pos)
        pos += 8
        sc = np.frombuffer(raw, dtype=">f4", count=rows, offset=pos).astype(np.float32)
        pos += rows * 4
        q = np.frombuffer(raw, dtype=np.int8, count=rows * cols, offset=pos).reshape(rows, cols).copy()
        pos += rows * cols
        return dequantize_int8_per_row(sc, q)

    def read_fvec(n):
        nonlocal pos
        arr = np.frombuffer(raw, dtype=">f4", count=n, offset=pos).astype(np.float32).copy()
        pos += n * 4
        return arr

    def read_str_list(n):
        nonlocal pos
        lst = []
        for _ in range(n):
            ul = struct.unpack_from(">H", raw, pos)[0]
            pos += 2
            lst.append(raw[pos:pos + ul].decode("utf-8"))
            pos += ul
        return lst

    E = read_qmat()
    W1 = read_qmat()
    W2 = read_qmat()
    b1 = read_fvec(hd)
    ln1_w = read_fvec(hd)
    ln1_b = read_fvec(hd)
    b2 = read_fvec(ed)
    proto_cats = read_str_list(np_cnt)
    proto_subs = read_str_list(np_cnt)
    proto_embs = read_qmat()
    p_norms = np.maximum(np.linalg.norm(proto_embs, axis=1, keepdims=True), 1e-6)
    proto_embs = proto_embs / p_norms
    assert pos == len(raw), f"Trailing bytes: {pos} != {len(raw)}"
    return {
        "vocab_size": nb,
        "vocab_hashes": vocab_hashes,
        "hash_to_idx": hash_to_idx,
        "emb_dim": ed,
        "hidden_dim": hd,
        "num_protos": np_cnt,
        "sem_th": float(sem_th),
        "sem_mg": float(sem_mg),
        "cls_th": float(cls_th),
        "cls_mg": float(cls_mg),
        "E": E,
        "W1": W1,
        "W2": W2,
        "b1": b1,
        "ln1_w": ln1_w,
        "ln1_b": ln1_b,
        "b2": b2,
        "proto_cats": proto_cats,
        "proto_subs": proto_subs,
        "proto_embs": proto_embs,
    }


def _layer_norm_np(x, w, b):
    import numpy as np

    mean = x.mean(axis=-1, keepdims=True)
    var = ((x - mean) ** 2).mean(axis=-1, keepdims=True)
    return ((x - mean) / np.sqrt(var + np.float32(1e-5))) * w + b


def _gelu_tanh_np(x):
    import numpy as np

    return np.float32(0.5) * x * (np.float32(1.0) + np.tanh(np.float32(0.7978845608028654) * (x + np.float32(0.044715) * x * x * x)))


def encode_int8_batch(md, texts, tag_lists=None):
    import numpy as np

    X = encode_inputs_to_csr(texts, tag_lists=tag_lists, hash_to_idx=md["hash_to_idx"])
    cov = np.asarray(X.sum(axis=1), dtype=np.float32)
    e0_raw = X @ md["E"]
    nnz = np.diff(X.indptr) > 0
    r0 = np.linalg.norm(e0_raw, axis=1, keepdims=True)
    e0 = np.where(nnz[:, None], e0_raw / np.maximum(r0, 1e-6), np.float32(0.0))
    h_pre = e0 @ md["W1"].T + md["b1"]
    h = _gelu_tanh_np(_layer_norm_np(h_pre, md["ln1_w"], md["ln1_b"]))
    e1 = e0 + (h @ md["W2"].T + md["b2"])
    n1 = np.maximum(np.linalg.norm(e1, axis=1, keepdims=True), 1e-6)
    r3 = r0 * r0 * r0
    c2 = cov * cov
    gate = (r3 / (r3 + np.float32(GATE_C ** 3))) * (c2 / (c2 + np.float32(COV_GATE_C * COV_GATE_C)))
    out = gate * (e1 / n1)
    return np.where(nnz[:, None], out, np.float32(0.0)).astype(np.float32)


def encode_tx_dual_batch(md, txs):
    """Exact mirror of Java SemanticSearch.encodeDocDirect / encodeTransaction dual-view blend."""
    import numpy as np

    docs, tags = [], []
    for t in txs:
        dt, tg = build_tx_doc_and_tags(
            t["merchant"], t["category"], t["subcategory"], t["kind"],
            t.get("note", ""), t.get("person", ""), "", t.get("lhdnBucket", ""),
        )
        docs.append(dt)
        tags.append(tg)
    surf = encode_int8_batch(md, docs, tag_lists=tags)
    p_map = {(md["proto_cats"][i], md["proto_subs"][i]): md["proto_embs"][i] for i in range(md["num_protos"])}
    out = surf.copy()
    for i, t in enumerate(txs):
        pv = p_map.get((t.get("category", ""), t.get("subcategory", "")))
        if pv is not None:
            v = DOC_SURF_WEIGHT * surf[i] + DOC_PROTO_WEIGHT * pv
            out[i] = v / max(1e-6, float(np.linalg.norm(v)))
    return out.astype(np.float32)


def lhdn_bucket_of(merchant: str, cat: str, sub: str, note: str = "") -> str:
    """Exact mirror of Finance.java applyTaxBuckets(L1000-1018)."""
    low = f"{cat} {sub} {merchant} {note}".lower()
    is_sport = bool(re.search(r"\b(?:sports?|gym|fitness|badminton|futsal|pickleball|decathlon|al-ikhsan|alikhsan|marathon)\b", low))
    bucket = ""
    if cat.startswith("Health") or re.search(
        r"\b(?:clinic|klinik|hospital|medical|pharmacy|farmasi|dental|dentist|pergigian|pathlab|bp healthcare|gribbles|optometrist|gleneagles|kpj|columbia asia)\b",
        low,
    ):
        bucket = "Sports & Gym" if is_sport else "Medical & Health"
    elif is_sport:
        bucket = "Sports & Gym"
    if cat == "Education" or re.search(r"\b(?:tuition|university|universiti|upskill|course|coursera|udemy|certification)\b", low):
        if not bucket:
            bucket = "Education"
    if ("Internet" in sub or "Mobile" in sub or "Books" in sub or "Electronics" in sub or re.search(
        r"\b(?:unifi|time fibre|time internet|maxis|celcom|digi|celcomdigi|yes 5g|umobile|books?|bookstore|bookshop|popular|kinokuniya|bookxcess|tsutaya|mph|laptop|macbook|smartphone|iphone|ipad|tablet|machines|harvey norman|all it|thundermatch|senheng)\b",
        low,
    )):
        if not bucket:
            bucket = "Lifestyle (Tech / Books / WiFi)"
    if cat == "Insurance & Takaful" or re.search(r"\b(?:takaful|prudential|prubsn|great eastern|aia|allianz|etiqa|zurich|manulife)\b", low):
        if "Medical" in sub or "Education" in sub or "medical card" in low or re.search(r"\bhealth\b", low):
            if not bucket:
                bucket = "Medical & Edu Insurance"
        elif not bucket:
            bucket = "Life Insurance / Takaful"
    if re.search(r"\b(?:sspn|prs|ev charging|gentari|chargev|jomcharge|chargenow|dc handal|shell recharge|evconnection|tesla supercharger)\b", low):
        if not bucket:
            bucket = "SSPN / PRS / EV"
    if re.search(r"\b(?:zakat|fitrah)\b", low):
        if not bucket:
            bucket = "Zakat / Fitrah Rebate"
    if re.search(r"\b(?:lhdn|pcb|cp38)\b", low) or "LHDN" in sub:
        if not bucket:
            bucket = "LHDN / PCB Paid"
    return bucket


def build_tx_doc_and_tags(merchant: str, cat: str, sub: str, kind: str = "expense", note: str = "", person: str = "", acct_name: str = "", tax_bucket: str = ""):
    """Exact mirror of Java SemanticSearch.encodeDocDirect surface + structured tags."""
    parts = []
    for p in (merchant, cat, sub, note, person, tax_bucket):
        s = (p or "").strip()
        if s:
            parts.append(s)
    if kind == "refund":
        parts.append("refund reversal")
    elif kind == "shared":
        parts.append("shared split")
    elif kind == "transfer":
        parts.append("account transfer topup")
    elif kind == "income":
        parts.append("income received")
    doc_text = " ".join(parts)

    tags = []
    if cat:
        tags.append(("cat:" + slugify_tag(cat), 3.5))
    if sub:
        tags.append(("sub:" + slugify_tag(sub), 4.5))
    if kind:
        tags.append(("kind:" + slugify_tag(kind), 2.8))
    if tax_bucket:
        tags.append(("lhdn:tax_relief", 5.0))
        tags.append(("lhdn:" + slugify_tag(tax_bucket), 4.0))
    return doc_text, tags


# Comprehensive EN / BM / Manglish / Simplified Chinese Concept Seed Bank across ALL 74 Subcategories
# + LHDN Tax Reliefs + Transaction Kinds. Used BOTH for teacher-student alignment AND to seed Qwen expansion.
CONCEPT_SEED_BANK = {
    ("Food & drinks", "Coffee, Tea & Drinks"): {
        "en": ["coffee", "latte", "espresso", "matcha drink", "bubble tea", "milk tea", "iced mocha", "cafe drink", "afternoon tea", "caffeine fix"],
        "bm": ["kopi", "kopi o", "teh tarik", "air minuman", "minuman manis", "boba teh", "kedai kopi", "minum petang"],
        "mg": ["kopi ais", "teh ais", "tapau coffee", "boba craving", "minum chill", "ngopi"],
        "zh": ["咖啡", "喝咖啡", "奶茶", "珍珠奶茶", "美式咖啡", "拿铁", "买水喝", "下午茶", "茶饮", "冻柠茶", "饮料"],
    },
    ("Food & drinks", "Hawker & Mamak"): {
        "en": ["hawker food", "street food", "mamak stall", "local noodles", "chicken rice", "economy rice", "supper", "breakfast toast"],
        "bm": ["makanan mamak", "kedai makan", "gerai makanan", "nasi lemak", "roti canai", "sarapan pagi", "makan tengah hari", "laksa", "satay"],
        "mg": ["makan mamak", "tapau nasi lemak", "chap fan lunch", "kopitiam breakfast", "makan gerai", "supper roti"],
        "zh": ["鸡饭", "海南鸡饭", "杂饭", "经济饭", "茶室", "麻麻档", "小贩中心", "早点", "云吞面", "炒粿条", "叻沙", "肉骨茶", "板面", "打包午饭"],
    },
    ("Food & drinks", "Fast Food"): {
        "en": ["fast food", "fried chicken", "burger meal", "drive thru", "pizza night", "quick burger"],
        "bm": ["makanan segera", "ayam goreng", "burger keju", "set kombo", "pizza"],
        "mg": ["fast food craving", "tapau burger", "makan kepci", "drive thru supper"],
        "zh": ["快餐", "汉堡", "炸鸡", "比萨", "披萨", "套餐", "得来速"],
    },
    ("Food & drinks", "Food Delivery (GrabFood / Foodpanda)"): {
        "en": ["food delivery", "ordered lunch online", "delivery dinner", "takeaway app"],
        "bm": ["penghantaran makanan", "pesan makanan dalam talian", "makan malam hantar"],
        "mg": ["order grabfood", "panda delivery makan", "malas keluar order food"],
        "zh": ["外卖", "点外卖", "叫外卖", "送餐", "网上订餐"],
    },
    ("Food & drinks", "Restaurant & Cafe"): {
        "en": ["restaurant dinner", "dining out", "brunch cafe", "hotpot dinner", "japanese sushi", "steakhouse", "family dinner"],
        "bm": ["makan di restoran", "makan malam keluarga", "makan luar", "kafe barat", "hidangan tengah hari"],
        "mg": ["lepak cafe makan", "dinner dengan family", "makan sedap restaurant", "brunch date"],
        "zh": ["聚餐", "吃大餐", "餐厅吃饭", "火锅", "寿司", "日式料理", "烤肉", "早午餐", "晚饭", "下馆子", "请客吃饭"],
    },
    ("Food & drinks", "Groceries & Snacks"): {
        "en": ["bakery bread", "pastry", "cake", "ice cream", "chocolates", "snacks", "dessert"],
        "bm": ["roti dan kek", "kuih muih", "aiskrim", "makanan ringan", "pencuci mulut", "coklat"],
        "mg": ["beli roti", "tapau cake", "snack petang", "dessert craving"],
        "zh": ["面包", "蛋糕", "甜品", "点心", "零食", "冰淇淋", "饼干", "巧克力", "糕点"],
    },
    ("Groceries", "Supermarket & Hypermarket"): {
        "en": ["groceries", "supermarket shopping", "weekly household food", "fresh vegetables and meat", "pantry restock"],
        "bm": ["barang dapur", "beli barang runcit", "pasar raya", "beli sayur ikan", "keperluan rumah"],
        "mg": ["beli groceries", "topup barang dapur", "shopping pasar raya"],
        "zh": ["买菜", "超市购物", "日用品", "大采购", "生鲜食品", "柴米油盐", "买菜买肉"],
    },
    ("Groceries", "Convenience Store (99 Speedmart / 7-Eleven / FamilyMart)"): {
        "en": ["convenience store", "mini market", "quick snacks and water", "24 hour store"],
        "bm": ["kedai serbaneka", "kedai runcit mini", "beli barang kecil"],
        "mg": ["singgah mini mart", "beli air speedmart", "tapau oden convenience"],
        "zh": ["便利店", "杂货店", "小商店", "买瓶水", "关东煮"],
    },
    ("Groceries", "Wet Market & Fresh Produce"): {
        "en": ["wet market", "fresh fish and poultry", "morning market fruit", "butcher"],
        "bm": ["pasar pagi", "pasar basah", "beli ikan ayam segar", "pasar malam sayur", "buah buahan segar"],
        "mg": ["pergi pasar pagi", "beli lauk basah"],
        "zh": ["早市", "菜市场", "巴刹", "买鱼买肉", "新鲜水果", "蔬菜"],
    },
    ("Groceries", "Household & Cleaning Supplies"): {
        "en": ["household supplies", "laundry detergent", "dishwashing liquid", "toilet paper", "cleaning products"],
        "bm": ["sabun basuh", "barangan pencuci rumah", "tisu tandas", "keperluan kebersihan"],
        "mg": ["beli sabun cuci", "topup tisu rumah"],
        "zh": ["清洁用品", "洗衣液", "洗洁精", "卫生纸", "纸巾", "家庭日用"],
    },
    ("Transport", "Petrol & EV Charging"): {
        "en": ["petrol", "fuel", "gasoline", "ron95", "ev charging", "electric car charger", "refuel tank"],
        "bm": ["minyak kereta", "isi minyak", "stesen minyak", "pengecasan ev", "petrol motor"],
        "mg": ["isi minyak full tank", "pump petrol", "charge kereta ev"],
        "zh": ["加油", "打油", "添油", "油钱", "汽油", "电动车充电", "充电桩"],
    },
    ("Transport", "Toll (TNG / RFID / PayDirect)"): {
        "en": ["highway toll", "expressway toll", "rfid toll", "paydirect charge", "bridge toll"],
        "bm": ["bayar tol", "lebuh raya tol", "tol rfid", "plaza tol"],
        "mg": ["kena tol highway", "rfid tol deduct"],
        "zh": ["过路费", "高速收费", "路费", "收费站"],
    },
    ("Transport", "Parking"): {
        "en": ["car park fee", "mall parking", "street parking coupon", "valet parking"],
        "bm": ["bayar parking", "tiket parkir", "letak kereta", "kupon parkir"],
        "mg": ["bayar parking mall", "ticket parking"],
        "zh": ["停车费", "泊车费", "停车场", "泊车"],
    },
    ("Transport", "E-Hailing (Grab / AirAsia Ride)"): {
        "en": ["e-hailing ride", "ride to airport", "cab fare", "taxi booking", "commute ride"],
        "bm": ["tambang kereta", "naik teksi", "perjalanan ke lapangan terbang", "panggilan kereta"],
        "mg": ["naik grab pergi kerja", "book ride balik rumah", "ambik cab"],
        "zh": ["打车", "网约车", "叫车", "搭车", "去机场的车费", "坐车"],
    },
    ("Transport", "Public Transit (MRT / LRT / Bus)"): {
        "en": ["public transit", "train ticket", "subway mrt", "commuter train", "bus fare"],
        "bm": ["pengangkutan awam", "tiket kereta api", "tambang bas", "pas tren bulanan"],
        "mg": ["naik mrt lrt", "komuter pergi office", "tap card tren"],
        "zh": ["地铁", "轻轨", "公交车", "搭巴士", "火车票", "通勤月票", "公共交通"],
    },
    ("Transport", "Vehicle Maintenance & Service"): {
        "en": ["car service", "workshop repair", "tyre change", "engine oil change", "car wash", "auto workshop"],
        "bm": ["servis kereta", "bengkel kereta", "tukar tayar", "cuci kereta", "baiki kenderaan"],
        "mg": ["hantar kereta workshop", "tukar minyak hitam", "balancing tayar"],
        "zh": ["修车", "汽车保养", "换轮胎", "洗车", "换黑油", "汽车维修"],
    },
    ("Transport", "Road Tax & Motor Insurance"): {
        "en": ["road tax renewal", "car insurance", "motor takaful renewal"],
        "bm": ["cukai jalan", "insurans kereta", "perbaharui roadtax", "takaful kenderaan"],
        "mg": ["renew roadtax kereta", "bayar insurance motor"],
        "zh": ["路税", "车险", "汽车保险", "更新路税"],
    },
    ("Transport", "Car Loan Installment"): {
        "en": ["car loan payment", "auto loan installment", "monthly vehicle financing"],
        "bm": ["ansuran kereta", "pinjaman kereta bulanan", "bayaran kenderaan"],
        "mg": ["bayar duit kereta bulan ini", "installment kereta"],
        "zh": ["车贷", "供车", "汽车分期月供"],
    },
    ("Bills & utilities", "Electricity (TNB)"): {
        "en": ["electricity bill", "power utility", "monthly electric charge"],
        "bm": ["bil elektrik", "bayaran tenaga elektrik", "bil api rumah"],
        "mg": ["bayar bil api", "bill letrik bulan lepas"],
        "zh": ["电费", "缴电费", "电力账单"],
    },
    ("Bills & utilities", "Water & Sewerage (Air Selangor / IWK)"): {
        "en": ["water bill", "sewerage bill", "water utility charge"],
        "bm": ["bil air", "pembetungan rumah", "bayar air"],
        "mg": ["bayar bil air", "bill air rumah"],
        "zh": ["水费", "排污费", "水务账单"],
    },
    ("Bills & utilities", "Home Internet & WiFi (Unifi / Time)"): {
        "en": ["home internet", "fibre broadband", "wifi bill", "router plan"],
        "bm": ["bil internet rumah", "jalur lebar gentian", "bil wifi"],
        "mg": ["bayar bil wifi rumah", "internet fibre"],
        "zh": ["网费", "宽带费", "家庭网络", "光纤网络"],
    },
    ("Bills & utilities", "Mobile Postpaid & Prepaid"): {
        "en": ["mobile phone bill", "postpaid plan", "prepaid reload data", "telco bill"],
        "bm": ["bil telefon bimbit", "pelan pascabayar", "tambah nilai prabayar", "data internet"],
        "mg": ["bayar bil phone", "topup line kredit", "bill telco"],
        "zh": ["话费", "手机费", "电话费", "流量充值", "电讯账单"],
    },
    ("Bills & utilities", "Streaming & App Subscriptions"): {
        "en": ["streaming subscription", "cloud storage plan", "music app membership", "software renewal", "ai subscription"],
        "bm": ["langganan penstriman", "langganan aplikasi", "keahlian muzik filem", "storan awan"],
        "mg": ["monthly subscription app", "langganan streaming"],
        "zh": ["会员订阅", "流媒体会员", "软件续费", "云盘订阅", "月费订阅"],
    },
    ("Bills & utilities", "Condo Maintenance & Sinking Fund"): {
        "en": ["condo maintenance fee", "jmb sinking fund", "apartment management fee"],
        "bm": ["yuran penyelenggaraan kondo", "dana terikat pangsapuri", "yuran pengurusan"],
        "mg": ["bayar maintenance condo", "jmb bill rumah"],
        "zh": ["物业费", "公寓管理费", "维修基金"],
    },
    ("Loans, BNPL & Credit", "BNPL (SPayLater / GrabPayLater / Atome)"): {
        "en": ["bnpl installment", "buy now pay later bill", "monthly paylater payment"],
        "bm": ["bayaran ansuran beli sekarang bayar kemudian", "bil paylater"],
        "mg": ["bayar bnpl", "settle paylater bulan ini"],
        "zh": ["先买后付", "分期账单", "分期付款"],
    },
    ("Loans, BNPL & Credit", "PTPTN Study Loan"): {
        "en": ["study loan", "student loan repayment", "education loan"],
        "bm": ["bayaran balik pinjaman pelajaran", "pinjaman pendidikan"],
        "mg": ["bayar study loan", "potongan ptptn bulanan"],
        "zh": ["助学贷款", "教育贷款还款", "学贷"],
    },
    ("Loans, BNPL & Credit", "Credit Card Bill"): {
        "en": ["credit card statement payment", "visa mastercard bill settlement"],
        "bm": ["bayar bil kad kredit", "penyata kad kredit"],
        "mg": ["settle credit card bill", "bayar kad kredit"],
        "zh": ["还信用卡", "信用卡账单", "卡债还款"],
    },
    ("Loans, BNPL & Credit", "Personal Loan"): {
        "en": ["personal loan repayment", "monthly loan installment"],
        "bm": ["pinjaman peribadi", "ansuran pinjaman"],
        "mg": ["bayar personal loan bulan ini"],
        "zh": ["个人贷款", "信用贷款还款"],
    },
    ("Loans, BNPL & Credit", "Hire Purchase / Installment"): {
        "en": ["hire purchase installment", "appliance installment plan", "easy payment plan"],
        "bm": ["ansuran sewa beli", "bayaran ansuran bulanan"],
        "mg": ["bayar installment perabot", "ansuran barang"],
        "zh": ["分期供款", "电器分期", "租购分期"],
    },
    ("Housing & property", "Rent"): {
        "en": ["monthly rent", "house rental", "room rent", "apartment lease", "deposit rent"],
        "bm": ["sewa rumah", "sewa bilik", "bayaran sewa bulanan"],
        "mg": ["bayar sewa bilik bulan ini", "transfer rent tuan rumah"],
        "zh": ["房租", "租金", "租房", "交房租", "房间租金"],
    },
    ("Housing & property", "Home Loan / Mortgage"): {
        "en": ["home loan", "mortgage payment", "housing financing"],
        "bm": ["pinjaman perumahan", "ansuran rumah bulanan"],
        "mg": ["bayar housing loan", "installment rumah"],
        "zh": ["房贷", "供房", "房屋贷款", "按揭"],
    },
    ("Housing & property", "Home Repair & Maintenance"): {
        "en": ["plumber repair", "aircond servicing", "electrician repair", "hardware tools", "home maintenance"],
        "bm": ["baiki paip rumah", "servis aircond", "kedai hardware", "tukang rumah"],
        "mg": ["repair sinki bocor", "panggil orang servis aircon"],
        "zh": ["修冷气", "洗冷气", "水电维修", "五金店", "修水管", "家居维修"],
    },
    ("Housing & property", "Furniture & Appliances"): {
        "en": ["home furniture", "kitchen appliance", "mattress sofa", "washing machine fridge"],
        "bm": ["perabot rumah", "perkakas elektrik", "tilam katil", "peti sejuk mesin basuh"],
        "mg": ["beli perabot rumah baru", "shopping appliances"],
        "zh": ["买家具", "家电", "冰箱洗衣机", "床垫沙发", "厨房电器"],
    },
    ("Housing & property", "Cukai Pintu & Cukai Tanah"): {
        "en": ["assessment tax", "quit rent", "municipal property tax"],
        "bm": ["cukai pintu", "cukai tanah", "cukai taksiran majlis"],
        "mg": ["bayar cukai pintu rumah"],
        "zh": ["门牌税", "地税", "房产市政税"],
    },
    ("Shopping", "Online Marketplace (Shopee / Lazada / TikTok)"): {
        "en": ["online shopping", "ecommerce parcel", "marketplace order"],
        "bm": ["beli belah dalam talian", "pesanan bungkusan"],
        "mg": ["shopping online parcel", "checkout cart malam ini"],
        "zh": ["网购", "网上购物", "买快递", "下单买东西", "电商购物"],
    },
    ("Shopping", "Clothing, Bags & Footwear"): {
        "en": ["clothes shopping", "shoes sneakers", "apparel bag", "fashion outfit"],
        "bm": ["beli baju", "kasut dan beg", "pakaian baru"],
        "mg": ["shopping baju kasut baru", "beli sneakers"],
        "zh": ["买衣服", "买鞋子", "包包", "服装鞋帽", "逛街买衣"],
    },
    ("Shopping", "Electronics & Gadgets"): {
        "en": ["laptop computer", "smartphone tablet", "headphones keyboard", "computer accessories", "camera gadget"],
        "bm": ["telefon pintar", "komputer riba", "aksesori gajet", "fon kepala"],
        "mg": ["beli gadget tech baru", "upgrade laptop pc"],
        "zh": ["买手机", "买电脑", "数码产品", "耳机键盘", "电子设备", "电脑配件"],
    },
    ("Shopping", "Beauty, Skincare & Grooming"): {
        "en": ["skincare cosmetics", "sunscreen cleanser", "haircut salon", "barber grooming", "perfume"],
        "bm": ["penjagaan kulit", "kosmetik mekap", "gunting rambut", "salun kecantikan"],
        "mg": ["potong rambut kedai", "beli skincare facial"],
        "zh": ["护肤品", "化妆品", "理发", "剪头发", "洗护用品", "香水", "美容"],
    },
    ("Shopping", "Home & Living"): {
        "en": ["home decor", "bedding towels", "kitchenware storage", "household organizer"],
        "bm": ["kelengkapan rumah", "barangan dapur", "hiasan rumah", "cadar tuala"],
        "mg": ["beli barang deco rumah"],
        "zh": ["家居用品", "床上用品", "收纳", "生活百货", "厨具餐具"],
    },
    ("Health & medical", "Clinic & GP"): {
        "en": ["doctor visit", "clinic consultation", "general practitioner", "fever checkup", "outpatient treatment"],
        "bm": ["jumpa doktor", "klinik kesihatan", "demam selsema", "rawatan klinik"],
        "mg": ["pergi klinik demam", "check doktor panel"],
        "zh": ["看医生", "去诊所", "看病", "发烧看门诊", "感冒看诊", "体检挂号"],
    },
    ("Health & medical", "Pharmacy & Medicine"): {
        "en": ["pharmacy medicine", "vitamins supplements", "cold flu tablets", "prescription refill"],
        "bm": ["beli ubat", "farmasi kesihatan", "vitamin tambahan", "ubat demam"],
        "mg": ["singgah farmasi beli ubat", "topup vitamin"],
        "zh": ["买药", "药房", "药店", "维生素", "保健品", "感冒药"],
    },
    ("Health & medical", "Hospital & Specialist"): {
        "en": ["specialist hospital", "blood test lab", "x-ray scan", "medical ward", "surgery consultation"],
        "bm": ["hospital pakar", "ujian darah makmal", "imbasan perubatan", "rawatan pakar"],
        "mg": ["follow up specialist hospital", "buat blood test"],
        "zh": ["看专科", "去医院", "验血检查", "住院费用", "医疗检查"],
    },
    ("Health & medical", "Dental & Optical"): {
        "en": ["dentist scaling", "dental clinic", "tooth filling", "eyeglasses spectacles", "contact lenses", "optometrist"],
        "bm": ["klinik gigi", "cuci gigi", "cermin mata", "kanta lekap", "kedai optik"],
        "mg": ["pergi dentist cuci gigi", "buat spek mata baru"],
        "zh": ["看牙医", "洗牙", "补牙", "配眼镜", "隐形眼镜", "眼科验光"],
    },
    ("Health & medical", "Full Medical Checkup (LHDN)"): {
        "en": ["full medical checkup", "annual health screening", "executive medical checkup", "vaccination"],
        "bm": ["pemeriksaan kesihatan penuh", "saringan kesihatan tahunan", "vaksinasi"],
        "mg": ["buat full medical checkup", "health screening pakej"],
        "zh": ["全身检查", "年度体检", "健康体检套餐", "疫苗接种"],
    },
    ("Health & medical", "Gym, Sports & Fitness"): {
        "en": ["gym membership", "badminton court", "sports equipment", "running shoes", "fitness studio", "swimming"],
        "bm": ["keahlian gimnasium", "gelanggang badminton", "peralatan sukan", "kasut larian", "kecergasan"],
        "mg": ["main badminton court dengan member", "renew gym pass"],
        "zh": ["健身房", "打羽毛球", "订球场", "运动装备", "跑步鞋", "健身月卡", "游泳"],
    },
    ("Insurance & Takaful", "Medical & Health Card"): {
        "en": ["medical card insurance", "health insurance premium", "hospitalization takaful"],
        "bm": ["kad perubatan", "insurans kesihatan", "takaful perubatan"],
        "mg": ["bayar medical card bulan ini"],
        "zh": ["医药卡", "医疗保险", "重疾医疗保费"],
    },
    ("Insurance & Takaful", "Life Insurance / Takaful"): {
        "en": ["life insurance premium", "family takaful plan", "critical illness policy"],
        "bm": ["insurans hayat", "takaful keluarga", "caruman bulanan"],
        "mg": ["bayar policy life insurance"],
        "zh": ["人寿保险", "寿险保费", "家庭保障计划"],
    },
    ("Insurance & Takaful", "Education Insurance"): {
        "en": ["child education insurance", "study savings policy"],
        "bm": ["insurans pendidikan anak", "pelan simpanan pendidikan"],
        "mg": ["caruman education policy anak"],
        "zh": ["孩子教育保险", "教育储蓄保单"],
    },
    ("Insurance & Takaful", "Personal Accident"): {
        "en": ["personal accident insurance", "pa coverage renewal"],
        "bm": ["insurans kemalangan diri", "perlindungan pa"],
        "mg": ["renew personal accident cover"],
        "zh": ["个人意外险", "意外保险"],
    },
    ("Education", "Books & Stationery"): {
        "en": ["books", "bookstore novel", "school stationery", "printer paper pens", "reading magazine"],
        "bm": ["kedai buku", "alat tulis pejabat", "buku rujukan", "novel membaca"],
        "mg": ["beli buku alat tulis", "shopping bookstore"],
        "zh": ["买书", "书店", "文具", "小说书籍", "参考书", "笔记本和笔"],
    },
    ("Education", "Higher Education & Tuition"): {
        "en": ["university tuition fee", "college semester fee", "tuition center class"],
        "bm": ["yuran pengajian universiti", "yuran kolej", "pusat tuisyen"],
        "mg": ["bayar yuran sem uni", "tuition fee"],
        "zh": ["大学学费", "补习费", "课程学费", "补习班"],
    },
    ("Education", "School & Childcare Fees"): {
        "en": ["kindergarten fee", "nursery daycare", "preschool monthly fee", "primary school fee"],
        "bm": ["yuran tadika", "yuran taska anak", "yuran sekolah"],
        "mg": ["bayar tadika anak bulan ini"],
        "zh": ["幼儿园学费", "托儿所", "安亲班", "学校杂费"],
    },
    ("Education", "Upskilling & Certification"): {
        "en": ["online course", "professional certification", "coding workshop", "upskilling seminar"],
        "bm": ["kursus peningkatan kemahiran", "sijil profesional", "bengkel latihan"],
        "mg": ["ambik online course certification"],
        "zh": ["网课", "技能提升课程", "考证培训", "研讨会"],
    },
    ("Family & gifts", "Parents Allowance"): {
        "en": ["parents allowance", "monthly money for mom and dad", "family remittance"],
        "bm": ["elaun ibu bapa", "duit belanja mak ayah", "beri kepada orang tua"],
        "mg": ["bagi duit mak ayah bulan ini", "transfer parents allowance"],
        "zh": ["给父母家用", "孝敬父母", "爸爸妈妈生活费", "孝亲费"],
    },
    ("Family & gifts", "Childcare & Family Support"): {
        "en": ["baby diapers formula milk", "kids supplies", "family support allowance"],
        "bm": ["lampin bayi dan susu", "keperluan anak kecil", "nafkah keluarga"],
        "mg": ["beli pampers susu anak", "barang baby"],
        "zh": ["纸尿裤奶粉", "宝宝用品", "家庭补贴", "小孩零花钱"],
    },
    ("Family & gifts", "Festive (Angpao / Duit Raya) & Gifts"): {
        "en": ["birthday present", "wedding gift", "flower bouquet", "festive red packet", "gift hamper"],
        "bm": ["duit raya", "hadiah hari jadi", "jambangan bunga", "hadiah perkahwinan", "hamper perayaan"],
        "mg": ["bagi angpao wedding kawan", "beli hadiah birthday", "duit raya budak"],
        "zh": ["红包", "礼金", "生日礼物", "送花", "礼盒", "节日送礼"],
    },
    ("Family & gifts", "Zakat & Fitrah"): {
        "en": ["zakat payment", "zakat fitrah", "income zakat contribution"],
        "bm": ["bayar zakat fitrah", "zakat pendapatan", "zakat harta"],
        "mg": ["tunaikan zakat online"],
        "zh": ["宗教天课捐款", "义捐天课"],
    },
    ("Family & gifts", "Charity & Donations"): {
        "en": ["charity donation", "ngo contribution", "animal shelter aid", "relief fund"],
        "bm": ["derma kebajikan", "sumbangan amal", "tabung bantuan"],
        "mg": ["derma charity organisasi"],
        "zh": ["捐款", "慈善募捐", "爱心公益"],
    },
    ("Entertainment & travel", "Movies, Concerts & Events"): {
        "en": ["movie cinema tickets", "concert ticket", "theme park pass", "live show"],
        "bm": ["tiket wayang", "pawagam filem", "konsert hiburan", "taman tema"],
        "mg": ["tengok wayang weekend", "beli tiket concert"],
        "zh": ["看电影", "电影票", "演唱会门票", "游乐园门票"],
    },
    ("Entertainment & travel", "Games & Hobbies"): {
        "en": ["video games", "steam store game", "mobile game topup", "collectible hobby"],
        "bm": ["permainan video", "tambah nilai permainan", "hobi koleksi"],
        "mg": ["beli game pc steam", "topup diamond game"],
        "zh": ["买游戏", "游戏充值", "点卡", "模型手办", "兴趣爱好"],
    },
    ("Entertainment & travel", "Flights & Transport"): {
        "en": ["flight ticket", "airline booking", "luggage add-on", "intercity express bus", "train travel"],
        "bm": ["tiket kapal terbang", "penerbangan percutian", "tiket bas ekspres"],
        "mg": ["book flight ticket cuti", "beli tiket balik kampung"],
        "zh": ["买机票", "飞机票", "长途巴士票", "高铁车票", "航空订票"],
    },
    ("Entertainment & travel", "Hotel & Holiday Stay"): {
        "en": ["hotel booking", "resort staycation", "airbnb homestay", "vacation accommodation"],
        "bm": ["tempahan hotel", "inap desa percutian", "penginapan resort"],
        "mg": ["book hotel staycation cuti", "homestay family trip"],
        "zh": ["订酒店", "民宿", "度假村住宿", "旅游住宿", "出游开房"],
    },
    ("Investments & savings", "EPF Voluntary (i-Saraan)"): {
        "en": ["epf voluntary contribution", "retirement fund topup", "kwsp savings"],
        "bm": ["caruman sukarela kwsp", "simpanan persaraan"],
        "mg": ["topup sendiri epf i-saraan"],
        "zh": ["公积金自愿供款", "退休金储蓄"],
    },
    ("Investments & savings", "ASB / Tabung Haji"): {
        "en": ["unit trust deposit", "tabung haji savings", "asb investment transfer"],
        "bm": ["simpanan asb", "pelaburan tabung haji", "amanah saham"],
        "mg": ["transfer masuk asb saving"],
        "zh": ["国民信托基金存款", "储蓄投资定存"],
    },
    ("Investments & savings", "PRS (Private Retirement Scheme)"): {
        "en": ["private retirement scheme contribution", "prs tax relief fund"],
        "bm": ["skim persaraan swasta", "caruman prs"],
        "mg": ["topup prs untuk tax relief"],
        "zh": ["私人退休计划", "退休投资减税供款"],
    },
    ("Investments & savings", "SSPN Savings"): {
        "en": ["education savings fund", "sspn child deposit"],
        "bm": ["simpanan pendidikan sspn", "deposit anak"],
        "mg": ["topup sspn pelepasan cukai"],
        "zh": ["国家教育储蓄基金", "孩子教育储蓄存款"],
    },
    ("Investments & savings", "Stocks, ETF & Crypto"): {
        "en": ["stock brokerage deposit", "etf shares investment", "robo advisor fund"],
        "bm": ["pelaburan saham", "dana indeks etf", "pelaburan digital"],
        "mg": ["masuk duit saham etf"],
        "zh": ["买股票", "基金定投", "证券入金", "投资理财"],
    },
    ("Taxes & government", "LHDN Income Tax / PCB"): {
        "en": ["income tax payment", "monthly tax deduction", "lhdn assessment"],
        "bm": ["cukai pendapatan", "potongan cukai bulanan", "bayaran cukai"],
        "mg": ["bayar income tax e-filing"],
        "zh": ["个人所得税", "报税缴税", "税务局付款"],
    },
    ("Taxes & government", "JPJ / Renewals / Summons"): {
        "en": ["driving license renewal", "traffic summons fine", "passport immigration fee"],
        "bm": ["lesen memandu", "bayar saman trafik", "perbaharui pasport", "urusan kerajaan"],
        "mg": ["bayar saman trafik", "renew lesen memandu"],
        "zh": ["换驾照", "交通罚单", "更新护照", "政府规费"],
    },
    ("Taxes & government", "Government Service & Stamp Duty"): {
        "en": ["stamp duty fee", "government stamp processing", "official certificate fee"],
        "bm": ["duti setem", "bayaran perkhidmatan kerajaan", "fi rasmi"],
        "mg": ["bayar stamp duty perjanjian"],
        "zh": ["印花税", "政府手续费", "官方认证费"],
    },
    ("Fees & charges", "Bank & Transfer Fee"): {
        "en": ["wire transfer fee", "atm withdrawal charge", "service charge bank"],
        "bm": ["caj pindahan bank", "yuran atm", "caj perkhidmatan bank"],
        "mg": ["kena bank transfer fee"],
        "zh": ["银行手续费", "转账费用", "取款手续费"],
    },
    ("Fees & charges", "Foreign Currency Fee"): {
        "en": ["foreign currency conversion fee", "overseas markup charge", "fx fee"],
        "bm": ["caj tukaran mata wang asing", "yuran transaksi luar negara"],
        "mg": ["kena fx fee luar negara"],
        "zh": ["外币转换费", "跨境手续费", "汇率手续费"],
    },
    ("Fees & charges", "Late Payment Fee"): {
        "en": ["late payment charge", "overdue penalty fee"],
        "bm": ["caj bayaran lewat", "denda lewat bayar"],
        "mg": ["terlambat bayar kena late fee"],
        "zh": ["逾期罚款", "滞纳金", "迟交罚款"],
    },
    ("Fees & charges", "Card Annual Fee"): {
        "en": ["credit card annual fee", "card membership renewal fee"],
        "bm": ["yuran tahunan kad kredit", "caj tahunan kad"],
        "mg": ["annual fee kad kredit kena charge"],
        "zh": ["信用卡年费", "银行卡年费"],
    },
    ("Salary", ""): {
        "en": ["monthly salary", "paycheck credited", "monthly payroll", "work bonus", "staff allowance"],
        "bm": ["gaji bulanan", "pendapatan gaji", "elaun kerja masuk", "bonus tahunan"],
        "mg": ["gaji dah masuk", "payday bonus credited", "duit gaji bulan ini"],
        "zh": ["工资", "薪水入账", "发工资", "年终奖金", "底薪"],
    },
    ("Other income", ""): {
        "en": ["money received from friend", "split bill repayment", "freelance payout", "cashback rebate credit", "side hustle income"],
        "bm": ["terima duit kawan", "duitnow masuk akaun", "bayaran kerja bebas", "pulangan tunai"],
        "mg": ["kawan bayar balik split", "terima duitnow client", "dapat side income"],
        "zh": ["朋友还钱", "收到转账", "兼职收入", "收款入账", "返现到账"],
    },
    ("Miscellaneous", ""): {
        "en": ["ewallet topup", "account transfer", "wallet reload balance", "internal fund move"],
        "bm": ["tambah nilai dompet", "pindahan antara akaun", "pindah duit sendiri"],
        "mg": ["topup ewallet dari bank", "transfer duit ke akaun lain", "reload tng balance"],
        "zh": ["钱包充值", "账户互转", "转钱给自己", "加额电子钱包"],
    },
}

LHDN_GENERAL_SEEDS = {
    "en": ["lhdn tax relief", "tax deductible expenses", "income tax claimable", "tax exemption receipts", "e-filing claims"],
    "bm": ["pelepasan cukai lhdn", "perbelanjaan layak potongan cukai", "resit pelepasan cukai", "tuntutan cukai pendapatan"],
    "mg": ["item boleh claim tax relief", "pelepasan cukai tahun ini", "resit untuk e-filing lhdn"],
    "zh": ["可扣税项目", "个人所得税减免", "报税抵扣", "可退税消费", "税务减免单据"],
}

KIND_SEARCH_SEEDS = {
    "refund": {
        "en": ["money returned", "cancelled order refund", "cashback reversal", "refunded purchase"],
        "bm": ["bayaran balik", "pulangan wang", "pesanan dibatalkan"],
        "mg": ["duit refund masuk balik", "barang cancel refund"],
        "zh": ["退款", "退钱", "退货退款", "取消订单退款"],
    },
    "shared": {
        "en": ["split bill", "shared dinner with friends", "friends owe me", "dutch treat"],
        "bm": ["bil kongsi", "makan kongsi bayar", "kawan berhutang"],
        "mg": ["split bill dengan kawan", "tolong bayar dulu", "makan aa"],
        "zh": ["平分账单", "朋友AA", "帮朋友垫付", "分摊聚餐"],
    },
    "income": {
        "en": ["monthly salary", "paycheck received", "freelance income", "bonus credited", "money received"],
        "bm": ["gaji bulanan", "pendapatan diterima", "elaun masuk", "bonus kerja"],
        "mg": ["gaji dah masuk", "terima bayaran client"],
        "zh": ["工资", "发薪水", "奖金入账", "兼职收入", "收到汇款"],
    },
    "transfer": {
        "en": ["wallet topup", "account transfer", "moved money between accounts", "reload balance"],
        "bm": ["pindahan akaun", "tambah nilai dompet", "pindah duit sendiri"],
        "mg": ["topup ewallet dari bank", "transfer duit antara akaun"],
        "zh": ["账户转账", "钱包充值", "加额", "转钱给自己"],
    },
}

# Fresh Unseen SMEs strictly reserved for VAL / BLIND TEST (never placed in train set!)
UNSEEN_TEST_SMES = [
    ("Food & drinks", "Coffee, Tea & Drinks", "Roast & Ground PJ"),
    ("Food & drinks", "Coffee, Tea & Drinks", "Kopi & Toast Sentul"),
    ("Food & drinks", "Coffee, Tea & Drinks", "Matcha Craft Mont Kiara"),
    ("Food & drinks", "Hawker & Mamak", "Warung Kak Mah Ampang"),
    ("Food & drinks", "Hawker & Mamak", "Restoran Salam Jaya Bistro"),
    ("Food & drinks", "Hawker & Mamak", "Kedai Mi Udang Bukit Mertajam"),
    ("Food & drinks", "Fast Food", "Crispy Bird Burger Damansara"),
    ("Food & drinks", "Food Delivery (GrabFood / Foodpanda)", "FoodDelivery Express KL"),
    ("Food & drinks", "Restaurant & Cafe", "Bistro Harmoni Bangsar"),
    ("Food & drinks", "Restaurant & Cafe", "Dapur Seri Melaka Subang"),
    ("Food & drinks", "Groceries & Snacks", "Roti Hangat Bakery Cheras"),
    ("Groceries", "Supermarket & Hypermarket", "Pasar Segar Sejahtera Puchong"),
    ("Groceries", "Supermarket & Hypermarket", "Borong Mart Kajang"),
    ("Groceries", "Convenience Store (99 Speedmart / 7-Eleven / FamilyMart)", "Kedai Mini Cahaya Setapak"),
    ("Groceries", "Wet Market & Fresh Produce", "Gerai Sayur Hijau Kepong"),
    ("Groceries", "Household & Cleaning Supplies", "CleanHome Supplies Klang"),
    ("Transport", "Petrol & EV Charging", "Stesen Minyak Maju Shah Alam"),
    ("Transport", "Petrol & EV Charging", "VoltCharge Station Cyberjaya"),
    ("Transport", "Toll (TNG / RFID / PayDirect)", "Lebuhraya Selatan Tol"),
    ("Transport", "Parking", "Menara Sentral Autoshop Parking"),
    ("Transport", "E-Hailing (Grab / AirAsia Ride)", "CityRide Express KLIA"),
    ("Transport", "Public Transit (MRT / LRT / Bus)", "Rapid Rail Pass Counter"),
    ("Transport", "Vehicle Maintenance & Service", "Bengkel Motor & Tayar Cekap"),
    ("Transport", "Vehicle Maintenance & Service", "Pusat Servis Automotif Wira"),
    ("Transport", "Road Tax & Motor Insurance", "Biro Insurans Kenderaan Maju"),
    ("Transport", "Car Loan Installment", "Pembiayaan Kenderaan Nasional"),
    ("Bills & utilities", "Electricity (TNB)", "TNB Careline AutoBil"),
    ("Bills & utilities", "Water & Sewerage (Air Selangor / IWK)", "Bil Air Negeri Bulanan"),
    ("Bills & utilities", "Home Internet & WiFi (Unifi / Time)", "FiberHome Broadband"),
    ("Bills & utilities", "Mobile Postpaid & Prepaid", "MyTelco Postpaid Billing"),
    ("Bills & utilities", "Streaming & App Subscriptions", "CloudNotes Pro Plan"),
    ("Bills & utilities", "Condo Maintenance & Sinking Fund", "JMB Residensi Tropika"),
    ("Loans, BNPL & Credit", "BNPL (SPayLater / GrabPayLater / Atome)", "FlexPay Installment MY"),
    ("Loans, BNPL & Credit", "PTPTN Study Loan", "Bayaran Tabung Siswazah"),
    ("Loans, BNPL & Credit", "Credit Card Bill", "Card Statement Settlement"),
    ("Housing & property", "Rent", "Encik Razak Tenancy Rent"),
    ("Housing & property", "Home Repair & Maintenance", "Kedai Hardware Tukang Kuat"),
    ("Housing & property", "Furniture & Appliances", "Perabot Klasik Sungai Buloh"),
    ("Shopping", "Online Marketplace (Shopee / Lazada / TikTok)", "ShopMart Online Checkout"),
    ("Shopping", "Clothing, Bags & Footwear", "Gaya Kasut Boutique KL"),
    ("Shopping", "Electronics & Gadgets", "Digital Zone Lowyat"),
    ("Shopping", "Beauty, Skincare & Grooming", "Salun Gunting Rambut Kacak"),
    ("Shopping", "Home & Living", "CozyHome Living Mid Valley"),
    ("Health & medical", "Clinic & GP", "Klinik Keluarga Impian"),
    ("Health & medical", "Clinic & GP", "Poliklinik Sihat Sri Petaling"),
    ("Health & medical", "Pharmacy & Medicine", "Farmasi Mesra Rakyat"),
    ("Health & medical", "Hospital & Specialist", "Pusat Pakar Medis Puteri"),
    ("Health & medical", "Dental & Optical", "Klinik Pergigian Berseri"),
    ("Health & medical", "Dental & Optical", "Pusat Optometri Fokus"),
    ("Health & medical", "Full Medical Checkup (LHDN)", "PathLab Executive Screening"),
    ("Health & medical", "Gym, Sports & Fitness", "Arena Badminton Tangkas"),
    ("Health & medical", "Gym, Sports & Fitness", "IronCore Fitness Club"),
    ("Insurance & Takaful", "Medical & Health Card", "Takaful Ikhlas MedCard"),
    ("Insurance & Takaful", "Life Insurance / Takaful", "Amanah Hayat Takaful"),
    ("Education", "Books & Stationery", "Kedai Buku Pustaka Ilmu"),
    ("Education", "Higher Education & Tuition", "Pusat Tuisyen Cendekia"),
    ("Education", "School & Childcare Fees", "Tadika Bintang Kecil"),
    ("Education", "Upskilling & Certification", "Akademi Kemahiran Digital"),
    ("Family & gifts", "Parents Allowance", "Kiriman Ibu Bapa Bulanan"),
    ("Family & gifts", "Childcare & Family Support", "Kedai Susu & Lampin Sayang"),
    ("Family & gifts", "Festive (Angpao / Duit Raya) & Gifts", "Kreatif Florist & Hadiah"),
    ("Family & gifts", "Zakat & Fitrah", "Pusat Pungutan Zakat"),
    ("Family & gifts", "Charity & Donations", "Tabung Kebajikan Harapan"),
    ("Entertainment & travel", "Movies, Concerts & Events", "Cineplex Premiere Ticket"),
    ("Entertainment & travel", "Games & Hobbies", "PixelQuest Digital Store"),
    ("Entertainment & travel", "Flights & Transport", "SkyJet Domestic Flights"),
    ("Entertainment & travel", "Hotel & Holiday Stay", "Villa Bayu Resort Langkawi"),
    ("Investments & savings", "SSPN Savings", "Tabung Pendidikan SSPN-i"),
    ("Investments & savings", "Stocks, ETF & Crypto", "Bursa ETF Brokerage"),
    ("Taxes & government", "LHDN Income Tax / PCB", "Bayaran Hasil Dalam Negeri"),
    ("Taxes & government", "JPJ / Renewals / Summons", "Kaunter Lesen & Saman"),
]


def _pick_entity_banks(seed: int = 20261124):
    """Partitions 771 MERCHANTS + synthetic entities cleanly into train vs val vs blind_test with ZERO group overlap."""
    unseen_nks = {f4.normalize_merchant_group(nm) for _, _, nm in UNSEEN_TEST_SMES}
    train_ents = defaultdict(list)  # (cat, sub) -> [merchant_names]
    val_ents = defaultdict(list)
    test_ents = defaultdict(list)
    group_split = {}  # nk -> "train" | "val" | "test"

    for pair_key, names in sorted(f4.MERCHANT_SEEDS_2026.items()):
        cat, sub = pair_key.split(" || ", 1)
        by_group = defaultdict(list)
        for nm in names:
            s = nm.strip()
            if not s:
                continue
            nk = f4.normalize_merchant_group(s)
            if not nk or nk in unseen_nks:
                continue
            by_group[nk].append(s)

        new_groups = sorted(nk for nk in by_group if nk not in group_split)
        pair_salt = int.from_bytes(hashlib.md5(pair_key.encode("utf-8")).digest()[:4], "big")
        rng_p = random.Random(seed + (pair_salt % 100000))
        rng_p.shuffle(new_groups)

        if len(new_groups) >= 5:
            n_te = max(1, int(round(0.15 * len(new_groups))))
            n_va = max(1, int(round(0.12 * len(new_groups))))
            for nk in new_groups[:n_te]:
                group_split[nk] = "test"
            for nk in new_groups[n_te:n_te + n_va]:
                group_split[nk] = "val"
            for nk in new_groups[n_te + n_va:]:
                group_split[nk] = "train"
        else:
            for nk in new_groups:
                group_split[nk] = "train"

        for nk, items in by_group.items():
            sp = group_split[nk]
            if sp == "test":
                test_ents[(cat, sub)].extend(items)
            elif sp == "val":
                val_ents[(cat, sub)].extend(items)
            else:
                train_ents[(cat, sub)].extend(items)

    rng_u = random.Random(seed + 77)
    unseen_shuffled = list(UNSEEN_TEST_SMES)
    rng_u.shuffle(unseen_shuffled)
    for idx, (cat, sub, nm) in enumerate(unseen_shuffled):
        if idx % 5 == 0:
            val_ents[(cat, sub)].append(nm)
        else:
            test_ents[(cat, sub)].append(nm)

    blocked_nks = set(unseen_nks)
    for plist in (val_ents, test_ents):
        for nms in plist.values():
            for nm in nms:
                nk = f4.normalize_merchant_group(nm)
                if nk:
                    blocked_nks.add(nk)

    # Mine additional frequent clean train merchants from dataset_malaysian_v5.jsonl.gz (non-eval only!)
    if DATASET_V5_GZ_PATH.exists():
        seen_train = {f4.normalize_merchant_group(nm) for nms in train_ents.values() for nm in nms}
        counts = Counter()
        votes = defaultdict(Counter)
        forms = defaultdict(Counter)
        with gzip.open(DATASET_V5_GZ_PATH, "rt", encoding="utf-8") as fh:
            for line in fh:
                s = line.strip()
                if not s:
                    continue
                r = json.loads(s)
                em = str(r.get("expected_merchant", "")).strip()
                if not em or "*" in em or len(em) < 4 or len(em) > 45:
                    continue
                nk = f4.normalize_merchant_group(em)
                if not nk or nk in blocked_nks or nk in seen_train:
                    continue
                pair = (r.get("category", ""), r.get("subcategory", ""))
                if pair not in PAIR_INDEX:
                    continue
                counts[nk] += 1
                votes[nk][pair] += 1
                forms[nk][em] += 1
        for nk, cnt in counts.most_common(450):
            if cnt < 3:
                continue
            pair, tv = votes[nk].most_common(1)[0]
            if tv / float(cnt) >= 0.85:
                rep = forms[nk].most_common(1)[0][0]
                if f4.normalize_merchant_group(rep) not in blocked_nks:
                    train_ents[pair].append(rep)
                    seen_train.add(nk)

    # Guarantee zero group overlap even across surface variants
    for pair in list(train_ents.keys()):
        train_ents[pair] = [nm for nm in train_ents[pair] if f4.normalize_merchant_group(nm) not in blocked_nks]

    return train_ents, val_ents, test_ents


def run_qwen_multilingual_synthesis():
    """Synthesizes diverse EN/BM/Manglish/ZH search queries & quick-entry phrases via Qwen2.5-7B on GPU."""
    import torch

    if GEN_CACHE_PATH.exists():
        try:
            cached = json.loads(GEN_CACHE_PATH.read_text(encoding="utf-8"))
            if len(cached.get("search_phrases", [])) >= 1200 and len(cached.get("quick_entries", [])) >= 400:
                print(f"[CACHE] Reusing {len(cached['search_phrases'])} search + {len(cached['quick_entries'])} quick entries", flush=True)
                return cached
        except Exception:
            pass

    update_status("LOADING_QWEN_TEACHER")
    from transformers import AutoModelForCausalLM, AutoTokenizer

    vram_gb = torch.cuda.get_device_properties(0).total_memory / (1024 ** 3) if torch.cuda.is_available() else 0.0
    dtype = torch.bfloat16 if (torch.cuda.is_available() and torch.cuda.get_device_capability(0)[0] >= 8) else torch.float16

    model = None
    tok = None
    used_id = None
    for mid in QWEN_CANDIDATES:
        try:
            print(f"Loading Qwen teacher: {mid} (VRAM={vram_gb:.1f}GB, dtype={dtype})...", flush=True)
            tok = AutoTokenizer.from_pretrained(mid)
            tok.padding_side = "left"
            if tok.pad_token is None:
                tok.pad_token = tok.eos_token
            model = AutoModelForCausalLM.from_pretrained(mid, torch_dtype=dtype, device_map="auto")
            model.eval()
            used_id = mid
            break
        except Exception as e:
            print(f"Failed {mid}: {e}", flush=True)
    if model is None:
        raise RuntimeError("Could not load any Qwen teacher model")

    # Build concept query synthesis prompts across all 74 subcategories (grouped in batches of 4-5)
    langs = [
        ("en", "English"),
        ("bm", "Bahasa Melayu"),
        ("mg", "Malaysian Manglish (mixed Malay/English casual everyday phrasing)"),
        ("zh", "Simplified Chinese (everyday Malaysian/Singaporean Chinese)"),
    ]
    search_prompts = []
    pair_chunks = [PAIRS_74[i:i + 5] for i in range(0, len(PAIRS_74), 5)]
    for rep in range(2):
        for lang_code, lang_desc in langs:
            for c_idx, chunk in enumerate(pair_chunks):
                items_txt = "\n".join(f"- {c} || {s}" for c, s in chunk)
                split_hint = "colloquial search queries you'd type in an expense tracker (short 2-5 word phrases, NO brand names if possible, focus on everyday intent/symptoms/items)" if rep == 0 else "varied natural synonyms, slang, or descriptive search phrases someone might remember an expense by (2-6 words)"
                prompt = (
                    f"Generate 4 realistic {lang_desc} {split_hint} for EACH Malaysian personal-finance category below.\n"
                    f"Return ONLY valid JSON: {{\"items\": [{{\"pair\": \"Category || Subcategory\", \"queries\": [\"...\", \"...\", \"...\", \"...\"]}}]}}.\n"
                    f"Only Simplified Chinese, Malay, or English script allowed (no Tamil/Arabic/Thai/Japanese).\n\n{items_txt}"
                )
                search_prompts.append((lang_code, rep, prompt))

    # Build LHDN + Kind search synthesis prompts
    special_targets = [
        ("lhdn", "tax_relief", "LHDN Malaysia income tax relief / deductible expenses / medical & lifestyle tax receipts"),
        ("kind", "refund", "refunds, reversed payments, cancelled online order money back"),
        ("kind", "shared", "split bills with friends, AA, kongsi makan, paying on behalf of friend"),
        ("kind", "income", "monthly salary received, paycheck, bonus, freelance transfer received"),
        ("kind", "transfer", "topup e-wallet, account transfer from bank to TNG eWallet"),
    ]
    for lang_code, lang_desc in langs:
        for s_kind, s_key, s_desc in special_targets:
            prompt = (
                f"Generate 8 short realistic {lang_desc} search queries (2-6 words) a Malaysian user types into an expense app to find: {s_desc}.\n"
                f"Return ONLY valid JSON: {{\"items\": [{{\"pair\": \"{s_kind}:{s_key}\", \"queries\": [\"...\"]}}]}}."
            )
            search_prompts.append((lang_code, 0, prompt))

    # Build Multilingual Quick-Entry prompts (covering unseen test phrasing & training colloquial notes)
    quick_prompts = []
    qe_scenarios = [
        ("expense", "daily coffee, hawker food, petrol, toll, clinic, groceries, online shopping, utilities"),
        ("shared", "split bill 2-way or 3-way, kongsi makan, AA hotpot, or paid on behalf of friend (e.g. Sarah/Ali/Mei)"),
        ("refund", "refund / bayaran balik / 退款 credited back from store/app"),
        ("income", "salary / gaji / 工资 or incoming money from a friend/client"),
        ("transfer", "transfer / pindah / topup / 充值 between Maybank MAE, TNG eWallet, Cash, CIMB, GXBank"),
    ]
    qe_sample_pairs = PAIRS_74[::3]
    for lang_code, lang_desc in langs:
        for kind_name, kind_desc in qe_scenarios:
            for split_role in ("train_note", "blind_test"):
                for round_i in range(2):
                    sub_pairs = qe_sample_pairs[(round_i * 12):((round_i + 1) * 12)]
                    pair_list_str = ", ".join(f"{c} || {s}" for c, s in sub_pairs[:8])
                    date_hint = "today, yesterday/semalam/昨天, kelmarin/前天, or 2 days ago"
                    acct_hint = "cash/tunai/现金, tng, mae/maybank, cimb, gxbank, shopeepay"
                    prompt = (
                        f"Generate 8 realistic short Malaysian quick-log expense entries in {lang_desc} for transaction kind='{kind_name}' ({kind_desc}).\n"
                        f"Vary amounts (RM 3.50 to RM 1250, use local currency style where natural like '12.50', 'RM 18', '25块'), dates ({date_hint}), accounts ({acct_hint}). Do NOT use famous multinational chain names if possible; prefer generic descriptions or local Malaysian shop names.\n"
                        f"Allowed expense categories if kind in ('expense','shared','refund'): {pair_list_str}.\n"
                        f"Return ONLY compact JSON: {{\"entries\": [{{\"text\": \"...\", \"kind\": \"{kind_name}\", \"amount_myr\": 12.50, \"days_ago\": 0, \"merchant_or_desc\": \"...\", \"account_hint\": \"cash|tng|mae|cimb|gxbank|shopeepay|any\", \"person\": \"\", \"split_ways\": 2, \"paid_for_friend_only\": false, \"category\": \"Food & drinks\", \"subcategory\": \"Hawker & Mamak\"}}]}}."
                    )
                    quick_prompts.append((lang_code, kind_name, split_role, prompt))

    def batched_generate(prompts_meta, get_prompt_fn, max_new=680, batch_size=16):
        texts_out = []
        formatted = []
        for meta in prompts_meta:
            msgs = [{"role": "user", "content": get_prompt_fn(meta)}]
            formatted.append(tok.apply_chat_template(msgs, tokenize=False, add_generation_prompt=True))
        for st in range(0, len(formatted), batch_size):
            batch_txt = formatted[st:st + batch_size]
            enc = tok(batch_txt, return_tensors="pt", padding=True, truncation=True, max_length=1536).to(model.device)
            in_len = enc["input_ids"].shape[1]
            with torch.no_grad():
                gen_ids = model.generate(
                    **enc,
                    max_new_tokens=max_new,
                    do_sample=True,
                    temperature=0.80,
                    top_p=0.92,
                    pad_token_id=tok.pad_token_id,
                )
            for b_idx in range(len(batch_txt)):
                out_str = tok.decode(gen_ids[b_idx, in_len:], skip_special_tokens=True)
                texts_out.append(out_str)
            if (st // batch_size) % 3 == 0:
                update_status("QWEN_SYNTHESIS", progress=f"{min(st + batch_size, len(formatted))}/{len(formatted)}")
        return texts_out

    def extract_json_obj(raw_txt: str):
        s = raw_txt.strip()
        m = re.search(r"\{.*\}", s, flags=re.S)
        if m:
            try:
                return json.loads(m.group(0))
            except Exception:
                pass
        # Rescue truncated array items if output hit max_new_tokens near the tail
        objs = []
        for om in re.finditer(r"\{[^{}]*\}", s):
            try:
                cand = json.loads(om.group(0))
                if "pair" in cand or "text" in cand:
                    objs.append(cand)
            except Exception:
                pass
        if objs:
            if "pair" in objs[0]:
                return {"items": objs}
            return {"entries": objs}
        return None

    update_status("QWEN_SYNTHESIS_SEARCH", count=len(search_prompts), model=used_id)
    raw_search_outs = batched_generate(search_prompts, lambda x: x[2], max_new=768, batch_size=16)

    search_phrases = []
    seen_q = set()
    for (lang_code, rep, _), out_str in zip(search_prompts, raw_search_outs):
        obj = extract_json_obj(out_str)
        if not obj or not isinstance(obj.get("items"), list):
            continue
        for it in obj["items"]:
            pair_s = str(it.get("pair", "")).strip()
            qs = it.get("queries", [])
            if not isinstance(qs, list):
                continue
            target_cat, target_sub, target_special = "", "", ""
            if " || " in pair_s:
                c, sb = pair_s.split(" || ", 1)
                if (c, sb) not in PAIR_INDEX:
                    continue
                target_cat, target_sub = c, sb
            elif pair_s.startswith(("lhdn:", "kind:")):
                target_special = pair_s
            else:
                continue
            for q in qs:
                qs_clean = str(q).strip()
                if not (2 <= len(qs_clean) <= 60) or FORBIDDEN_SCRIPT_RE.search(qs_clean):
                    continue
                k = qs_clean.lower()
                if k in seen_q:
                    continue
                seen_q.add(k)
                search_phrases.append({
                    "query": qs_clean,
                    "lang": lang_code,
                    "cat": target_cat,
                    "sub": target_sub,
                    "special": target_special,
                })

    update_status("QWEN_SYNTHESIS_QUICK_ENTRY", count=len(quick_prompts), search_collected=len(search_phrases))
    raw_qe_outs = batched_generate(quick_prompts, lambda x: x[3], max_new=1100, batch_size=12)

    quick_entries = []
    seen_qe = set()
    valid_accts = {"cash", "tng", "mae", "cimb", "gxbank", "shopeepay", "any", ""}
    for (lang_code, kind_name, split_role, _), out_str in zip(quick_prompts, raw_qe_outs):
        obj = extract_json_obj(out_str)
        if not obj or not isinstance(obj.get("entries"), list):
            continue
        for e in obj["entries"]:
            txt = str(e.get("text", "")).strip()
            if not (4 <= len(txt) <= 110) or FORBIDDEN_SCRIPT_RE.search(txt):
                continue
            try:
                amt = float(e.get("amount_myr", 0))
            except Exception:
                continue
            if not (0.50 <= amt <= 50000.0):
                continue
            amt_minor = int(round(amt * 100))
            if amt_minor <= 0:
                continue
            days_ago = int(e.get("days_ago", 0) or 0)
            if days_ago < 0 or days_ago > 30:
                days_ago = 0
            cat = str(e.get("category", "Miscellaneous")).strip()
            sub = str(e.get("subcategory", "")).strip()
            if kind_name in ("expense", "shared", "refund"):
                if (cat, sub) not in PAIR_INDEX:
                    # Try matching category first sub if sub invalid
                    subs_c = f4.CANONICAL_TAXONOMY.get(cat)
                    if not subs_c:
                        continue
                    sub = subs_c[0]
            acct = str(e.get("account_hint", "any")).strip().lower()
            if acct not in valid_accts:
                acct = "any"
            k = txt.lower()
            if k in seen_qe:
                continue
            seen_qe.add(k)
            quick_entries.append({
                "text": txt,
                "lang": lang_code,
                "split_role": split_role,
                "kind": kind_name,
                "amountMinor": amt_minor,
                "daysAgo": days_ago,
                "merchant": str(e.get("merchant_or_desc", "")).strip()[:60],
                "account": acct,
                "person": str(e.get("person", "")).strip()[:32],
                "splitWays": max(2, int(e.get("split_ways", 2) or 2)),
                "paidForFriendOnly": bool(e.get("paid_for_friend_only", False)),
                "category": cat if kind_name not in ("income", "transfer") else ("Other income" if kind_name == "income" else "Miscellaneous"),
                "subcategory": sub if kind_name not in ("income", "transfer") else "",
            })

    res = {
        "model_id": used_id,
        "search_phrases": search_phrases,
        "quick_entries": quick_entries,
    }
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    GEN_CACHE_PATH.write_text(json.dumps(res, indent=2, ensure_ascii=False), encoding="utf-8")
    print(f"[QWEN DONE] search_phrases={len(search_phrases)}, quick_entries={len(quick_entries)}", flush=True)

    # Free GPU VRAM completely before loading E5 teacher
    del model, tok
    gc.collect()
    if torch.cuda.is_available():
        torch.cuda.empty_cache()
    return res


def _build_blind_test_quick_entries(synth_qe, val_ents, test_ents, seed: int = 20261124):
    """Builds ~800 high-rigor deterministic + LLM-verified multilingual quick-entry blind test cases (200 per lang)."""
    rng = random.Random(seed + 404)
    cases = []

    def add_case(text, lang, kind, amt_minor, days_ago, account="", person="", own_minor=None, category="Food & drinks", check_cat=True, edge="standard"):
        cases.append({
            "text": text,
            "lang": lang,
            "expected": {
                "kind": kind,
                "amountMinor": int(amt_minor),
                "daysAgo": int(days_ago),
                "account": account,
                "person": person,
                "ownAmountMinor": int(own_minor) if own_minor is not None else None,
                "category": category,
                "checkCategory": check_cat,
            },
            "edge": edge,
        })

    # Hand-crafted adversarial & structured core scenarios across EN / BM / Manglish / ZH (guaranteed exact ground truth)
    accounts = ["cash", "tng", "mae", "cimb", "gxbank", "shopeepay"]
    friends = ["Ali", "Sarah", "Mei", "Kumar", "Farah", "Jason", "Siti", "Daniel"]

    # 1. Brand-number & comma-thousand & sen-unit & date traps (EN, BM, Manglish, ZH)
    brand_traps = [
        ("99 Speedmart 24.50 tng", "en", "expense", 2450, 0, "tng", "Groceries"),
        ("7-Eleven RM 8.90 cash", "en", "expense", 890, 0, "cash", "Groceries"),
        ("1 Utama parking 5.00 tng", "mg", "expense", 500, 0, "tng", "Transport"),
        ("4 Fingers crispy chicken 28.90 mae", "en", "expense", 2890, 0, "mae", "Food & drinks"),
        ("Beli air di 99 Speedmart 14.20 tunai semalam", "bm", "expense", 1420, 1, "cash", "Groceries"),
        ("在7 Eleven买零食 11.50块 TNG 昨天", "zh", "expense", 1150, 1, "tng", "Groceries"),
        ("Monthly house rent RM 1,450.00 mae yesterday", "en", "expense", 145000, 1, "mae", "Housing & property"),
        ("Bayar sewa rumah 1,350 ringgit maybank semalam", "bm", "expense", 135000, 1, "mae", "Housing & property"),
        ("上个月房租 1,600元 MAE", "zh", "expense", 160000, 0, "mae", "Housing & property"),
        ("Parking fee 80 sen cash", "en", "expense", 80, 0, "cash", "Transport"),
    ]
    for row in brand_traps:
        add_case(row[0], row[1], row[2], row[3], row[4], row[5], "", None, row[6], True, "trap_num")

    # 2. Split bills (2-way, 3-way, paid-for-friend ownAmount=0, EN/BM/Manglish/ZH)
    split_cases = [
        ("Dinner 60 split with Sarah tng", "en", 6000, 0, "tng", "Sarah", 3000, "Food & drinks"),
        ("Lunch RM 90 split 3 ways with Ali mae yesterday", "en", 9000, 1, "mae", "Ali", 3000, "Food & drinks"),
        ("SteamBoat dinner 120 split with Ali & Mei tng", "mg", 12000, 0, "tng", "Ali & Mei", 4000, "Food & drinks"),
        ("Paid for Ahmad coffee 14.50 cash", "en", 1450, 0, "cash", "Ahmad", 0, "Food & drinks"),
        ("Makan malam 84 kongsi dengan Siti tunai", "bm", 8400, 0, "cash", "Siti", 4200, "Food & drinks"),
        ("Badminton court 60 bahagi 3 dengan Jason mae semalam", "mg", 6000, 1, "mae", "Jason", 2000, "Health & medical"),
        ("Bayar dulu untuk Farah kopi RM 13.50 tng", "bm", 1350, 0, "tng", "Farah", 0, "Food & drinks"),
        ("昨晚和Mei平分吃火锅 90块 TNG", "zh", 9000, 1, "tng", "Mei", 4500, "Food & drinks"),
        ("和Jason还有Kumar三人AA吃烤肉 150元 MAE", "zh", 15000, 0, "mae", "Jason & Kumar", 5000, "Food & drinks"),
        ("帮Ali垫付奶茶 18块 TNG 昨天", "zh", 1800, 1, "tng", "Ali", 0, "Food & drinks"),
        ("今天跟Sarah AA打羽毛球 48令吉 cash", "zh", 4800, 0, "cash", "Sarah", 2400, "Health & medical"),
    ]
    for txt, lg, amt, da, ac, pr, own, cat in split_cases:
        add_case(txt, lg, "shared", amt, da, ac, pr, own, cat, True, "split")

    # 3. Refunds, Income (Salary & Other), Directional Transfers across all 4 languages
    special_cases = [
        ("Received refund from Shopee RM 39.90 shopeepay yesterday", "en", "refund", 3990, 1, "shopeepay", "", None, "Shopping", True, "refund"),
        ("Bayaran balik Lazada 55.00 mae kelmarin", "bm", "refund", 5500, 2, "mae", "", None, "Shopping", True, "refund"),
        ("Shopee退款 42.50块 到TNG 昨天", "zh", "refund", 4250, 1, "tng", "", None, "Shopping", True, "refund"),
        ("Flight ticket refund RM 220 cimb", "mg", "refund", 22000, 0, "cimb", "", None, "Entertainment & travel", True, "refund"),
        ("Salary 5500 credited mae", "en", "income", 550000, 0, "mae", "", None, "Salary", True, "income"),
        ("Gaji bulanan 4800 masuk maybank", "bm", "income", 480000, 0, "mae", "", None, "Salary", True, "income"),
        ("收到这个月工资 6200块 进Maybank", "zh", "income", 620000, 0, "mae", "", None, "Salary", True, "income"),
        ("Terima RM 65 daripada Kumar duitnow gxbank semalam", "bm", "income", 6500, 1, "gxbank", "", None, "Other income", True, "income"),
        ("收到Mei转账 50块 TNG 刚才", "zh", "income", 5000, 0, "tng", "", None, "Other income", True, "income"),
        ("Transfer 200 mae to tng", "en", "transfer", 20000, 0, "mae", "", None, "Miscellaneous", False, "transfer"),
        ("Topup tng 80 dari maybank semalam", "bm", "transfer", 8000, 1, "mae", "", None, "Miscellaneous", False, "transfer"),
        ("Reload grabpay 50 from cimb", "mg", "transfer", 5000, 0, "cimb", "", None, "Miscellaneous", False, "transfer"),
        ("从MAE充值 100块 到TNG 今天", "zh", "transfer", 10000, 0, "mae", "", None, "Miscellaneous", False, "transfer"),
    ]
    for txt, lg, kd, amt, da, ac, pr, own, cat, chk, edge in special_cases:
        add_case(txt, lg, kd, amt, da, ac, pr, own, cat, chk, edge)

    # 4. Programmatically expand blind unseen-merchant & multilingual phrase test cases across ALL 15 categories & relative dates
    date_tokens = {
        "en": [(0, "today"), (1, "yesterday"), (2, "2 days ago"), (3, "3 days ago")],
        "bm": [(0, "hari ini"), (1, "semalam"), (2, "kelmarin"), (3, "3 hari lepas")],
        "mg": [(0, "harini"), (1, "semalam"), (2, "2 days ago"), (1, "smlm")],
        "zh": [(0, "今天"), (1, "昨天"), (2, "前天"), (3, "3天前")],
    }
    acct_tokens = {
        "en": [("cash", "cash"), ("tng", "tng"), ("mae", "mae"), ("cimb", "cimb"), ("gxbank", "gxbank")],
        "bm": [("cash", "tunai"), ("tng", "tng"), ("mae", "maybank"), ("cimb", "cimb"), ("gxbank", "gx")],
        "mg": [("cash", "cash"), ("tng", "tng"), ("mae", "mae"), ("cimb", "cimb"), ("shopeepay", "spay")],
        "zh": [("cash", "现金"), ("tng", "TNG"), ("mae", "MAE"), ("cimb", "CIMB"), ("gxbank", "GXBank")],
    }

    # Include held-out LLM quick-entries that don't have ambiguous formatting first (or verified blind templates)
    per_lang_target = {"en": 200, "bm": 200, "mg": 200, "zh": 200}
    cur_counts = Counter(c["lang"] for c in cases)

    test_pair_items = [(cat, sub, nm) for (cat, sub), nms in test_ents.items() for nm in nms]
    # Also draw unseen concept seeds held out or natural multilingual phrases
    all_pairs_cyc = list(PAIRS_74)
    idx = 0
    while any(cur_counts[lg] < per_lang_target[lg] for lg in ("en", "bm", "mg", "zh")):
        for lg in ("en", "bm", "mg", "zh"):
            if cur_counts[lg] >= per_lang_target[lg]:
                continue
            pair = all_pairs_cyc[idx % len(all_pairs_cyc)]
            idx += 1
            cat, sub = pair
            cents = rng.choice([0, 50, 80, 90, 20, 40])
            whole = rng.randint(4, 180)
            amt_f = whole + cents / 100.0
            amt_minor = whole * 100 + cents
            amt_str = f"{amt_f:.2f}" if cents else str(whole)
            days_ago, dt_tok = rng.choice(date_tokens[lg])
            acct_id, acct_tok = rng.choice(acct_tokens[lg])

            r_mode = idx % 10
            if r_mode == 0 and test_ents.get(pair):
                # Unseen test SME merchant name!
                merch = rng.choice(test_ents[pair])
                if lg == "en":
                    txt = f"{merch} RM {amt_str} {acct_tok} {dt_tok}"
                elif lg == "bm":
                    txt = f"Bayar {merch} RM {amt_str} {acct_tok} {dt_tok}"
                elif lg == "mg":
                    txt = f"Spend at {merch} {amt_str} {acct_tok} {dt_tok}"
                else:
                    txt = f"{dt_tok}在{merch}花了{amt_str}块 {acct_tok}"
                add_case(txt, lg, "expense", amt_minor, days_ago, acct_id, "", None, cat, True, "unseen_sme")
                cur_counts[lg] += 1
            elif r_mode == 1:
                # Split bill
                fr = rng.choice(friends)
                even_amt = rng.choice([2400, 3600, 4500, 6000, 8400, 12000])
                e_str = f"{even_amt // 100}"
                if lg == "en":
                    txt = f"Lunch RM {e_str} split with {fr} {acct_tok} {dt_tok}"
                elif lg == "bm":
                    txt = f"Makan malam {e_str} kongsi dengan {fr} {acct_tok} {dt_tok}"
                elif lg == "mg":
                    txt = f"Kopi makan {e_str} split {fr} {acct_tok} {dt_tok}"
                else:
                    txt = f"{dt_tok}和{fr}平分吃午饭 {e_str}块 {acct_tok}"
                add_case(txt, lg, "shared", even_amt, days_ago, acct_id, fr, even_amt // 2, "Food & drinks", True, "split_syn")
                cur_counts[lg] += 1
            elif r_mode == 2:
                # Refund / Transfer / Income mix
                sub_k = idx % 3
                if sub_k == 0:
                    if lg == "en":
                        txt = f"Refund Shopee RM {amt_str} {acct_tok} {dt_tok}"
                    elif lg == "bm":
                        txt = f"Bayaran balik Shopee RM {amt_str} {acct_tok} {dt_tok}"
                    elif lg == "mg":
                        txt = f"Dapat refund Lazada RM {amt_str} {acct_tok} {dt_tok}"
                    else:
                        txt = f"{dt_tok} Shopee退款 {amt_str}元 {acct_tok}"
                    add_case(txt, lg, "refund", amt_minor, days_ago, acct_id, "", None, "Shopping", True, "refund_syn")
                elif sub_k == 1:
                    fr = rng.choice(friends)
                    if lg == "en":
                        txt = f"Received RM {amt_str} from {fr} {acct_tok} {dt_tok}"
                    elif lg == "bm":
                        txt = f"Terima RM {amt_str} daripada {fr} {acct_tok} {dt_tok}"
                    elif lg == "mg":
                        txt = f"Dapat RM {amt_str} from {fr} {acct_tok} {dt_tok}"
                    else:
                        txt = f"{dt_tok}收到{fr}转账 {amt_str}块 {acct_tok}"
                    add_case(txt, lg, "income", amt_minor, days_ago, acct_id, "", None, "Other income", True, "income_syn")
                else:
                    src_id, src_tok = rng.choice(acct_tokens[lg][:4])
                    dst_id, dst_tok = rng.choice([x for x in acct_tokens[lg][:5] if x[0] != src_id])
                    if lg == "en":
                        txt = f"Transfer {amt_str} {src_tok} to {dst_tok} {dt_tok}"
                    elif lg == "bm":
                        txt = f"Topup {dst_tok} {amt_str} dari {src_tok} {dt_tok}"
                    elif lg == "mg":
                        txt = f"Reload {dst_tok} {amt_str} from {src_tok} {dt_tok}"
                    else:
                        txt = f"{dt_tok}从{src_tok}充值 {amt_str}块到{dst_tok}"
                    add_case(txt, lg, "transfer", amt_minor, days_ago, src_id, "", None, "Miscellaneous", False, "transfer_syn")
                cur_counts[lg] += 1
            else:
                # Multilingual conceptual description (especially testing Chinese/Manglish/BM category classification!)
                seed_list = CONCEPT_SEED_BANK.get(pair, {}).get(lg, [])
                if not seed_list:
                    continue
                phrase = rng.choice(seed_list)
                if lg == "en":
                    txt = f"{phrase} RM {amt_str} {acct_tok} {dt_tok}"
                elif lg == "bm":
                    txt = f"{phrase} {amt_str} ringgit {acct_tok} {dt_tok}"
                elif lg == "mg":
                    txt = f"{phrase} {amt_str} {acct_tok} {dt_tok}"
                else:
                    unit = rng.choice(["块", "元", "令吉", ""])
                    amt_part = f"RM{amt_str}" if not unit else f"{amt_str}{unit}"
                    txt = f"{dt_tok} {phrase} {amt_part} {acct_tok}"
                add_case(txt, lg, "expense", amt_minor, days_ago, acct_id, "", None, cat, True, "multilingual_cat")
                cur_counts[lg] += 1

    return {"today": "2026-11-24", "cases": cases}


def _build_blind_ledger_and_queries(verified_search_phrases, val_ents, test_ents, seed: int = 20261124):
    """Builds unseen-SME transactions + stratified 3-way split queries across all 74 subcategories + OOD sets."""
    rng = random.Random(seed + 505)
    today = "2026-11-24"
    accounts = [
        {"id": "cash", "name": "Cash", "type": "cash"},
        {"id": "tng", "name": "Touch 'n Go eWallet", "type": "wallet"},
        {"id": "mae", "name": "MAE by Maybank2u", "type": "bank"},
        {"id": "cimb", "name": "CIMB OCTO", "type": "bank"},
        {"id": "gxbank", "name": "GXBank", "type": "bank"},
        {"id": "shopeepay", "name": "ShopeePay", "type": "wallet"},
    ]
    acct_names = {a["id"]: a["name"] for a in accounts}

    def iso_days_ago(d):
        import datetime
        dt = datetime.date.fromisoformat(today) - datetime.timedelta(days=d)
        return dt.isoformat()

    # Build 228 transactions strictly from UNSEEN / TEST entities (never overlapping training entities!)
    txs = []
    tx_idx = 0
    for cat, sub in PAIRS_74:
        cands = test_ents.get((cat, sub), []) or [f"{sub.split('(')[0].strip()} Station MY"]
        for k in range(3):
            tx_idx += 1
            merch = cands[k % len(cands)]
            kind = "expense"
            person = ""
            amt_minor = rng.choice([650, 1250, 1890, 2450, 3500, 4800, 6800, 8500, 12500, 24000, 45000])
            days_ago = rng.randint(0, 68)
            acct = rng.choice([a["id"] for a in accounts])
            note = ""
            if (cat, sub) == ("Food & drinks", "Restaurant & Cafe") and k == 2:
                kind = "shared"
                person = rng.choice(["Ali", "Sarah", "Mei"])
                amt_minor = 9000
                note = "Shared team dinner"
            elif (cat, sub) == ("Shopping", "Online Marketplace (Shopee / Lazada / TikTok)") and k == 2:
                kind = "refund"
                note = "Returned damaged item refund"
            lhdn = lhdn_bucket_of(merch, cat, sub, note)
            txs.append({
                "id": f"tx-{tx_idx:03d}",
                "kind": kind,
                "amount": amt_minor,
                "account": acct,
                "accountName": acct_names[acct],
                "merchant": merch,
                "category": cat,
                "subcategory": sub,
                "note": note,
                "person": person,
                "tags": [],
                "lhdnBucket": lhdn,
                "date": iso_days_ago(days_ago),
                "created": 1700000000000 + tx_idx * 1000,
            })

    extra_specials = [
        ("income", 580000, "mae", "ACME Tech Sdn Bhd Salary", "Salary", "", "Monthly net paycheck", "", 25),
        ("income", 4500, "tng", "Ali", "Other income", "", "Split repayment DuitNow", "Ali", 5),
        ("transfer", 15000, "mae", "Touch 'n Go eWallet Top-Up", "Miscellaneous", "", "Reload eWallet balance", "", 4),
        ("transfer", 20000, "cimb", "Setel Reload", "Miscellaneous", "", "Account transfer for fuel", "", 12),
        ("refund", 7500, "gxbank", "Klinik Pergigian Berseri", "Health & medical", "Dental & Optical", "Overcharge refund credited", "", 8),
        ("shared", 12000, "tng", "Restoran Salam Jaya Bistro", "Food & drinks", "Hawker & Mamak", "Lunch split 3 ways", "Jason", 1),
    ]
    for kd, amt_m, ac, merch, ct, sb, nt, pr, da in extra_specials:
        tx_idx += 1
        txs.append({
            "id": f"tx-{tx_idx:03d}",
            "kind": kd,
            "amount": amt_m,
            "account": ac,
            "accountName": acct_names[ac],
            "merchant": merch,
            "category": ct,
            "subcategory": sb,
            "note": nt,
            "person": pr,
            "tags": [],
            "lhdnBucket": lhdn_bucket_of(merch, ct, sb, nt),
            "date": iso_days_ago(da),
            "created": 1700000000000 + tx_idx * 1000,
        })

    tx_by_id = {t["id"]: t for t in txs}

    # Index pre-filtered E5-verified LLM search phrases by E5 rank
    llm_rk1_map = defaultdict(list)
    llm_rk2_map = defaultdict(list)
    llm_by_target = defaultdict(list)
    for item in verified_search_phrases:
        qt = item["query"].strip()
        if item.get("cat") and (item["cat"], item.get("sub", "")) in PAIR_INDEX:
            rk = int(item.get("e5_rk1", 1) or 1)
            k_slot = (item["cat"], item.get("sub", ""), item["lang"])
            if rk <= 1:
                llm_rk1_map[k_slot].append(qt)
            elif rk <= 2:
                llm_rk2_map[k_slot].append(qt)
        elif item.get("special"):
            llm_by_target[("special", item["special"], item["lang"])].append(qt)

    def is_zero_lexical_overlap(q: str, matching_txs):
        q_toks = set(re.findall(r"[a-z0-9]{2,}|[\u4e00-\u9fff]+", q.lower()))
        if not q_toks:
            return False
        for t in matching_txs:
            hay = f"{t['merchant']} {t['category']} {t['subcategory']} {t['note']} {t['accountName']} {t['person']}".lower()
            for tk in q_toks:
                if tk in hay:
                    return False
        return True

    def make_rec(qtxt, lg, pos_txs, pos_ids):
        zlex = is_zero_lexical_overlap(qtxt, pos_txs)
        return {
            "query": qtxt,
            "lang": lg,
            "slice": "zero_lexical_overlap" if zlex else "concept",
            "relevant_ids": pos_ids,
        }

    # Stratified per-(pair, lang) 3-way split across ALL 74 subcategories (eval prefers rk1==1 consensus!)
    val_queries = []
    val_seen = set()
    primary_blind_by_lg = defaultdict(list)
    secondary_blind_by_lg = defaultdict(list)

    for pair in PAIRS_74:
        cat, sub = pair
        pos_txs = [t for t in txs if t["category"] == cat and t["subcategory"] == sub]
        pos_ids = [t["id"] for t in pos_txs]
        for lg in ("en", "bm", "mg", "zh"):
            seeds_raw = list(CONCEPT_SEED_BANK.get(pair, {}).get(lg, []))
            seen_s = {s_txt.lower().strip() for s_txt in seeds_raw if s_txt and s_txt.strip()}

            rk1_map = {}
            for qtxt in llm_rk1_map.get((cat, sub, lg), []):
                ql = qtxt.lower().strip()
                if ql and ql not in seen_s and ql not in rk1_map:
                    rk1_map[ql] = qtxt.strip()
            llm_rk1 = sorted(rk1_map.values(), key=lambda s: hashlib.md5(s.lower().encode("utf-8")).hexdigest())

            seen_1 = seen_s | set(rk1_map.keys())
            rk2_map = {}
            for qtxt in llm_rk2_map.get((cat, sub, lg), []):
                ql = qtxt.lower().strip()
                if ql and ql not in seen_1 and ql not in rk2_map:
                    rk2_map[ql] = qtxt.strip()
            llm_rk2 = sorted(rk2_map.values(), key=lambda s: hashlib.md5(s.lower().encode("utf-8")).hexdigest())

            val_cand, prim_cand, sec_cand = None, None, None
            if len(llm_rk1) >= 1:
                prim_cand = llm_rk1[0]
                if len(llm_rk1) >= 3:
                    val_cand = llm_rk1[1]
                if len(llm_rk1) >= 4:
                    sec_cand = llm_rk1[2]
            elif len(llm_rk2) >= 2:
                prim_cand = llm_rk2[0]

            if val_cand:
                vl = val_cand.lower().strip()
                if vl not in val_seen:
                    val_seen.add(vl)
                    val_queries.append(make_rec(val_cand, lg, pos_txs, pos_ids))
            if prim_cand:
                primary_blind_by_lg[lg].append(make_rec(prim_cand, lg, pos_txs, pos_ids))
            if sec_cand:
                secondary_blind_by_lg[lg].append(make_rec(sec_cand, lg, pos_txs, pos_ids))

    # Select balanced round-robin blind queries across ALL 74 subcategories (~100 per language)
    blind_queries = []
    blind_seen = set(val_seen)
    for lg in ("en", "bm", "mg", "zh"):
        picked_lg = []
        for rec in primary_blind_by_lg[lg]:
            ql = rec["query"].lower().strip()
            if ql not in blind_seen:
                blind_seen.add(ql)
                picked_lg.append(rec)
                if len(picked_lg) >= 98:
                    break
        if len(picked_lg) < 98:
            for rec in secondary_blind_by_lg[lg]:
                ql = rec["query"].lower().strip()
                if ql not in blind_seen:
                    blind_seen.add(ql)
                    picked_lg.append(rec)
                    if len(picked_lg) >= 98:
                        break
        blind_queries.extend(picked_lg)

    # 2. Structured constraint + LHDN tax relief + Kind queries
    structured_specs = [
        ("coffee >10", "en", "structured_constraint", lambda t: t["subcategory"] == "Coffee, Tea & Drinks" and t["amount"] > 1000),
        ("makan >30 last month", "mg", "structured_constraint", lambda t: t["category"] == "Food & drinks" and t["amount"] > 3000 and "2026-10-01" <= t["date"] <= "2026-10-31"),
        ("咖啡 >10", "zh", "structured_constraint", lambda t: t["subcategory"] == "Coffee, Tea & Drinks" and t["amount"] > 1000),
        ("打油 >40", "zh", "structured_constraint", lambda t: t["subcategory"] == "Petrol & EV Charging" and t["amount"] > 4000),
        ("petrol >=50 via tng", "en", "structured_constraint", lambda t: t["subcategory"] == "Petrol & EV Charging" and t["amount"] >= 5000 and t["account"] == "tng"),
        ("klinik >20", "bm", "structured_constraint", lambda t: t["category"] == "Health & medical" and t["amount"] > 2000),
        ("pelepasan cukai", "bm", "lhdn_tax", lambda t: bool(t["lhdnBucket"])),
        ("tax relief lhdn", "en", "lhdn_tax", lambda t: bool(t["lhdnBucket"])),
        ("报税可扣税", "zh", "lhdn_tax", lambda t: bool(t["lhdnBucket"])),
        ("退款", "zh", "kind_filter", lambda t: t["kind"] == "refund"),
        ("refund", "en", "kind_filter", lambda t: t["kind"] == "refund"),
        ("split kongsi", "mg", "kind_filter", lambda t: t["kind"] in ("shared", "reimbursement")),
        ("工资", "zh", "kind_filter", lambda t: t["kind"] == "income"),
        ("gaji", "bm", "kind_filter", lambda t: t["kind"] == "income"),
        ("account:mae >100", "en", "structured_constraint", lambda t: t["account"] == "mae" and t["amount"] > 10000),
    ]
    for qtxt, lg, sl, pred in structured_specs:
        rids = [t["id"] for t in txs if pred(t)]
        if rids:
            blind_queries.append({"query": qtxt, "lang": lg, "slice": sl, "relevant_ids": rids})

    covered_cats = {tx_by_id[q["relevant_ids"][0]]["category"] for q in blind_queries if q["slice"] in ("concept", "zero_lexical_overlap") and q["relevant_ids"]}
    assert len(covered_cats) == len(f4.CANONICAL_TAXONOMY), f"Expected all {len(f4.CANONICAL_TAXONOMY)} categories in blind, got {len(covered_cats)}: {sorted(covered_cats)}"

    ood_val = [
        "quantum entanglement equations", "ramalan cuaca melaka esok", "明年股票预测算法", "javascript promise await tutorial",
        "sejarah kesultanan melaka", "古代诗词欣赏全集", "weather forecast kuala lumpur tomorrow", "kubernetes pod crashloopbackoff",
        "resepi kek coklat moist kukus", "明天几点日落", "differential geometry lecture notes", "asdfqwer998877",
        "bola sepak liga super keputusan", "如何训练大模型", "piano chord progression jazz", "solar eclipse nasa 2027",
        "lirik lagu negaraku penuh", "红烧肉做法家常", "docker compose network bridge", "hujan lebat negeri sembilan",
        "graphql schema stitching guide", "keputusan badminton terbaharu malaysia", "人工智能大模型训练", "cara membuat kuih bahulu",
        "webgl shader fragment noise", "sinopsis novel klasik melayu", "天文学太阳系八大行星", "bash awk sed stream editing",
        "cuaca mendung di ipoh perak", "宋词三百首苏轼作品", "react server components hydration", "cara menjaga ikan emas kolam",
        "物理学麦克斯韦方程组", "nota ringkas geografi tingkatan dua", "prometheus grafana alerting rules", "古典音乐贝多芬交响曲",
        "jadual waktu berbuka puasa klang", "神经网络卷积层原理", "terraform state lock dynamodb", "sejarah penubuhan malaysia 1963",
    ]
    ood_test = [
        "quantum computing shor algorithm", "keputusan pilihan raya umum", "python异步编程教程", "ramalan hujan johor bahru",
        "recipe for sourdough starter", "量子力学波函数推导", "react native navigation stack", "suhu bumi pemanasan global",
        "钢琴入门指法练习", "cara membuat kuih lapis", "postgresql vacuum full analyze", "明天台北天气怎么样",
        "champions league final highlights", "唐诗三百首李白", "android gradle daemon memory", "permainan catur strategi pembukaan",
        "机器学习梯度下降数学", "how to tune acoustic guitar", "peta topografi semenanjung malaysia", "zxcvbnm12345",
        "terraform aws s3 bucket policy", "cara tanam pokok cili", "宇宙大爆炸理论简介", "kadar tukaran dolar zimbabwe",
        "rust borrow checker lifetime", "pantun empat kerat nasihat", "红楼梦主要人物关系", "fibonacci heap time complexity",
        "jadual waktu solat subuh", "今天星座运势查询", "linux kernel scheduler cfs", "sejarah kemerdekaan malaya 1957",
        "微积分泰勒展开公式", "chess sicilian defense najdorf", "cara menanam bunga ros merah", "围棋定式教学视频",
        "fastapi pydantic v2 migration", "ramalan angin monsun timur laut", "摄影构图黄金比例", "graphql ast fragment directive",
        "nota ringkas sains tingkatan 3", "书法楷书基本笔画", "numpy einsum broadcasting rules", "festival layang layang pasir gudang",
        "天文学黑洞事件视界", "blender 3d rigging armature", "jadual pasang surut air laut", "茶道泡茶水温控制",
        "openssl x509 self signed cert", "cara jaga pokok orkid", "数学奥林匹克几何题", "typescript conditional infer types",
        "lagu rakyat tradisional pahang", "历史朝代时间线总结", "pytorch distributed data parallel", "cuaca mendung seluruh pantai timur",
        "中医针灸穴位图解", "nginx reverse proxy gzip header", "cara buat baja kompos organik", "古典吉他指弹曲谱",
    ]
    return {
        "val_ledger": {"today": today, "accounts": accounts, "transactions": txs, "queries": val_queries, "ood_queries": [{"query": q} for q in ood_val]},
        "blind_ledger": {"today": today, "accounts": accounts, "transactions": txs, "queries": blind_queries, "ood_queries": [{"query": q} for q in ood_test]},
    }


def _make_query_wrapper(q: str, lg: str, rng) -> str:
    ql = q.lower().strip()
    if not ql:
        return ""
    has_cjk = any("\u4e00" <= c <= "\u9fff" for c in ql)
    if lg == "zh" or (lg == "llm" and has_cjk):
        cands = []
        if not ql.startswith(("买", "付", "缴", "订")):
            cands.extend([f"买{q}", f"付{q}"])
        if not ql.endswith(("费", "钱", "账单")):
            cands.append(f"{q}费用")
        return rng.choice(cands) if cands else ""
    is_bm = lg in ("bm", "mg") or (lg == "llm" and bool(re.search(r"\b(?:bayar|beli|makan|minum|kereta|rumah|duit|bil|cukai|yuran|kos)\b", ql)))
    if is_bm:
        cands = []
        if not ql.startswith(("bayar ", "beli ", "kos ", "yuran ")):
            cands.extend([f"bayar {q}", f"beli {q}", f"kos {q}"])
        return rng.choice(cands) if cands else ""
    cands = []
    if not any(w in ql.split() for w in ("bill", "cost", "fee", "fees", "expense", "expenses", "payment")):
        cands.extend([f"{q} bill", f"{q} cost", f"{q} expense", f"{q} payment"])
    return rng.choice(cands) if cands else ""


def build_training_examples(train_ents, verified_search_phrases, synth_qe, blind_exclude_queries, blind_exclude_qe, seed: int = 20261124):
    """Constructs rich zero-leakage train triplets & prototype examples across ALL 77 pairs."""
    rng = random.Random(seed + 101)
    llm_search = defaultdict(list)
    for item in verified_search_phrases:
        qt = item["query"].strip()
        ql = qt.lower()
        if ql in blind_exclude_queries:
            continue
        if item.get("cat") and (item["cat"], item.get("sub", "")) in PAIR_INDEX:
            llm_search[(item["cat"], item.get("sub", ""))].append(qt)
        elif item.get("special"):
            llm_search[item["special"]].append(qt)

    # Collect clean synthetic quick-entry notes for train entities / descriptions (strictly excluding blind QE)
    llm_qe_by_pair = defaultdict(list)
    for e in synth_qe:
        if e.get("split_role") == "blind_test":
            continue
        tl = e["text"].strip().lower()
        if tl in blind_exclude_qe:
            continue
        if e["kind"] in ("expense", "shared", "refund") and (e["category"], e["subcategory"]) in PAIR_INDEX:
            llm_qe_by_pair[(e["category"], e["subcategory"])].append(e["text"].strip())

    accts = ["Cash", "Touch 'n Go eWallet", "MAE by Maybank2u", "CIMB OCTO", "GXBank", "ShopeePay"]
    non_exp_merch = {
        ("Salary", ""): [
            "Malaysia Tech Payroll Sdn Bhd", "Monthly Net Salary Credited", "Corporate Staff Bonus Credit",
            "Global Ops Monthly Paycheck", "Enterprise Salary Remittance", "Kuala Lumpur Office Payroll",
        ],
        ("Other income", ""): [
            "DuitNow Peer Repayment", "Freelance Design Settlement", "Cashback Wallet Reward",
            "Friend Dinner Split Return", "Part-Time Tutoring Credit", "Marketplace Buyer Refund",
        ],
        ("Miscellaneous", ""): [
            "E-Wallet Balance Reload", "Bank to TNG Wallet Transfer", "Self Account Fund Transfer",
            "CIMB to MAE Transfer", "GXBank Pocket Reload", "ShopeePay Auto Top-Up",
        ],
    }
    non_exp_kind = {
        ("Salary", ""): "income",
        ("Other income", ""): "income",
        ("Miscellaneous", ""): "transfer",
    }

    pair_pos = []  # (query_text, doc_text, doc_tags, cat, sub, cls_pid, hard_neg_pair, doc_pid)
    proto_examples = []  # (text, proto_id)

    same_cat_neighbors = defaultdict(list)
    diff_cat_neighbors = defaultdict(list)
    for c1, s1 in PAIRS_77:
        peers = [(c2, s2) for c2, s2 in PAIRS_77 if c2 == c1 and s2 != s1]
        others = [(c2, s2) for c2, s2 in PAIRS_77 if c2 != c1]
        same_cat_neighbors[(c1, s1)] = peers if peers else others
        diff_cat_neighbors[(c1, s1)] = others

    for p_idx, pair in enumerate(PAIRS_77):
        cat, sub = pair
        pid = p_idx
        if pair in non_exp_merch:
            merch_pool = list(non_exp_merch[pair])
            doc_kind_default = non_exp_kind[pair]
        else:
            merch_pool = list(train_ents.get(pair, [])) or [sub.split("(")[0].strip() + " Malaysia"]
            doc_kind_default = "expense"

        # 1. Seed bank + E5-verified LLM queries (strictly matching _build_blind_ledger_and_queries holdouts!)
        q_pool = []
        seen_q_local = set()
        seeds_dict = CONCEPT_SEED_BANK.get(pair, {})
        for lg in ("en", "bm", "mg", "zh"):
            seeds_raw = list(seeds_dict.get(lg, []))
            seeds_lg = []
            seen_s = set()
            for s_t in seeds_raw:
                sl_s = s_t.lower().strip()
                if sl_s and sl_s not in seen_s:
                    seen_s.add(sl_s)
                    seeds_lg.append(s_t.strip())
            for q in seeds_lg:
                ql = q.lower().strip()
                if ql not in blind_exclude_queries and ql not in seen_q_local:
                    seen_q_local.add(ql)
                    q_pool.append((q, lg))
                    wrap = _make_query_wrapper(q, lg, rng)
                    wl = wrap.lower().strip()
                    if wl and wl not in blind_exclude_queries and wl not in seen_q_local and rng.random() < 0.70:
                        seen_q_local.add(wl)
                        q_pool.append((wrap, lg))
        for q in llm_search.get(pair, []):
            ql = q.lower().strip()
            if ql not in blind_exclude_queries and ql not in seen_q_local:
                seen_q_local.add(ql)
                q_pool.append((q, "llm"))
                wrap = _make_query_wrapper(q, "llm", rng)
                wl = wrap.lower().strip()
                if wl and wl not in blind_exclude_queries and wl not in seen_q_local and rng.random() < 0.50:
                    seen_q_local.add(wl)
                    q_pool.append((wrap, "llm"))

        for q, _ in q_pool:
            proto_examples.append((q, pid))
        for m in merch_pool[:18]:
            if m.lower().strip() not in blind_exclude_queries:
                proto_examples.append((m, pid))
        for qe_t in llm_qe_by_pair.get(pair, []):
            if qe_t.lower().strip() not in blind_exclude_queries:
                proto_examples.append((qe_t, pid))
                q_pool.append((qe_t, "qe"))

        neg_same = same_cat_neighbors[pair]
        neg_diff = diff_cat_neighbors[pair]
        for q, lg in q_pool:
            for rep_mode in range(3):
                merch = rng.choice(merch_pool)
                acct = rng.choice(accts)
                note = ""
                if rep_mode == 2 and rng.random() < 0.45:
                    note = rng.choice(llm_qe_by_pair.get(pair, [merch])[:8]) if llm_qe_by_pair.get(pair) else ""
                lhdn = lhdn_bucket_of(merch, cat, sub, note)
                doc_txt, doc_tags = build_tx_doc_and_tags(merch, cat, sub, doc_kind_default, note, "", acct, lhdn)
                neg_pair = rng.choice(neg_same if rep_mode == 0 else neg_diff)
                pair_pos.append((q, doc_txt, doc_tags, cat, sub, pid, neg_pair, pid))

        for merch in merch_pool[:12]:
            if merch.lower().strip() in blind_exclude_queries:
                continue
            acct = rng.choice(accts)
            lhdn = lhdn_bucket_of(merch, cat, sub, "")
            doc_txt, doc_tags = build_tx_doc_and_tags(merch, cat, sub, doc_kind_default, "", "", acct, lhdn)
            pair_pos.append((merch.lower(), doc_txt, doc_tags, cat, sub, pid, rng.choice(neg_diff), pid))

    # LHDN general queries -> LHDN-eligible training docs (doc_pid preserves dual-view blend; cls_pid=-1 avoids single-sub collapse)
    lhdn_pairs = [p for p in PAIRS_74 if p[0] in ("Health & medical", "Insurance & Takaful", "Education", "Investments & savings", "Taxes & government") or p[1] in ("Books & Stationery", "Home Internet & WiFi (Unifi / Time)", "Mobile Postpaid & Prepaid", "Zakat & Fitrah")]
    lhdn_qs = [q for lg_list in LHDN_GENERAL_SEEDS.values() for q in lg_list] + llm_search.get("lhdn:tax_relief", [])
    for q in lhdn_qs:
        if q.lower().strip() in blind_exclude_queries:
            continue
        for _ in range(8):
            pair = rng.choice(lhdn_pairs)
            merch = rng.choice(train_ents.get(pair, [pair[1]]) or [pair[1]])
            acct = rng.choice(accts)
            lhdn = lhdn_bucket_of(merch, pair[0], pair[1], "tax relief LHDN") or "Medical & Health"
            doc_txt, doc_tags = build_tx_doc_and_tags(merch, pair[0], pair[1], "expense", "Tax claimable receipt", "", acct, lhdn)
            pair_pos.append((q, doc_txt, doc_tags, pair[0], pair[1], -1, ("Food & drinks", "Hawker & Mamak"), PAIR_INDEX[pair]))

    # Kind-specific queries (refund, shared, income, transfer)
    for kd_name, seeds_map in KIND_SEARCH_SEEDS.items():
        all_kd_qs = [q for lst in seeds_map.values() for q in lst] + llm_search.get(f"kind:{kd_name}", [])
        for q in all_kd_qs:
            if q.lower().strip() in blind_exclude_queries:
                continue
            for _ in range(6):
                acct = rng.choice(accts)
                if kd_name == "refund":
                    doc_txt, doc_tags = build_tx_doc_and_tags("Online Store MY", "Shopping", "Online Marketplace (Shopee / Lazada / TikTok)", "refund", "Returned order refund", "", acct, "")
                    neg_p = ("Shopping", "Clothing, Bags & Footwear")
                    dpid = PAIR_INDEX[("Shopping", "Online Marketplace (Shopee / Lazada / TikTok)")]
                    cp, sp = "Shopping", "Online Marketplace (Shopee / Lazada / TikTok)"
                elif kd_name == "shared":
                    fr = rng.choice(["Ali", "Sarah", "Mei", "Jason"])
                    doc_txt, doc_tags = build_tx_doc_and_tags("Local Bistro", "Food & drinks", "Restaurant & Cafe", "shared", f"Dinner split with {fr}", fr, acct, "")
                    neg_p = ("Food & drinks", "Hawker & Mamak")
                    dpid = PAIR_INDEX[("Food & drinks", "Restaurant & Cafe")]
                    cp, sp = "Food & drinks", "Restaurant & Cafe"
                elif kd_name == "income":
                    doc_txt, doc_tags = build_tx_doc_and_tags("Malaysia Tech Payroll Sdn Bhd", "Salary", "", "income", "Monthly salary credited", "", acct, "")
                    neg_p = ("Fees & charges", "Bank & Transfer Fee")
                    dpid = PAIR_INDEX[("Salary", "")]
                    cp, sp = "Salary", ""
                else:
                    doc_txt, doc_tags = build_tx_doc_and_tags("E-Wallet Balance Reload", "Miscellaneous", "", "transfer", "Reload wallet from bank", "", acct, "")
                    neg_p = ("Loans, BNPL & Credit", "Credit Card Bill")
                    dpid = PAIR_INDEX[("Miscellaneous", "")]
                    cp, sp = "Miscellaneous", ""
                pair_pos.append((q, doc_txt, doc_tags, cp, sp, -1, neg_p, dpid))

    docs_by_pair = defaultdict(list)
    for row in pair_pos:
        docs_by_pair[(row[3], row[4])].append((row[1], row[2]))

    # Expanded ~180 real multilingual OOD queries (strictly disjoint from ood_val and ood_test)
    ood_train_queries = [
        "weather forecast today", "cuaca panas hari ini", "今天天气好不好", "python list comprehension",
        "java hashmap collision", "football world cup standings", "历史朝代时间表", "guitar chords minor scale",
        "cooking pasta recipe garlic", "caricature drawing tutorial", "cara tanam sayur hidroponik", "宇宙行星运行轨迹",
        "quantum mechanics schrodinger", "sukan sea pingat emas", "机器学习反向传播", "linux systemd unit file",
        "lorem ipsum dolor sit amet", "abcd1234", "random test string", "电影推荐科幻大片",
        "how to center a div in css", "docker container port mapping", "git rebase interactive squash",
        "nearest mountain hiking trail", "best DSLR aperture settings", "solar system planet distances",
        "periodic table noble gases", "shakespeare hamlet soliloquy", "french revolution timeline",
        "kubernetes ingress controller", "postgres index btree vs hash", "rust ownership lifetimes",
        "cara buat kek lapis sarawak", "jadual liga perdana inggeris", "keputusan badminton terbaharu",
        "cara menanam pokok cili padi", "sejarah kesultanan johor riau", "nota sejarah tingkatan empat",
        "ramalan banjir pantai timur", "suhu tertinggi di kuala lumpur", "lagu tradisional negeri melaka",
        "resepi rendang daging hari raya", "cara tukar tayar basikal", "peribahasa melayu dan maksudnya",
        "唐宋八大家代表作品", "相对论时间膨胀效应", "深度学习注意力机制", "黑洞霍金辐射原理",
        "如何学好钢琴五线谱", "红烧排骨怎么做好吃", "西湖龙井冲泡水温", "中国古代四大发明",
        "北京故宫建筑布局介绍", "围棋定式开局技巧", "羽毛球发球规则裁判", "羽毛球比赛直播比分",
        "明天深圳会下大雨吗", "苹果手机怎么重启强制", "数据结构红黑树旋转", "线性代数特征值分解",
        "microeconomics supply demand curve", "organic chemistry benzene ring", "photosynthesis light reaction",
        "newton third law momentum", "fibonacci sequence dynamic programming", "binary search tree traversal",
        "http status code 502 bad gateway", "oauth2 pkce authorization code flow", "tls handshake cipher suite",
        "mendung di pulau pinang petang ini", "angin monsun barat daya malaysia", "flora dan fauna hutan hujan tropika",
        "puncak gunung kinabalu ketinggian", "pulau langkawi legenda mahsuri", "sungai rajang sarawak panjang",
        "书法楷书入门教程", "中医经络养生穴位", "摄影构图三分法技巧", "天文学超新星爆发",
        "chess opening sicilian defense", "piano jazz chord voicings", "watercolor painting wet on wet",
        "typography kerning leading guide", "sourdough starter hydration ratio", "espresso extraction brew ratio",
        "cara menjaga ikan laga betta", "tips menanam orkid di rumah", "peraturan permainan bola tampar",
        "ketinggian menara berkembar petronas", "jenis jenis awan kumulonimbus", "habitat penyu belimbing terengganu",
        "操作系统进程调度算法", "计算机网络TCP三次握手", "数据库事务ACID特性", "编译原理词法分析器",
        "xyzzy plugh qwerty987", "fghjkl 0000 test", "hello world foobar baz", "nonexistent concept xyz",
        "kotlin coroutine structured concurrency", "golang channel select deadlock", "llvm irssa optimization pass",
        "cuda warp divergence shared memory", "webassembly simd vector instructions", "sqlite wal checkpoint mode",
        "redis sorted set skip list", "kafka consumer group rebalance", "grpc protobuf backward compatibility",
        "elasticsearch inverted index bm25", "envoy filter http wasm", "istio mutual tls peerauthentication",
        "ansible playbook idempotent roles", "helm chart values schema validation", "argocd gitops sync wave",
        "bahasa melayu baku tatabahasa", "karangan spm langkah mengatasi pencemaran", "puisi moden sasterawan negara",
        "ibu negeri sembilan seremban sejarah", "sistem pemerintahan raja berperlembagaan", "gunung tahan pahang ekspedisi",
        "spesies orang utan borneo sabah", "tarian tradisional zapin johor", "alat muzik gamelan tradisional",
        "kompang majlis perkahwinan melayu", "batik lukis tangan kelantan", "tenunan songket terengganu",
        "sungai pahang terpanjang semenanjung", "tasik kenyir empangan hidroelektrik", "pulau tioman terumbu karang",
        "量子纠缠贝尔不等式", "狭义相对论洛伦兹变换", "热力学第二定律熵增", "麦克斯韦妖思想实验",
        "三国演义赤壁之战", "水浒传一百单八将", "西游记大闹天宫回目", "世说新语魏晋风度",
        "黄山奇松怪石云海", "九寨沟五花海风景", "桂林山水甲天下", "敦煌莫高窟壁画飞天",
        "青花瓷釉下彩工艺", "紫砂壶泥料原矿朱泥", "古琴曲流水高山流水", "二胡名曲二泉映月",
        "宫崎骏动画电影原声", "黑泽明七武士镜头语言", "贝多芬月光奏鸣曲第三乐章", "肖邦夜曲降E大调",
        "围棋死活题劫争技巧", "象棋当头炮屏风马", "太极拳二十四式口令", "八段锦养生功法分解",
        "transformer positional encoding rotary", "flash attention tiling sram", "lora low rank adaptation",
        "kv cache paged attention vllm", "speculative decoding draft model", "bfloat16 dynamic loss scaling",
        "eigenvalue decomposition symmetric matrix", "svd singular value truncation", "convex optimization kkt conditions",
        "bayesian posterior conjugate prior", "markov chain monte carlo metropolis", "kalman filter state covariance",
        "pnb permutation matrix factorization", "random forest gini impurity split", "gradient boosted decision trees",
        "foobar baz qux 123456", "lorem ipsum consectetur adipiscing", "qwertyuiop asdfghjkl zxcvbnm",
        "test ping pong latency check", "unknown random gibberish phrase", "zero relation outside domain",
    ]
    # Add 180 synthetic OOD token-recombination phrases to densely cover unseen OOD bucket combinations
    _polysemous_skip = {"gas", "game", "plan", "home", "food", "water", "music", "book", "tree", "card", "bank", "fee", "pay", "tax", "tea", "air", "oil", "bus", "car", "test"}
    _ood_words = [t for oq in ood_train_queries for t in re.findall(r"[a-z]{3,}", oq.lower()) if t not in _polysemous_skip]
    _ood_cjk = [run[i:i + 2] for oq in ood_train_queries for run in re.findall(r"[\u4e00-\u9fff]{2,}", oq) for i in range(0, len(run) - 1, 2)]
    _ood_seen = blind_exclude_queries | {x.lower().strip() for x in ood_train_queries}
    _syn_added = 0
    for _si in range(600):
        if _syn_added >= 180:
            break
        if _si % 3 == 0 and len(_ood_cjk) >= 4:
            _cand = "".join(rng.sample(_ood_cjk, rng.randint(2, 4)))
        elif len(_ood_words) >= 5:
            _cand = " ".join(rng.sample(_ood_words, rng.randint(2, 5)))
        else:
            continue
        _cl = _cand.lower().strip()
        if _cl and _cl not in _ood_seen:
            _ood_seen.add(_cl)
            ood_train_queries.append(_cand)
            _syn_added += 1
    return {
        "pair_pos": pair_pos,
        "proto_examples": proto_examples,
        "docs_by_pair": docs_by_pair,
        "ood_train_queries": ood_train_queries,
    }


def load_e5_teacher():
    """Loads intfloat/multilingual-e5-base once on GPU and returns (encode_fn, cleanup_fn)."""
    import numpy as np
    import torch
    from transformers import AutoModel, AutoTokenizer

    update_status("LOADING_E5_TEACHER", model=E5_TEACHER_ID)
    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    tok = AutoTokenizer.from_pretrained(E5_TEACHER_ID)
    model = AutoModel.from_pretrained(E5_TEACHER_ID)
    teacher_dim = int(model.config.hidden_size)
    if torch.cuda.is_available():
        model = model.to(device=device, dtype=torch.float16)
    model.eval()

    def encode_texts(prefixed_texts, batch_size=96):
        out = []
        for st in range(0, len(prefixed_texts), batch_size):
            bt = prefixed_texts[st:st + batch_size]
            enc = tok(bt, padding=True, truncation=True, max_length=96, return_tensors="pt").to(device)
            with torch.no_grad():
                res = model(**enc).last_hidden_state
                mask = enc["attention_mask"].unsqueeze(-1).to(res.dtype)
                pooled = (res * mask).sum(dim=1) / mask.sum(dim=1).clamp(min=1e-9)
                pooled = torch.nn.functional.normalize(pooled.float(), p=2, dim=1)
                out.append(pooled.cpu().numpy().astype(np.float16))
        return np.vstack(out) if out else np.zeros((0, teacher_dim), dtype=np.float16)

    def cleanup():
        nonlocal model, tok
        del model, tok
        gc.collect()
        if torch.cuda.is_available():
            torch.cuda.empty_cache()

    return encode_texts, cleanup


def filter_synth_phrases_with_e5(synth_data, proto_descs, encode_e5):
    """Pre-split E5 Oracle + Script + Indonesian blocklist filter before building val/blind/train."""
    import numpy as np

    t_p_raw = encode_e5(["passage: " + p for p in proto_descs], batch_size=64)
    cand_items = []
    special_items = []
    raw_total = 0
    for item in synth_data.get("search_phrases", []):
        qt = str(item.get("query", "")).strip()
        if not (2 <= len(qt) <= 60):
            continue
        if FORBIDDEN_SCRIPT_RE.search(qt) or INDON_BLOCKLIST_RE.search(qt):
            continue
        if len(qt) >= 6 and qt.isupper() and " " not in qt:
            continue
        has_cjk = any("\u4e00" <= c <= "\u9fff" for c in qt)
        lg = item.get("lang", "")
        if lg == "zh" and (not has_cjk or TRAD_SLANG_ZH_RE.search(qt)):
            continue
        if lg in ("en", "bm") and has_cjk:
            continue
        cat = str(item.get("cat", "")).strip()
        sub = str(item.get("sub", "")).strip()
        spec = str(item.get("special", "")).strip()
        if cat and (cat, sub) in PAIR_INDEX:
            raw_total += 1
            cand_items.append({**item, "query": qt, "cat": cat, "sub": sub})
        elif spec:
            special_items.append({**item, "query": qt})

    kept_pair_items = []
    if cand_items:
        t_cand_raw = encode_e5(["query: " + it["query"] for it in cand_items], batch_size=96)
        mean_vec = np.vstack([t_p_raw, t_cand_raw]).astype(np.float32).mean(axis=0, keepdims=True)

        def _cnorm(arr):
            c = arr.astype(np.float32) - mean_vec
            return c / np.maximum(np.linalg.norm(c, axis=1, keepdims=True), 1e-6)

        t_p_iso = _cnorm(t_p_raw)
        t_cand_iso = _cnorm(t_cand_raw)
        sims = t_cand_iso @ t_p_iso.T
        for i, it in enumerate(cand_items):
            pid = PAIR_INDEX[(it["cat"], it["sub"])]
            rk1 = int(np.sum(sims[i] > sims[i, pid])) + 1
            if rk1 <= 3:
                kept_pair_items.append({**it, "e5_rk1": rk1})

    print(
        f"[PRE-SPLIT E5 ORACLE] Kept {len(kept_pair_items)}/{raw_total} verified LLM search phrases (+{len(special_items)} special)",
        flush=True,
    )
    return kept_pair_items + special_items, t_p_raw


def _parse_query_filters_mirror(raw_q: str, today: str):
    import datetime
    work = raw_q.strip()
    min_m, max_m = -1, -1
    from_d, until_d = "", ""
    hard_k, acct_h = "", ""
    m_k = re.search(r"(?i)\b(?:type|kind):(expense|income|transfer|refund|shared|split)\b", work)
    if m_k:
        hard_k = "shared" if m_k.group(1).lower() == "split" else m_k.group(1).lower()
        work = (work[:m_k.start()] + " " + work[m_k.end():]).strip()
    m_a = re.search(r"(?i)(?:\b(?:account|acct|akaun):\s*|\b(?:via|from|dari|daripada|pakai|guna)\s+|(?:用|从|通过)\s*)(tng|touch\s*'?n\s*go|mae|maybank|cimb|rhb|hlb|gxbank|gx|aeon|ryt|shopeepay|spay|grabpay|boost|bigpay|cash|tunai|现金)\b", work)
    if m_a:
        al = m_a.group(1).lower().strip()
        acct_h = "touch" if ("tng" in al or "touch" in al) else ("maybank" if ("mae" in al or "maybank" in al) else ("cash" if al in ("cash", "tunai", "现金") else al))
        work = (work[:m_a.start()] + " " + work[m_a.end():]).strip()
    m_c = re.search(r"(?i)(>=|<=|>|<)\s*(?:rm|myr)?\s*([0-9]{1,6}(?:\.[0-9]{1,2})?)", work)
    if m_c:
        op = m_c.group(1)
        val = int(round(float(m_c.group(2)) * 100))
        if op == ">=":
            min_m = val
        elif op == ">":
            min_m = val + 1
        elif op == "<=":
            max_m = val
        elif op == "<":
            max_m = max(0, val - 1)
        work = (work[:m_c.start()] + " " + work[m_c.end():]).strip()
    ref_dt = datetime.date.fromisoformat(today)
    if re.search(r"(?i)\b(?:last\s+month|bulan\s+lepas)\b|上个月|上月", work):
        first_this = ref_dt.replace(day=1)
        last_prev = first_this - datetime.timedelta(days=1)
        from_d = last_prev.replace(day=1).isoformat()
        until_d = last_prev.isoformat()
        work = re.sub(r"(?i)\b(?:last\s+month|bulan\s+lepas)\b|上个月|上月", " ", work).strip()
    sem_txt = re.sub(r"\s+", " ", work).strip()
    return sem_txt, min_m, max_m, from_d, until_d, hard_k, acct_h


def eval_ledger_retrieval(md, ledger):
    """Evaluates INT8 binary on a ledger dict identically to Java Finance.searchTransactions."""
    import numpy as np

    txs = ledger["transactions"]
    tx_ids = [t["id"] for t in txs]
    doc_embs = encode_tx_dual_batch(md, txs)

    queries = ledger["queries"]
    today_s = ledger.get("today", "2026-11-24")
    parsed_qs = [_parse_query_filters_mirror(q["query"], today_s) for q in queries]
    q_embs = encode_int8_batch(md, [pq[0] if pq[0] else q["query"] for pq, q in zip(parsed_qs, queries)])
    sem_th = md["sem_th"]

    r5_hits, r10_hits, mrr10 = [], [], []
    by_slice = defaultdict(list)
    by_lang = defaultdict(list)
    for qi, qobj in enumerate(queries):
        rel = set(qobj["relevant_ids"])
        if not rel:
            continue
        sem_txt, min_m, max_m, from_d, until_d, hard_k, acct_h = parsed_qs[qi]
        scores = doc_embs @ q_embs[qi]
        q_low = qobj["query"].lower().strip()
        sem_low = sem_txt.lower().strip()
        is_tax_q = any(k in q_low for k in ("tax relief", "lhdn", "pelepasan cukai", "报税", "扣税"))
        kind_b = ""
        if "refund" in q_low or "退款" in q_low or "bayaran balik" in q_low:
            kind_b = "refund"
        elif "split" in q_low or "kongsi" in q_low or "平分" in q_low:
            kind_b = "shared"
        elif "gaji" in q_low or "salary" in q_low or "工资" in q_low:
            kind_b = "income"
        elif "transfer" in q_low or "转账" in q_low or "充值" in q_low:
            kind_b = "transfer"

        keep = np.zeros(len(txs), dtype=bool)
        comb = np.full(len(txs), -1e9, dtype=np.float32)
        for ti, t in enumerate(txs):
            amt = t["amount"]
            dt = t["date"]
            kd = t["kind"]
            if min_m >= 0 and amt < min_m:
                continue
            if max_m >= 0 and amt > max_m:
                continue
            if from_d and dt < from_d:
                continue
            if until_d and dt > until_d:
                continue
            if hard_k and kd != hard_k:
                continue
            if acct_h and acct_h not in (t["account"] + " " + t["accountName"]).lower():
                continue
            if not sem_txt and not is_tax_q and not kind_b:
                keep[ti] = True
                comb[ti] = 1.0
                continue
            lex = 0.0
            hay = f"{t['merchant']} {t['category']} {t['subcategory']} {t['accountName']} {t['note']} {t['person']}".lower()
            if sem_low and sem_low in hay:
                lex += 0.9
            if is_tax_q and t["lhdnBucket"]:
                lex += 0.95
            if kind_b:
                if (kind_b == "shared" and kd in ("shared", "reimbursement")) or kd == kind_b:
                    lex += 0.95
            if lex > 0.0 or scores[ti] >= sem_th:
                keep[ti] = True
                comb[ti] = lex * 0.70 + max(0.0, float(scores[ti])) * 0.55

        order = [idx for idx in np.argsort(-comb) if keep[idx]]
        top5 = {tx_ids[idx] for idx in order[:5]}
        top10 = {tx_ids[idx] for idx in order[:10]}
        hit5 = 1.0 if (top5 & rel) else 0.0
        hit10 = 1.0 if (top10 & rel) else 0.0
        rr = 0.0
        for rank_idx, idx in enumerate(order[:10], start=1):
            if tx_ids[idx] in rel:
                rr = 1.0 / rank_idx
                break
        r5_hits.append(hit5)
        r10_hits.append(hit10)
        mrr10.append(rr)
        by_slice[qobj["slice"]].append(hit5)
        by_lang[qobj["lang"]].append(hit5)

    oods = [x["query"] for x in ledger.get("ood_queries", [])]
    ood_embs = encode_int8_batch(md, oods)
    ood_max = (ood_embs @ doc_embs.T).max(axis=1) if len(ood_embs) else np.array([])
    ood_fpr = float(np.mean(ood_max >= sem_th)) if len(ood_max) else 0.0

    q_raw_embs = encode_int8_batch(md, [q["query"] for q in queries])
    unth_r5 = []
    for qi, qobj in enumerate(queries):
        if qobj["slice"] in ("structured_constraint", "lhdn_tax", "kind_filter"):
            continue
        rel = set(qobj["relevant_ids"])
        top5 = {tx_ids[idx] for idx in np.argsort(-(doc_embs @ q_raw_embs[qi]))[:5]}
        unth_r5.append(1.0 if (top5 & rel) else 0.0)

    return {
        "n_queries": len(r5_hits),
        "recall_at_5": round(float(np.mean(r5_hits)) if r5_hits else 0.0, 4),
        "recall_at_10": round(float(np.mean(r10_hits)) if r10_hits else 0.0, 4),
        "mrr_at_10": round(float(np.mean(mrr10)) if mrr10 else 0.0, 4),
        "unthresholded_concept_r5": round(float(np.mean(unth_r5)) if unth_r5 else 0.0, 4),
        "ood_fpr": round(ood_fpr, 4),
        "recall_at_5_by_lang": {k: round(float(np.mean(v)), 4) for k, v in sorted(by_lang.items())},
        "recall_at_5_by_slice": {k: round(float(np.mean(v)), 4) for k, v in sorted(by_slice.items())},
    }


def eval_proto_classifier(md, samples):
    import numpy as np

    exp_n = len(PAIRS_74)
    texts = [s[0] for s in samples]
    pids = [s[1] for s in samples]
    embs = encode_int8_batch(md, texts)
    sims = embs @ md["proto_embs"][:exp_n].T
    top1 = sims.argmax(axis=1)
    cat_top = [md["proto_cats"][int(i)] for i in top1]
    cat_true = [PAIRS_74[p][0] for p in pids]

    raw_cat_acc = sum(1 for a, b in zip(cat_top, cat_true) if a == b) / max(1, len(pids))
    sub_acc = sum(1 for i, p in enumerate(pids) if int(top1[i]) == p) / max(1, len(pids))
    return {"n": len(pids), "raw_cat_acc": round(raw_cat_acc, 4), "raw_sub_acc": round(sub_acc, 4)}


def train_and_export():
    import numpy as np
    import torch
    import torch.nn as nn
    import torch.nn.functional as F

    update_status("PREPARING_ENTITY_SPLITS")
    train_ents, val_ents, test_ents = _pick_entity_banks()
    synth_data = run_qwen_multilingual_synthesis()

    proto_descs = []
    for cat, sub in PAIRS_77:
        seeds = CONCEPT_SEED_BANK.get((cat, sub), {})
        bits = [cat, sub] if sub else [cat]
        for lg in ("en", "bm", "mg", "zh"):
            bits.extend(seeds.get(lg, [])[:4])
        proto_descs.append(" | ".join(bits))

    encode_e5, cleanup_e5 = load_e5_teacher()
    verified_search_phrases, t_p_raw = filter_synth_phrases_with_e5(synth_data, proto_descs, encode_e5)

    ledgers = _build_blind_ledger_and_queries(verified_search_phrases, val_ents, test_ents)
    quick_blind = _build_blind_test_quick_entries(synth_data.get("quick_entries", []), val_ents, test_ents)

    blind_exclude_q = {q["query"].lower().strip() for q in (ledgers["val_ledger"]["queries"] + ledgers["blind_ledger"]["queries"])}
    blind_exclude_qe = {c["text"].lower().strip() for c in quick_blind["cases"]}

    train_bundle = build_training_examples(
        train_ents,
        verified_search_phrases,
        synth_data.get("quick_entries", []),
        blind_exclude_q,
        blind_exclude_qe,
    )
    pair_pos = train_bundle["pair_pos"]
    proto_examples = train_bundle["proto_examples"]
    docs_by_pair = train_bundle["docs_by_pair"]
    ood_qs = train_bundle["ood_train_queries"]

    # Zero-leakage assertions across queries, OOD sets, and merchant entities
    train_qset = {r[0].lower().strip() for r in pair_pos}
    overlap_q = train_qset & blind_exclude_q
    assert not overlap_q, f"Query leakage detected: {sorted(overlap_q)[:5]}"
    ood_train_set = {x.lower().strip() for x in ood_qs}
    ood_val_set = {x["query"].lower().strip() for x in ledgers["val_ledger"]["ood_queries"]}
    ood_test_set = {x["query"].lower().strip() for x in ledgers["blind_ledger"]["ood_queries"]}
    assert not (ood_train_set & (ood_val_set | ood_test_set)), "OOD query leakage detected!"
    train_merch_groups = {f4.normalize_merchant_group(nm) for nms in train_ents.values() for nm in nms}
    eval_merch_groups = {f4.normalize_merchant_group(nm) for d in (val_ents, test_ents) for nms in d.values() for nm in nms}
    assert not (train_merch_groups & eval_merch_groups), "Merchant entity leakage detected!"

    # Build Exact 63-bit Sorted Vocabulary strictly from training texts (zero eval/OOD leakage)
    vocab_src_texts = sorted({r[0] for r in pair_pos} | {r[1] for r in pair_pos} | {x[0] for x in proto_examples} | set(proto_descs))
    vocab_hashes, vocab_keys, hash_to_idx = build_exact_vocab(vocab_src_texts, max_vocab=MAX_VOCAB)
    vocab_size = len(vocab_hashes)
    print(f"[EXACT VOCAB] size={vocab_size} (max={MAX_VOCAB})", flush=True)

    # Encode training queries, docs, and vocab surface words using already-loaded E5 teacher, then free GPU VRAM
    unique_qs = sorted({r[0] for r in pair_pos})
    unique_ds = sorted({r[1] for r in pair_pos})
    q2idx = {q: i for i, q in enumerate(unique_qs)}
    d2idx = {d: i for i, d in enumerate(unique_ds)}

    svd_rows, svd_texts = [], []
    for v_i, fk in enumerate(vocab_keys):
        pref, _, surf = fk.partition(":")
        if pref in ("w", "zh2", "zh3") and surf:
            svd_rows.append(v_i)
            svd_texts.append(surf)

    update_status("ENCODING_E5_TRAIN_PAIRS", queries=len(unique_qs), docs=len(unique_ds), vocab_svd=len(svd_texts))
    _e5_bs = 32 if (torch.cuda.is_available() and torch.cuda.get_device_properties(0).total_memory < 20 * (1024**3)) else 96
    t_q_embs = encode_e5(["query: " + q for q in unique_qs], batch_size=_e5_bs)
    t_d_embs = encode_e5(["passage: " + d for d in unique_ds], batch_size=_e5_bs)
    teacher_dim = int(t_q_embs.shape[1])
    t_v_embs = encode_e5(["query: " + s for s in svd_texts], batch_size=_e5_bs) if svd_texts else np.zeros((0, teacher_dim), dtype=np.float16)
    cleanup_e5()

    # Isotropize E5 embeddings via All-but-the-Top centering
    t_mean = np.vstack([t_q_embs, t_d_embs]).astype(np.float32).mean(axis=0, keepdims=True)
    def _center_norm(arr):
        c = arr.astype(np.float32) - t_mean
        return c / np.maximum(np.linalg.norm(c, axis=1, keepdims=True), 1e-6)
    t_q_iso = _center_norm(t_q_embs)
    t_d_iso = _center_norm(t_d_embs)
    t_p_iso = _center_norm(t_p_raw)

    # Compute 768 -> 64 SVD warm-start vectors for exact vocab rows
    v_init = None
    if len(svd_rows) >= EMB_DIM:
        t_v_iso = _center_norm(t_v_embs)
        _, _, vt = np.linalg.svd(t_v_iso, full_matrices=False)
        proj = t_v_iso @ vt[:EMB_DIM].T
        proj = proj / np.maximum(np.linalg.norm(proj, axis=1, keepdims=True), 1e-6)
        v_init = (0.25 * proj).astype(np.float32)

    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    update_status("TRAINING_STUDENT_ENCODER", train_pairs=len(pair_pos), proto_examples=len(proto_examples), vocab_size=vocab_size, device=str(device))

    # Build exact-vocab CSR matrices
    q_texts = [r[0] for r in pair_pos]
    d_texts = [r[1] for r in pair_pos]
    d_tags = [r[2] for r in pair_pos]
    cls_pids_arr = np.array([r[5] for r in pair_pos], dtype=np.int64)
    doc_pids_arr = np.array([r[7] for r in pair_pos], dtype=np.int64)
    rng = random.Random(20261124)
    hn_texts, hn_tags, hn_pids = [], [], []
    for r in pair_pos:
        pool = docs_by_pair.get(r[6]) or [(r[1], r[2])]
        pick = rng.choice(pool)
        hn_texts.append(pick[0])
        hn_tags.append(pick[1])
        hn_pids.append(PAIR_INDEX.get(r[6], -1))

    X_q = encode_inputs_to_csr(q_texts, hash_to_idx=hash_to_idx)
    X_d = encode_inputs_to_csr(d_texts, tag_lists=d_tags, hash_to_idx=hash_to_idx)
    X_hn = encode_inputs_to_csr(hn_texts, tag_lists=hn_tags, hash_to_idx=hash_to_idx)
    X_proto = encode_inputs_to_csr(proto_descs, hash_to_idx=hash_to_idx)
    X_ood = encode_inputs_to_csr(ood_qs, hash_to_idx=hash_to_idx)

    X_cls = encode_inputs_to_csr([x[0] for x in proto_examples], hash_to_idx=hash_to_idx)
    y_cls = np.array([x[1] for x in proto_examples], dtype=np.int64)

    q_teacher_idx = np.array([q2idx[r[0]] for r in pair_pos], dtype=np.int64)
    d_teacher_idx = np.array([d2idx[r[1]] for r in pair_pos], dtype=np.int64)
    tq_gpu = torch.from_numpy(t_q_iso).to(device=device, dtype=torch.float32)
    td_gpu = torch.from_numpy(t_d_iso).to(device=device, dtype=torch.float32)
    tp_gpu = torch.from_numpy(t_p_iso).to(device=device, dtype=torch.float32)
    cls_pids_t = torch.from_numpy(cls_pids_arr).to(device)
    doc_pids_t = torch.from_numpy(doc_pids_arr).to(device)
    hn_pids_t = torch.from_numpy(np.array(hn_pids, dtype=np.int64)).to(device)

    def to_gpu(sub):
        return (
            torch.from_numpy(sub.indices.astype(np.int64, copy=False)).to(device),
            torch.from_numpy(sub.indptr[:-1].astype(np.int64, copy=False)).to(device),
            torch.from_numpy(sub.data.astype(np.float32, copy=False)).to(device),
            torch.from_numpy((np.diff(sub.indptr) > 0)[:, None]).to(device),
            torch.from_numpy(np.asarray(sub.sum(axis=1), dtype=np.float32)).to(device),
        )

    X_proto_gpu = to_gpu(X_proto)
    X_ood_gpu = to_gpu(X_ood)
    y_cls_t = torch.from_numpy(y_cls).to(device)

    class SearchBiEncoder(nn.Module):
        def __init__(self, v_size: int, num_protos: int):
            super().__init__()
            self.E = nn.EmbeddingBag(v_size, EMB_DIM, mode="sum")
            nn.init.normal_(self.E.weight, mean=0.0, std=0.02)
            self.fc1 = nn.Linear(EMB_DIM, HIDDEN_DIM)
            self.ln1 = nn.LayerNorm(HIDDEN_DIM, eps=1e-5)
            self.fc2 = nn.Linear(HIDDEN_DIM, EMB_DIM)
            self.P_res = nn.Parameter(torch.zeros(num_protos, EMB_DIM))
            self.e5_proj = nn.Linear(EMB_DIM, teacher_dim, bias=False)

        def fq(self, w, qat: bool):
            if not qat:
                return w
            with torch.no_grad():
                sc = w.abs().amax(dim=1, keepdim=True).clamp(min=1e-8) / 127.0
                wq = torch.clamp(torch.round(w / sc), -127.0, 127.0) * sc
            return w + (wq - w).detach()

        def encode_csr(self, idx, off, val, nnz, cov, qat: bool = False, feat_drop: float = 0.0, return_r: bool = False):
            Ew = self.fq(self.E.weight, qat)
            if feat_drop > 0.0 and self.training:
                keep = (torch.rand(val.shape, device=val.device) >= feat_drop).to(val.dtype)
                e0_drop = F.embedding_bag(idx, Ew, off, mode="sum", per_sample_weights=val * keep)
                has_k = e0_drop.abs().sum(dim=-1, keepdim=True) > 1e-6
                e0_raw = torch.where(has_k, e0_drop, F.embedding_bag(idx, Ew, off, mode="sum", per_sample_weights=val))
            else:
                e0_raw = F.embedding_bag(idx, Ew, off, mode="sum", per_sample_weights=val)
            r0 = e0_raw.norm(dim=-1, keepdim=True)
            e0 = torch.where(nnz, e0_raw / r0.clamp(min=1e-6), torch.zeros_like(e0_raw))
            w1 = self.fq(self.fc1.weight, qat)
            h = F.gelu(self.ln1(F.linear(e0, w1, self.fc1.bias)), approximate="tanh")
            w2 = self.fq(self.fc2.weight, qat)
            e1 = e0 + F.linear(h, w2, self.fc2.bias)
            norm = e1.norm(dim=-1, keepdim=True).clamp(min=1e-6)
            e1_unit = torch.where(nnz, e1 / norm, torch.zeros_like(e1))
            r3 = r0 * r0 * r0
            c2 = cov * cov
            gate = (r3 / (r3 + (GATE_C ** 3))) * (c2 / (c2 + (COV_GATE_C * COV_GATE_C)))
            out = torch.where(nnz, gate * e1_unit, torch.zeros_like(e1_unit))
            if return_r:
                return out, r0.squeeze(-1)
            return out

    net = SearchBiEncoder(vocab_size, len(PAIRS_77)).to(device)
    if v_init is not None:
        with torch.no_grad():
            net.E.weight[torch.tensor(svd_rows, dtype=torch.long, device=device)] = torch.from_numpy(v_init).to(device=device, dtype=torch.float32)

    epochs = 100
    batch_size = 512
    N = len(pair_pos)
    N_cls = len(proto_examples)
    opt = torch.optim.AdamW(net.parameters(), lr=4.0e-3, weight_decay=5e-4)
    sched = torch.optim.lr_scheduler.CosineAnnealingLR(opt, T_max=max(1, epochs * math.ceil(N / batch_size)))

    rng_np = np.random.default_rng(2026)
    tau = 0.07
    t0 = time.perf_counter()

    for ep in range(1, epochs + 1):
        qat = ep > (epochs - 20)
        fdrop = 0.0 if qat else 0.15
        net.train()
        perm = rng_np.permutation(N)
        perm_cls = rng_np.permutation(N_cls)
        cls_cursor = 0
        tot_loss, steps = 0.0, 0
        for st in range(0, N, batch_size):
            bi = perm[st:st + batch_size]
            bp = cls_pids_t[bi]
            bdp = doc_pids_t[bi]
            hbp = hn_pids_t[bi]

            P_clean = net.encode_csr(*X_proto_gpu, qat=qat, feat_drop=0.0)
            P_all = F.normalize(P_clean + 0.60 * torch.tanh(net.P_res), dim=-1)

            uq = net.encode_csr(*to_gpu(X_q[bi]), qat=qat, feat_drop=fdrop)
            vd_surf = net.encode_csr(*to_gpu(X_d[bi]), qat=qat, feat_drop=0.0)
            hn_surf = net.encode_csr(*to_gpu(X_hn[bi]), qat=qat, feat_drop=0.0)

            dmask = bdp >= 0
            vd = torch.where(dmask[:, None], F.normalize(DOC_SURF_WEIGHT * vd_surf + DOC_PROTO_WEIGHT * P_all[bdp.clamp(min=0)], dim=-1), vd_surf)
            hmask = hbp >= 0
            hn = torch.where(hmask[:, None], F.normalize(DOC_SURF_WEIGHT * hn_surf + DOC_PROTO_WEIGHT * P_all[hbp.clamp(min=0)], dim=-1), hn_surf)

            B = uq.shape[0]
            sim_mat = (uq @ vd.T) / tau
            same_pid = ((bp[:, None] >= 0) & (bp[:, None] == bp[None, :])).to(uq.dtype)
            eye_b = torch.eye(B, device=device, dtype=uq.dtype)
            pos_mask = torch.maximum(same_pid, eye_b)
            pos_cnt = pos_mask.sum(dim=1).clamp(min=1.0)
            sim_full_q = torch.cat([sim_mat, (uq * hn).sum(dim=-1, keepdim=True) / tau], dim=1)
            loss_u = -((pos_mask * F.log_softmax(sim_full_q, dim=1)[:, :B]).sum(dim=1) / pos_cnt).mean()

            sim_full_d = torch.cat([sim_mat.T, (vd * hn).sum(dim=-1, keepdim=True) / tau], dim=1)
            loss_v = -((pos_mask * F.log_softmax(sim_full_d, dim=1)[:, :B]).sum(dim=1) / pos_cnt).mean()

            pos_cos = (uq * vd).sum(dim=-1)
            hn_cos = (uq * hn).sum(dim=-1)
            diff_mask = 1.0 - pos_mask
            inbatch_neg_max = ((uq @ vd.T) * diff_mask - pos_mask * 10.0).max(dim=1).values
            loss_nce = (
                0.5 * (loss_u + loss_v)
                + 0.8 * F.relu(0.50 + hn_cos - pos_cos).mean()
                + 1.5 * F.relu(0.82 - pos_cos).pow(2).mean()
                + 1.0 * F.relu(inbatch_neg_max - 0.16).pow(2).mean()
                + 1.0 * F.relu(hn_cos - 0.14).pow(2).mean()
            )

            tq_b = tq_gpu[q_teacher_idx[bi]]
            td_b = td_gpu[d_teacher_idx[bi]]
            t_rel = (tq_b @ td_b.T) / 0.04
            s_rel = (uq @ vd.T) / 0.12
            loss_kl = F.kl_div(F.log_softmax(s_rel, dim=-1), F.softmax(t_rel, dim=-1), reduction="batchmean")
            s_q2p = F.normalize(net.e5_proj(uq), dim=-1)
            s_d2p = F.normalize(net.e5_proj(vd), dim=-1)
            loss_distill = 0.15 * loss_kl + 0.25 * (
                (1.0 - (s_q2p * tq_b).sum(dim=-1)).mean() + (1.0 - (s_d2p * td_b).sum(dim=-1)).mean()
            )

            pmask = bp >= 0
            loss_proto = 0.02 * net.P_res.pow(2).mean()
            if pmask.any():
                yp = bp[pmask]
                oh_p = F.one_hot(yp, num_classes=len(PAIRS_77)).to(uq.dtype)
                loss_proto = loss_proto + F.cross_entropy((uq[pmask] @ P_all.T) * 18.0 - 2.0 * oh_p, yp, label_smoothing=0.02)
                loss_proto = loss_proto + F.cross_entropy((vd_surf[pmask] @ P_all.T) * 18.0 - 2.0 * oh_p, yp, label_smoothing=0.02)
                loss_proto = loss_proto + 1.2 * F.relu(0.84 - (uq[pmask] * P_all[yp]).sum(dim=-1)).pow(2).mean()
                loss_proto = loss_proto + 0.8 * F.relu(0.82 - (vd_surf[pmask] * P_all[yp]).sum(dim=-1)).pow(2).mean()

            c_idx = perm_cls[cls_cursor:cls_cursor + min(batch_size, N_cls - cls_cursor)]
            if len(c_idx) < 32:
                cls_cursor = 0
                c_idx = perm_cls[:min(batch_size, N_cls)]
            else:
                cls_cursor += len(c_idx)
            u_cls = net.encode_csr(*to_gpu(X_cls[c_idx]), qat=qat, feat_drop=fdrop)
            y_c = y_cls_t[c_idx]
            one_h = F.one_hot(y_c, num_classes=len(PAIRS_77)).to(u_cls.dtype)
            loss_proto = loss_proto + 0.9 * F.cross_entropy((u_cls @ P_all.T) * 18.0 - 2.0 * one_h, y_c, label_smoothing=0.02)
            p2t = F.normalize(net.e5_proj(P_all), dim=-1)
            loss_proto = loss_proto + 0.30 * (1.0 - (p2t * tp_gpu).sum(dim=-1)).mean()

            # Real OOD sentences only (zero hash collisions in exact vocab; stopwords/common OOD terms shrink to 0)
            u_ood, r_ood = net.encode_csr(*X_ood_gpu, qat=qat, feat_drop=0.0, return_r=True)
            ood_d_sims = u_ood @ vd.T
            ood_s_sims = u_ood @ vd_surf.T
            ood_p_sims = u_ood @ P_all.T
            ood_self = u_ood @ u_ood.T
            ood_eye = torch.eye(u_ood.shape[0], device=device, dtype=u_ood.dtype)
            ood_self_off = ood_self * (1.0 - ood_eye) - ood_eye
            loss_ood = (
                1.8 * (
                    F.relu(ood_d_sims - 0.14).pow(2).mean()
                    + F.relu(ood_s_sims - 0.16).pow(2).mean()
                    + F.relu(ood_d_sims.max(dim=1).values - 0.18).pow(2).mean()
                    + F.relu(ood_p_sims.max(dim=1).values - 0.18).pow(2).mean()
                    + 0.8 * F.relu(ood_self_off - 0.18).pow(2).mean()
                )
                + 3.0 * r_ood.pow(2).mean()
                + 0.002 * net.E.weight.pow(2).mean()
            )
            loss_bias = 0.08 * (net.fc1.bias.pow(2).mean() + net.fc2.bias.pow(2).mean())

            loss = 1.0 * loss_nce + 0.85 * loss_distill + 1.0 * loss_proto + loss_ood + loss_bias
            opt.zero_grad(set_to_none=True)
            loss.backward()
            torch.nn.utils.clip_grad_norm_(net.parameters(), 2.0)
            opt.step()
            sched.step()
            tot_loss += float(loss.item())
            steps += 1
        if ep in (1, 20, 45, 80, 100):
            print(f"[Search Ep {ep}/{epochs} QAT={qat}] loss={tot_loss / max(1, steps):.4f}", flush=True)

    sd = net.state_dict()
    weights = {
        "E": sd["E.weight"].detach().cpu().numpy(),
        "W1": sd["fc1.weight"].detach().cpu().numpy(),
        "b1": sd["fc1.bias"].detach().cpu().numpy(),
        "ln1_w": sd["ln1.weight"].detach().cpu().numpy(),
        "ln1_b": sd["ln1.bias"].detach().cpu().numpy(),
        "W2": sd["fc2.weight"].detach().cpu().numpy(),
        "b2": sd["fc2.bias"].detach().cpu().numpy(),
    }
    p_res_np = 0.60 * np.tanh(sd["P_res"].detach().cpu().numpy())

    # Export initial INT8 binary -> compute exact INT8 proto embeddings + fold P_res cleanly
    gz0 = serialize_search_bin_gz(weights, vocab_hashes, PAIRS_77, np.zeros((len(PAIRS_77), EMB_DIM), dtype=np.float32), 0.45, 0.0, 0.50, 0.04)
    md0 = load_search_bin_gz(gz0)
    p_embs_clean = encode_int8_batch(md0, proto_descs)
    p_embs_raw = p_embs_clean + p_res_np
    p_embs_final = (p_embs_raw / np.maximum(np.linalg.norm(p_embs_raw, axis=1, keepdims=True), 1e-6)).astype(np.float32)

    # Calibrate semanticThreshold on VAL LEDGER (NEVER blind test ledger!)
    gz_calib = serialize_search_bin_gz(weights, vocab_hashes, PAIRS_77, p_embs_final, 0.45, 0.0, 0.50, 0.04)
    md_val = load_search_bin_gz(gz_calib)

    val_txs = ledgers["val_ledger"]["transactions"]
    val_doc_embs = encode_tx_dual_batch(md_val, val_txs)
    val_ood_embs = encode_int8_batch(md_val, [x["query"] for x in ledgers["val_ledger"]["ood_queries"]])
    val_ood_max = (val_ood_embs @ val_doc_embs.T).max(axis=1)

    best_sem_score, best_sem_th = -1.0, 0.35
    for max_fpr in (0.025, 0.035, 0.05):
        for sth in np.arange(0.18, 0.68, 0.01):
            fpr = float(np.mean(val_ood_max >= sth))
            if fpr > max_fpr:
                continue
            md_val["sem_th"] = float(round(sth, 3))
            m = eval_ledger_retrieval(md_val, ledgers["val_ledger"])
            comb_sc = m["recall_at_5"] + 0.5 * m["mrr_at_10"]
            if comb_sc > best_sem_score:
                best_sem_score = comb_sc
                best_sem_th = float(round(sth, 3))
        if best_sem_score >= 0.0:
            break
    safe_floor = float(round(min(0.38, float(np.percentile(val_ood_max, 98)) + 0.025), 3))
    if best_sem_th < safe_floor and float(val_ood_max.max()) < 0.45:
        md_val["sem_th"] = safe_floor
        eval_ledger_retrieval(md_val, ledgers["val_ledger"])
        best_sem_th = safe_floor

    # Calibrate classifyThreshold & classifyMargin over ONLY first 74 expense prototypes using unseen VAL SMEs + val LLM phrases
    exp_n = len(PAIRS_74)
    val_tx_by_id = {t["id"]: t for t in val_txs}
    val_cls_texts = []
    val_cls_true_cat = []
    for (c, s), nms in val_ents.items():
        for nm in nms:
            val_cls_texts.append(nm)
            val_cls_true_cat.append(c)
    for vq in ledgers["val_ledger"]["queries"]:
        if vq["slice"] in ("concept", "zero_lexical_overlap") and vq.get("relevant_ids"):
            t0_v = val_tx_by_id.get(vq["relevant_ids"][0])
            if t0_v and (t0_v["category"], t0_v["subcategory"]) in PAIR_INDEX and PAIR_INDEX[(t0_v["category"], t0_v["subcategory"])] < exp_n:
                val_cls_texts.append(vq["query"])
                val_cls_true_cat.append(t0_v["category"])
    val_c_embs = encode_int8_batch(md_val, val_cls_texts)
    exp_proto_embs = md_val["proto_embs"][:exp_n]
    exp_proto_cats = md_val["proto_cats"][:exp_n]
    val_c_sims = val_c_embs @ exp_proto_embs.T
    val_ood_c_sims = val_ood_embs @ exp_proto_embs.T

    def calc_cat_margin(sim_mat):
        res_rows = []
        for r in range(sim_mat.shape[0]):
            best_p_idx = int(sim_mat[r].argmax())
            top_c = exp_proto_cats[best_p_idx]
            s1 = float(sim_mat[r, best_p_idx])
            s2_other = -1.0
            for j, pc in enumerate(exp_proto_cats):
                if pc != top_c and float(sim_mat[r, j]) > s2_other:
                    s2_other = float(sim_mat[r, j])
            res_rows.append((top_c, s1, s1 - max(0.0, s2_other)))
        return res_rows

    val_c_pred = calc_cat_margin(val_c_sims)
    val_ood_pred = calc_cat_margin(val_ood_c_sims)

    best_cf1, best_cth, best_cmg = -1.0, 0.40, 0.04
    for cth in np.arange(0.24, 0.72, 0.02):
        for cmg in (0.02, 0.04, 0.06, 0.08):
            ood_fp = sum(1 for _, s1, mg in val_ood_pred if s1 >= cth and mg >= cmg) / max(1, len(val_ood_pred))
            if ood_fp > 0.025:
                continue
            acc_ok = sum(1 for i, (tc, s1, mg) in enumerate(val_c_pred) if s1 >= cth and mg >= cmg and tc == val_cls_true_cat[i]) / max(1, len(val_c_pred))
            if acc_ok > best_cf1:
                best_cf1 = acc_ok
                best_cth = float(round(cth, 3))
                best_cmg = float(round(cmg, 3))

    final_gz = serialize_search_bin_gz(weights, vocab_hashes, PAIRS_77, p_embs_final, best_sem_th, 0.0, best_cth, best_cmg)
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    MODEL_BIN_GZ_PATH.write_bytes(final_gz)
    assert len(final_gz) < MAX_BIN_GZ_BYTES, f"Asset {len(final_gz)} >= {MAX_BIN_GZ_BYTES}"

    md_final = load_search_bin_gz(final_gz)
    val_metrics = eval_ledger_retrieval(md_final, ledgers["val_ledger"])
    blind_metrics = eval_ledger_retrieval(md_final, ledgers["blind_ledger"])

    # Unseen SME + Blind Multilingual Phrase Prototype Evaluation (74 expense prototypes)
    blind_tx_by_id = {t["id"]: t for t in ledgers["blind_ledger"]["transactions"]}
    blind_cls_samples = []
    for pair in PAIRS_74:
        for nm in test_ents.get(pair, []):
            blind_cls_samples.append((nm, PAIR_INDEX[pair]))
    for bq in ledgers["blind_ledger"]["queries"]:
        if bq["slice"] in ("concept", "zero_lexical_overlap") and bq.get("relevant_ids"):
            t0_b = blind_tx_by_id.get(bq["relevant_ids"][0])
            if t0_b and (t0_b["category"], t0_b["subcategory"]) in PAIR_INDEX:
                pid_b = PAIR_INDEX[(t0_b["category"], t0_b["subcategory"])]
                if pid_b < exp_n:
                    blind_cls_samples.append((bq["query"], pid_b))
    proto_eval = eval_proto_classifier(md_final, blind_cls_samples)

    # Save blind datasets & parity vectors
    SEMANTIC_BLIND_PATH.write_text(json.dumps(ledgers["blind_ledger"], indent=2, ensure_ascii=False), encoding="utf-8")
    QUICK_BLIND_PATH.write_text(json.dumps(quick_blind, indent=2, ensure_ascii=False), encoding="utf-8")

    parity_rows = []
    for i in range(50):
        bq = ledgers["blind_ledger"]["queries"][i % len(ledgers["blind_ledger"]["queries"])]
        bt = ledgers["blind_ledger"]["transactions"][i % len(ledgers["blind_ledger"]["transactions"])]
        q_txt = bq["query"]
        qe = encode_int8_batch(md_final, [q_txt])[0]
        de = encode_tx_dual_batch(md_final, [bt])[0]
        psims = qe @ md_final["proto_embs"].T
        top_p = int(psims.argmax())
        parity_rows.append({
            "query": q_txt,
            "query_emb": [round(float(v), 7) for v in qe],
            "top_proto_cat": md_final["proto_cats"][top_p],
            "top_proto_sub": md_final["proto_subs"][top_p],
            "top_proto_sim": round(float(psims[top_p]), 7),
            "tx_doc": {
                "merchant": bt["merchant"],
                "category": bt["category"],
                "subcategory": bt["subcategory"],
                "kind": bt["kind"],
                "note": bt["note"],
                "person": bt["person"],
                "accountName": bt["accountName"],
                "lhdnBucket": bt["lhdnBucket"],
            },
            "tx_emb": [round(float(v), 7) for v in de],
            "qd_sim": round(float(np.dot(qe, de)), 7),
        })
    PARITY_VECTORS_PATH.write_text(json.dumps(parity_rows, indent=2, ensure_ascii=False), encoding="utf-8")

    report = {
        "train_seconds": round(time.perf_counter() - t0, 1),
        "gz_bytes": len(final_gz),
        "vocab_size": vocab_size,
        "emb_dim": EMB_DIM,
        "hidden_dim": HIDDEN_DIM,
        "num_protos": len(PAIRS_77),
        "train_pairs": len(pair_pos),
        "llm_teacher": synth_data.get("model_id", "none"),
        "embed_teacher": E5_TEACHER_ID,
        "calibrated_thresholds": {
            "semanticThreshold": best_sem_th,
            "classifyThreshold": best_cth,
            "classifyMargin": best_cmg,
        },
        "val_retrieval": val_metrics,
        "blind_retrieval": blind_metrics,
        "blind_proto_cls": proto_eval,
        "quick_entry_blind_count": len(quick_blind["cases"]),
    }
    REPORT_PATH.write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")
    print("TRACK 4 SEARCH REPORT:", json.dumps(report, ensure_ascii=False), flush=True)
    update_status(
        "DONE",
        gz_bytes=len(final_gz),
        blind_r5=blind_metrics["recall_at_5"],
        blind_mrr=blind_metrics["mrr_at_10"],
        ood_fpr=blind_metrics["ood_fpr"],
        proto_cat=proto_eval["raw_cat_acc"],
    )


def run_self_test():
    import numpy as np

    assert fnv1a63("") == 0x4BF29CE484222325
    f_en = dict(extract_features("ZUS Coffee KLCC"))
    assert f_en["w:zus"] == 3.0 and f_en["wb:zus_coffee"] == 1.8 and not any(k.startswith("c3:") for k in f_en)
    f_zh = dict(extract_features("昨天喝了冰美式咖啡 RM12.50"))
    assert f_zh["zh2:咖啡"] == 2.6 and f_zh["zh1:咖"] == 0.50 and "w:rm12" in f_zh
    f_acc = dict(extract_features("7-Eleven 7-CAFé"))
    assert "w:cafe" in f_acc and "w:7" in f_acc and "w:eleven" in f_acc

    vh, vk, _ = build_exact_vocab(["咖啡", "kopi", "ZUS Coffee KLCC"], max_vocab=MAX_VOCAB)
    rng = np.random.default_rng(7)
    extra_needed = MAX_VOCAB - len(vh)
    if extra_needed > 0:
        extra_h = rng.integers(1, 2**62, size=extra_needed, dtype=np.int64)
        vh = np.sort(np.unique(np.concatenate([vh, extra_h])))
    w = {
        "E": rng.normal(0, 0.08, size=(len(vh), EMB_DIM)).astype(np.float32),
        "W1": rng.normal(0, 0.08, size=(HIDDEN_DIM, EMB_DIM)).astype(np.float32),
        "b1": np.zeros(HIDDEN_DIM, dtype=np.float32),
        "ln1_w": np.ones(HIDDEN_DIM, dtype=np.float32),
        "ln1_b": np.zeros(HIDDEN_DIM, dtype=np.float32),
        "W2": rng.normal(0, 0.08, size=(EMB_DIM, HIDDEN_DIM)).astype(np.float32),
        "b2": np.zeros(EMB_DIM, dtype=np.float32),
    }
    pe = rng.normal(0, 0.1, size=(len(PAIRS_77), EMB_DIM)).astype(np.float32)
    gz = serialize_search_bin_gz(w, vh, PAIRS_77, pe, 0.44, 0.0, 0.50, 0.04)
    md = load_search_bin_gz(gz)
    embs = encode_int8_batch(md, ["咖啡", "kopi", ""])
    assert embs.shape == (3, EMB_DIM) and (0.0 < float(np.linalg.norm(embs[0])) <= 1.0 + 1e-4) and np.all(embs[2] == 0.0)
    assert len(gz) < MAX_BIN_GZ_BYTES
    print(f"TRACK 4 SELF-TEST PASS: MLSS v2 vocab={len(vh)} dummy_gz_bytes={len(gz)} (< {MAX_BIN_GZ_BYTES}), {len(PAIRS_77)} pairs & features OK.")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        run_self_test()
        return
    try:
        train_and_export()
    except Exception as exc:
        import traceback
        tb = traceback.format_exc()
        print(tb, flush=True)
        update_status("ERROR", error=str(exc), traceback=tb)
        sys.exit(1)


if __name__ == "__main__":
    main()
