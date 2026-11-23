#!/usr/bin/env python3
"""MoneyLens Foundry v5 — Multi-Teacher Ensemble Distillation + Pure-Java INT8 SubwordResNet Reranker."""
import argparse
import gzip
import hashlib
import json
import math
import os
import random
import re
import struct
import subprocess
import sys
import time
from collections import Counter, defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import run_foundry_v4 as f4

OUT_DIR = Path("/content/outputs_v5")
DATASET_V4_GZ_PATH = Path("/content/dataset_malaysian_v4.jsonl.gz")
HOLDOUT_V3_PATH = Path("/content/holdout_v3.json")
HOLDOUT_V4_PATH = Path("/content/holdout_v4.json")

STATUS_PATH = OUT_DIR / "foundry_v5_status.json"
CHECKPOINT_PATH = OUT_DIR / "gen_v5_checkpoint.jsonl"
PROGRESS_PATH = OUT_DIR / "gen_v5_progress.json"
DATASET_PATH = OUT_DIR / "dataset_malaysian_v5.jsonl"
DATASET_GZ_PATH = OUT_DIR / "dataset_malaysian_v5.jsonl.gz"
TEACHER_CACHE_PATH = OUT_DIR / "teacher_logits_v5.npz"
REPORT_PATH = OUT_DIR / "foundry_v5_report.json"
V5_HARD_TEST_PATH = OUT_DIR / "v5_hard_test.json"
OOD_MISC_TEST_PATH = OUT_DIR / "ood_misc_test.json"

STAGE1_HOLDOUT_JSON = OUT_DIR / "moneylens_classifier_holdout.json"
STAGE1_HOLDOUT_GZ = OUT_DIR / "moneylens_classifier_holdout.json.gz"
STAGE1_FULL_JSON = OUT_DIR / "moneylens_classifier.json"
STAGE1_FULL_GZ = OUT_DIR / "moneylens_classifier.json.gz"

RERANKER_HOLDOUT_GZ = OUT_DIR / "moneylens_reranker_holdout.bin.gz"
RERANKER_FULL_GZ = OUT_DIR / "moneylens_reranker.bin.gz"

MAX_STAGE1_GZ_BYTES = 1_880_000
MAX_RERANKER_GZ_BYTES = 1_800_000

MAGIC = b"MLRR"
RERANKER_VERSION = 1
NUM_BUCKETS = 12288
EMB_DIM = 96
STRUCT_DIM = 16
HIDDEN_DIM = 256
IN_DIM = EMB_DIM * 3 + STRUCT_DIM + 75  # 379

CANONICAL_74 = list(f4.ALL_PAIRS)
MISC_PAIR = "Miscellaneous||Auto-Captured (Uncategorized)"
CLASSES_75 = sorted([f"{r.split(' || ')[0]}||{r.split(' || ')[1]}" for r in CANONICAL_74] + [MISC_PAIR])
assert len(CLASSES_75) == 75
CLS2ID_75 = {c: i for i, c in enumerate(CLASSES_75)}

CATEGORIES_16 = sorted({c.split("||")[0] for c in CLASSES_75})
CAT2ID_16 = {c: i for i, c in enumerate(CATEGORIES_16)}
CLS_TO_CAT_ID = [CAT2ID_16[c.split("||")[0]] for c in CLASSES_75]

VOWEL_RE = re.compile(r"[aeiou]")
ASCII_ALNUM_RE = re.compile(r"^[a-z0-9]{4,}$")
FORBIDDEN_SCRIPT_RE = re.compile(r"[\u0600-\u06FF\u0400-\u04FF\u0900-\u097F\u0E00-\u0E7F\uAC00-\uD7AF\u1100-\u11FF]")

GATEWAY_LITS = (
    "PAY*", "DNQR*", "IPAY88*", "TNGD*", "FPX*", "SP*",
    "RAZERMS*", "MYDEBIT*", "SHOPEEPAY*", "GRAB*", "POS*", "MAE*",
)
FX_CUE_STRUCT_RE = re.compile(r"(?<![A-Z0-9])(?:USD|SGD|THB|JPY|EUR|GBP|AUD|IDR|KRW|HKD|CNY|FX|RATE)(?![A-Z0-9])")
LHDN_STRUCT_RE = re.compile(r"(?<![A-Z0-9])(?:TIN|LHDN|MYINVOIS|SSM|E-INVOICE|SST|CUKAI|ZAKAT|PCB)(?![A-Z0-9])")
RECEIPT_STRUCT_RE = re.compile(r"(?<![A-Z0-9])(?:SUBTOTAL|GRAND\s+TOTAL|ROUNDING|CHANGE|BAKI)(?![A-Z0-9])")
BNPL_STRUCT_RE = re.compile(r"(?<![A-Z0-9])(?:SPAYLATER|GRABPAYLATER|ATOME|PTPTN|INSTALLMENT|ANSURAN|LOAN|PINJAMAN)(?![A-Z0-9])")
P2P_STRUCT_RE = re.compile(r"(?<![A-Z0-9])(?:SPLIT|KONGSI|SHARE|BAYAR\s+BALIK|TRANSFER\s+TO|DUITNOW\s+TRANSFER|P2P)(?![A-Z0-9])")
MALAY_STRUCT_RE = re.compile(r"(?<![A-Z0-9])(?:BAYARAN|PEMBAYARAN|TRANSAKSI|JUMLAH|KEDAI|KLINIK|RESTORAN|TARIKH|KEPADA|BAKI)(?![A-Z0-9])")


def update_status(stage: str, **extra):
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    payload = {"stage": stage, "ts": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()), **extra}
    STATUS_PATH.write_text(json.dumps(payload, indent=2), encoding="utf-8")
    print(f"[STATUS] {stage} {extra}", flush=True)


def fnv1a32(s: str) -> int:
    """Exact bit-for-bit parity with Java MlNeuralReranker.fnv1a32()."""
    h = 0x811C9DC5
    for b in s.encode("utf-8"):
        h ^= b
        h = (h * 0x01000193) & 0xFFFFFFFF
    return h


def feature_bucket_and_sign(key: str, num_buckets: int = NUM_BUCKETS):
    h = fnv1a32(key)
    bucket = (h & 0x7FFFFFFF) % num_buckets
    sign = 1.0 if ((h >> 31) & 1) == 0 else -1.0
    return bucket, sign


def consonant_skeletons(w: str, prefix: str, include_3: bool = True):
    """Emits sk, (optional) sk3, and sk4 keys for ASCII alnum tokens >= 4 chars."""
    if not ASCII_ALNUM_RE.match(w):
        return []
    skel = VOWEL_RE.sub("", w)[:16]
    if len(skel) < 3:
        return []
    out = [f"{prefix}sk:{skel}"]
    if include_3:
        for i in range(len(skel) - 2):
            out.append(f"{prefix}sk3:{skel[i:i + 3]}")
    if len(skel) >= 4:
        for i in range(len(skel) - 3):
            out.append(f"{prefix}sk4:{skel[i:i + 4]}")
    return out


def extract_reranker_bags(merchant: str, text: str):
    """Exact bit-for-bit parity with Java MlNeuralReranker feature extraction."""
    merch_s = "" if merchant is None else str(merchant)
    text_s = "" if text is None else str(text)

    # Merchant bag
    m_counts = Counter()
    m_words = f4.java_parity_words(merch_s)
    for i, w in enumerate(m_words):
        m_counts["mw:" + w] += 1
        if i + 1 < len(m_words):
            m_counts["mb:" + w + " " + m_words[i + 1]] += 1
        padded = " " + w + " "
        L = len(padded)
        for n in (3, 4, 5):
            if L >= n:
                for st in range(L - n + 1):
                    m_counts["mc:" + padded[st:st + n]] += 1
        for sk in consonant_skeletons(w, "m", include_3=True):
            m_counts[sk] += 1

    m_cjk = [ch for ch in merch_s if "\u4e00" <= ch <= "\u9fff"]
    for i, ch in enumerate(m_cjk):
        m_counts["mz:" + ch] += 1
        if i + 1 < len(m_cjk):
            m_counts["mz2:" + ch + m_cjk[i + 1]] += 1
    for k in m_counts:
        if m_counts[k] > 3:
            m_counts[k] = 3

    # Text bag
    t_counts = Counter()
    t_words = f4.java_parity_words(text_s)[:120]
    for i, w in enumerate(t_words):
        t_counts["tw:" + w] += 1
        if i + 1 < len(t_words):
            t_counts["tb:" + w + " " + t_words[i + 1]] += 1
        if i < 60:
            padded = " " + w + " "
            L = len(padded)
            for n in (3, 4, 5):
                if L >= n:
                    for st in range(L - n + 1):
                        t_counts["tc:" + padded[st:st + n]] += 1
        if i < 40:
            for sk in consonant_skeletons(w, "t", include_3=False):
                t_counts[sk] += 1

    t_cjk = [ch for ch in text_s if "\u4e00" <= ch <= "\u9fff"][:80]
    for i, ch in enumerate(t_cjk):
        t_counts["tz:" + ch] += 1
        if i + 1 < len(t_cjk):
            t_counts["tz2:" + ch + t_cjk[i + 1]] += 1
    for k in t_counts:
        if t_counts[k] > 3:
            t_counts[k] = 3

    return m_counts, t_counts


def extract_struct_features(merchant: str, text: str, s1_present: float = 0.0, s1_prob: float = 0.0, s1_margin: float = 0.0, s1_signals: int = 0):
    """Exact bit-for-bit parity with Java MlNeuralReranker.extractStructFeatures()."""
    import numpy as np

    merch_s = "" if merchant is None else str(merchant)
    text_s = "" if text is None else str(text)
    comb = merch_s + "\n" + text_s
    comb_up = comb.upper()

    st = np.zeros(STRUCT_DIM, dtype=np.float32)
    st[0] = 1.0 if any(g in comb_up for g in GATEWAY_LITS) else 0.0
    st[1] = 1.0 if FX_CUE_STRUCT_RE.search(comb_up) else 0.0
    st[2] = 1.0 if LHDN_STRUCT_RE.search(comb_up) else 0.0
    nl_count = text_s.count("\n")
    st[3] = 1.0 if (nl_count >= 3 or RECEIPT_STRUCT_RE.search(comb_up)) else 0.0
    st[4] = 1.0 if BNPL_STRUCT_RE.search(comb_up) else 0.0
    st[5] = 1.0 if P2P_STRUCT_RE.search(comb_up) else 0.0
    st[6] = 1.0 if any("\u4e00" <= ch <= "\u9fff" for ch in comb) else 0.0
    st[7] = 1.0 if MALAY_STRUCT_RE.search(comb_up) else 0.0
    st[8] = min(1.0, len(merch_s) / 40.0)
    st[9] = min(1.0, len(text_s) / 300.0)

    ascii_letters = 0
    consonants = 0
    digits = 0
    for ch in merch_s:
        if ("a" <= ch <= "z") or ("A" <= ch <= "Z"):
            ascii_letters += 1
            if ch not in "aeiouAEIOU":
                consonants += 1
        elif "0" <= ch <= "9":
            digits += 1
    st[10] = (consonants / float(ascii_letters)) if ascii_letters > 0 else 0.0
    st[11] = (digits / float(len(merch_s))) if len(merch_s) > 0 else 0.0
    st[12] = float(s1_present)
    st[13] = float(s1_prob)
    st[14] = float(s1_margin)
    st[15] = min(1.0, s1_signals / 25.0) if s1_present > 0.0 else 0.0
    return st


def bags_to_csr_and_struct(rows_m_t_s1, num_buckets=NUM_BUCKETS):
    """Builds sparse hashed matrices (N, num_buckets) for merch/text plus dense struct & s1_probs."""
    import numpy as np
    from scipy.sparse import csr_matrix

    N = len(rows_m_t_s1)
    m_indptr, m_indices, m_data = [0], [], []
    t_indptr, t_indices, t_data = [0], [], []
    st_arr = np.zeros((N, STRUCT_DIM), dtype=np.float32)
    s1_probs_arr = np.zeros((N, 75), dtype=np.float32)

    for i, item in enumerate(rows_m_t_s1):
        merch, text = item[0], item[1]
        s1_info = item[2] if len(item) > 2 else None
        mc, tc = extract_reranker_bags(merch, text)

        mb_map = {}
        for k, cnt in mc.items():
            b, sgn = feature_bucket_and_sign(k, num_buckets)
            mb_map[b] = mb_map.get(b, 0.0) + sgn * cnt
        if mb_map:
            for b, v in sorted(mb_map.items()):
                if v != 0.0:
                    m_indices.append(b)
                    m_data.append(v)
        m_indptr.append(len(m_indices))

        tb_map = {}
        for k, cnt in tc.items():
            b, sgn = feature_bucket_and_sign(k, num_buckets)
            tb_map[b] = tb_map.get(b, 0.0) + sgn * cnt
        if tb_map:
            for b, v in sorted(tb_map.items()):
                if v != 0.0:
                    t_indices.append(b)
                    t_data.append(v)
        t_indptr.append(len(t_indices))

        if s1_info is not None:
            st_arr[i] = extract_struct_features(
                merch, text, 1.0, s1_info["prob"], s1_info["margin"], s1_info["signals"]
            )
            s1_probs_arr[i] = s1_info["probs"]
        else:
            st_arr[i] = extract_struct_features(merch, text)

    Xm = csr_matrix((np.array(m_data, dtype=np.float32), np.array(m_indices, dtype=np.int32), np.array(m_indptr, dtype=np.int32)), shape=(N, num_buckets), dtype=np.float32)
    Xt = csr_matrix((np.array(t_data, dtype=np.float32), np.array(t_indices, dtype=np.int32), np.array(t_indptr, dtype=np.int32)), shape=(N, num_buckets), dtype=np.float32)
    return Xm, Xt, st_arr, s1_probs_arr


def quantize_int8_per_row(mat):
    """Symmetric per-row INT8 quantization matching Java MlNeuralReranker."""
    import numpy as np

    w = np.asarray(mat, dtype=np.float32)
    rows, cols = w.shape
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
    return (
        struct.pack(">2i", rows, cols)
        + np.asarray(scales, dtype=">f4").tobytes()
        + np.asarray(q, dtype=np.int8).tobytes()
    )


def _pack_fvec(arr) -> bytes:
    import numpy as np
    return np.asarray(arr, dtype=">f4").tobytes()


