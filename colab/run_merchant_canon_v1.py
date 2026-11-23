#!/usr/bin/env python3
"""MoneyLens Track 3 — Contrastive Pure-Java INT8 Malaysian Merchant Canonicalizer & Zero-Shot Bi-Encoder."""
import argparse
import gzip
import json
import math
import os
import random
import re
import struct
import subprocess
import sys
import time
import unicodedata
from collections import Counter, defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import run_foundry_v4 as f4

OUT_DIR = Path("/content/outputs_merchant_v1")
DATASET_V5_GZ_PATH = Path("/content/dataset_malaysian_v5.jsonl.gz")
RECEIPT_V1_GZ_PATH = Path("/content/receipt_vision_v1_dataset.jsonl.gz")
HOLDOUT_V4_PATH = Path("/content/holdout_v4_details.json")
V5_HARD_TEST_PATH = Path("/content/v5_hard_test.json")

STATUS_PATH = OUT_DIR / "merchant_canon_v1_status.json"
REPORT_PATH = OUT_DIR / "merchant_canon_v1_report.json"
TEST_PAIRS_PATH = OUT_DIR / "merchant_canon_v1_test_pairs.json"
MODEL_BIN_GZ_PATH = OUT_DIR / "moneylens_merchant_canon.bin.gz"

MAX_BIN_GZ_BYTES = 512_000
MAGIC = b"MLMC"
MODEL_VERSION = 1
NUM_BUCKETS = 5632
EMB_DIM = 64
HIDDEN_DIM = 128

GW_LEAD_RE = re.compile(
    r"^(?:fpx|dnqr|duitnow\s*qr|duitnow|paynet|ipay88|razerms|razer\s*ms|molpay|"
    r"billplz|senangpay|gkash|stripe|curlec|2c2p|revpay|kiple|shopeepay|shopeep|"
    r"spaylater|spay|atome|payflex|mydebit|tngd|tng|grab|pay|pos|qr|visa|mc|sp|cimbl)\s*\*\s*"
)
SSM_PAREN_RE = re.compile(r"\(\s*(?:[0-9]{5,8}-[a-z]|[0-9]{12}|m)\s*\)")
GLUED_AMT_RE = re.compile(r"(?:(?:rmyr|myr|(?<![a-z])rm)\s*[0-9,]+(?:\.[0-9]{1,2})?|[0-9,]+\.[0-9]{2}(?:\s*(?:rmyr|myr|rm))?)")
LONG_DIGITS_RE = re.compile(r"[0-9]{8,}")
DATE_NOISE_RE = re.compile(
    r"\b(?:[0-3]?[0-9][/-](?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)(?:[/-](?:20)?[0-9]{2})?"
    r"|(?:0[1-9]|[12][0-9]|3[01])[/-](?:0[1-9]|1[0-2])(?:[/-](?:20)?[0-9]{2})?"
    r"|[0-3]?[0-9][/-][01]?[0-9][/-](?:20)?[0-9]{2}|20[0-9]{2}[/-][01]?[0-9][/-][0-3]?[0-9])\b"
)
CORP_LOC_NOISE_RE = re.compile(
    r"\b(?:sdn\.?[\s/-]*bhd\.?|sdn\.?[\s/-]*bh\.?|sdnbhd|sdnbh|s/b|sendirian\s+berhad|berhad|bhd\.?|plt|llp|"
    r"rmyr|myr|rm|rem|charge|payment|"
    r"malaysia|mys|klcc|trx|pj|kl|jb|bngs|bngk|bngklaut)\b"
)
GLUED_TAIL_RE = re.compile(r"([a-z0-9]{4,})(?:sdnbhd|sdnbh|rmyr|myr|klcc|trx)(?=$|[^a-z0-9])")
OCR_ZERO_RE = re.compile(r"(?<=[a-z])0(?=[a-z])")
OCR_ONE_RE = re.compile(r"(?<=[a-z])1(?=[a-z])")
DIGIT_RUN_RE = re.compile(r"[0-9]{2,}")
ASCII_ALNUM_4_RE = re.compile(r"^[a-z0-9]{4,}$")
ASCII_ALNUM_RE = re.compile(r"^[a-z0-9]+$")
VOWEL_RE = re.compile(r"[aeiou]")

GENERIC_ENTITY_WORDS = {
    "klinik", "clinic", "poliklinik", "pergigian", "dental", "keluarga", "restoran", "restaurant",
    "kedai", "kopi", "kopitiam", "kafe", "cafe", "farmasi", "pharmacy", "bengkel", "kereta",
    "servis", "motors", "auto", "pasar", "mini", "mart", "market", "supermarket", "tadika",
    "taska", "pusat", "tuisyen", "tuition", "hospital", "medical", "specialist", "center",
    "centre", "store", "shop", "enterprise", "trading", "resources", "services", "solutions",
    "holdings", "ventures", "global", "group", "maybank", "cimb", "public", "bank", "hong",
    "leong", "ambank", "rhb", "takaful", "insurance", "insurans", "allianz", "great", "eastern",
    "aia", "prudential", "unifi", "tnb", "tenaga", "nasional", "petronas", "shell", "caltex",
    "housing", "loan", "credit", "card", "financing", "saving", "savings", "account", "payment",
    "transfer", "duitnow", "jompay", "fpx", "online", "digital", "mobile", "network", "zakat",
    "kitchen", "hardware", "runcit", "dobi", "salun", "rambut", "roti", "elektrik",
    "bunga", "cermin", "mata", "haiwan", "tayar", "soup",
}


def update_status(stage: str, **extra):
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    payload = {"stage": stage, "ts": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()), **extra}
    STATUS_PATH.write_text(json.dumps(payload, indent=2), encoding="utf-8")
    print(f"[STATUS] {stage} {extra}", flush=True)


def fnv1a32(s: str) -> int:
    """Exact bit-for-bit parity with Java MerchantCanonicalizer.fnv1a32()."""
    h = 0x811C9DC5
    for b in s.encode("utf-8"):
        h ^= b
        h = (h * 0x01000193) & 0xFFFFFFFF
    return h


def normalize_surface(raw: str) -> str:
    """Exact bit-for-bit parity with Java MerchantCanonicalizer.normalizeSurface()."""
    if raw is None:
        return ""
    s = str(raw).lower().strip()
    if not s:
        return ""
    s = "".join(ch for ch in unicodedata.normalize("NFKD", s) if unicodedata.category(ch) != "Mn")
    for _ in range(2):
        ns = GW_LEAD_RE.sub("", s).strip()
        if ns == s:
            break
        s = ns
    s = SSM_PAREN_RE.sub(" ", s)
    s = GLUED_AMT_RE.sub(" ", s)
    s = LONG_DIGITS_RE.sub(" ", s)
    s = DATE_NOISE_RE.sub(" ", s)
    s = CORP_LOC_NOISE_RE.sub(" ", s)
    s = GLUED_TAIL_RE.sub(r"\1", s)
    s = OCR_ZERO_RE.sub("o", s)
    s = OCR_ONE_RE.sub("i", s)
    out = []
    prev_space = True
    for ch in s:
        if ch.isalnum():
            out.append(ch)
            prev_space = False
        elif not prev_space:
            out.append(" ")
            prev_space = True
    return "".join(out).strip()


def has_distinct_entity_conflict(raw_a: str, raw_b: str) -> bool:
    """Exact Python mirror of Java MerchantCanonicalizer.hasDistinctEntityConflict()."""
    na = normalize_surface(raw_a)
    nb = normalize_surface(raw_b)
    if not na or not nb or na == nb:
        return False
    nums_a = {tok for tok in DIGIT_RUN_RE.findall(na) if not (len(tok) == 4 and tok.startswith("20"))}
    nums_b = {tok for tok in DIGIT_RUN_RE.findall(nb) if not (len(tok) == 4 and tok.startswith("20"))}
    if nums_a and nums_b and not (nums_a & nums_b):
        return True
    toks_a = na.split(" ")
    toks_b = nb.split(" ")
    full_a = [t for t in toks_a if len(t) >= 4 and VOWEL_RE.search(t)]
    full_b = [t for t in toks_b if len(t) >= 4 and VOWEL_RE.search(t)]
    dist_a = [t for t in full_a if t not in GENERIC_ENTITY_WORDS]
    dist_b = [t for t in full_b if t not in GENERIC_ENTITY_WORDS]
    if len(full_a) >= 2 and len(full_b) >= 2 and dist_a and dist_b:
        for a in dist_a:
            ska = VOWEL_RE.sub("", a)
            for b in dist_b:
                if a == b or a in b or b in a:
                    return False
                skb = VOWEL_RE.sub("", b)
                if len(ska) >= 3 and len(skb) >= 3 and (ska in skb or skb in ska):
                    return False
        return True
    return False