def serialize_reranker_bin_gz(weights_dict, classes_75, temperature: float, ood_misc_threshold: float) -> bytes:
    """Packs exact MLRR v1 big-endian binary and returns gzip level-9 compressed bytes."""
    buf = bytearray()
    buf.extend(struct.pack(
        ">4s6i2f",
        MAGIC,
        RERANKER_VERSION,
        NUM_BUCKETS,
        EMB_DIM,
        STRUCT_DIM,
        HIDDEN_DIM,
        len(classes_75),
        float(temperature),
        float(ood_misc_threshold),
    ))
    for c in classes_75:
        buf.extend(_pack_utf8(c))

    for name in ("E", "W1", "W2", "W_out"):
        scales, q = quantize_int8_per_row(weights_dict[name])
        buf.extend(_pack_qmat(scales, q))

    for name in ("b1", "ln1_w", "ln1_b", "b2", "ln2_w", "ln2_b", "b_out"):
        buf.extend(_pack_fvec(weights_dict[name]))

    return gzip.compress(bytes(buf), compresslevel=9)


def load_reranker_bin_gz(gz_bytes: bytes):
    """Reads MLRR v1 .bin.gz back for exact Python INT8 verification and self-test."""
    import numpy as np

    raw = gzip.decompress(gz_bytes)
    pos = 0
    hdr_sz = struct.calcsize(">4s6i2f")
    magic, ver, nb, ed, sd, hd, nc, temp, ood_th = struct.unpack_from(">4s6i2f", raw, pos)
    pos += hdr_sz
    assert magic == MAGIC and ver == RERANKER_VERSION
    classes = []
    for _ in range(nc):
        ulen = struct.unpack_from(">H", raw, pos)[0]
        pos += 2
        classes.append(raw[pos:pos + ulen].decode("utf-8"))
        pos += ulen

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

    E = read_qmat()
    W1 = read_qmat()
    W2 = read_qmat()
    W_out = read_qmat()
    b1 = read_fvec(hd)
    ln1_w = read_fvec(hd)
    ln1_b = read_fvec(hd)
    b2 = read_fvec(hd)
    ln2_w = read_fvec(hd)
    ln2_b = read_fvec(hd)
    b_out = read_fvec(nc)
    assert pos == len(raw), f"Trailing bytes in MLRR binary: {pos} != {len(raw)}"
    return {
        "num_buckets": nb,
        "emb_dim": ed,
        "struct_dim": sd,
        "hidden_dim": hd,
        "num_classes": nc,
        "temperature": float(temp),
        "ood_misc_threshold": float(ood_th),
        "classes": classes,
        "E": E,
        "W1": W1,
        "b1": b1,
        "ln1_w": ln1_w,
        "ln1_b": ln1_b,
        "W2": W2,
        "b2": b2,
        "ln2_w": ln2_w,
        "ln2_b": ln2_b,
        "W_out": W_out,
        "b_out": b_out,
    }


def _layer_norm_np(x, w, b):
    import numpy as np
    mean = x.mean(axis=-1, keepdims=True)
    var = ((x - mean) ** 2).mean(axis=-1, keepdims=True)
    return ((x - mean) / np.sqrt(var + np.float32(1e-5))) * w + b


def _gelu_tanh_np(x):
    import numpy as np
    c = np.float32(0.7978845608)
    k = np.float32(0.044715)
    return np.float32(0.5) * x * (np.float32(1.0) + np.tanh(c * (x + k * x * x * x)))


def reranker_int8_predict_batch(model_dict, rows_m_t_s1):
    """Exact INT8-dequantized forward pass matching Java MlNeuralReranker bit-for-bit."""
    import numpy as np

    Xm, Xt, st_arr, s1_probs_arr = bags_to_csr_and_struct(rows_m_t_s1, num_buckets=model_dict["num_buckets"])
    E = model_dict["E"]

    em = Xm @ E
    m_nnz = np.diff(Xm.indptr) > 0
    m_norms = np.maximum(np.linalg.norm(em, axis=1, keepdims=True), 1e-6)
    em = np.where(m_nnz[:, None], em / m_norms, np.float32(0.0))

    et = Xt @ E
    t_nnz = np.diff(Xt.indptr) > 0
    t_norms = np.maximum(np.linalg.norm(et, axis=1, keepdims=True), 1e-6)
    et = np.where(t_nnz[:, None], et / t_norms, np.float32(0.0))

    inter = em * et
    x = np.hstack([em, et, inter, st_arr, s1_probs_arr]).astype(np.float32)

    h1_pre = x @ model_dict["W1"].T + model_dict["b1"]
    h1_ln = _layer_norm_np(h1_pre, model_dict["ln1_w"], model_dict["ln1_b"])
    h1 = _gelu_tanh_np(h1_ln)

    h2_pre = h1 @ model_dict["W2"].T + model_dict["b2"]
    h2_ln = _layer_norm_np(h2_pre, model_dict["ln2_w"], model_dict["ln2_b"])
    h2 = h1 + _gelu_tanh_np(h2_ln)

    raw_logits = h2 @ model_dict["W_out"].T + model_dict["b_out"]
    return raw_logits, raw_logits / np.float32(model_dict["temperature"])


def merchant_collision_keys(name: str):
    grp = f4.normalize_merchant_group(name)
    keys = {grp}
    collapsed = re.sub(r"\s+", "", grp)
    if len(collapsed) >= 4:
        keys.add(collapsed)
    skel = VOWEL_RE.sub("", collapsed)
    if len(skel) >= 5:
        keys.add("sk:" + skel)
    return keys


def build_forbidden_merchant_keys(v3_rows, v4_test_rows):
    forbidden = set()
    for r in v3_rows + v4_test_rows:
        forbidden.update(merchant_collision_keys(r["expected_merchant"]))
    return forbidden


def read_jsonl_any(path: Path):
    rows = []
    if path.suffix == ".gz":
        with gzip.open(path, "rt", encoding="utf-8") as fh:
            for line in fh:
                s = line.strip()
                if s:
                    rows.append(json.loads(s))
    else:
        with path.open("r", encoding="utf-8") as fh:
            for line in fh:
                s = line.strip()
                if s:
                    rows.append(json.loads(s))
    return rows