def extract_surface_features(raw: str):
    """Exact bit-for-bit parity with Java MerchantCanonicalizer.extractFeatures()."""
    norm = normalize_surface(raw)
    feats = set()
    if not norm:
        return feats

    all_tokens = [w for w in norm.split(" ") if w][:12]
    tokens = [w for w in all_tokens if len(w) >= 2]
    if not tokens:
        tokens = all_tokens
    for i, tok in enumerate(tokens):
        feats.add("w:" + tok)
        if i + 1 < len(tokens):
            feats.add("b:" + tok + " " + tokens[i + 1])
        padded = " " + tok + " "
        L = len(padded)
        ns = (2, 3, 4) if len(tok) <= 3 else (3, 4)
        for n in ns:
            if L >= n:
                for st in range(L - n + 1):
                    feats.add("c:" + padded[st:st + n])
        if ASCII_ALNUM_4_RE.match(tok):
            sk = VOWEL_RE.sub("", tok)[:16]
            if len(sk) >= 3:
                feats.add("sk:" + sk)
                if len(sk) >= 4:
                    for st in range(len(sk) - 3):
                        feats.add("sk4:" + sk[st:st + 4])

    full_alnum_all = "".join(ch for ch in norm if ("a" <= ch <= "z") or ("0" <= ch <= "9"))
    fa_str = full_alnum_all[:24]
    if len(fa_str) >= 2:
        feats.add("fa:" + fa_str)
    if len(fa_str) >= 3:
        for st in range(len(fa_str) - 2):
            feats.add("fa3:" + fa_str[st:st + 3])
    if len(fa_str) >= 4:
        for st in range(len(fa_str) - 3):
            feats.add("fa4:" + fa_str[st:st + 4])

    fsk = VOWEL_RE.sub("", full_alnum_all)[:20]
    if len(fsk) >= 4:
        feats.add("fsk:" + fsk)
        for st in range(len(fsk) - 3):
            feats.add("fsk4:" + fsk[st:st + 4])

    ascii_toks = [t for t in all_tokens if ASCII_ALNUM_RE.match(t)]
    hsk = "".join(t[0] + VOWEL_RE.sub("", t[1:]) for t in ascii_toks)[:20]
    if len(hsk) >= 2:
        for st in range(len(hsk) - 1):
            feats.add("hb:" + hsk[st:st + 2])
    if len(hsk) >= 3:
        for st in range(len(hsk) - 2):
            feats.add("hs:" + hsk[st] + hsk[st + 2])
        feats.add("hsk:" + hsk)
        if len(hsk) <= 10:
            feats.add("abbr:" + hsk)
        for st in range(len(hsk) - 2):
            feats.add("hsk3:" + hsk[st:st + 3])
    if len(hsk) >= 4:
        for st in range(len(hsk) - 3):
            feats.add("hsk4:" + hsk[st:st + 4])
    if len(ascii_toks) == 1 and 3 <= len(ascii_toks[0]) <= 14:
        feats.add("abbr:" + ascii_toks[0])
    if len(ascii_toks) >= 2:
        ini = "".join(t[0] for t in ascii_toks[:8])
        if len(ini) >= 3:
            feats.add("abbr:" + ini)
        first_ini = ascii_toks[0] + "".join(t[0] for t in ascii_toks[1:6])
        if 3 <= len(first_ini) <= 14:
            feats.add("abbr:" + first_ini)
        pre2 = "".join(t[:2] for t in ascii_toks[:6])
        if len(pre2) >= 4:
            feats.add("abbr:" + pre2)
        pre4 = "".join(t[:4] for t in ascii_toks[:4])
        if len(pre4) >= 5:
            feats.add("abbr:" + pre4)
            for st in range(len(pre4) - 3):
                feats.add("fa4:" + pre4[st:st + 4])

    cjk = [ch for ch in norm if "\u4e00" <= ch <= "\u9fff"]
    for i, ch in enumerate(cjk):
        feats.add("z:" + ch)
        if i + 1 < len(cjk):
            feats.add("z2:" + ch + cjk[i + 1])
    return feats