V5_GEN_SCRIPT = r'''
import gzip
import json
import os
import random
import re
import time
from collections import Counter
from decimal import Decimal
from pathlib import Path
from typing import Literal, Optional, List
import torch
from pydantic import BaseModel, Field

import run_foundry_v4 as f4
import run_foundry_v5 as f5

V5_GEN_OUT = Path("/content/outputs_v5/.gen_v5.jsonl")
V5_META_OUT = Path("/content/outputs_v5/.gen_v5_meta.json")

EXTRA_SEEDS_V5 = {
    "Food & drinks || Coffee, Tea & Drinks": ["ChaTraMue Malaysia", "Richiamo Coffee", "Gigi Coffee", "Bask Bear Coffee", "HWC Coffee MY", "Kenangan Coffee MY"],
    "Food & drinks || Restaurant & Cafe": ["Yakiniku Like MY", "Kura Sushi MY", "Oriental Kopi", "Padi House", "Serai Group", "Dolly Dim Sum", "Nyonya Colors"],
    "Groceries || Convenience Store (99 Speedmart / 7-Eleven / FamilyMart)": ["KK Super Mart", "99 Speedmart", "MyNews.com", "BilaBila Mart", "7-Eleven Cafe", "CU Mart"],
    "Groceries || Household & Cleaning Supplies": ["Ninso Value Store", "Eco-Shop Malaysia", "MR.DIY Express", "NOKO Malaysia", "Mr. Dollar MY"],
    "Health & medical || Full Medical Checkup (LHDN)": ["BP Healthcare Screen", "Pathlab Wellness Screen", "Gribbles Pathology Checkup", "Tung Shin Screen Centre"],
    "Health & medical || Dental & Optical": ["Tiew Dental Group", "Q&M Dental MY", "Focus Point Optical", "OWNDAYS Malaysia", "Mog Eyewear"],
    "Insurance & Takaful || Education Insurance": ["Prudential BSN EduPlan", "AIA A-Life Ikhtiar", "Great Eastern SmartEdu", "Takaful Ikhlas EduCare", "Allianz EduSave"],
    "Loans, BNPL & Credit || PTPTN Study Loan": ["PTPTN Ujrah Direct", "myPTPTN Bayaran Pinjaman", "JomPAY PTPTN Loan"],
    "Investments & savings || SSPN Savings": ["Simpan SSPN Prime Deposit", "myPTPTN Simpanan SSPN", "Maybank2u SSPN-i Plus"],
    "Housing & property || Cukai Pintu & Cukai Tanah": ["DBKL e-Cukai Taksiran", "MBPJ eCukai Pintu", "e-Tanah Selangor Pejabat Tanah", "MBSJ Assessment Pay"],
}


class NotifSampleV5(BaseModel):
    taxonomy_pair: str
    source_type: Literal["payment_notification"]
    edge_case: str
    expected_merchant: str
    expected_total_myr: str
    expected_date_iso: str
    lhdn_bucket: Optional[str] = None
    raw_text: str = Field(..., min_length=24, max_length=550)


class NotifBatchV5(BaseModel):
    samples: List[NotifSampleV5] = Field(..., min_length=8, max_length=8)


class ReceiptSampleV5(BaseModel):
    taxonomy_pair: str
    source_type: Literal["thermal_receipt"]
    edge_case: str
    expected_merchant: str
    expected_total_myr: str
    expected_date_iso: str
    lhdn_bucket: Optional[str] = None
    raw_text: str = Field(..., min_length=85, max_length=1650)


class ReceiptBatchV5(BaseModel):
    samples: List[ReceiptSampleV5] = Field(..., min_length=4, max_length=4)


BAD_LEAK_RE = re.compile(
    r"(\|\||taxonomy_pair|expected_merchant|expected_total|source_type|Indomaret|Alfamart|"
    r"\bRp\.?\s?\d|1234567890|Lot 1 Jalan 1|<MERCHANT>|\[MERCHANT\]|\bMERCHANT_NAME\b)",
    re.IGNORECASE,
)
NEG_NOTIF_RE = re.compile(r"\b(declined|failed|unsuccessful|gagal|rejected|awaiting)\b|(?:transaction|payment|status)\s*(?:is|:)?\s*pending\b", re.IGNORECASE)
FX_CUE_RE = re.compile(r"(?:\b(?:SGD|USD|JPY|THB|EUR|GBP|AUD|CNY|RMB|HKD|TWD|KRW|IDR|VND|fx|foreign|overseas|luar negara|exchange rate|kadar tukaran|cross[- ]border)\b|[¥€£$])", re.IGNORECASE)
PLACEHOLDER_MERCH = {"merchant", "unknown", "n/a", "na", "none", "test merchant", "your merchant", "recipient"}


def clean_amount_v5(val: str, raw_text: str):
    s = str(val).strip().upper().replace("RM", "").replace("MYR", "").replace(",", "").strip()
    m = re.search(r"([0-9]+(?:\.[0-9]{1,2})?)", s)
    if not m:
        return None
    try:
        dec = Decimal(m.group(1))
    except Exception:
        return None
    if dec < Decimal("0.05") or dec >= Decimal("500000"):
        return None
    amt_str = f"{dec:.2f}"
    raw_nocomma = raw_text.replace(",", "").upper()
    if amt_str in raw_nocomma:
        return amt_str
    if amt_str.endswith(".00"):
        whole = amt_str[:-3]
        if re.search(rf"(?:RM|MYR)\s*{re.escape(whole)}(?:\.00)?(?![0-9])", raw_nocomma):
            return amt_str
    return None


def dedup_key(raw_text: str) -> str:
    return re.sub(r"\s+", " ", raw_text.strip().lower())[:180]


def pick_merchant_examples(pair: str, idx: int, n_pick: int):
    base = list(f4.MERCHANT_SEEDS_2026[pair]) + EXTRA_SEEDS_V5.get(pair, [])
    return [base[(idx * 3 + j) % len(base)] for j in range(min(n_pick, len(base)))]


def build_v5_prompt(kind: str, pair: str, edge_case: str, idx: int, rng: random.Random):
    city = f4.CITIES[(idx * 7 + rng.randint(0, 23)) % len(f4.CITIES)]
    mon = ((idx + rng.randint(0, 11)) % 12) + 1
    day = ((idx * 3 + rng.randint(0, 27)) % 28) + 1
    picked = pick_merchant_examples(pair, idx, 6)
    lhdn_hint = f4.LHDN_PAIRS_BUCKETS.get(pair)
    n_pick = 8 if kind == "notif" else 4

    if edge_case == "v5_truncated_gateway":
        style = (
            "Generate 8 ultra-realistic Malaysian card-terminal / SMS / bank alerts with HARSH gateway-prefixed & vowel-dropped/truncated merchant descriptors "
            "using prefixes like PAY*, DNQR*, TNGD*, IPAY88*, RAZERMS*, MYDEBIT*, SHOPEEPAY*, FPX* where vowels/suffixes are dropped to 10-20 chars "
            "(e.g., 'PAY*KLNKMDVR BNGS', 'TNGD*EZBKXPRSS', 'DNQR*99SPDM-KJ', 'MYDEBIT*BNGKLAUT S/B', 'FPX*SYKT BKT BRU'). "
            "Put the FRESH CLEAN Malaysian brand/store name (vary brand names—do NOT repeat existing chain clichés) in expected_merchant while raw_text contains ONLY the truncated gateway string!"
        )
    elif edge_case == "v5_multilingual_bm_cjk":
        style = (
            "Generate 8 authentic Malaysian MULTILINGUAL alerts (mix 50% natural Bahasa Melayu / Manglish like 'Pembayaran DuitNow QR berjaya RM... kepada...', 'Bil elektrik TNB selesai', "
            "AND 50% Simplified/Traditional Chinese or EN+CJK bilingual like '大马美食茶室 RM14.50', '亚坤咖椰吐司 MYR 18.90', '余仁生药材店 DuitNow RM128.00'). "
            "NEVER use Thai, Arabic, Cyrillic, Hindi, Korean, or Indonesian Rupiah. Use ONLY English, Bahasa Melayu, and/or Chinese characters with RM/MYR."
        )
    elif edge_case == "v5_disambiguation_lhdn":
        lhdn_rule = (
            f"Set lhdn_bucket='{lhdn_hint}' on LHDN-eligible items."
            if lhdn_hint else
            "Set lhdn_bucket=null (NOT eligible for LHDN relief)."
        )
        style = (
            f"Generate 8 hard BOUNDARY DISAMBIGUATION alerts for '{pair}' that specifically distinguish it from commonly confused neighbors "
            f"(e.g. PTPTN Study Loan repayment vs Simpan SSPN Savings; Convenience Store coffee vs Coffee Shop; Household supplies vs Home & Living; "
            f"Cukai Pintu assessment vs Stamp Duty vs Home Internet; Credit Card Bill repayment vs Card Annual/Late Fee; Clinic GP vs Full Medical Checkup vs Dental/Optical; "
            f"BNPL SPayLater/Atome vs Online Marketplace order). Make the exact service/purpose unmistakable while sounding like a real bank/e-wallet/FPX alert. {lhdn_rule}"
        )
    elif edge_case == "v5_digital_bnpl_fx":
        style = (
            "Generate 8 diverse 2025/2026 Malaysian digital bank & cross-border FX/BNPL notifications "
            "(GXBank, AEON Bank, Ryt Bank, Boost Bank, Boost PayFlex, SPayLater, GrabPayLater, Atome, TNG GO+, Wise/BigPay MYR-settled FX in SGD/THB/JPY/USD/EUR/AUD/KRW). "
            "Wait: expected_total_myr MUST match the exact MYR/RM billed amount in raw_text."
        )
    else:  # v5_receipt_item_body
        style = (
            "Generate 4 realistic compact Malaysian 80mm receipt OCR transcripts where the header is short/generic or cryptic (e.g., an unfamiliar local SME name) "
            "so the category is revealed clearly by the itemized lines, SST 6%/8%, 5-sen rounding, and tender/change lines. Keep TOTAL / FINAL AMOUNT in RM exact."
        )

    msg = (
        f"{style}\n"
        f"Target taxonomy_pair for ALL {n_pick} samples MUST be: \"{pair}\"\n"
        f"Target edge_case for ALL {n_pick} samples MUST be: \"{edge_case}\"\n"
        f"Anchor location: {city}. Anchor date: 2026-{mon:02d}-{day:02d} (vary formats).\n"
        f"Invent fresh, realistic, localized Malaysian SME/outlet names inspired by (DO NOT copy verbatim only): {', '.join(picked)}.\n"
        f"STRICT rules:\n"
        f"1. expected_total_myr = exact 2-decimal MYR string e.g. '35.80' (no RM/comma), and that exact figure MUST appear in raw_text with RM or MYR.\n"
        f"2. NEVER output '||', placeholders, Rupiah/Indomaret, OTP/failed/pending, or Thai/Arabic/Korean/Russian scripts.\n"
        f"3. Output {n_pick} unique samples."
    )
    return (
        "<|im_start|>system\n"
        "You synthesize fresh, unseen, adversarial Malaysian payment & receipt texts for PFM model training.<|im_end|>\n"
        f"<|im_start|>user\n{msg}<|im_end|>\n<|im_start|>assistant\n"
    )


def build_v5_jobs():
    rng = random.Random(20261123)
    jobs_notif, jobs_rcpt = [], []
    non_lhdn = [p for p in f4.ALL_PAIRS if p not in f4.LHDN_PAIRS_BUCKETS]
    lhdn_list = [p for p in f4.ALL_PAIRS if p in f4.LHDN_PAIRS_BUCKETS]
    boundary_pairs = [
        "Loans, BNPL & Credit || PTPTN Study Loan",
        "Investments & savings || SSPN Savings",
        "Loans, BNPL & Credit || BNPL (SPayLater / GrabPayLater / Atome)",
        "Shopping || Online Marketplace (Shopee / Lazada / TikTok)",
        "Loans, BNPL & Credit || Credit Card Bill",
        "Fees & charges || Card Annual Fee",
        "Fees & charges || Late Payment Fee",
        "Groceries || Convenience Store (99 Speedmart / 7-Eleven / FamilyMart)",
        "Groceries || Household & Cleaning Supplies",
        "Shopping || Home & Living",
        "Food & drinks || Coffee, Tea & Drinks",
        "Housing & property || Cukai Pintu & Cukai Tanah",
        "Taxes & government || Government Service & Stamp Duty",
        "Housing & property || Home Repair & Maintenance",
        "Transport || Vehicle Maintenance & Service",
        "Health & medical || Full Medical Checkup (LHDN)",
        "Health & medical || Dental & Optical",
        "Health & medical || Clinic & GP",
        "Insurance & Takaful || Education Insurance",
        "Insurance & Takaful || Medical & Health Card",
    ]

    specs = [
        ("v5_truncated_gateway", f4.ALL_PAIRS, 6),      # 444 prompts -> ~3,550 raw slots (~3,000+ clean)
        ("v5_multilingual_bm_cjk", f4.ALL_PAIRS, 5),    # 370 prompts -> ~2,960 raw slots (~2,500+ clean)
        ("v5_disambiguation_lhdn", boundary_pairs, 8),  # 160 prompts
        ("v5_disambiguation_lhdn", lhdn_list, 3),       # 66 prompts
        ("v5_disambiguation_lhdn", non_lhdn, 2),        # 104 prompts -> ~2,640 raw slots (~2,000+ clean)
        ("v5_digital_bnpl_fx", f4.ALL_PAIRS, 3),        # 222 prompts -> ~1,776 raw slots (~1,500+ clean)
    ]
    idx = 0
    for ec, plist, reps in specs:
        for pair in plist:
            for _ in range(reps):
                idx += 1
                jobs_notif.append({
                    "kind": "notif",
                    "pair": pair,
                    "edge_case": ec,
                    "prompt": build_v5_prompt("notif", pair, ec, idx, rng),
                })

    for pair in f4.ALL_PAIRS:
        for _ in range(4):  # 296 prompts -> ~1,184 raw slots (~1,000+ clean)
            idx += 1
            jobs_rcpt.append({
                "kind": "receipt",
                "pair": pair,
                "edge_case": "v5_receipt_item_body",
                "prompt": build_v5_prompt("receipt", pair, "v5_receipt_item_body", idx, rng),
            })

    rng.shuffle(jobs_notif)
    rng.shuffle(jobs_rcpt)
    chunks = []
    for i in range(0, len(jobs_notif), 180):
        chunks.append(("notif", jobs_notif[i:i + 180]))
    for i in range(0, len(jobs_rcpt), 110):
        chunks.append(("receipt", jobs_rcpt[i:i + 110]))
    return chunks


def run_v5_gen():
    from vllm import LLM, SamplingParams
    try:
        from vllm.sampling_params import StructuredOutputsParams
        def make_sp(schema, max_toks, temp):
            return SamplingParams(temperature=temp, top_p=0.95, max_tokens=max_toks, structured_outputs=StructuredOutputsParams(json=schema))
    except ImportError:
        from vllm.sampling_params import GuidedDecodingParams
        def make_sp(schema, max_toks, temp):
            return SamplingParams(temperature=temp, top_p=0.95, max_tokens=max_toks, guided_decoding=GuidedDecodingParams(json=schema))

    v3_h = json.loads(f5.HOLDOUT_V3_PATH.read_text(encoding="utf-8"))
    v4_h = json.loads(f5.HOLDOUT_V4_PATH.read_text(encoding="utf-8"))
    forbidden_keys = f5.build_forbidden_merchant_keys(v3_h, v4_h)
    v4_rows = f5.read_jsonl_any(f5.DATASET_V4_GZ_PATH)
    seen = {dedup_key(r["raw_text"]) for r in v4_rows}

    new_samples = []
    done_chunks = set()
    drop_reasons = Counter()
    if f5.PROGRESS_PATH.exists():
        try:
            prog = json.loads(f5.PROGRESS_PATH.read_text(encoding="utf-8"))
            done_chunks = set(prog.get("done_chunks", []))
            drop_reasons = Counter(prog.get("drop_reasons", {}))
        except Exception:
            pass
    if f5.CHECKPOINT_PATH.exists():
        with f5.CHECKPOINT_PATH.open("r", encoding="utf-8") as fh:
            for line in fh:
                s = line.strip()
                if s:
                    try:
                        r = json.loads(s)
                        seen.add(dedup_key(r["raw_text"]))
                        new_samples.append(r)
                    except Exception:
                        pass

    props = torch.cuda.get_device_properties(0)
    vram = props.total_memory / (1024 ** 3)
    candidates = []
    if vram >= 38.0:
        candidates.append(("Qwen/Qwen2.5-32B-Instruct-AWQ", "awq", 3584))
    if props.major >= 8 and vram >= 20.0:
        candidates.append(("Qwen/Qwen2.5-14B-Instruct-AWQ", "awq", 3584))
    candidates.append(("Qwen/Qwen2.5-7B-Instruct-AWQ", "awq", 3584))
    candidates.append(("Qwen/Qwen2.5-7B-Instruct", None, 3584))

    llm = None
    model_id = None
    for mid, quant, mlen in candidates:
        for use_pc in (True, False):
            try:
                kw = {"model": mid, "dtype": "half", "max_model_len": mlen, "gpu_memory_utilization": 0.90}
                if quant:
                    kw["quantization"] = quant
                if use_pc:
                    kw["enable_prefix_caching"] = True
                print(f"Loading vLLM v5 teacher: {mid} (pc={use_pc})...", flush=True)
                llm = LLM(**kw)
                model_id = mid
                break
            except Exception as e:
                print(f"vLLM init failed {mid} (pc={use_pc}): {e}", flush=True)
        if llm is not None:
            break
    if llm is None:
        raise RuntimeError("No vLLM model initialized for v5 synthesis")

    chunks = build_v5_jobs()
    sp_notif = make_sp(NotifBatchV5.model_json_schema(), 2048, 0.90)
    sp_receipt = make_sp(ReceiptBatchV5.model_json_schema(), 2600, 0.86)
    edge_counts = Counter(r.get("edge_case", "unknown") for r in new_samples)
    total_chunks = len(chunks)
    t_gen0 = time.perf_counter()
    added_this_run = 0

    def process_chunk(c_idx, c_kind, jobs):
        nonlocal added_this_run
        sp = sp_notif if c_kind == "notif" else sp_receipt
        cls_m = NotifBatchV5 if c_kind == "notif" else ReceiptBatchV5
        outputs = llm.generate([j["prompt"] for j in jobs], sp)
        added = []
        for job, out in zip(jobs, outputs):
            txt = out.outputs[0].text.strip()
            try:
                parsed = cls_m.model_validate_json(txt)
            except Exception:
                drop_reasons["json_error"] += 1
                continue
            for s in parsed.samples:
                pair = str(s.taxonomy_pair).strip()
                if " || " not in pair:
                    drop_reasons["pair_format"] += 1
                    continue
                cat, sub = pair.split(" || ", 1)
                if sub not in f4.CANONICAL_TAXONOMY.get(cat, []):
                    drop_reasons["pair_mismatch"] += 1
                    continue
                if BAD_LEAK_RE.search(s.raw_text):
                    drop_reasons["leak_or_placeholder"] += 1
                    continue
                if f5.FORBIDDEN_SCRIPT_RE.search(s.raw_text) or f5.FORBIDDEN_SCRIPT_RE.search(s.expected_merchant):
                    drop_reasons["forbidden_script"] += 1
                    continue
                ec = job["edge_case"]
                if s.source_type == "payment_notification" and NEG_NOTIF_RE.search(s.raw_text):
                    drop_reasons["neg_notif_trigger"] += 1
                    continue
                merch = s.expected_merchant.strip()
                if len(merch) < 2 or len(merch) > 90 or merch.lower() in PLACEHOLDER_MERCH:
                    drop_reasons["bad_merchant"] += 1
                    continue
                if f5.merchant_collision_keys(merch) & forbidden_keys:
                    drop_reasons["holdout_merchant_collision"] += 1
                    continue
                norm_amt = clean_amount_v5(s.expected_total_myr, s.raw_text)
                if not norm_amt:
                    drop_reasons["bad_amount"] += 1
                    continue
                key = dedup_key(s.raw_text)
                if key in seen:
                    drop_reasons["duplicate"] += 1
                    continue
                seen.add(key)
                lhdn = s.lhdn_bucket
                if lhdn is not None:
                    lhdn = str(lhdn).strip()
                    if not lhdn or lhdn.lower() in {"none", "null", "n/a"}:
                        lhdn = None
                row = {
                    "source_type": s.source_type,
                    "edge_case": ec,
                    "raw_text": s.raw_text,
                    "expected_merchant": merch,
                    "expected_total_myr": norm_amt,
                    "expected_date_iso": s.expected_date_iso.strip(),
                    "category": cat,
                    "subcategory": sub,
                    "lhdn_bucket": lhdn,
                }
                added.append(row)
                new_samples.append(row)
                edge_counts[ec] += 1
                added_this_run += 1

        if added:
            with f5.CHECKPOINT_PATH.open("a", encoding="utf-8") as cf:
                for r in added:
                    cf.write(json.dumps(r, ensure_ascii=False) + "\n")
                cf.flush()
                os.fsync(cf.fileno())
        done_chunks.add(c_idx)
        f5.PROGRESS_PATH.write_text(json.dumps({
            "model": model_id,
            "done_chunks": sorted(done_chunks),
            "total_chunks": total_chunks,
            "new_valid": len(new_samples),
            "dropped": sum(drop_reasons.values()),
            "drop_reasons": dict(drop_reasons),
            "edge_case_counts": dict(edge_counts),
        }, indent=2), encoding="utf-8")
        elapsed = max(1.0, time.perf_counter() - t_gen0)
        rate = added_this_run / elapsed
        f5.update_status("GENERATING_V5_VLLM", model=model_id, chunks_done=len(done_chunks), chunks_total=total_chunks, new_valid=len(new_samples), rate=round(rate, 2), edge_case_counts=dict(edge_counts))
        print(f"[v5 Chunk {len(done_chunks)}/{total_chunks}] +{len(added)} | new_valid={len(new_samples)} | rate={rate:.2f}/s | drops={dict(drop_reasons)}", flush=True)

    for c_idx, (c_kind, jobs) in enumerate(chunks):
        if c_idx in done_chunks:
            continue
        process_chunk(c_idx, c_kind, jobs)

    # Top-up guarantee if drop rate leaves < 9,600 new samples
    rng_tu = random.Random(777)
    round_i = 0
    while len(new_samples) < 9600 and round_i < 4:
        round_i += 1
        needed = 9800 - len(new_samples)
        n_p = min(180, max(32, needed // 5 + 10))
        edges = ["v5_truncated_gateway", "v5_multilingual_bm_cjk", "v5_disambiguation_lhdn", "v5_digital_bnpl_fx"]
        tu_jobs = [
            {
                "kind": "notif",
                "pair": f4.ALL_PAIRS[(round_i * 41 + j) % len(f4.ALL_PAIRS)],
                "edge_case": edges[(round_i + j) % len(edges)],
                "prompt": build_v5_prompt("notif", f4.ALL_PAIRS[(round_i * 41 + j) % len(f4.ALL_PAIRS)], edges[(round_i + j) % len(edges)], 80000 + round_i * 1000 + j, rng_tu),
            }
            for j in range(n_p)
        ]
        process_chunk(total_chunks + round_i, "notif", tu_jobs)

    combined = v4_rows + new_samples
    with V5_GEN_OUT.open("w", encoding="utf-8") as f:
        for row in combined:
            f.write(json.dumps(row, ensure_ascii=False) + "\n")
    meta = {
        "model": model_id,
        "gpu": f"{props.name} ({vram:.1f} GB)",
        "v4_base": len(v4_rows),
        "new_v5_valid": len(new_samples),
        "total_combined": len(combined),
        "dropped": sum(drop_reasons.values()),
        "drop_reasons": dict(drop_reasons),
        "edge_case_counts": dict(edge_counts),
        "gen_seconds": round(time.perf_counter() - t_gen0, 1),
    }
    V5_META_OUT.write_text(json.dumps(meta, indent=2), encoding="utf-8")
    print(f"Completed v5 synthesis: {len(combined)} total ({len(new_samples)} new v5)", flush=True)


if __name__ == "__main__":
    run_v5_gen()
'''


def ensure_deps(train_only: bool = False, distill_only: bool = False):
    pkgs = ["sentencepiece", "protobuf", "scikit-learn", "scipy", "transformers"]
    if not distill_only:
        pkgs = ["hf_transfer"] + pkgs
    if not train_only and not distill_only:
        pkgs = ["vllm"] + pkgs
    update_status("INSTALLING", detail=f"pip install {' '.join(pkgs)}")
    subprocess.run([sys.executable, "-m", "pip", "install", "-q", *pkgs], check=True)


def run_v5_synthesis():
    update_status("GENERATING_V5_VLLM", detail="Starting isolated vLLM v5 hard-case synthesis (~10,000 samples)")
    gp = Path("/content/gen_vllm_v5.py")
    gp.write_text(V5_GEN_SCRIPT, encoding="utf-8")
    env = {
        **os.environ,
        "HF_HUB_ENABLE_HF_TRANSFER": "1",
        "PYTHONPATH": f"{Path(__file__).resolve().parent}:{os.environ.get('PYTHONPATH', '')}",
    }
    subprocess.run([sys.executable, "-u", str(gp)], check=True, env=env)
    tmp_ds = OUT_DIR / ".gen_v5.jsonl"
    DATASET_PATH.write_bytes(tmp_ds.read_bytes())
    with DATASET_PATH.open("rb") as f_in, gzip.open(DATASET_GZ_PATH, "wb", compresslevel=9) as f_out:
        f_out.writelines(f_in)
    return json.loads((OUT_DIR / ".gen_v5_meta.json").read_text(encoding="utf-8"))


def build_v5_splits(samples, v3_h_rows, v4_h_rows):
    """Preserves frozen v3_holdout (384) & v4_test (5938), merges v4 train+v5 train, and carves disjoint v5_cal + v5_hard_test."""
    v3_exact = {(h["expected_merchant"].strip(), h["raw_text"]) for h in v3_h_rows}
    v4_test_exact = {(h["expected_merchant"].strip(), h["raw_text"]) for h in v4_h_rows}
    forbidden = build_forbidden_merchant_keys(v3_h_rows, v4_h_rows)

    v5_edges = {"v5_truncated_gateway", "v5_multilingual_bm_cjk", "v5_disambiguation_lhdn", "v5_digital_bnpl_fx", "v5_receipt_item_body"}
    v4_rows_indices = [i for i, r in enumerate(samples) if r.get("edge_case") not in v5_edges]
    v5_rows_indices = [i for i, r in enumerate(samples) if r.get("edge_case") in v5_edges]

    # Exact v4 splits anchored directly from frozen holdouts + merchant groups
    v4_sub = [samples[i] for i in v4_rows_indices]
    f4.HOLDOUT_V3_PATH = HOLDOUT_V3_PATH
    v4_sp = f4.build_zero_leakage_splits(v4_sub)
    v4_train_global = [v4_rows_indices[k] for k in v4_sp["train_idx"]]
    v4_cal_global = [v4_rows_indices[k] for k in v4_sp["cal_idx"]]
    v4_test_global = [v4_rows_indices[k] for k in v4_sp["v4_test_idx"]]
    v3_hold_global = [v4_rows_indices[k] for k in v4_sp["v3_holdout_idx"]]

    # Verify strict 100% alignment with uploaded holdouts
    v4_test_set = {(samples[i]["expected_merchant"].strip(), samples[i]["raw_text"]) for i in v4_test_global}
    assert len(v3_hold_global) == len(v3_h_rows), f"v3 mismatch {len(v3_hold_global)} != {len(v3_h_rows)}"
    assert v4_test_set == v4_test_exact, f"v4 test mismatch ({len(v4_test_set)} vs {len(v4_test_exact)})"

    # Also collect all merchant groups already inside v4_train and v4_cal so new v5 test groups stay disjoint from BOTH v4 train/cal and v3/v4 test!
    v4_tr_cal_grps = {f4.normalize_merchant_group(samples[i]["expected_merchant"]) for i in (v4_train_global + v4_cal_global)}

    v5_elig_by_grp = defaultdict(list)
    v5_forced_train = []
    for i in v5_rows_indices:
        r = samples[i]
        assert not (merchant_collision_keys(r["expected_merchant"]) & forbidden), f"Leak in v5 row: {r['expected_merchant']}"
        g = f4.normalize_merchant_group(r["expected_merchant"])
        if g in v4_tr_cal_grps:
            v5_forced_train.append(i)
        else:
            v5_elig_by_grp[g].append(i)

    grps = sorted(v5_elig_by_grp.keys())
    rng = random.Random(20261123)
    rng.shuffle(grps)
    n_test_target = max(900, int(0.15 * len(v5_rows_indices)))
    n_cal_target = max(350, int(0.05 * len(v5_rows_indices)))

    v5_train_global = list(v5_forced_train)
    v5_cal_global, v5_test_global = [], []
    for g in grps:
        idxs = v5_elig_by_grp[g]
        if len(v5_test_global) < n_test_target:
            v5_test_global.extend(idxs)
        elif len(v5_cal_global) < n_cal_target:
            v5_cal_global.extend(idxs)
        else:
            v5_train_global.extend(idxs)

    # Assertion: ZERO merchant-group leakage between train_all/cal_all and ANY test split
    eval_grps = {f4.normalize_merchant_group(samples[i]["expected_merchant"]) for i in (v3_hold_global + v4_test_global + v5_test_global)}
    train_cal_grps = {f4.normalize_merchant_group(samples[i]["expected_merchant"]) for i in (v4_train_global + v4_cal_global + v5_train_global + v5_cal_global)}
    overlap = eval_grps & train_cal_grps
    assert not overlap, f"Merchant-group leakage detected: {list(overlap)[:10]}"

    return {
        "train_idx": v4_train_global + v5_train_global,
        "cal_idx": v4_cal_global + v5_cal_global,
        "v3_holdout_idx": v3_hold_global,
        "v4_test_idx": v4_test_global,
        "v5_hard_test_idx": v5_test_global,
        "v5_train_n": len(v5_train_global),
        "v5_cal_n": len(v5_cal_global),
    }


def _rows_labels_75(rows):
    import numpy as np
    return np.array([CLS2ID_75[f"{r['category']}||{r['subcategory']}"] for r in rows], dtype=np.int64)


def train_hf_teacher(backbone: str, samples, splits, epochs=4, max_len=176):
    """Fine-tunes a HuggingFace encoder teacher (mDeBERTa-v3 / E5 / XLM-R) and returns float16 logits."""
    import gc
    import numpy as np
    import torch
    import torch.nn as nn
    from torch.utils.data import DataLoader, TensorDataset
    from transformers import AutoModel, AutoTokenizer, get_cosine_schedule_with_warmup

    train_idx = splits["train_idx"]
    cal_idx = splits["cal_idx"]
    vram_gb = torch.cuda.get_device_properties(0).total_memory / (1024 ** 3) if torch.cuda.is_available() else 0.0
    batch_size = 64 if vram_gb >= 20.0 else 32
    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")

    cc_major = torch.cuda.get_device_capability(0)[0] if torch.cuda.is_available() else 0
    native_bf16 = torch.cuda.is_available() and cc_major >= 8
    if native_bf16:
        torch.backends.cuda.matmul.allow_tf32 = True
        torch.backends.cudnn.allow_tf32 = True

    is_deberta = "deberta" in backbone.lower()
    if is_deberta:
        # DeBERTa-v3 disentangled attention overflows in bf16/fp16; run pure fp32/TF32 cleanly
        use_amp = False
        amp_dtype = torch.float32
        encoder_lr = 1.5e-5
        warmup_frac = 0.10
    else:
        use_amp = torch.cuda.is_available()
        amp_dtype = torch.bfloat16 if native_bf16 else torch.float16
        encoder_lr = 2.5e-5
        warmup_frac = 0.08

    update_status(
        "TRAINING_TEACHER",
        backbone=backbone,
        train_n=len(train_idx),
        cal_n=len(cal_idx),
        batch_size=batch_size,
        amp=(str(amp_dtype) if use_amp else ("tf32/fp32" if native_bf16 else "fp32")),
    )
    try:
        tok = AutoTokenizer.from_pretrained(backbone)
    except Exception:
        tok = AutoTokenizer.from_pretrained(backbone, use_fast=False)
    enc = AutoModel.from_pretrained(backbone)

    class TeacherNet(nn.Module):
        def __init__(self, encoder, n_cls):
            super().__init__()
            self.encoder = encoder
            self.drop = nn.Dropout(0.15)
            self.head = nn.Linear(encoder.config.hidden_size, n_cls)

        def forward(self, input_ids, attention_mask):
            out = self.encoder(input_ids=input_ids, attention_mask=attention_mask).last_hidden_state
            mask = attention_mask.unsqueeze(-1).float()
            pooled = (out * mask).sum(dim=1) / mask.sum(dim=1).clamp(min=1e-6)
            return self.head(self.drop(pooled))

    model = TeacherNet(enc, 75).to(device)
    tr_rows = [samples[i] for i in train_idx]
    tr_texts = [f"{r['expected_merchant']}\n{r['raw_text']}" for r in tr_rows] + [r["raw_text"] for r in tr_rows]
    tr_y = np.tile(_rows_labels_75(tr_rows), 2)

    tr_enc = tok(tr_texts, padding=True, truncation=True, max_length=max_len, return_tensors="pt")
    tr_dl = DataLoader(TensorDataset(tr_enc["input_ids"], tr_enc["attention_mask"], torch.from_numpy(tr_y)), batch_size=batch_size, shuffle=True)

    counts = Counter(tr_y.tolist())
    weights = torch.tensor([min(4.0, len(tr_y) / (75.0 * max(1, counts.get(i, 1)))) for i in range(75)], dtype=torch.float32, device=device)
    crit = nn.CrossEntropyLoss(weight=weights, label_smoothing=0.04)
    opt = torch.optim.AdamW([
        {"params": model.encoder.parameters(), "lr": encoder_lr, "weight_decay": 0.01},
        {"params": model.head.parameters(), "lr": 3e-4, "weight_decay": 0.01},
    ])
    total_steps = len(tr_dl) * epochs
    sched = get_cosine_schedule_with_warmup(opt, num_warmup_steps=max(10, int(warmup_frac * total_steps)), num_training_steps=total_steps)
    scaler = torch.amp.GradScaler("cuda", enabled=(use_amp and amp_dtype == torch.float16))

    def predict_logits(texts):
        model.eval()
        enc_d = tok(texts, padding=True, truncation=True, max_length=max_len, return_tensors="pt")
        dl = DataLoader(TensorDataset(enc_d["input_ids"], enc_d["attention_mask"]), batch_size=batch_size * 2)
        outs = []
        with torch.no_grad():
            for ids, mask in dl:
                ids, mask = ids.to(device), mask.to(device)
                with torch.amp.autocast("cuda", dtype=amp_dtype, enabled=use_amp):
                    outs.append(model(ids, mask).float().cpu().numpy())
        return np.vstack(outs)

    cal_rows = [samples[i] for i in cal_idx]
    cal_y = _rows_labels_75(cal_rows)
    best_state, best_f1, best_ep = None, -1.0, 0
    t0 = time.perf_counter()

    try:
        for ep in range(1, epochs + 1):
            model.train()
            for ids, mask, y in tr_dl:
                ids, mask, y = ids.to(device), mask.to(device), y.to(device)
                opt.zero_grad(set_to_none=True)
                with torch.amp.autocast("cuda", dtype=amp_dtype, enabled=use_amp):
                    loss = crit(model(ids, mask), y)
                if not torch.isfinite(loss).item():
                    raise RuntimeError(f"Non-finite loss in {backbone} at epoch {ep}")
                if scaler.is_enabled():
                    scaler.scale(loss).backward()
                    scaler.unscale_(opt)
                    torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
                    scaler.step(opt)
                    scaler.update()
                else:
                    loss.backward()
                    torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
                    opt.step()
                sched.step()
            cal_l = predict_logits([r["raw_text"] for r in cal_rows])
            if not np.all(np.isfinite(cal_l)):
                raise RuntimeError(f"Non-finite cal logits in {backbone} at epoch {ep}")
            cm = f4.compute_calibration_metrics(cal_l, cal_y, CLASSES_75)
            print(f"[{backbone}] Epoch {ep}/{epochs}: cal raw subcat={cm['subcat_acc']} macro_f1={cm['subcat_macro_f1']} nll={cm['nll']}", flush=True)
            if cm["subcat_macro_f1"] > best_f1:
                best_f1 = cm["subcat_macro_f1"]
                best_ep = ep
                best_state = {k: v.cpu() for k, v in model.state_dict().items()}

        if best_state is not None:
            model.load_state_dict({k: v.to(device) for k, v in best_state.items()})

        z_both = predict_logits([f"{r['expected_merchant']}\n{r['raw_text']}" for r in samples]).astype(np.float16)
        z_raw = predict_logits([r["raw_text"] for r in samples]).astype(np.float16)
        sec = round(time.perf_counter() - t0, 1)
    finally:
        del model, enc, best_state, tr_enc, tr_dl
        gc.collect()
        if torch.cuda.is_available():
            torch.cuda.empty_cache()

    return {"backbone": backbone, "epochs": epochs, "best_epoch": best_ep, "train_seconds": sec, "z_both": z_both, "z_raw": z_raw}