def surfaces_to_csr(surfaces, num_buckets=NUM_BUCKETS):
    import numpy as np
    from scipy.sparse import csr_matrix

    indptr, indices, data = [0], [], []
    for raw in surfaces:
        bmap = {}
        for fk in extract_surface_features(raw):
            h = fnv1a32(fk)
            b = (h & 0x7FFFFFFF) % num_buckets
            sgn = 1.0 if ((h >> 31) & 1) == 0 else -1.0
            bmap[b] = bmap.get(b, 0.0) + sgn
        for b in sorted(bmap.keys()):
            v = bmap[b]
            if v != 0.0:
                indices.append(b)
                data.append(v)
        indptr.append(len(indices))
    return csr_matrix(
        (np.array(data, dtype=np.float32), np.array(indices, dtype=np.int32), np.array(indptr, dtype=np.int32)),
        shape=(len(surfaces), num_buckets),
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


def serialize_canon_bin_gz(weights, catalog_entries, catalog_embs, brand_th: float, brand_margin: float, pair_th: float) -> bytes:
    """Packs exact MLMC v1 big-endian binary and returns gzip level-9 bytes."""
    import numpy as np

    buf = bytearray()
    buf.extend(struct.pack(
        ">4s5i3f",
        MAGIC,
        MODEL_VERSION,
        NUM_BUCKETS,
        EMB_DIM,
        HIDDEN_DIM,
        len(catalog_entries),
        float(brand_th),
        float(brand_margin),
        float(pair_th),
    ))
    for name in ("E", "W1", "W2"):
        sc, q = quantize_int8_per_row(weights[name])
        buf.extend(_pack_qmat(sc, q))
    for name in ("b1", "ln1_w", "ln1_b", "b2"):
        buf.extend(np.asarray(weights[name], dtype=">f4").tobytes())

    for c_name, _, _ in catalog_entries:
        buf.extend(_pack_utf8(c_name))
    for _, c_cat, _ in catalog_entries:
        buf.extend(_pack_utf8(c_cat))
    for _, _, c_sub in catalog_entries:
        buf.extend(_pack_utf8(c_sub))
    c_sc, c_q = quantize_int8_per_row(catalog_embs)
    buf.extend(_pack_qmat(c_sc, c_q))
    return gzip.compress(bytes(buf), compresslevel=9)


def load_canon_bin_gz(gz_bytes: bytes):
    import numpy as np

    raw = gzip.decompress(gz_bytes)
    pos = 0
    hdr_sz = struct.calcsize(">4s5i3f")
    magic, ver, nb, ed, hd, ncat, b_th, b_mg, p_th = struct.unpack_from(">4s5i3f", raw, pos)
    pos += hdr_sz
    assert magic == MAGIC and ver == MODEL_VERSION

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
    names = read_str_list(ncat)
    cats = read_str_list(ncat)
    subs = read_str_list(ncat)
    cat_embs = read_qmat()
    cat_norms = np.maximum(np.linalg.norm(cat_embs, axis=1, keepdims=True), 1e-6)
    cat_embs = cat_embs / cat_norms
    assert pos == len(raw), f"Trailing bytes: {pos} != {len(raw)}"
    return {
        "num_buckets": nb,
        "emb_dim": ed,
        "hidden_dim": hd,
        "num_catalog": ncat,
        "brand_th": float(b_th),
        "brand_margin": float(b_mg),
        "pair_th": float(p_th),
        "E": E,
        "W1": W1,
        "W2": W2,
        "b1": b1,
        "ln1_w": ln1_w,
        "ln1_b": ln1_b,
        "b2": b2,
        "names": names,
        "cats": cats,
        "subs": subs,
        "cat_embs": cat_embs,
    }


def _layer_norm_np(x, w, b):
    import numpy as np
    mean = x.mean(axis=-1, keepdims=True)
    var = ((x - mean) ** 2).mean(axis=-1, keepdims=True)
    return ((x - mean) / np.sqrt(var + np.float32(1e-5))) * w + b


def _gelu_tanh_np(x):
    import numpy as np
    return np.float32(0.5) * x * (np.float32(1.0) + np.tanh(np.float32(0.7978845608) * (x + np.float32(0.044715) * x * x * x)))


def encode_int8_batch(md, surfaces):
    import numpy as np
    X = surfaces_to_csr(surfaces, num_buckets=md["num_buckets"])
    e0 = X @ md["E"]
    nnz = np.diff(X.indptr) > 0
    norms = np.maximum(np.linalg.norm(e0, axis=1, keepdims=True), 1e-6)
    e0 = np.where(nnz[:, None], e0 / norms, np.float32(0.0))
    h_pre = e0 @ md["W1"].T + md["b1"]
    h = _gelu_tanh_np(_layer_norm_np(h_pre, md["ln1_w"], md["ln1_b"]))
    e1 = e0 + (h @ md["W2"].T + md["b2"])
    n1 = np.maximum(np.linalg.norm(e1, axis=1, keepdims=True), 1e-6)
    out = e1 / n1
    return np.where(nnz[:, None], out, np.float32(0.0))


def synthesize_terminal_corruptions(name: str, rng: random.Random, n: int = 8):
    """Generates high-realism Malaysian gateway/vowel-drop/glued-suffix/OCR surface variants."""
    gws = ["PAY*", "DNQR*", "TNGD*", "IPAY88*", "RAZERMS*", "MYDEBIT*", "SHOPEEPAY*", "SHOPEEP*", "FPX*", "TNG*"]
    sfxs = ["", " SDN BH", " S/B", " SDN BHD", " KLCC", "-TRX", "-PJ", " KL RM", " MYR", " BNGS", " BNGK", " SUBANG JAYA", " 202201063", " RM35.80"]
    glued_sfxs = ["KLCC", "TRX", "MYR", "SDNBH", "SDNBHD", "-TRX", "-PJ", "-KL", ""]
    clean = re.sub(r"\s+", " ", name.strip())
    words = [w for w in re.split(r"\s+", clean.upper()) if w]
    variants = []

    def head_vowel_drop(tok: str) -> str:
        if len(tok) <= 3:
            return tok
        return tok[0] + re.sub(r"[AEIOU]", "", tok[1:])

    def all_vowel_drop(tok: str) -> str:
        if len(tok) <= 2:
            return tok
        d = re.sub(r"[AEIOU]", "", tok)
        return d if len(d) >= 2 else tok[:2]

    date_sfxs = ["", " 05-JUL-26", " 13/06", " 02-03-2026", " REM", "-TRX", " KLCC"]

    for i in range(n):
        mode = i % 12
        if mode == 0:  # Gateway + head-preserving vowel drop glued
            core = "".join(head_vowel_drop(w) for w in words)[:16]
            s = f"{rng.choice(gws)}{core}{rng.choice(sfxs[:8])}"
        elif mode == 1:  # Gateway + space vowel drop + trailing noise
            core = " ".join(head_vowel_drop(w) for w in words)[:22]
            s = f"{rng.choice(gws)}{core}{rng.choice(sfxs)}"
        elif mode == 2:  # Plain uppercase truncation + suffix (incl hyphenated SDN-BH)
            core = clean.upper()[: max(8, min(18, int(len(clean) * rng.uniform(0.55, 0.85))))]
            s = f"{rng.choice(['', rng.choice(gws)])}{core}{rng.choice(sfxs + [' SDN-BH', '-SDN-BH'])}"
        elif mode == 3:  # Glued uppercase alnum + glued KLCC/TRX/MYR/SDNBH tail + optional date
            core = re.sub(r"[^A-Z0-9]", "", clean.upper())[:16]
            tail = rng.choice(glued_sfxs)
            dt_sfx = f"2025{rng.randint(1,12):02d}{rng.randint(1,28):02d}{rng.randint(1000,9999)}" if tail in ("SDNBHD", "SDNBH") and rng.random() < 0.4 else ""
            amt = "" if dt_sfx else (f"{rng.randint(8, 250)}.{rng.randint(0, 99):02d}" if tail in ("MYR", "") and rng.random() < 0.35 else "")
            s = f"{rng.choice(gws)}{core}{tail}{dt_sfx}{amt}"
        elif mode == 4:  # OCR digit/symbol noise on receipt header
            rep = str.maketrans({"O": "0", "o": "0", "I": "1", "S": "5"})
            noisy = clean.translate(rep) if rng.random() < 0.7 else clean.upper()
            if rng.random() < 0.5:
                paren_l, paren_r = ("（", ")") if rng.random() < 0.35 else ("(", ")")
                noisy += f" {paren_l}{rng.randint(100000, 999999)}-X{paren_r}"
            s = noisy
        elif mode == 5:  # Aggressive full-vowel drop glued (e.g. AEONCRDSDNBH / FNGRS)
            core = "".join(all_vowel_drop(w) for w in words)[:14]
            s = f"{rng.choice(gws)}{core}{rng.choice(['', 'KLCC', 'TRX', 'MYR', '-TRX', ' S/B'])}"
        elif mode == 6:  # 2-char prefix contraction (e.g. 4FCRCHN, ACAS, ADDSRGNS)
            core = "".join(w[:2] for w in words[:4])[:14] if len(words) >= 2 else head_vowel_drop(words[0])[:14]
            if len(core) < 4:
                core = "".join(head_vowel_drop(w) for w in words)[:14]
            s = f"{rng.choice(gws)}{core}{rng.choice(['', ' KL', ' KL RM', ' MYR', '-KJ'])}"
        elif mode == 7:  # pre4 word-prefix concat (e.g. AWSCERTTEST)
            core = "".join(w[:4] for w in words[:4])[:16] if len(words) >= 2 else words[0][:14]
            s = f"{rng.choice(gws)}{core}{rng.choice([' KLCC', '-TRX', ' MYR', ''])}"
        elif mode == 8:  # Pure initials (ADMC) or first_word + initials (APUB / AIRASIAMR)
            if len(words) >= 3:
                core = "".join(w[0] for w in words[:6]) if rng.random() < 0.45 else (words[0][:7] + "".join(w[0] for w in words[1:5]))
            elif len(words) == 2:
                core = words[0][:7] + words[1][:2]
            else:
                core = head_vowel_drop(words[0])[:12]
            s = f"{rng.choice(gws)}{core}{rng.choice(['', ' SDN BH', '-TRX', ' KL'])}"
        elif mode == 9:  # Mixed first word + contracted remainder
            if len(words) >= 2:
                core = words[0][:4] + " " + "".join(head_vowel_drop(w) for w in words[1:])[:14]
                s = f"{rng.choice(gws)}{core}{rng.choice(['', ' PJ RM', ' TRX', ' MYR'])}"
            else:
                s = f"{rng.choice(gws)}{head_vowel_drop(words[0])[:14]}{rng.choice(['', ' MYR'])}"
        elif mode == 10:  # Stochastic inner character drop (ALLITHPMR, BRYS, AVSSPLTH, BARNQPLAZ)
            fa_u = re.sub(r"[^A-Z0-9]", "", clean.upper())
            if len(fa_u) > 4:
                keep_h = rng.randint(2, min(4, len(fa_u) - 1))
                p_keep = rng.uniform(0.50, 0.75)
                tail_kept = "".join(ch for ch in fa_u[keep_h:] if rng.random() < p_keep)
                core = (fa_u[:keep_h] + tail_kept)[:16]
            else:
                core = fa_u
            s = f"{rng.choice(gws)}{core}{rng.choice(date_sfxs)}"
        else:  # First-word initial + remaining consonant skeleton / word2+ (ASLNGR, BWNLSSCRNNG, BPAYFLEX)
            if len(words) >= 2:
                if rng.random() < 0.6:
                    core = words[0][0] + "".join(all_vowel_drop(w) for w in words[1:])[:14]
                else:
                    core = words[0][0] + "".join(re.sub(r"[^A-Z0-9]", "", w) for w in words[1:])[:12]
            else:
                core = head_vowel_drop(words[0])[:12]
            s = f"{rng.choice(gws)}{core}{rng.choice(['', '-TRX', ' PJ', ' 05-JUL-26', ' 13/06'])}"
        if not re.sub(r"^(?:PAY|DNQR|TNGD|IPAY88|RAZERMS|MYDEBIT|SHOPEEPAY|SHOPEEP|FPX|TNG)\*", "", s).strip() or len(normalize_surface(s)) < 2:
            s = f"{rng.choice(['', 'PAY*', 'DNQR*'])}{clean}{rng.choice(['', ' SDN BHD', ' RM25.00', ' DuitNow'])}"
        variants.append(s.strip())
    return variants


def read_jsonl_gz(path: Path):
    rows = []
    with gzip.open(path, "rt", encoding="utf-8") as fh:
        for line in fh:
            s = line.strip()
            if s:
                rows.append(json.loads(s))
    return rows


def build_catalog_and_datasets(seed: int = 20261124):
    rng = random.Random(seed)

    # 1. Build clean canonical brand catalog (~650-800 brands)
    catalog_map = {}  # norm_key -> (canonicalName, cat, sub)
    for pair_key, names in f4.MERCHANT_SEEDS_2026.items():
        cat, sub = pair_key.split(" || ", 1)
        for nm in names:
            nk = normalize_surface(nm)
            if len(nk) >= 3 and nk not in catalog_map:
                catalog_map[nk] = (nm.strip(), cat, sub)

    v5_rows = read_jsonl_gz(DATASET_V5_GZ_PATH) if DATASET_V5_GZ_PATH.exists() else []
    rec_rows = read_jsonl_gz(RECEIPT_V1_GZ_PATH) if RECEIPT_V1_GZ_PATH.exists() else []

    # Add frequent clean multi-sample Malaysian merchants into catalog up to 800 total
    m_counts = Counter()
    m_cat_votes = defaultdict(Counter)
    m_raw_forms = defaultdict(Counter)
    for r in v5_rows:
        em = str(r.get("expected_merchant", "")).strip()
        if not em or "*" in em or len(em) < 4 or len(em) > 45:
            continue
        if em.upper() == em and len(em) <= 6:
            continue
        nk = normalize_surface(em)
        if len(nk) < 4:
            continue
        m_counts[nk] += 1
        m_cat_votes[nk][(r["category"], r["subcategory"])] += 1
        m_raw_forms[nk][em] += 1

    for nk, cnt in m_counts.most_common():
        if len(catalog_map) >= 800:
            break
        if cnt >= 5 and nk not in catalog_map:
            (best_cat, best_sub), top_v = m_cat_votes[nk].most_common(1)[0]
            if top_v / float(cnt) >= 0.85:
                rep_name = m_raw_forms[nk].most_common(1)[0][0]
                catalog_map[nk] = (rep_name, best_cat, best_sub)

    catalog_entries = sorted(catalog_map.values(), key=lambda x: x[0].lower())
    catalog_nk_set = {normalize_surface(e[0]): idx for idx, e in enumerate(catalog_entries)}

    def _resolve_input(p_colab: Path, rel_local: str):
        if p_colab.exists():
            return p_colab
        cand = Path(__file__).resolve().parent / rel_local
        return cand if cand.exists() else p_colab

    eval_keys = set()
    for p_json in (
        _resolve_input(HOLDOUT_V4_PATH, "outputs_v4/holdout_v4_details.json"),
        _resolve_input(V5_HARD_TEST_PATH, "outputs_v5/v5_hard_test.json"),
    ):
        if p_json.exists():
            try:
                for row in json.loads(p_json.read_text(encoding="utf-8")):
                    em_k = str(row.get("expected_merchant", "")).strip()
                    rt_k = str(row.get("raw_text", ""))
                    if em_k and rt_k:
                        eval_keys.add((em_k, rt_k))
            except Exception:
                pass

    # 2. Harvest real observed noisy surfaces from v5 corpus & track-1 OCR receipts
    # Collect surfaces grouped by normalized canonical entity, strictly respecting v4/v5 blind holdouts
    real_surfaces_by_ent = defaultdict(list)  # nk -> [(surface, is_eval_holdout, cat, sub)]
    leak_surface_to_owner = [
        ("99SPDM", "99 speedmart"),
        ("KLNKMDVR", "klinik mediviron"),
        ("KLNKMDV", "klinik mediviron"),
        ("EZBKXPRSS", "easybook express"),
        ("ZUS COFFEE", "zus coffee"),
    ]
    for r in v5_rows:
        em = str(r.get("expected_merchant", "")).strip()
        nk = normalize_surface(em)
        if len(nk) < 3 or "*" in em:
            continue
        raw = str(r.get("raw_text", ""))
        is_eval = (em, raw) in eval_keys
        m_gw = re.search(r"(?:PAY|DNQR|TNGD|TNG|IPAY88|RAZERMS|MYDEBIT|SHOPEEPAY|SHOPEEP|FPX|CIMBL)\*[A-Za-z0-9 &/'._-]{3,34}", raw.upper())
        if m_gw:
            surf = re.split(
                r"(?:\s*(?:RMYR|MYR|RM)(?=\s*[0-9])|\s*\b(?:RMYR|MYR|RM)\b|[0-9]+\.[0-9]{2}|\s+CHARGE\b|\s+FOR\b|\s+ON\s+[0-9])",
                m_gw.group(0),
            )[0].strip()
            if any(pat in surf and owner not in nk for pat, owner in leak_surface_to_owner):
                continue
            if len(surf) >= 4 and len(normalize_surface(surf)) >= 2:
                real_surfaces_by_ent[nk].append((surf, is_eval, r["category"], r["subcategory"]))
        elif r.get("source_type") == "thermal_receipt":
            lines = [ln.strip() for ln in raw.splitlines() if ln.strip()]
            for cand in lines[:4]:
                if re.match(r"(?i)^(?:date|tarikh|time|masa|ref|receipt|invoice|tax|no\.?)\b", cand):
                    continue
                if "INVOICE" in cand.upper() or "RECEIPT" in cand.upper() or "TOTAL" in cand.upper():
                    continue
                if 3 <= len(cand) <= 50 and len(normalize_surface(cand)) >= 2:
                    real_surfaces_by_ent[nk].append((cand, is_eval, r["category"], r["subcategory"]))
                    break

    for rr in rec_rows:
        em = str(rr.get("expected_merchant", "")).strip()
        nk = normalize_surface(em)
        if len(nk) < 3:
            continue
        is_test = rr.get("split") != "train"
        for ln, lb in zip(rr.get("lines", []), rr.get("labels", [])):
            if lb == "MERCHANT" and 3 <= len(ln.strip()) <= 55 and len(normalize_surface(ln)) >= 2:
                real_surfaces_by_ent[nk].append((ln.strip(), is_test, rr["category"], rr["subcategory"]))

    # 3. Partition non-catalog clean SMEs strictly by entity (exclude branch aliases & noisy abbreviations!)
    cat_nks = [nk for nk in catalog_nk_set if len(nk) >= 4]
    cat_fas = set()
    cat_hsks = set()
    cat_fa_3grams = []
    cat_dist_toks_by_cat = defaultdict(set)
    for nk, (cname, ct, _sb) in zip([normalize_surface(c[0]) for c in catalog_entries], catalog_entries):
        fa_c = "".join(ch for ch in nk if ("a" <= ch <= "z") or ("0" <= ch <= "9"))
        if len(fa_c) >= 5:
            cat_fas.add(fa_c)
        if len(fa_c) >= 3:
            cat_fa_3grams.append({fa_c[i:i + 3] for i in range(len(fa_c) - 2)})
        h_c = "".join(t[0] + VOWEL_RE.sub("", t[1:]) for t in nk.split() if ASCII_ALNUM_RE.match(t))[:20]
        if len(h_c) >= 5:
            cat_hsks.add(h_c)
        for tok in nk.split():
            if len(tok) >= 4 and tok not in GENERIC_ENTITY_WORDS:
                cat_dist_toks_by_cat[ct].add(tok)

    def looks_noisy_or_catalog_alias(nk: str, rep_name: str) -> bool:
        if nk in catalog_nk_set:
            return True
        if any(cnk in nk or nk in cnk for cnk in cat_nks):
            return True
        fa_c = "".join(ch for ch in nk if ("a" <= ch <= "z") or ("0" <= ch <= "9"))
        if len(fa_c) >= 5 and any(cfa in fa_c or fa_c in cfa for cfa in cat_fas):
            return True
        h_c = "".join(t[0] + VOWEL_RE.sub("", t[1:]) for t in nk.split() if ASCII_ALNUM_RE.match(t))[:20]
        if len(h_c) >= 5 and h_c in cat_hsks:
            return True
        if "*" in rep_name or re.search(r"(?i)-(?:trx|kj|pj|kl|jb|bngs|my)\b", rep_name):
            return True
        if re.search(r"[A-Za-z][0-9][A-Za-z]", rep_name):
            return True
        for alpha_tok in re.findall(r"[A-Za-z]+", rep_name):
            if len(alpha_tok) >= 4 and not VOWEL_RE.search(alpha_tok.lower()) and alpha_tok.lower() not in ("lhdn", "kwsp", "hsbc", "ocbc", "mbsb", "myeg", "ptptn", "ktmb"):
                return True
        return False

    def is_pure_noncat(nk: str, rep_name: str, cat: str) -> bool:
        if looks_noisy_or_catalog_alias(nk, rep_name):
            return False
        fa_c = "".join(ch for ch in nk if ("a" <= ch <= "z") or ("0" <= ch <= "9"))
        if len(fa_c) >= 3:
            g3 = {fa_c[i:i + 3] for i in range(len(fa_c) - 2)}
            for cg3 in cat_fa_3grams:
                jac = len(g3 & cg3) / float(max(1, len(g3 | cg3)))
                if jac >= 0.35:
                    return False
        cdist = cat_dist_toks_by_cat.get(cat, set())
        for tok in nk.split():
            if len(tok) >= 4 and tok not in GENERIC_ENTITY_WORDS:
                if tok in cdist or any((tok in ct_tok or ct_tok in tok) for ct_tok in cdist):
                    return False
        return True

    noncat_pool = []
    for nk, cnt in m_counts.items():
        if cnt < 2:
            continue
        (best_cat, best_sub), top_v = m_cat_votes[nk].most_common(1)[0]
        if top_v / float(cnt) < 0.85:
            continue
        rep_name = m_raw_forms[nk].most_common(1)[0][0]
        if rep_name.upper() == rep_name or "a&w" in rep_name.lower():
            continue
        if re.search(r"[a-z][A-Z]", rep_name) or any(len(w) >= 4 and len(VOWEL_RE.findall(w.lower())) / float(len(w)) < 0.25 for w in re.findall(r"[A-Za-z]+", rep_name)):
            continue
        nk_toks = set(nk.split())
        if any(t in nk_toks for t in ("maybank", "cimb", "ambank", "rhb", "tnb", "tng", "grab", "shopee", "lazada", "maxis", "celcom", "digi", "unifi", "petronas", "shell")) or "hong leong" in nk or "public bank" in nk:
            continue
        if len(rep_name) >= 5 and is_pure_noncat(nk, rep_name, best_cat):
            noncat_pool.append((nk, rep_name, best_cat, best_sub))

    # Programmatically synthesize ~550+ pure unseen Malaysian SMEs across 24 distinct templates
    sme_templates = [
        ("Klinik Pergigian", "Health & medical", "Dental & Optical"),
        ("Klinik Keluarga", "Health & medical", "Clinic & GP"),
        ("Poliklinik", "Health & medical", "Clinic & GP"),
        ("Restoran", "Food & drinks", "Restaurant & Cafe"),
        ("Restoran Nasi Kandar", "Food & drinks", "Hawker & Mamak"),
        ("Kedai Kopi", "Food & drinks", "Coffee, Tea & Drinks"),
        ("Kopitiam", "Food & drinks", "Coffee, Tea & Drinks"),
        ("Kafe", "Food & drinks", "Coffee, Tea & Drinks"),
        ("Farmasi", "Health & medical", "Pharmacy & Medicine"),
        ("Bengkel Kereta", "Transport", "Vehicle Maintenance & Service"),
        ("Pusat Servis Tayar", "Transport", "Vehicle Maintenance & Service"),
        ("Pasar Mini", "Groceries", "Supermarket"),
        ("Kedai Runcit", "Groceries", "Convenience Store (99 Speedmart / 7-Eleven / FamilyMart)"),
        ("Tadika", "Education", "School & Childcare Fees"),
        ("Pusat Tuisyen", "Education", "Higher Education & Tuition"),
        ("Kedai Buku", "Education", "Books & Stationery"),
        ("Kedai Hardware", "Home & Living", "Hardware & DIY"),
        ("Dobi Layan Diri", "Personal Care", "Laundry & Dry Cleaning"),
        ("Klinik Haiwan", "Health & medical", "Veterinary & Pet Care"),
        ("Salun Rambut", "Personal Care", "Hair & Beauty Salon"),
        ("Kedai Cermin Mata", "Health & medical", "Dental & Optical"),
        ("Kedai Roti", "Food & drinks", "Bakery & Desserts"),
        ("Kedai Elektrik", "Home & Living", "Home Appliances & Electronics"),
        ("Kedai Bunga", "Shopping", "Gifts & Flowers"),
    ]
    sme_propers = [
        "Harmoni", "Sentosa", "Ceria", "Sejahtera", "Murni", "Bakti",
        "Gemilang", "Bestari", "Lestari", "Wawasan", "Permata", "Sinar",
        "Fajar", "Bayu", "Damai", "Bahagia", "Makmur", "Cendekia",
        "Ilmuwan", "Aman", "Setia", "Indah", "Harapan", "Permai",
        "Perdana", "Cemerlang", "Mutiara", "Saujana",
        "Ah Hock", "Ah Chong", "Ismail", "Faridah", "Pak Mat", "Seri Kembangan",
    ]
    seen_noncat = {x[0] for x in noncat_pool}
    for prefix, ct, sb in sme_templates:
        for prop in sme_propers:
            nm = f"{prefix} {prop}"
            nk = normalize_surface(nm)
            if nk not in seen_noncat and is_pure_noncat(nk, nm, ct):
                seen_noncat.add(nk)
                noncat_pool.append((nk, nm, ct, sb))

    rng.shuffle(noncat_pool)
    n_nc = len(noncat_pool)
    n_tr = int(0.60 * n_nc)
    n_va = int(0.20 * n_nc)
    noncat_train = noncat_pool[:n_tr]
    noncat_val = noncat_pool[n_tr:n_tr + n_va]
    noncat_test = noncat_pool[n_tr + n_va:]

    # Build positive training pairs + clean val/test catalog queries with zero surface overlap
    train_pairs = []
    val_catalog_queries = []
    test_catalog_queries = []
    rng_tr = random.Random(seed + 1)
    rng_ev = random.Random(seed + 2)

    for cat_idx, (cname, cat, sub) in enumerate(catalog_entries):
        nk = normalize_surface(cname)
        all_real = real_surfaces_by_ent.get(nk, [])
        ev_reals_dedup = []
        seen_ev = set()
        for surf, is_ev, _, _ in all_real:
            if is_ev:
                uk = surf.upper()
                if uk not in seen_ev:
                    seen_ev.add(uk)
                    ev_reals_dedup.append(surf)
        tr_reals_dedup = []
        seen_tr = set()
        for surf, is_ev, _, _ in all_real:
            if not is_ev:
                uk = surf.upper()
                if uk not in seen_ev and uk not in seen_tr:
                    seen_tr.add(uk)
                    tr_reals_dedup.append(surf)

        # Pre-select fallback test surface when no ev_reals exist so train/val/test have 0 surface overlap
        chosen_test_surfs = list(ev_reals_dedup[:3])
        if not chosen_test_surfs:
            fb_rng = random.Random(seed + 7000 + cat_idx)
            chosen_test_surfs = [synthesize_terminal_corruptions(cname, fb_rng, n=1)[0]]
        test_upper = {s.upper() for s in chosen_test_surfs}
        for s in chosen_test_surfs:
            test_catalog_queries.append((s, cat_idx, cname))

        # Synthetic corruptions for train & val (strictly excluding test surfaces)
        for surf in synthesize_terminal_corruptions(cname, rng_tr, n=28):
            if surf.upper() not in test_upper:
                train_pairs.append((surf, cname, cat, sub, True, cat_idx))

        # Split non-eval real surfaces strictly by hash into val (20%) vs train (80%)
        val_r = []
        trn_r = []
        for s in tr_reals_dedup:
            if s.upper() in test_upper:
                continue
            if (fnv1a32(s.upper()) % 10) < 2 and len(val_r) < 3:
                val_r.append(s)
            else:
                trn_r.append(s)
        for s in trn_r[:16]:
            train_pairs.append((s, cname, cat, sub, True, cat_idx))
        for s in val_r:
            val_catalog_queries.append((s, cat_idx, cname))
        if cat_idx < 250:
            for surf in synthesize_terminal_corruptions(cname, rng_ev, n=1):
                if surf.upper() not in test_upper:
                    val_catalog_queries.append((surf, cat_idx, cname))

    for nk, sname, cat, sub in noncat_train:
        for surf in synthesize_terminal_corruptions(sname, rng_tr, n=10):
            train_pairs.append((surf, sname, cat, sub, False, -1))
        for surf, is_ev, _, _ in real_surfaces_by_ent.get(nk, [])[:6]:
            if not is_ev:
                train_pairs.append((surf, sname, cat, sub, False, -1))

    by_cat_entities = defaultdict(list)
    all_ent_records = [(c[0], c[1], c[2]) for c in catalog_entries] + [(x[1], x[2], x[3]) for x in noncat_train]
    for nm, cat, _ in all_ent_records:
        by_cat_entities[cat].append(nm)

    def make_pairwise_eval(entity_list, rng_local):
        pos_pairs = []
        neg_pairs = []
        by_c = defaultdict(list)
        for nk, nm, cat, sub in entity_list:
            by_c[cat].append((nk, nm))
            corrs = synthesize_terminal_corruptions(nm, rng_local, n=2)
            pos_pairs.append((nm, corrs[0], 1, "clean_vs_trunc"))
            pos_pairs.append((corrs[0], corrs[1], 1, "trunc_vs_trunc"))
        for nk, nm, cat, sub in entity_list:
            peers = [p_nm for p_nk, p_nm in by_c[cat] if p_nk != nk]
            if not peers:
                continue
            nm_words = set(normalize_surface(nm).split())
            same_tok_peers = [p for p in peers if (nm_words & set(normalize_surface(p).split()))]
            cand = rng_local.choice(same_tok_peers if same_tok_peers else peers)
            neg_pairs.append((nm, cand, 0, "hard_neg_same_cat"))
            neg_pairs.append((synthesize_terminal_corruptions(nm, rng_local, n=1)[0], cand, 0, "hard_neg_trunc"))
        return pos_pairs + neg_pairs

    val_pairs = make_pairwise_eval(noncat_val, rng_ev)
    test_pairs = make_pairwise_eval(noncat_test, random.Random(seed + 99))

    return {
        "catalog_entries": catalog_entries,
        "train_pairs": train_pairs,
        "by_cat_entities": by_cat_entities,
        "val_catalog_queries": val_catalog_queries,
        "test_catalog_queries": test_catalog_queries,
        "noncat_val": [x[1] for x in noncat_val],
        "noncat_test": [x[1] for x in noncat_test],
        "val_pairs": val_pairs,
        "test_pairs": test_pairs,
    }


def train_and_export():
    import numpy as np
    import torch
    import torch.nn as nn
    import torch.nn.functional as F

    update_status("BUILDING_MERCHANT_PAIRS")
    bundle = build_catalog_and_datasets()
    catalog = bundle["catalog_entries"]
    train_pairs = bundle["train_pairs"]
    by_cat_ents = bundle["by_cat_entities"]

    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    print(
        f"Catalog={len(catalog)} | TrainPairs={len(train_pairs)} | ValCatQ={len(bundle['val_catalog_queries'])} | "
        f"TestCatQ={len(bundle['test_catalog_queries'])} | UnseenSME TestPairs={len(bundle['test_pairs'])} on {device}",
        flush=True,
    )

    class MerchantBiEncoder(nn.Module):
        def __init__(self, num_cat: int):
            super().__init__()
            self.E = nn.EmbeddingBag(NUM_BUCKETS, EMB_DIM, mode="sum")
            nn.init.normal_(self.E.weight, mean=0.0, std=0.06)
            self.fc1 = nn.Linear(EMB_DIM, HIDDEN_DIM)
            self.ln1 = nn.LayerNorm(HIDDEN_DIM, eps=1e-5)
            self.fc2 = nn.Linear(HIDDEN_DIM, EMB_DIM)
            self.P_res = nn.Parameter(torch.zeros(num_cat, EMB_DIM))

        def fq(self, w, qat: bool):
            if not qat:
                return w
            with torch.no_grad():
                sc = w.abs().amax(dim=1, keepdim=True).clamp(min=1e-8) / 127.0
                wq = torch.clamp(torch.round(w / sc), -127.0, 127.0) * sc
            return w + (wq - w).detach()

        def encode_csr(self, idx, off, val, nnz, qat: bool = False, feat_drop: float = 0.0):
            Ew = self.fq(self.E.weight, qat)
            e0_full = F.embedding_bag(idx, Ew, off, mode="sum", per_sample_weights=val)
            if feat_drop > 0.0 and self.training:
                keep = (torch.rand(val.shape, device=val.device) >= feat_drop).to(val.dtype)
                e0_drop = F.embedding_bag(idx, Ew, off, mode="sum", per_sample_weights=val * keep)
                has_kept = e0_drop.abs().sum(dim=-1, keepdim=True) > 1e-6
                e0 = torch.where(has_kept, e0_drop, e0_full)
            else:
                e0 = e0_full
            e0 = torch.where(nnz, e0 / e0.norm(dim=-1, keepdim=True).clamp(min=1e-6), torch.zeros_like(e0))
            w1 = self.fq(self.fc1.weight, qat)
            h = F.gelu(self.ln1(F.linear(e0, w1, self.fc1.bias)), approximate="tanh")
            w2 = self.fq(self.fc2.weight, qat)
            e1 = e0 + F.linear(h, w2, self.fc2.bias)
            norm = e1.norm(dim=-1, keepdim=True).clamp(min=1e-6)
            return torch.where(nnz, e1 / norm, torch.zeros_like(e1))

    anchors = [p[0] for p in train_pairs]
    positives = [p[1] for p in train_pairs]
    cat_ids = np.array([p[5] for p in train_pairs], dtype=np.int64)
    rng_hn = random.Random(2026)
    hard_negs = []
    for p in train_pairs:
        cands = [x for x in by_cat_ents[p[2]] if normalize_surface(x) != normalize_surface(p[1])]
        nm_toks = set(normalize_surface(p[1]).split())
        close = [x for x in cands if (nm_toks & set(normalize_surface(x).split()))]
        pick_pool = close if close else (cands if cands else ["XYZ Enterprise"])
        hard_negs.append(rng_hn.choice(pick_pool))

    X_a = surfaces_to_csr(anchors)
    X_p = surfaces_to_csr(positives)
    X_n = surfaces_to_csr(hard_negs)
    X_cat = surfaces_to_csr([c[0] for c in catalog])

    def to_gpu(sub):
        return (
            torch.from_numpy(sub.indices.astype(np.int64, copy=False)).to(device),
            torch.from_numpy(sub.indptr[:-1].astype(np.int64, copy=False)).to(device),
            torch.from_numpy(sub.data.astype(np.float32, copy=False)).to(device),
            torch.from_numpy((np.diff(sub.indptr) > 0)[:, None]).to(device),
        )

    X_cat_gpu = to_gpu(X_cat)
    cat_ids_t = torch.from_numpy(cat_ids).to(device)
    num_cat = len(catalog)

    net = MerchantBiEncoder(num_cat).to(device)
    epochs = 80
    batch_size = 512
    opt = torch.optim.AdamW(net.parameters(), lr=3e-3, weight_decay=3e-4)
    N = len(train_pairs)
    sched = torch.optim.lr_scheduler.CosineAnnealingLR(opt, T_max=max(1, epochs * math.ceil(N / batch_size)))

    rng_np = np.random.default_rng(42)
    tau = 0.07
    t0 = time.perf_counter()
    update_status("TRAINING_BIENCODER", epochs=epochs, pairs=N)

    for ep in range(1, epochs + 1):
        qat = ep > (epochs - 14)
        fdrop = 0.0 if qat else 0.12
        net.train()
        perm = rng_np.permutation(N)
        total_loss = 0.0
        steps = 0
        for st in range(0, N, batch_size):
            bi = perm[st:st + batch_size]
            bc = cat_ids_t[bi]
            ua = net.encode_csr(*to_gpu(X_a[bi]), qat=qat, feat_drop=fdrop)
            vp = net.encode_csr(*to_gpu(X_p[bi]), qat=qat, feat_drop=0.0)
            hn = net.encode_csr(*to_gpu(X_n[bi]), qat=qat, feat_drop=0.0)

            B = ua.shape[0]
            labels = torch.arange(B, device=device)
            # Symmetric InfoNCE + explicit hard negative column
            sim_in = (ua @ vp.T) / tau
            sim_hn = (ua * hn).sum(dim=-1, keepdim=True) / tau
            loss_u = F.cross_entropy(torch.cat([sim_in, sim_hn], dim=1), labels)
            loss_v = F.cross_entropy(torch.cat([sim_in.T, (vp * hn).sum(dim=-1, keepdim=True) / tau], dim=1), labels)

            pos_cos = (ua * vp).sum(dim=-1)
            neg_cos = (ua * hn).sum(dim=-1)
            triplet = F.relu(0.30 + neg_cos - pos_cos).mean()
            anti_merge = F.relu(neg_cos - 0.35).pow(2).mean()
            loss = 0.5 * (loss_u + loss_v) + 0.6 * triplet + 0.4 * anti_merge

            # Supervised 800-way CosFace (s=18.0, m=0.15, ls=0.02) with bounded residual prototype + OOD suppression
            C_clean = net.encode_csr(*X_cat_gpu, qat=qat, feat_drop=0.0)
            C = F.normalize(C_clean + 0.18 * torch.tanh(net.P_res), dim=-1)
            cm = bc >= 0
            if cm.any():
                u_cat = ua[cm]
                v_cat = vp[cm]
                y_cat = bc[cm]
                one_hot = F.one_hot(y_cat, num_classes=num_cat).to(u_cat.dtype)
                logits_cat = (u_cat @ C.T) * 18.0 - 2.7 * one_hot
                loss = (
                    loss
                    + F.cross_entropy(logits_cat, y_cat, label_smoothing=0.02)
                    + 0.3 * F.cross_entropy((v_cat @ C.T) * 18.0, y_cat, label_smoothing=0.02)
                    + 0.03 * net.P_res.pow(2).mean()
                )
            nm = bc < 0
            if nm.any():
                max_cat_sim = (ua[nm] @ C.T).max(dim=1).values
                loss = loss + 0.8 * F.relu(max_cat_sim - 0.40).pow(2).mean()

            opt.zero_grad(set_to_none=True)
            loss.backward()
            torch.nn.utils.clip_grad_norm_(net.parameters(), 2.0)
            opt.step()
            sched.step()
            total_loss += float(loss.item())
            steps += 1
        if ep in (1, 15, 30, 45, 66, 80):
            print(f"[Canon Ep {ep}/{epochs} QAT={qat}] loss={total_loss/max(1,steps):.4f}", flush=True)

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
    p_res_np = 0.18 * np.tanh(sd["P_res"].detach().cpu().numpy())

    # Initial export with temporary thresholds to compute INT8 catalog (+ learned residual) & calibration embeddings
    init_cat_embs = np.zeros((len(catalog), EMB_DIM), dtype=np.float32)
    gz0 = serialize_canon_bin_gz(weights, catalog, init_cat_embs, 0.68, 0.03, 0.60)
    md0 = load_canon_bin_gz(gz0)
    cat_embs_clean = encode_int8_batch(md0, [c[0] for c in catalog])
    cat_embs_raw = cat_embs_clean + p_res_np
    cat_embs = (cat_embs_raw / np.maximum(np.linalg.norm(cat_embs_raw, axis=1, keepdims=True), 1e-6)).astype(np.float32)
    gz_calib = serialize_canon_bin_gz(weights, catalog, cat_embs, 0.68, 0.03, 0.60)
    md = load_canon_bin_gz(gz_calib)

    # Calibrate brandMatchThreshold & brandMatchMargin on Val Catalog vs Clean Unseen SMEs (with Java conflict guard!)
    val_q_texts = [x[0] for x in bundle["val_catalog_queries"]]
    val_q_truth = [x[1] for x in bundle["val_catalog_queries"]]
    val_q_embs = encode_int8_batch(md, val_q_texts)
    val_sims = val_q_embs @ md["cat_embs"].T
    val_top1 = val_sims.argmax(axis=1)
    part_v = np.partition(val_sims, -2, axis=1)
    val_s1 = val_sims.max(axis=1)
    val_mg = val_s1 - part_v[:, -2]
    val_conflicts = np.array([
        has_distinct_entity_conflict(val_q_texts[i], catalog[int(val_top1[i])][0])
        for i in range(len(val_q_texts))
    ], dtype=bool)

    sme_val_texts = bundle["noncat_val"]
    sme_val_embs = encode_int8_batch(md, sme_val_texts)
    sme_val_sims = sme_val_embs @ md["cat_embs"].T
    sme_top1 = sme_val_sims.argmax(axis=1)
    sme_s1 = sme_val_sims.max(axis=1)
    sme_mg = sme_s1 - np.partition(sme_val_sims, -2, axis=1)[:, -2]
    sme_val_conflicts = np.array([
        has_distinct_entity_conflict(sme_val_texts[i], catalog[int(sme_top1[i])][0])
        for i in range(len(sme_val_texts))
    ], dtype=bool)

    best_b_f1, best_b_th, best_b_mg = -1.0, 0.66, 0.03
    for max_fpr in (0.02, 0.03, 0.05):
        for bth in np.arange(0.48, 0.84, 0.02):
            for bmg in (0.02, 0.03, 0.04, 0.06):
                ok_cat = sum(
                    1 for i in range(len(val_q_texts))
                    if (not val_conflicts[i]) and val_s1[i] >= bth and val_mg[i] >= bmg and int(val_top1[i]) == val_q_truth[i]
                )
                fpr_sme = sum(
                    1 for i in range(len(sme_val_texts))
                    if (not sme_val_conflicts[i]) and sme_s1[i] >= bth and sme_mg[i] >= bmg
                ) / max(1, len(sme_val_texts))
                if fpr_sme > max_fpr:
                    continue
                rec = ok_cat / max(1, len(val_q_texts))
                if rec > best_b_f1:
                    best_b_f1 = rec
                    best_b_th = float(round(bth, 3))
                    best_b_mg = float(round(bmg, 3))
        if best_b_f1 >= 0.0:
            break

    # Calibrate pairSameThreshold on Val Unseen SME pairs targeting Hard-Neg FPR <= 2% (mirroring Java conflict guard)
    vp = bundle["val_pairs"]
    va_e = encode_int8_batch(md, [p[0] for p in vp])
    vb_e = encode_int8_batch(md, [p[1] for p in vp])
    v_cos = (va_e * vb_e).sum(axis=1)
    v_lbl = np.array([p[2] for p in vp], dtype=np.int64)
    v_conf = np.array([has_distinct_entity_conflict(p[0], p[1]) for p in vp], dtype=bool)

    best_p_f1, best_p_th = -1.0, 0.60
    for pth in np.arange(0.46, 0.86, 0.02):
        pred = (~v_conf) & (v_cos >= pth)
        tp = int(np.sum(pred & (v_lbl == 1)))
        fp = int(np.sum(pred & (v_lbl == 0)))
        fn = int(np.sum((~pred) & (v_lbl == 1)))
        tn = int(np.sum((~pred) & (v_lbl == 0)))
        fpr = fp / max(1, fp + tn)
        f1 = (2.0 * tp) / max(1, 2 * tp + fp + fn)
        if fpr <= 0.02 and f1 > best_p_f1:
            best_p_f1 = f1
            best_p_th = float(round(pth, 3))

    final_gz = serialize_canon_bin_gz(weights, catalog, cat_embs, best_b_th, best_b_mg, best_p_th)
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    MODEL_BIN_GZ_PATH.write_bytes(final_gz)
    assert len(final_gz) < MAX_BIN_GZ_BYTES, f"Asset {len(final_gz)} >= {MAX_BIN_GZ_BYTES}"

    # Blind Test Evaluation on exact reloaded INT8 binary (with Java conflict guard!)
    md_final = load_canon_bin_gz(final_gz)
    tq = bundle["test_catalog_queries"]
    tq_embs = encode_int8_batch(md_final, [x[0] for x in tq])
    tq_sims = tq_embs @ md_final["cat_embs"].T
    tq_top1 = tq_sims.argmax(axis=1)
    tq_s1 = tq_sims.max(axis=1)
    tq_mg = tq_s1 - np.partition(tq_sims, -2, axis=1)[:, -2]
    tq_conflicts = np.array([
        has_distinct_entity_conflict(tq[i][0], md_final["names"][int(tq_top1[i])])
        for i in range(len(tq))
    ], dtype=bool)
    top1_raw_acc = sum(1 for i in range(len(tq)) if int(tq_top1[i]) == tq[i][1]) / max(1, len(tq))
    matched_mask = (~tq_conflicts) & (tq_s1 >= best_b_th) & (tq_mg >= best_b_mg)
    cat_recall_at_th = sum(1 for i in range(len(tq)) if matched_mask[i] and int(tq_top1[i]) == tq[i][1]) / max(1, len(tq))
    cat_prec_at_th = sum(1 for i in range(len(tq)) if matched_mask[i] and int(tq_top1[i]) == tq[i][1]) / max(1, int(np.sum(matched_mask)))

    sme_te = encode_int8_batch(md_final, bundle["noncat_test"])
    sme_tsims = sme_te @ md_final["cat_embs"].T
    sme_ttop1 = sme_tsims.argmax(axis=1)
    sme_ts1 = sme_tsims.max(axis=1)
    sme_tmg = sme_ts1 - np.partition(sme_tsims, -2, axis=1)[:, -2]
    sme_tconf = np.array([
        has_distinct_entity_conflict(bundle["noncat_test"][i], md_final["names"][int(sme_ttop1[i])])
        for i in range(len(bundle["noncat_test"]))
    ], dtype=bool)
    sme_false_canon = sum(
        1 for i in range(len(bundle["noncat_test"]))
        if (not sme_tconf[i]) and sme_ts1[i] >= best_b_th and sme_tmg[i] >= best_b_mg
    ) / max(1, len(bundle["noncat_test"]))

    # Pairwise Blind Unseen-SME Evaluation + ROC-AUC (with Java conflict guard!)
    tp_rows = bundle["test_pairs"]
    ta_e = encode_int8_batch(md_final, [p[0] for p in tp_rows])
    tb_e = encode_int8_batch(md_final, [p[1] for p in tp_rows])
    t_cos = (ta_e * tb_e).sum(axis=1)
    t_lbl = np.array([p[2] for p in tp_rows], dtype=np.int64)
    tp_conf = np.array([has_distinct_entity_conflict(p[0], p[1]) for p in tp_rows], dtype=bool)
    t_eff_score = np.where(tp_conf, np.minimum(t_cos, -1.0), t_cos)
    from sklearn.metrics import roc_auc_score
    auc = float(roc_auc_score(t_lbl, t_eff_score))
    tp_pred = (~tp_conf) & (t_cos >= best_p_th)
    tp_c = int(np.sum(tp_pred & (t_lbl == 1)))
    fp_c = int(np.sum(tp_pred & (t_lbl == 0)))
    fn_c = int(np.sum((~tp_pred) & (t_lbl == 1)))
    tn_c = int(np.sum((~tp_pred) & (t_lbl == 0)))
    tpr = tp_c / max(1, tp_c + fn_c)
    fpr = fp_c / max(1, fp_c + tn_c)
    f1_p = (2.0 * tp_c) / max(1, 2 * tp_c + fp_c + fn_c)

    TEST_PAIRS_PATH.write_text(json.dumps([
        {"a": p[0], "b": p[1], "label": p[2], "kind": p[3], "cos": round(float(t_cos[i]), 4)}
        for i, p in enumerate(tp_rows)
    ], indent=2, ensure_ascii=False), encoding="utf-8")

    report = {
        "train_seconds": round(time.perf_counter() - t0, 1),
        "gz_bytes": len(final_gz),
        "num_catalog": len(catalog),
        "num_train_pairs": len(train_pairs),
        "calibrated_thresholds": {
            "brandMatchThreshold": best_b_th,
            "brandMatchMargin": best_b_mg,
            "pairSameThreshold": best_p_th,
        },
        "catalog_blind_eval": {
            "n_queries": len(tq),
            "top1_unthresholded_acc": round(top1_raw_acc, 4),
            "recall_at_threshold": round(cat_recall_at_th, 4),
            "precision_at_threshold": round(cat_prec_at_th, 4),
            "unseen_sme_false_canon_rate": round(sme_false_canon, 4),
            "n_unseen_sme": len(bundle["noncat_test"]),
        },
        "zero_shot_pairwise_eval": {
            "n_pairs": len(tp_rows),
            "roc_auc": round(auc, 4),
            "f1": round(f1_p, 4),
            "tpr": round(tpr, 4),
            "hard_negative_fpr": round(fpr, 4),
        },
    }
    REPORT_PATH.write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")
    print("MERCHANT CANON REPORT:", json.dumps(report), flush=True)
    update_status("DONE", gz_bytes=len(final_gz), auc=round(auc, 4), recall=round(cat_recall_at_th, 4))


def run_self_test():
    import numpy as np

    assert fnv1a32("") == 0x811C9DC5
    assert normalize_surface("PAY*CRNGPHARM-TRX SDN BH") == "crngpharm"
    assert normalize_surface("DNQR*99SPDM-KJ (519537-X)") == "99spdm kj"
    assert normalize_surface("D0MIN0'SPIZZA RM25.00") == "domin0 spizza"
    assert normalize_surface("A&W Malaysia") == "a w"
    assert normalize_surface("MYDEBIT*7-ELEVENKLCC RM32.90") == "7 eleven"
    assert normalize_surface("DNQR*ALOOKEYWRTRX MYR70.00") == "alookeywr"
    assert normalize_surface("PAY*ACUTABOVESALONMYR115.50") == "acutabovesalon"
    assert normalize_surface("SHOPEEPAY*AEONCRDSDNBH") == "aeoncrd"
    assert normalize_surface("AE0NBIGSDNBHD202501020423") == "aeonbig"
    assert normalize_surface("AIA EDUSMARTSDNBHD（945615-D)") == "aia edusmart"
    assert normalize_surface("PAY*ANKKU-SDN-BH") == "ankku"
    assert normalize_surface("7-Eleven 7-CAFé") == "7 eleven 7 cafe"
    assert normalize_surface("7-11 Malaysia") == "7 11"
    assert normalize_surface("PAY*AIAAPLH-MYS 05-JUL-26") == "aiaaplh"
    assert normalize_surface("FPX*BERYLSCS TRX 02-03-2026") == "berylscs"
    assert normalize_surface("PAY*BEACWKSF-TRX 13/06") == "beacwksf"

    f1 = extract_surface_features("PAY*CRNGPHARM-TRX")
    assert "sk:crngphrm" in f1 and "fsk:crngphrm" in f1 and "hsk:crngphrm" in f1 and "fa:crngpharm" in f1
    f_ws = extract_surface_features("Watsons Pharmacy")
    assert "hs:ws" in f_ws and "hb:wt" in f_ws
    f_aw = extract_surface_features("A&W Malaysia")
    assert "w:a" in f_aw and "w:w" in f_aw and "fa:aw" in f_aw
    f_admc_cat = extract_surface_features("Ara Damansara Medical Centre")
    f_admc_q = extract_surface_features("MYDEBIT*ADMC SDN BH")
    assert "abbr:admc" in f_admc_cat and "abbr:admc" in f_admc_q
    assert has_distinct_entity_conflict("Klinik Pergigian Harmoni", "Klinik Pergigian Sentosa")
    f2 = extract_surface_features("亚坤咖椰吐司")
    assert "z:亚" in f2 and "z2:亚坤" in f2

    rng = np.random.default_rng(11)
    cat = [("99 Speedmart", "Groceries", "Convenience Store (99 Speedmart / 7-Eleven / FamilyMart)"), ("CARiNG Pharmacy", "Health & medical", "Pharmacy & Medicine")]
    w = {
        "E": rng.normal(0, 0.1, size=(NUM_BUCKETS, EMB_DIM)).astype(np.float32),
        "W1": rng.normal(0, 0.1, size=(HIDDEN_DIM, EMB_DIM)).astype(np.float32),
        "b1": np.zeros(HIDDEN_DIM, dtype=np.float32),
        "ln1_w": np.ones(HIDDEN_DIM, dtype=np.float32),
        "ln1_b": np.zeros(HIDDEN_DIM, dtype=np.float32),
        "W2": rng.normal(0, 0.1, size=(EMB_DIM, HIDDEN_DIM)).astype(np.float32),
        "b2": np.zeros(EMB_DIM, dtype=np.float32),
    }
    ce = rng.normal(0, 0.1, size=(len(cat), EMB_DIM)).astype(np.float32)
    gz = serialize_canon_bin_gz(w, cat, ce, 0.74, 0.04, 0.68)
    md = load_canon_bin_gz(gz)
    embs = encode_int8_batch(md, ["PAY*CRNGPHARM-TRX", "DNQR*99SPDM-KJ", ""])
    assert embs.shape == (3, EMB_DIM) and abs(float(np.linalg.norm(embs[0])) - 1.0) < 1e-4 and np.all(embs[2] == 0.0)
    print(f"MERCHANT CANON SELF-TEST PASS: dummy_gz_bytes={len(gz)}, features/INT8 roundtrip verified.")


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