def _embedding_bag_linear_gpu(X_tr_csr, y_tr, X_eval_list, num_classes=75, epochs=12, batch_size=512, lr=2e-2, wd=2e-4, sample_weights=None):
    """Ultra-fast O(nnz) GPU sparse linear trainer using nn.EmbeddingBag (supports 200k+ dims)."""
    import numpy as np
    import torch
    import torch.nn as nn
    import torch.nn.functional as F

    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    N, D = X_tr_csr.shape
    emb = nn.EmbeddingBag(D, num_classes, mode="sum", sparse=False).to(device)
    bias = nn.Parameter(torch.zeros(num_classes, dtype=torch.float32, device=device))
    nn.init.zeros_(emb.weight)

    opt = torch.optim.AdamW([{"params": emb.parameters()}, {"params": [bias], "weight_decay": 0.0}], lr=lr, weight_decay=wd)
    steps = max(1, epochs * math.ceil(N / batch_size))
    sched = torch.optim.lr_scheduler.CosineAnnealingLR(opt, T_max=steps)

    counts = Counter(y_tr.tolist())
    mean_c = N / max(1, num_classes)
    cw = torch.tensor([min(3.0, math.sqrt(mean_c / max(1, counts.get(i, 1)))) for i in range(num_classes)], dtype=torch.float32, device=device)

    rng = np.random.default_rng(42)
    X_csr = X_tr_csr.tocsr()
    y_t = torch.from_numpy(np.asarray(y_tr, dtype=np.int64))
    sw_t = torch.from_numpy(np.asarray(sample_weights, dtype=np.float32)) if sample_weights is not None else None

    def forward_csr_batch(sub_csr):
        idx_t = torch.from_numpy(sub_csr.indices.astype(np.int64, copy=False)).to(device)
        off_t = torch.from_numpy(sub_csr.indptr[:-1].astype(np.int64, copy=False)).to(device)
        val_t = torch.from_numpy(sub_csr.data.astype(np.float32, copy=False)).to(device)
        return emb(idx_t, off_t, per_sample_weights=val_t) + bias

    emb.train()
    for _ in range(epochs):
        perm = rng.permutation(N)
        for st in range(0, N, batch_size):
            bi = perm[st:st + batch_size]
            sub = X_csr[bi]
            logits = forward_csr_batch(sub)
            yb = y_t[bi].to(device)
            if sw_t is not None:
                loss_vec = F.cross_entropy(logits, yb, weight=cw, reduction="none")
                loss = (loss_vec * sw_t[bi].to(device)).mean()
            else:
                loss = F.cross_entropy(logits, yb, weight=cw)
            opt.zero_grad(set_to_none=True)
            loss.backward()
            opt.step()
            sched.step()

    emb.eval()
    eval_logits = []
    with torch.no_grad():
        for X_ev in X_eval_list:
            X_ec = X_ev.tocsr()
            outs = []
            for st in range(0, X_ec.shape[0], batch_size * 2):
                outs.append(forward_csr_batch(X_ec[st:st + batch_size * 2]).cpu().numpy())
            eval_logits.append(np.vstack(outs) if outs else np.zeros((0, num_classes), dtype=np.float32))

    W_np = emb.weight.detach().cpu().numpy().T.astype(np.float64)
    b_np = bias.detach().cpu().numpy().astype(np.float64)
    del emb, bias
    if torch.cuda.is_available():
        torch.cuda.empty_cache()
    return eval_logits, W_np, b_np


def _high_dim_featurize(texts):
    from collections import Counter as Cnt
    out = []
    for txt in texts:
        wc, cc = f4.featurize_counts(txt)
        merged = Cnt(wc)
        merged.update(cc)
        for w in f4.java_parity_words(txt)[:60]:
            for sk in consonant_skeletons(w, "h", include_3=True):
                merged[sk] += 1
        out.append(merged)
    return out


def train_oof_highdim_teacher(samples, splits):
    """5-fold OOF High-Dim Word+Char+ConsonantSkeleton Linear Teacher on GPU (<1 min)."""
    import numpy as np
    from scipy.sparse import csr_matrix

    update_status("TRAINING_TEACHER", backbone="HighDim-200k-5Fold-OOF")
    t0 = time.perf_counter()
    train_idx = splits["train_idx"]
    n_tot = len(samples)
    tr_rows = [samples[i] for i in train_idx]

    both_all_texts = [f"{r['expected_merchant']}\n{r['raw_text']}" for r in samples]
    raw_all_texts = [r["raw_text"] for r in samples]

    parsed_tr_b = _high_dim_featurize([both_all_texts[i] for i in train_idx])
    parsed_tr_r = _high_dim_featurize([raw_all_texts[i] for i in train_idx])
    df = Counter()
    for c_dict in parsed_tr_b + parsed_tr_r:
        for k in c_dict:
            df[k] += 1
    vocab = [k for k, cnt in df.most_common() if cnt >= 2][:200000]
    v_idx = {k: i for i, k in enumerate(vocab)}
    N_tr_docs = len(parsed_tr_b) + len(parsed_tr_r)
    idf = np.array([math.log((1.0 + N_tr_docs) / (1.0 + df[k])) + 1.0 for k in vocab], dtype=np.float32)

    def to_csr(parsed_list):
        indptr, indices, data = [0], [], []
        for cnt in parsed_list:
            ri, rv = [], []
            for tok, tf in cnt.items():
                fi = v_idx.get(tok)
                if fi is not None:
                    ri.append(fi)
                    rv.append((1.0 + math.log(tf)) * idf[fi])
            if rv:
                norm = math.sqrt(sum(v * v for v in rv))
                if norm > 0:
                    rv = [v / norm for v in rv]
            indices.extend(ri)
            data.extend(rv)
            indptr.append(len(indices))
        return csr_matrix((np.array(data, dtype=np.float32), np.array(indices, dtype=np.int32), np.array(indptr, dtype=np.int32)), shape=(len(parsed_list), len(vocab)), dtype=np.float32)

    X_all_both = to_csr(_high_dim_featurize(both_all_texts))
    X_all_raw = to_csr(_high_dim_featurize(raw_all_texts))

    # 5-fold stratified-by-group OOF over train_idx
    from scipy.sparse import vstack as sp_vstack
    z_both = np.zeros((n_tot, 75), dtype=np.float16)
    z_raw = np.zeros((n_tot, 75), dtype=np.float16)

    rng = random.Random(1337)
    tr_grps = sorted({f4.normalize_merchant_group(samples[i]["expected_merchant"]) for i in train_idx})
    rng.shuffle(tr_grps)
    grp_fold = {g: k % 5 for k, g in enumerate(tr_grps)}
    folds_of_tr = [grp_fold[f4.normalize_merchant_group(samples[i]["expected_merchant"])] for i in train_idx]

    y_tr_base = _rows_labels_75(tr_rows)
    non_train_mask = np.ones(n_tot, dtype=bool)
    non_train_mask[train_idx] = False
    non_train_idx = np.flatnonzero(non_train_mask)

    acc_nt_b = np.zeros((len(non_train_idx), 75), dtype=np.float64)
    acc_nt_r = np.zeros((len(non_train_idx), 75), dtype=np.float64)

    for fold in range(5):
        fit_pos = [k for k, f in enumerate(folds_of_tr) if f != fold]
        oof_pos = [k for k, f in enumerate(folds_of_tr) if f == fold]
        fit_global = [train_idx[k] for k in fit_pos]
        oof_global = [train_idx[k] for k in oof_pos]

        X_fit = sp_vstack([X_all_both[fit_global], X_all_raw[fit_global]], format="csr")
        y_fit = np.tile(y_tr_base[fit_pos], 2)
        evals, _, _ = _embedding_bag_linear_gpu(
            X_fit, y_fit,
            [X_all_both[oof_global], X_all_raw[oof_global], X_all_both[non_train_idx], X_all_raw[non_train_idx]],
            epochs=12, batch_size=512, lr=2e-2, wd=2e-4,
        )
        z_both[oof_global] = evals[0].astype(np.float16)
        z_raw[oof_global] = evals[1].astype(np.float16)
        acc_nt_b += evals[2] / 5.0
        acc_nt_r += evals[3] / 5.0

    z_both[non_train_idx] = acc_nt_b.astype(np.float16)
    z_raw[non_train_idx] = acc_nt_r.astype(np.float16)
    sec = round(time.perf_counter() - t0, 1)
    print(f"[HighDim-200k-OOF] Finished 5-fold in {sec}s (vocab={len(vocab)})", flush=True)
    return {"backbone": "HighDim-200k-5Fold-OOF", "vocab_size": len(vocab), "train_seconds": sec, "z_both": z_both, "z_raw": z_raw}


def blend_teacher_logits(t1, t2, t3, samples, splits):
    """Finds calibration-optimal temperature-scaled ensemble blend weights (w1, w2, w3) on cal_idx."""
    import numpy as np
    from scipy.optimize import minimize

    update_status("BLENDING_TEACHER_ENSEMBLE")
    cal_idx = splits["cal_idx"]
    cal_y = _rows_labels_75([samples[i] for i in cal_idx])
    yb = np.concatenate([cal_y, cal_y])

    def norm_logits(t_dict):
        zb = t_dict["z_both"].astype(np.float64)
        zr = t_dict["z_raw"].astype(np.float64)
        T = f4.fit_temperature(np.vstack([zb[cal_idx], zr[cal_idx]]), yb)
        return zb / T, zr / T, round(T, 4)

    zb1, zr1, T1 = norm_logits(t1)
    zb2, zr2, T2 = norm_logits(t2)
    zb3, zr3, T3 = norm_logits(t3)

    cal_b = [zb1[cal_idx], zb2[cal_idx], zb3[cal_idx]]
    cal_r = [zr1[cal_idx], zr2[cal_idx], zr3[cal_idx]]
    ar = np.arange(len(yb))

    def obj(params):
        e = np.exp(params - np.max(params))
        w = e / e.sum()
        lb = w[0] * cal_b[0] + w[1] * cal_b[1] + w[2] * cal_b[2]
        lr = w[0] * cal_r[0] + w[1] * cal_r[1] + w[2] * cal_r[2]
        comb = np.vstack([lb, lr])
        comb -= comb.max(axis=1, keepdims=True)
        exps = np.exp(comb)
        probs = exps / exps.sum(axis=1, keepdims=True)
        return float(-np.mean(np.log(np.clip(probs[ar, yb], 1e-12, 1.0))))

    res = minimize(obj, x0=[0.6, 0.4, -0.3], method="Nelder-Mead")
    e = np.exp(res.x - np.max(res.x))
    w = (e / e.sum()).tolist()
    ens_both = (w[0] * zb1 + w[1] * zb2 + w[2] * zb3).astype(np.float16)
    ens_raw = (w[0] * zr1 + w[1] * zr2 + w[2] * zr3).astype(np.float16)

    def eval_ens_on(idxs):
        ys = _rows_labels_75([samples[i] for i in idxs])
        mr = f4.compute_calibration_metrics(ens_raw[idxs], ys, CLASSES_75)
        mb = f4.compute_calibration_metrics(ens_both[idxs], ys, CLASSES_75)
        return {"raw_only": {k: v for k, v in mr.items() if k != "preds"}, "with_merchant": {k: v for k, v in mb.items() if k != "preds"}}

    eval_summary = {
        "weights": [round(x, 4) for x in w],
        "individual_temps": [T1, T2, T3],
        "v3_holdout": eval_ens_on(splits["v3_holdout_idx"]),
        "v4_test": eval_ens_on(splits["v4_test_idx"]),
        "v5_hard_test": eval_ens_on(splits["v5_hard_test_idx"]),
    }
    print(f"[Ensemble Blend w={eval_summary['weights']}] v4_test={eval_summary['v4_test']} v5_hard={eval_summary['v5_hard_test']}", flush=True)
    return ens_both, ens_raw, eval_summary


def build_ood_misc_split(n_train=800, n_cal=120, n_test=200, seed=20261123):
    import train_foundry_v4_local as l4
    all_ood = l4.build_ood_misc_rows(n=n_train + n_cal + n_test, seed=seed)
    return all_ood[:n_train], all_ood[n_train:n_train + n_cal], all_ood[n_train + n_cal:]


def train_stage1_and_oof(samples, splits, ens_both, ens_raw, ood_train):
    """Trains calibrated <1.88 MB Stage-1 sparse student and computes 5-fold OOF Stage-1 probs for Stage 2."""
    import numpy as np
    from scipy.sparse import vstack as sp_vstack
    from sklearn.svm import LinearSVC

    update_status("TRAINING_STAGE1_LINEAR", train_n=len(splits["train_idx"]))
    train_idx = splits["train_idx"]
    cal_idx = splits["cal_idx"]
    train_rows = [samples[i] for i in train_idx] + ood_train
    cal_rows = [samples[i] for i in cal_idx]

    # Sample-wise noise filtering weights using ensemble disagreement
    n_clean_tr = len(train_idx)
    ens_b_tr = ens_both[train_idx].astype(np.float32)
    ens_b_sh = ens_b_tr - ens_b_tr.max(axis=1, keepdims=True)
    ens_b_p = np.exp(ens_b_sh) / np.exp(ens_b_sh).sum(axis=1, keepdims=True)
    y_tr_clean = _rows_labels_75([samples[i] for i in train_idx])
    p_true = ens_b_p[np.arange(n_clean_tr), y_tr_clean]
    p_max = ens_b_p.max(axis=1)
    downweight_mask = (p_true < 0.05) & (p_max >= 0.85)
    n_downweighted = int(np.sum(downweight_mask))

    base_sw = np.array(
        [
            0.85 if r.get("edge_case") == "ood_misc" else (
                (0.25 if (i < n_clean_tr and downweight_mask[i]) else 1.0)
                * (1.8 if r["source_type"] == "thermal_receipt" else 1.0)
                * (1.5 if r.get("edge_case") == "seed_v3" else 1.0)
            )
            for i, r in enumerate(train_rows)
        ],
        dtype=np.float32,
    )
    sw_4x = np.concatenate([base_sw, base_sw, base_sw * 0.7, base_sw * 0.7])

    # Build zero-padded teacher soft targets aligned with first 2*len(train_rows) rows (ood gets uniform/hard only)
    ood_zeros = np.zeros((len(ood_train), 75), dtype=np.float16)
    tz_b = np.vstack([ens_both[train_idx], ood_zeros])
    tz_r = np.vstack([ens_raw[train_idx], ood_zeros])

    vb = f4.build_tfidf_vocab_and_matrix(train_rows, CLASSES_75, CLS2ID_75, teacher_z_both=tz_b, teacher_z_raw=tz_r, max_w_feats=16000, max_c_feats=14000)
    X_cal_r = f4.transform_with_vocab([r["raw_text"] for r in cal_rows], vb)
    X_cal_b = f4.transform_with_vocab([f"{r['expected_merchant']}\n{r['raw_text']}" for r in cal_rows], vb)
    y_cal = _rows_labels_75(cal_rows)

    def eval_cand(name, cfg, W_raw, b_raw):
        l_r = X_cal_r @ W_raw.T + b_raw
        l_b = X_cal_b @ W_raw.T + b_raw
        T = f4.fit_temperature(np.vstack([l_r, l_b]), np.concatenate([y_cal, y_cal]))
        m_r = f4.compute_calibration_metrics(l_r / T, y_cal, CLASSES_75)
        m_b = f4.compute_calibration_metrics(l_b / T, y_cal, CLASSES_75)
        sc = 0.5 * (m_r["subcat_macro_f1"] + m_b["subcat_macro_f1"])
        nll = 0.5 * (m_r["nll"] + m_b["nll"])
        print(f"[Stage1 {name}] cal_f1={sc:.4f} cal_nll={nll:.4f} T={T:.3f}", flush=True)
        return sc, -nll, W_raw, b_raw, cfg, name

    best = None
    # Candidate A: LinearSVC(C=0.30, balanced)
    svc = LinearSVC(C=0.30, dual="auto", class_weight="balanced", max_iter=2500, random_state=42)
    svc.fit(vb["X"], vb["y"], sample_weight=sw_4x)
    c1 = eval_cand("svc_bal_C0.30", {"kind": "svc", "C": 0.30}, svc.coef_.astype(np.float64), svc.intercept_.astype(np.float64))
    best = c1

    # Candidate B: GPU Soft-Label Distilled Sparse Linear (uses exact EmbeddingBag / linear on 30k dims)
    import torch
    import torch.nn as nn
    import torch.nn.functional as F

    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    N_all, D_s1 = vb["X"].shape
    n_teacher_valid = len(train_idx)

    def fit_distill_s1(alpha=0.50, lr=1.5e-2, wd=1.5e-4, epochs=16):
        layer = nn.Linear(D_s1, 75).to(device)
        nn.init.zeros_(layer.weight)
        nn.init.zeros_(layer.bias)
        opt = torch.optim.AdamW(layer.parameters(), lr=lr, weight_decay=wd)
        bs = 512
        sched = torch.optim.lr_scheduler.CosineAnnealingLR(opt, T_max=max(1, epochs * math.ceil(N_all / bs)))
        counts = Counter(vb["y"].tolist())
        cw = torch.tensor([min(3.5, math.sqrt((N_all / 75.0) / max(1, counts.get(i, 1)))) for i in range(75)], dtype=torch.float32, device=device)
        rng = np.random.default_rng(42)
        z_t = vb["teacher_logits"]
        sw_t = torch.from_numpy(sw_4x)
        n_base = len(train_rows)

        for _ in range(epochs):
            perm = rng.permutation(N_all)
            for st in range(0, N_all, bs):
                bi = perm[st:st + bs]
                xb = torch.from_numpy(vb["X"][bi].toarray()).to(device)
                yb = torch.from_numpy(vb["y"][bi]).to(device)
                logits = layer(xb)
                ce = (F.cross_entropy(logits, yb, weight=cw, reduction="none") * sw_t[bi].to(device)).mean()
                # Soft KL only on clean v4/v5 rows (positions < n_clean_tr in view 0, and n_base..n_base+n_clean_tr in view 1)
                soft_mask = ((bi < n_teacher_valid) | ((bi >= n_base) & (bi < n_base + n_teacher_valid)))
                if np.any(soft_mask):
                    s_idx = torch.from_numpy(soft_mask).to(device)
                    t_sub = torch.from_numpy(z_t[bi[soft_mask]]).to(device)
                    kl = F.kl_div(F.log_softmax(logits[s_idx] / 2.0, dim=-1), F.softmax(t_sub / 2.0, dim=-1), reduction="batchmean") * 4.0
                    loss = (1.0 - alpha) * ce + alpha * kl
                else:
                    loss = ce
                opt.zero_grad(set_to_none=True)
                loss.backward()
                opt.step()
                sched.step()
        W_np = layer.weight.detach().cpu().numpy().astype(np.float64)
        b_np = layer.bias.detach().cpu().numpy().astype(np.float64)
        del layer
        if torch.cuda.is_available():
            torch.cuda.empty_cache()
        return W_np, b_np

    for alpha in (0.45, 0.55):
        W_d, b_d = fit_distill_s1(alpha=alpha)
        c2 = eval_cand(f"distill_kl_a{alpha}", {"kind": "distill", "alpha": alpha}, W_d, b_d)
        if c2[:2] > best[:2]:
            best = c2

    _, _, W_best, b_best, winner_cfg, winner_name = best
    import train_foundry_v4_local as l4
    l4.f4 = f4
    hold_export = l4.adaptive_prune_calibrate(W_best, b_best, CLASSES_75, vb, X_cal_r, X_cal_b, y_cal, max_gz_bytes=MAX_STAGE1_GZ_BYTES)
    STAGE1_HOLDOUT_JSON.write_text(hold_export["raw_json"], encoding="utf-8")
    STAGE1_HOLDOUT_GZ.write_bytes(hold_export["gz_bytes"])
    assert len(hold_export["gz_bytes"]) < 2_097_152

    # Compute 5-fold OOF calibrated Stage-1 probabilities on train_rows for leak-free Stage-2 training
    rng_f = random.Random(9001)
    tr_grps = sorted({f4.normalize_merchant_group(r["expected_merchant"]) for r in train_rows})
    rng_f.shuffle(tr_grps)
    g2f = {g: k % 5 for k, g in enumerate(tr_grps)}
    row_folds = [g2f[f4.normalize_merchant_group(r["expected_merchant"])] for r in train_rows]

    N_r = len(train_rows)
    oof_probs = np.zeros((3, N_r, 75), dtype=np.float32)  # views: 0=both, 1=raw, 2=surf
    X_both_tr = f4.transform_with_vocab([f"{r['expected_merchant']}\n{r['raw_text']}" for r in train_rows], vb)
    X_raw_tr = f4.transform_with_vocab([r["raw_text"] for r in train_rows], vb)
    X_surf_tr = f4.transform_with_vocab([f"{infer_surface_merchant(r)}\n{r['raw_text']}" for r in train_rows], vb)

    T_s1 = float(hold_export["temperature"])
    for fold in range(5):
        fit_pos = [k for k, f in enumerate(row_folds) if f != fold]
        val_pos = [k for k, f in enumerate(row_folds) if f == fold]
        fit_4x = fit_pos + [N_r + k for k in fit_pos] + [2 * N_r + k for k in fit_pos] + [3 * N_r + k for k in fit_pos]
        if winner_cfg["kind"] == "svc":
            clf_f = LinearSVC(C=0.30, dual="auto", class_weight="balanced", max_iter=2000, random_state=42)
            clf_f.fit(vb["X"][fit_4x], vb["y"][fit_4x], sample_weight=sw_4x[fit_4x])
            Wf = clf_f.coef_.astype(np.float64)
            bf = clf_f.intercept_.astype(np.float64)
            T_f = T_s1
        else:
            evals_f, Wf, bf = _embedding_bag_linear_gpu(
                vb["X"][fit_4x], vb["y"][fit_4x], [X_both_tr[val_pos]], epochs=12, batch_size=512, lr=1.5e-2, wd=2e-4, sample_weights=sw_4x[fit_4x]
            )
            T_f = 1.0
        for vi, Xv in enumerate((X_both_tr, X_raw_tr, X_surf_tr)):
            logits_v = (Xv[val_pos] @ Wf.T + bf) / T_f
            sh = logits_v - logits_v.max(axis=1, keepdims=True)
            ex = np.exp(sh)
            oof_probs[vi, val_pos] = (ex / ex.sum(axis=1, keepdims=True)).astype(np.float32)

    return {
        "winner_name": winner_name,
        "winner_cfg": winner_cfg,
        "hold_export": hold_export,
        "vb": vb,
        "train_rows": train_rows,
        "sw_4x": sw_4x,
        "base_sw": base_sw,
        "oof_probs": oof_probs,
        "n_downweighted": n_downweighted,
        "X_tr_all": vb["X"],
        "tz_b": tz_b,
        "tz_r": tz_r,
    }


def infer_surface_merchant(row):
    """Simulates runtime PaymentParser extracted merchant (either gateway stub or first header line)."""
    raw = row["raw_text"].strip()
    m = re.search(r"(?:PAY|DNQR|TNGD|IPAY88|RAZERMS|MYDEBIT|SHOPEEPAY|FPX|GRAB|MAE|SP)\*([A-Za-z0-9 &/'-]{3,22})", raw.upper())
    if m:
        return m.group(1).strip()
    lines = [ln.strip() for ln in raw.splitlines() if ln.strip()]
    if row["source_type"] == "thermal_receipt" and lines:
        return lines[0][:40]
    return "Detected payment"


def stage1_predict_rows(s1_export, vb, rows_m_t):
    """Exact Stage-1 Python inference matching Java MlClassifier for generating [probs, prob, margin, signals]."""
    import numpy as np

    texts = [f"{m}\n{t}" for m, t in rows_m_t]
    X = f4.transform_with_vocab(texts, vb)
    W = s1_export["W_final"]
    b = s1_export["b_final"]
    logits = X @ W.T + b
    sh = logits - logits.max(axis=1, keepdims=True)
    ex = np.exp(sh)
    probs = (ex / ex.sum(axis=1, keepdims=True)).astype(np.float32)

    w_idx, c_idx = vb["w_idx"], vb["c_idx"]
    top_prob = probs.max(axis=1)
    part = np.partition(probs, -2, axis=1)
    margin = np.maximum(0.0, top_prob - part[:, -2])
    sig_counts = np.zeros(len(texts), dtype=np.int32)
    for i, txt in enumerate(texts):
        wc, cc = f4.featurize_counts(txt)
        sig_counts[i] = sum(1 for k in wc if k in w_idx) + sum(1 for k in cc if k in c_idx)
    return [
        {"probs": probs[i], "prob": float(top_prob[i]), "margin": float(margin[i]), "signals": int(sig_counts[i])}
        for i in range(len(texts))
    ]


def train_stage2_reranker(s1_bundle, samples, splits, ens_both, ens_raw, ood_train, ood_cal, s1_drop_rate: float = 0.25):
    """Trains SubwordResNet-Reranker with multi-teacher distillation + QAT and exports INT8 MLRR v1."""
    import numpy as np
    import torch
    import torch.nn as nn
    import torch.nn.functional as F

    update_status("TRAINING_STAGE2_RERANKER", hidden_dim=HIDDEN_DIM, buckets=NUM_BUCKETS, emb_dim=EMB_DIM)
    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    train_idx = splits["train_idx"]
    cal_idx = splits["cal_idx"]
    n_clean_tr = len(train_idx)

    # Assemble 3 views of train_rows: clean-merchant, raw-only, surface-merchant
    train_rows = s1_bundle["train_rows"]
    oof = s1_bundle["oof_probs"]
    tr_items = []
    tr_y = []
    tr_t_logits = []
    tr_has_t = []
    tr_sw = []

    y_tr_all = _rows_labels_75(train_rows)
    base_sw = s1_bundle["base_sw"]
    z_zero = np.zeros(75, dtype=np.float32)

    for view_i in range(3):
        for i, r in enumerate(train_rows):
            if view_i == 0:
                merch = r["expected_merchant"]
            elif view_i == 1:
                merch = ""
            else:
                merch = infer_surface_merchant(r)
            p_vec = oof[view_i, i]
            top_p = float(p_vec.max())
            p_sorted = np.partition(p_vec, -2)
            marg = float(max(0.0, top_p - p_sorted[-2]))
            tr_items.append((merch, r["raw_text"], {"probs": p_vec, "prob": top_p, "margin": marg, "signals": 12}))
            tr_y.append(int(y_tr_all[i]))
            tr_sw.append(float(base_sw[i]) * (1.0 if view_i < 2 else 0.85))
            if i < n_clean_tr:
                tr_t_logits.append((ens_both[train_idx[i]] if view_i == 0 else ens_raw[train_idx[i]]).astype(np.float32))
                tr_has_t.append(True)
            else:
                tr_t_logits.append(z_zero)
                tr_has_t.append(False)

    Xm_tr, Xt_tr, st_tr, s1p_tr = bags_to_csr_and_struct(tr_items)
    y_tr_np = np.array(tr_y, dtype=np.int64)
    y_cat_tr_np = np.array([CLS_TO_CAT_ID[y] for y in y_tr_np], dtype=np.int64)
    z_tr_np = np.vstack(tr_t_logits).astype(np.float32)
    has_t_np = np.array(tr_has_t, dtype=bool)
    sw_tr_np = np.array(tr_sw, dtype=np.float32)

    class SubwordResNet(nn.Module):
        def __init__(self):
            super().__init__()
            self.E = nn.EmbeddingBag(NUM_BUCKETS, EMB_DIM, mode="sum")
            nn.init.normal_(self.E.weight, mean=0.0, std=0.05)
            self.fc1 = nn.Linear(IN_DIM, HIDDEN_DIM)
            self.ln1 = nn.LayerNorm(HIDDEN_DIM, eps=1e-5)
            self.fc2 = nn.Linear(HIDDEN_DIM, HIDDEN_DIM)
            self.ln2 = nn.LayerNorm(HIDDEN_DIM, eps=1e-5)
            self.fc_out = nn.Linear(HIDDEN_DIM, 75)
            self.cat_aux = nn.Linear(HIDDEN_DIM, 16)
            self.drop = nn.Dropout(0.18)

        def fake_quant_w(self, w, qat: bool):
            if not qat:
                return w
            with torch.no_grad():
                sc = w.abs().amax(dim=1, keepdim=True).clamp(min=1e-8) / 127.0
                wq = torch.clamp(torch.round(w / sc), -127.0, 127.0) * sc
            return w + (wq - w).detach()

        def forward(self, m_idx, m_off, m_val, m_nnz, t_idx, t_off, t_val, t_nnz, st, s1p, qat: bool = False):
            Ew = self.fake_quant_w(self.E.weight, qat)
            em = F.embedding_bag(m_idx, Ew, m_off, mode="sum", per_sample_weights=m_val)
            em = torch.where(m_nnz, em / em.norm(dim=-1, keepdim=True).clamp(min=1e-6), torch.zeros_like(em))
            et = F.embedding_bag(t_idx, Ew, t_off, mode="sum", per_sample_weights=t_val)
            et = torch.where(t_nnz, et / et.norm(dim=-1, keepdim=True).clamp(min=1e-6), torch.zeros_like(et))
            x = torch.cat([em, et, em * et, st, s1p], dim=-1)

            w1 = self.fake_quant_w(self.fc1.weight, qat)
            h1 = F.linear(x, w1, self.fc1.bias)
            h1 = F.gelu(self.ln1(h1), approximate="tanh")
            w2 = self.fake_quant_w(self.fc2.weight, qat)
            h2_in = F.linear(self.drop(h1), w2, self.fc2.bias)
            h2 = h1 + F.gelu(self.ln2(h2_in), approximate="tanh")
            wo = self.fake_quant_w(self.fc_out.weight, qat)
            logits = F.linear(self.drop(h2), wo, self.fc_out.bias)
            return logits, self.cat_aux(h2)

    net = SubwordResNet().to(device)
    epochs = 24
    batch_size = 512
    opt = torch.optim.AdamW(net.parameters(), lr=2.5e-3, weight_decay=2e-4)
    sched = torch.optim.lr_scheduler.CosineAnnealingLR(opt, T_max=max(1, epochs * math.ceil(len(y_tr_np) / batch_size)))

    counts = Counter(y_tr_np.tolist())
    cw = torch.tensor([min(3.5, math.sqrt((len(y_tr_np) / 75.0) / max(1, counts.get(i, 1)))) for i in range(75)], dtype=torch.float32, device=device)

    cal_rows = [samples[i] for i in cal_idx]
    cal_y = _rows_labels_75(cal_rows)
    cal_m_t = [(r["expected_merchant"], r["raw_text"]) for r in cal_rows] + [("", r["raw_text"]) for r in cal_rows]
    cal_s1 = stage1_predict_rows(s1_bundle["hold_export"], s1_bundle["vb"], cal_m_t)
    cal_items = [(cal_m_t[k][0], cal_m_t[k][1], cal_s1[k]) for k in range(len(cal_m_t))]

    def csr_to_tensors(sub_csr):
        idx = torch.from_numpy(sub_csr.indices.astype(np.int64, copy=False)).to(device)
        off = torch.from_numpy(sub_csr.indptr[:-1].astype(np.int64, copy=False)).to(device)
        val = torch.from_numpy(sub_csr.data.astype(np.float32, copy=False)).to(device)
        nnz = torch.from_numpy((np.diff(sub_csr.indptr) > 0)[:, None]).to(device)
        return idx, off, val, nnz

    def export_weights_dict():
        sd = net.state_dict()
        return {
            "E": sd["E.weight"].detach().cpu().numpy(),
            "W1": sd["fc1.weight"].detach().cpu().numpy(),
            "b1": sd["fc1.bias"].detach().cpu().numpy(),
            "ln1_w": sd["ln1.weight"].detach().cpu().numpy(),
            "ln1_b": sd["ln1.bias"].detach().cpu().numpy(),
            "W2": sd["fc2.weight"].detach().cpu().numpy(),
            "b2": sd["fc2.bias"].detach().cpu().numpy(),
            "ln2_w": sd["ln2.weight"].detach().cpu().numpy(),
            "ln2_b": sd["ln2.bias"].detach().cpu().numpy(),
            "W_out": sd["fc_out.weight"].detach().cpu().numpy(),
            "b_out": sd["fc_out.bias"].detach().cpu().numpy(),
        }

    rng = np.random.default_rng(2026)
    best_score, best_state = -1.0, None
    t0 = time.perf_counter()

    for ep in range(1, epochs + 1):
        qat = ep > (epochs - 5)
        net.train()
        perm = rng.permutation(len(y_tr_np))
        for st in range(0, len(y_tr_np), batch_size):
            bi = perm[st:st + batch_size]
            mi, mo, mv, mn = csr_to_tensors(Xm_tr[bi])
            ti, to, tv, tn = csr_to_tensors(Xt_tr[bi])
            st_b = torch.from_numpy(st_tr[bi]).to(device)
            s1_b = torch.from_numpy(s1p_tr[bi]).to(device)
            # Stage-1 feature dropout for robustness when Stage-1 misses/disagrees
            drop_s1 = (torch.rand(len(bi), 1, device=device) < s1_drop_rate)
            s1_b = torch.where(drop_s1, torch.zeros_like(s1_b), s1_b)
            st_b[:, 12:16] = torch.where(drop_s1, torch.zeros_like(st_b[:, 12:16]), st_b[:, 12:16])

            logits, cat_logits = net(mi, mo, mv, mn, ti, to, tv, tn, st_b, s1_b, qat=qat)
            yb = torch.from_numpy(y_tr_np[bi]).to(device)
            ycb = torch.from_numpy(y_cat_tr_np[bi]).to(device)
            sw_b = torch.from_numpy(sw_tr_np[bi]).to(device)

            sub_ce = (F.cross_entropy(logits, yb, weight=cw, reduction="none") * sw_b).mean()
            cat_ce = (F.cross_entropy(cat_logits, ycb, reduction="none") * sw_b).mean()
            base_loss = 0.75 * sub_ce + 0.25 * cat_ce

            t_mask = has_t_np[bi]
            if np.any(t_mask):
                tm_t = torch.from_numpy(t_mask).to(device)
                tz = torch.from_numpy(z_tr_np[bi[t_mask]]).to(device)
                kl = F.kl_div(F.log_softmax(logits[tm_t] / 2.0, dim=-1), F.softmax(tz / 2.0, dim=-1), reduction="batchmean") * 4.0
                loss = 0.60 * base_loss + 0.40 * kl
            else:
                loss = base_loss

            opt.zero_grad(set_to_none=True)
            loss.backward()
            torch.nn.utils.clip_grad_norm_(net.parameters(), 2.0)
            opt.step()
            sched.step()

        if ep in (6, 12, 18, 20, 22, 24):
            gz_tmp = serialize_reranker_bin_gz(export_weights_dict(), CLASSES_75, 1.0, 0.35)
            md_tmp = load_reranker_bin_gz(gz_tmp)
            raw_cal_l, _ = reranker_int8_predict_batch(md_tmp, cal_items)
            y_comb_c = np.concatenate([cal_y, cal_y])
            T_c = f4.fit_temperature(raw_cal_l, y_comb_c)
            cm = f4.compute_calibration_metrics(raw_cal_l / T_c, y_comb_c, CLASSES_75)
            print(f"[Reranker Ep {ep}/{epochs} QAT={qat}] INT8 cal subcat={cm['subcat_acc']} macro_f1={cm['subcat_macro_f1']} nll={cm['nll']} bytes={len(gz_tmp)}", flush=True)
            if cm["subcat_macro_f1"] > best_score:
                best_score = cm["subcat_macro_f1"]
                best_state = export_weights_dict()

    if best_state is None:
        best_state = export_weights_dict()

    # Final temperature & OOD misc threshold calibration on INT8 model
    gz_pre = serialize_reranker_bin_gz(best_state, CLASSES_75, 1.0, 0.35)
    md_pre = load_reranker_bin_gz(gz_pre)
    raw_cal_l, _ = reranker_int8_predict_batch(md_pre, cal_items)
    T_nn = f4.fit_temperature(raw_cal_l, np.concatenate([cal_y, cal_y]))

    # Measure miscProbability on clean cal vs OOD cal to pick strict OOD gate
    sh_c = (raw_cal_l / T_nn) - (raw_cal_l / T_nn).max(axis=1, keepdims=True)
    p_c = np.exp(sh_c) / np.exp(sh_c).sum(axis=1, keepdims=True)
    misc_id = CLS2ID_75[MISC_PAIR]

    ood_m_t = [(r["expected_merchant"], r["raw_text"]) for r in ood_cal]
    ood_s1 = stage1_predict_rows(s1_bundle["hold_export"], s1_bundle["vb"], ood_m_t)
    ood_items = [(ood_m_t[k][0], ood_m_t[k][1], ood_s1[k]) for k in range(len(ood_m_t))]
    raw_ood_l, _ = reranker_int8_predict_batch(md_pre, ood_items)
    sh_o = (raw_ood_l / T_nn) - (raw_ood_l / T_nn).max(axis=1, keepdims=True)
    p_o = np.exp(sh_o) / np.exp(sh_o).sum(axis=1, keepdims=True)

    # Choose ood_misc_threshold keeping >= 99% clean cal pass while flagging maximum OOD
    clean_misc_q99 = float(np.quantile(p_c[:, misc_id], 0.99))
    ood_misc_th = round(min(0.45, max(0.18, clean_misc_q99 + 0.04)), 4)

    hold_gz = serialize_reranker_bin_gz(best_state, CLASSES_75, T_nn, ood_misc_th)
    RERANKER_HOLDOUT_GZ.write_bytes(hold_gz)
    assert len(hold_gz) < 2_097_152, f"Reranker exceeds 2MB: {len(hold_gz)}"

    del net
    if torch.cuda.is_available():
        torch.cuda.empty_cache()

    return {
        "hold_gz": hold_gz,
        "temperature": round(T_nn, 4),
        "ood_misc_threshold": ood_misc_th,
        "train_seconds": round(time.perf_counter() - t0, 1),
        "best_cal_macro_f1": round(best_score, 4),
        "weights": best_state,
    }


def evaluate_cascade(s1_export, vb, reranker_md, rows, extra_ood_rows=None):
    """Evaluates Stage 1 alone, Stage 2 INT8 standalone, and Full 2-Stage Cascade."""
    import numpy as np

    misc_id = CLS2ID_75[MISC_PAIR]

    def eval_view(with_merchant: bool):
        m_t = [((r["expected_merchant"] if with_merchant else ""), r["raw_text"]) for r in rows]
        s1_info = stage1_predict_rows(s1_export, vb, m_t)
        s1_probs = np.vstack([x["probs"] for x in s1_info])

        items = [(m_t[k][0], m_t[k][1], s1_info[k]) for k in range(len(rows))]
        _, nn_scaled = reranker_int8_predict_batch(reranker_md, items)
        sh = nn_scaled - nn_scaled.max(axis=1, keepdims=True)
        ex = np.exp(sh)
        nn_probs = ex / ex.sum(axis=1, keepdims=True)

        ys = _rows_labels_75(rows)
        s1_pred = s1_probs.argmax(axis=1)
        nn_pred = nn_probs.argmax(axis=1)

        # Cascade simulation matching Java fast-path + Tier-2 acceptance
        cas_pred = np.copy(s1_pred)
        t2_triggered = 0
        for i in range(len(rows)):
            sig = s1_info[i]["signals"]
            p1 = s1_info[i]["prob"]
            m1 = s1_info[i]["margin"]
            if sig >= 4 and p1 >= 0.72 and m1 >= 0.25 and s1_pred[i] != misc_id:
                continue
            t2_triggered += 1
            np_max = float(nn_probs[i].max())
            if nn_pred[i] != misc_id and nn_probs[i, misc_id] < reranker_md["ood_misc_threshold"] and np_max >= 0.22:
                cas_pred[i] = nn_pred[i]

        def metrics_for(pred_arr):
            p_str = [CLASSES_75[k] for k in pred_arr]
            t_str = [CLASSES_75[k] for k in ys]
            cat_ok = sum(1 for a, b in zip(p_str, t_str) if a.split("||")[0] == b.split("||")[0])
            sub_ok = sum(1 for a, b in zip(p_str, t_str) if a == b)
            return {"cat_acc": round(cat_ok / len(rows), 4), "subcat_acc": round(sub_ok / len(rows), 4)}

        by_edge = {}
        edge_idxs = defaultdict(list)
        for i, r in enumerate(rows):
            edge_idxs[r.get("edge_case", "unknown")].append(i)
        for ec, idxs in sorted(edge_idxs.items()):
            sub_y = [CLASSES_75[ys[k]] for k in idxs]
            sub_s1 = [CLASSES_75[s1_pred[k]] for k in idxs]
            sub_cas = [CLASSES_75[cas_pred[k]] for k in idxs]
            by_edge[ec] = {
                "n": len(idxs),
                "s1_cat": round(sum(1 for a, b in zip(sub_s1, sub_y) if a.split("||")[0] == b.split("||")[0]) / len(idxs), 4),
                "cas_cat": round(sum(1 for a, b in zip(sub_cas, sub_y) if a.split("||")[0] == b.split("||")[0]) / len(idxs), 4),
                "s1_sub": round(sum(1 for a, b in zip(sub_s1, sub_y) if a == b) / len(idxs), 4),
                "cas_sub": round(sum(1 for a, b in zip(sub_cas, sub_y) if a == b) / len(idxs), 4),
            }

        return {
            "n": len(rows),
            "stage1_only": metrics_for(s1_pred),
            "stage2_int8_only": metrics_for(nn_pred),
            "cascade": metrics_for(cas_pred),
            "tier2_trigger_rate": round(t2_triggered / max(1, len(rows)), 4),
            "by_edge": by_edge,
        }

    out = {"raw_only": eval_view(False), "with_merchant": eval_view(True)}
    if extra_ood_rows:
        m_t_o = [(r["expected_merchant"], r["raw_text"]) for r in extra_ood_rows]
        s1_o = stage1_predict_rows(s1_export, vb, m_t_o)
        _, nn_o_l = reranker_int8_predict_batch(reranker_md, [(m_t_o[k][0], m_t_o[k][1], s1_o[k]) for k in range(len(extra_ood_rows))])
        sh_o = nn_o_l - nn_o_l.max(axis=1, keepdims=True)
        p_o = np.exp(sh_o) / np.exp(sh_o).sum(axis=1, keepdims=True)
        abstained = 0
        for i in range(len(extra_ood_rows)):
            pred_c = int(p_o[i].argmax())
            # Match PaymentParser runtime gate (rules only first, here all OOD are synthetic unseen generics)
            is_misc = (pred_c == misc_id) or (p_o[i, misc_id] >= reranker_md["ood_misc_threshold"]) or (float(p_o[i].max()) < 0.25)
            if is_misc:
                abstained += 1
        out["ood_misc_test"] = {"n": len(extra_ood_rows), "abstention_rate": round(abstained / len(extra_ood_rows), 4)}
    return out


def run_self_test():
    """Fast local CPU parity & roundtrip check before launching Colab GPU."""
    import numpy as np

    assert fnv1a32("") == 0x811C9DC5
    h_test = fnv1a32("mw:klinik")
    assert 0 <= ((h_test & 0x7FFFFFFF) % NUM_BUCKETS) < NUM_BUCKETS

    mc, tc = extract_reranker_bags("Klinik Mediviron", "PAY*KLNKMDVR RM35.00 亚坤咖椰")
    assert "msk:klnk" in mc and "msk:mdvrn" in mc and "mz:亚" not in tc
    assert "tz:亚" in tc and "tz2:亚坤" in tc and "tsk:klnkmdvr" in tc

    st = extract_struct_features("PAY*KLNKMDVR", "DuitNow USD 10.00 -> RM 44.50\nA\nB\nC", 1.0, 0.8, 0.4, 15)
    assert st.shape == (16,) and st[0] == 1.0 and st[1] == 1.0 and st[3] == 1.0 and st[12] == 1.0

    rng = np.random.default_rng(7)
    w_dict = {
        "E": rng.normal(0, 0.1, size=(NUM_BUCKETS, EMB_DIM)).astype(np.float32),
        "W1": rng.normal(0, 0.1, size=(HIDDEN_DIM, IN_DIM)).astype(np.float32),
        "b1": np.zeros(HIDDEN_DIM, dtype=np.float32),
        "ln1_w": np.ones(HIDDEN_DIM, dtype=np.float32),
        "ln1_b": np.zeros(HIDDEN_DIM, dtype=np.float32),
        "W2": rng.normal(0, 0.1, size=(HIDDEN_DIM, HIDDEN_DIM)).astype(np.float32),
        "b2": np.zeros(HIDDEN_DIM, dtype=np.float32),
        "ln2_w": np.ones(HIDDEN_DIM, dtype=np.float32),
        "ln2_b": np.zeros(HIDDEN_DIM, dtype=np.float32),
        "W_out": rng.normal(0, 0.1, size=(75, HIDDEN_DIM)).astype(np.float32),
        "b_out": np.zeros(75, dtype=np.float32),
    }
    gz = serialize_reranker_bin_gz(w_dict, CLASSES_75, 1.15, 0.28)
    md = load_reranker_bin_gz(gz)
    assert md["classes"] == CLASSES_75 and abs(md["temperature"] - 1.15) < 1e-6

    dummy_s1 = {"probs": np.full(75, 1.0 / 75.0, dtype=np.float32), "prob": 0.25, "margin": 0.10, "signals": 8}
    raw_l, scaled_l = reranker_int8_predict_batch(
        md,
        [("Klinik Mediviron", "PAY*KLNKMDVR RM35.00", dummy_s1), ("", "TNGD*EZBKXPRSS RM 25.00", None), ("", "", None)],
    )
    assert raw_l.shape == (3, 75) and np.allclose(raw_l / 1.15, scaled_l, atol=1e-5)

    local_v4 = Path(__file__).resolve().parent / "outputs_v4" / "dataset_malaysian_v4.jsonl.gz"
    local_h3 = Path(__file__).resolve().parent / "outputs_v3" / "holdout_v3_details.json"
    local_h4 = Path(__file__).resolve().parent / "outputs_v4" / "holdout_v4_details.json"
    if local_v4.exists() and local_h3.exists() and local_h4.exists():
        global HOLDOUT_V3_PATH, HOLDOUT_V4_PATH
        HOLDOUT_V3_PATH = local_h3
        HOLDOUT_V4_PATH = local_h4
        v4_rows = read_jsonl_any(local_v4)
        v3_h = json.loads(local_h3.read_text(encoding="utf-8"))
        v4_h = json.loads(local_h4.read_text(encoding="utf-8"))
        sp = build_v5_splits(v4_rows, v3_h, v4_h)
        assert len(sp["v3_holdout_idx"]) == 384 and len(sp["v4_test_idx"]) == 5938
        print(f"LOCAL V4 SPLIT VERIFIED: train={len(sp['train_idx'])} cal={len(sp['cal_idx'])} v4_test={len(sp['v4_test_idx'])} v3={len(sp['v3_holdout_idx'])}")

    print(f"FOUNDRY V5 SELF-TEST PASS: IN_DIM={IN_DIM}, dummy_gz_bytes={len(gz)}, parity/quantization verified.")


def refit_production_models(samples, splits, ens_both, ens_raw, stage1_cfg, ood_rows, hold_eval_report):
    """Refits final production assets on ALL v4 + v5 + OOD rows with calibrated temperature."""
    import numpy as np
    from sklearn.svm import LinearSVC

    update_status("TRAINING_FULL_PROD_ASSETS", total_n=len(samples) + len(ood_rows))
    prod_rows = list(samples) + list(ood_rows)
    cal_rows = [samples[i] for i in splits["cal_idx"]]
    y_cal = _rows_labels_75(cal_rows)

    # Full Stage 1
    n_s = len(samples)
    base_sw = np.array(
        [0.85 if r.get("edge_case") == "ood_misc" else (1.8 if r["source_type"] == "thermal_receipt" else 1.0) for r in prod_rows],
        dtype=np.float32,
    )
    sw_4x = np.concatenate([base_sw, base_sw, base_sw * 0.7, base_sw * 0.7])
    tz_b = np.vstack([ens_both, np.zeros((len(ood_rows), 75), dtype=np.float16)])
    tz_r = np.vstack([ens_raw, np.zeros((len(ood_rows), 75), dtype=np.float16)])
    vb_full = f4.build_tfidf_vocab_and_matrix(prod_rows, CLASSES_75, CLS2ID_75, teacher_z_both=tz_b, teacher_z_raw=tz_r, max_w_feats=16000, max_c_feats=14000)

    if stage1_cfg["winner_cfg"]["kind"] == "svc":
        svc_f = LinearSVC(C=0.30, dual="auto", class_weight="balanced", max_iter=2500, random_state=42)
        svc_f.fit(vb_full["X"], vb_full["y"], sample_weight=sw_4x)
        W_p, b_p = svc_f.coef_.astype(np.float64), svc_f.intercept_.astype(np.float64)
    else:
        _, W_p, b_p = _embedding_bag_linear_gpu(vb_full["X"], vb_full["y"], [], epochs=16, batch_size=512, lr=1.5e-2, wd=2e-4, sample_weights=sw_4x)

    import train_foundry_v4_local as l4
    l4.f4 = f4
    X_c_r = f4.transform_with_vocab([r["raw_text"] for r in cal_rows], vb_full)
    X_c_b = f4.transform_with_vocab([f"{r['expected_merchant']}\n{r['raw_text']}" for r in cal_rows], vb_full)
    prod_s1 = l4.adaptive_prune_calibrate(
        W_p, b_p, CLASSES_75, vb_full, X_c_r, X_c_b, y_cal,
        max_gz_bytes=MAX_STAGE1_GZ_BYTES, fixed_temperature=stage1_cfg["hold_export"]["temperature"],
    )
    STAGE1_FULL_JSON.write_text(prod_s1["raw_json"], encoding="utf-8")
    STAGE1_FULL_GZ.write_bytes(prod_s1["gz_bytes"])
    assert len(prod_s1["gz_bytes"]) < 2_097_152

    # Full Stage 2 using full Stage-1 predictions + OOD-dropout
    # Create clean 5-fold OOF or direct Stage-1 features across prod_rows using holdout-compatible bundle
    import copy
    full_splits = dict(splits)
    full_splits["train_idx"] = list(range(len(samples)))
    prod_s1_bundle = {
        "winner_name": stage1_cfg["winner_name"],
        "winner_cfg": stage1_cfg["winner_cfg"],
        "hold_export": prod_s1,
        "vb": vb_full,
        "train_rows": prod_rows,
        "sw_4x": sw_4x,
        "base_sw": base_sw,
        "oof_probs": np.stack([
            np.vstack([x["probs"] for x in stage1_predict_rows(prod_s1, vb_full, [(r["expected_merchant"], r["raw_text"]) for r in prod_rows])]),
            np.vstack([x["probs"] for x in stage1_predict_rows(prod_s1, vb_full, [("", r["raw_text"]) for r in prod_rows])]),
            np.vstack([x["probs"] for x in stage1_predict_rows(prod_s1, vb_full, [(infer_surface_merchant(r), r["raw_text"]) for r in prod_rows])]),
        ], axis=0),
    }
    prod_s2 = train_stage2_reranker(prod_s1_bundle, prod_rows, full_splits, ens_both, ens_raw, [], ood_rows[:120], s1_drop_rate=0.35)
    # Re-serialize with calibrated production temperature from holdout Stage 2
    final_reranker_gz = serialize_reranker_bin_gz(
        prod_s2["weights"],
        CLASSES_75,
        hold_eval_report["stage2_temperature"],
        hold_eval_report["ood_misc_threshold"],
    )
    RERANKER_FULL_GZ.write_bytes(final_reranker_gz)
    assert len(final_reranker_gz) < 2_097_152
    return prod_s1, len(final_reranker_gz)


def main():
    import numpy as np

    parser = argparse.ArgumentParser()
    parser.add_argument("--self-test", action="store_true", help="Run fast local parity & binary roundtrip self-test")
    parser.add_argument("--train-only", action="store_true", help="Skip vLLM synthesis and train teachers + students from dataset_malaysian_v5.jsonl")
    parser.add_argument("--distill-only", action="store_true", help="Skip vLLM and HF teacher training; load teacher_logits_v5.npz")
    args = parser.parse_args()

    if args.self_test:
        run_self_test()
        return

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    t_all = time.perf_counter()
    try:
        ensure_deps(train_only=args.train_only, distill_only=args.distill_only)
        v3_h = json.loads(HOLDOUT_V3_PATH.read_text(encoding="utf-8"))
        v4_h = json.loads(HOLDOUT_V4_PATH.read_text(encoding="utf-8"))

        if (args.train_only or args.distill_only) and (DATASET_PATH.exists() or DATASET_GZ_PATH.exists()):
            src_ds = DATASET_PATH if DATASET_PATH.exists() else DATASET_GZ_PATH
            samples = read_jsonl_any(src_ds)
            gen_meta = {"skipped_vllm": True, "total_samples": len(samples)}
        else:
            gen_meta = run_v5_synthesis()
            samples = read_jsonl_any(DATASET_PATH)

        splits = build_v5_splits(samples, v3_h, v4_h)
        print(
            f"v5 Zero-Leakage Splits: total={len(samples)} train={len(splits['train_idx'])} "
            f"(v5_tr={splits['v5_train_n']}) cal={len(splits['cal_idx'])} (v5_cal={splits['v5_cal_n']}) "
            f"v3={len(splits['v3_holdout_idx'])} v4={len(splits['v4_test_idx'])} v5_hard={len(splits['v5_hard_test_idx'])}",
            flush=True,
        )

        # Save v5_hard_test.json + ood_misc_test.json cleanly
        v5_test_rows = [samples[i] for i in splits["v5_hard_test_idx"]]
        V5_HARD_TEST_PATH.write_text(json.dumps(v5_test_rows, indent=2, ensure_ascii=False), encoding="utf-8")
        ood_train, ood_cal, ood_test = build_ood_misc_split(n_train=800, n_cal=120, n_test=200, seed=20261123)
        OOD_MISC_TEST_PATH.write_text(json.dumps(ood_test, indent=2, ensure_ascii=False), encoding="utf-8")

        if args.distill_only and TEACHER_CACHE_PATH.exists():
            npz = np.load(TEACHER_CACHE_PATH)
            ens_both, ens_raw = npz["ens_both"], npz["ens_raw"]
            ens_summary = json.loads(npz["ens_summary_json"].item())
            teacher_meta = {"loaded_from_cache": True, "ensemble": ens_summary}
        else:
            import gc
            import torch
            cc_major = torch.cuda.get_device_capability(0)[0] if torch.cuda.is_available() else 0
            if cc_major >= 8:
                try:
                    t1 = train_hf_teacher("microsoft/mdeberta-v3-base", samples, splits, epochs=4, max_len=176)
                except RuntimeError as e:
                    print(f"[Fallback] mdeberta-v3-base failed ({e}); switching Teacher 1 to intfloat/multilingual-e5-base", flush=True)
                    gc.collect()
                    if torch.cuda.is_available():
                        torch.cuda.empty_cache()
                    t1 = train_hf_teacher("intfloat/multilingual-e5-base", samples, splits, epochs=4, max_len=176)
            else:
                t1 = train_hf_teacher("intfloat/multilingual-e5-base", samples, splits, epochs=4, max_len=176)
            t2 = train_hf_teacher("xlm-roberta-base", samples, splits, epochs=4, max_len=176)
            t3 = train_oof_highdim_teacher(samples, splits)
            ens_both, ens_raw, ens_summary = blend_teacher_logits(t1, t2, t3, samples, splits)
            np.savez_compressed(
                TEACHER_CACHE_PATH,
                ens_both=ens_both,
                ens_raw=ens_raw,
                ens_summary_json=np.array(json.dumps(ens_summary)),
            )
            teacher_meta = {
                "teacher1": {k: v for k, v in t1.items() if k not in ("z_both", "z_raw")},
                "teacher2": {k: v for k, v in t2.items() if k not in ("z_both", "z_raw")},
                "teacher3": {k: v for k, v in t3.items() if k not in ("z_both", "z_raw")},
                "ensemble": ens_summary,
            }

        s1_bundle = train_stage1_and_oof(samples, splits, ens_both, ens_raw, ood_train)
        s2_bundle = train_stage2_reranker(s1_bundle, samples, splits, ens_both, ens_raw, ood_train, ood_cal)
        reranker_md_hold = load_reranker_bin_gz(s2_bundle["hold_gz"])

        v3_rows = [samples[i] for i in splits["v3_holdout_idx"]]
        v4_rows = [samples[i] for i in splits["v4_test_idx"]]
        holdout_eval = {
            "stage1_temperature": s1_bundle["hold_export"]["temperature"],
            "stage2_temperature": s2_bundle["temperature"],
            "ood_misc_threshold": s2_bundle["ood_misc_threshold"],
            "v3_holdout": evaluate_cascade(s1_bundle["hold_export"], s1_bundle["vb"], reranker_md_hold, v3_rows),
            "v4_test": evaluate_cascade(s1_bundle["hold_export"], s1_bundle["vb"], reranker_md_hold, v4_rows, extra_ood_rows=ood_test),
            "v5_hard_test": evaluate_cascade(s1_bundle["hold_export"], s1_bundle["vb"], reranker_md_hold, v5_test_rows),
        }

        prod_s1, prod_s2_gz_bytes = refit_production_models(samples, splits, ens_both, ens_raw, s1_bundle, ood_train + ood_cal + ood_test, holdout_eval)

        report = {
            "total_seconds": round(time.perf_counter() - t_all, 1),
            "generation": gen_meta,
            "splits": {k: (len(v) if isinstance(v, list) else v) for k, v in splits.items()},
            "teachers": teacher_meta,
            "stage1_student": {
                "winner": s1_bundle["winner_name"],
                "n_downweighted": s1_bundle["n_downweighted"],
                "holdout_gz_bytes": len(s1_bundle["hold_export"]["gz_bytes"]),
                "holdout_active_features": s1_bundle["hold_export"]["active_features"],
                "prod_gz_bytes": len(prod_s1["gz_bytes"]),
                "prod_active_features": prod_s1["active_features"],
            },
            "stage2_reranker": {
                "holdout_gz_bytes": len(s2_bundle["hold_gz"]),
                "prod_gz_bytes": prod_s2_gz_bytes,
                "temperature": s2_bundle["temperature"],
                "ood_misc_threshold": s2_bundle["ood_misc_threshold"],
                "best_cal_macro_f1": s2_bundle["best_cal_macro_f1"],
            },
            "zero_leakage_eval": holdout_eval,
        }
        REPORT_PATH.write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")
        update_status(
            "DONE",
            total_samples=len(samples),
            s1_gz_bytes=len(prod_s1["gz_bytes"]),
            s2_gz_bytes=prod_s2_gz_bytes,
            v4_raw_cas_cat=holdout_eval["v4_test"]["raw_only"]["cascade"]["cat_acc"],
            v4_both_cas_cat=holdout_eval["v4_test"]["with_merchant"]["cascade"]["cat_acc"],
        )
    except Exception as exc:
        import traceback
        tb = traceback.format_exc()
        print(tb, flush=True)
        update_status("ERROR", error=str(exc), traceback=tb)
        sys.exit(1)


if __name__ == "__main__":
    main()
