#!/usr/bin/env python3
"""MoneyLens Foundry v6 (Colab A100) — Qwen2.5-14B Synthesis + bge-m3/e5-large Ensemble + Binary MLCL v1 + QAT MLRR."""
import argparse
import gc
import gzip
import json
import math
import os
import random
import re
import struct
import sys
import time
from collections import Counter, defaultdict
from pathlib import Path

import numpy as np

OUT_DIR = Path("/content/outputs_v6")
STATUS_PATH = OUT_DIR / "foundry_v6_status.json"
V5_DATASET_GZ = Path("/content/dataset_malaysian_v5.jsonl.gz")
HOLDOUT_V3_PATH = Path("/content/holdout_v3.json")
HOLDOUT_V4_PATH = Path("/content/holdout_v4.json")
V5_HARD_TEST_PATH = Path("/content/v5_hard_test.json")
OOD_MISC_TEST_PATH = Path("/content/ood_misc_test.json")
TEACHER_CACHE_PATH = OUT_DIR / "teacher_logits_v6.npz"

S1_HOLD_BIN_GZ = OUT_DIR / "moneylens_classifier_holdout.bin.gz"
S1_PROD_BIN_GZ = OUT_DIR / "moneylens_classifier.bin.gz"
S2_HOLD_BIN_GZ = OUT_DIR / "moneylens_reranker_holdout.bin.gz"
S2_PROD_BIN_GZ = OUT_DIR / "moneylens_reranker.bin.gz"
PARITY_PATH = OUT_DIR / "foundry_v6_parity_vectors.json"
REPORT_PATH = OUT_DIR / "foundry_v6_report.json"

MAX_MLCL_GZ_BYTES = 980_000
MAX_MLRR_GZ_BYTES = 1_380_000

sys.path.insert(0, "/content")
import run_foundry_v4 as f4  # noqa: E402
import run_foundry_v5 as f5  # noqa: E402

CLASSES_75 = f5.CLASSES_75
CLS2ID_75 = f5.CLS2ID_75
FORBIDDEN_SCRIPT_RE = re.compile(
    r"[\u0400-\u04FF\u0600-\u06FF\u0900-\u097F\u0E00-\u0E7F\u3040-\u30FF\uAC00-\uD7AF\u1100-\u11FF]"
)


def dedup_key(raw_text: str) -> str:
    return re.sub(r"\s+", " ", (raw_text or "").strip().lower())[:180]


def update_status(stage: str, **extra):
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    payload = {"stage": stage, "ts": int(time.time()), **extra}
    STATUS_PATH.write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")


def _pack_utf8(s: str) -> bytes:
    b = s.encode("utf-8")
    return struct.pack(">H", len(b)) + b


def serialize_mlcl_bin_gz(W_f32: np.ndarray, b_f32: np.ndarray, classes: list[str], vb: dict, extra_prune_th: float = 0.0):
    """Packs sparse LinearSVC/linear classifier into binary MLCL v1 (.bin.gz) matching MlClassifier.java."""
    w_vocab, c_vocab = vb["w_vocab"], vb["c_vocab"]
    feat_names = w_vocab + c_vocab
    idf = np.concatenate([np.asarray(vb["w_idf"], dtype=np.float32), np.asarray(vb["c_idf"], dtype=np.float32)])
    word_count = len(w_vocab)
    num_classes = len(classes)

    W_work = W_f32.astype(np.float32, copy=True)
    if extra_prune_th > 0.0:
        W_work[np.abs(W_work) < extra_prune_th] = 0.0

    encoded_feats = []
    nnz_total = 0
    w_scales_list = []
    w_q_dict = {}  # feat_idx -> (scale_f32, [(cls_i, q_i8), ...])

    for f_idx, name in enumerate(feat_names):
        col = W_work[:, f_idx]
        nz = np.flatnonzero(col)
        if nz.size == 0:
            continue
        max_abs = float(np.max(np.abs(col[nz])))
        if max_abs <= 1e-7:
            continue
        scale = np.float32(max_abs / 127.0)
        q_vals = np.clip(np.round(col[nz] / float(scale)), -127, 127).astype(np.int8)
        keep_mask = q_vals != 0
        if not np.any(keep_mask):
            continue
        cls_keep = nz[keep_mask]
        q_keep = q_vals[keep_mask]
        block = 0 if f_idx < word_count else 1
        key_bytes = name.encode("utf-8")
        if len(key_bytes) > 65000:
            continue
        rec = bytearray()
        rec.append(block)
        rec += struct.pack(">H", len(key_bytes))
        rec += key_bytes
        rec += struct.pack(">ffH", float(idf[f_idx]), float(scale), int(len(cls_keep)))
        for c_i, q_v in zip(cls_keep.tolist(), q_keep.tolist()):
            rec += struct.pack(">Bb", int(c_i), int(q_v))
        encoded_feats.append(bytes(rec))
        nnz_total += int(len(cls_keep))
        w_q_dict[f_idx] = (float(scale), [(int(c_i), int(q_v)) for c_i, q_v in zip(cls_keep.tolist(), q_keep.tolist())])

    buf = bytearray()
    buf += b"MLCL"
    buf += struct.pack(">ii", 1, num_classes)
    for c in classes:
        buf += _pack_utf8(c)
    buf += struct.pack(f">{num_classes}f", *[float(np.float32(x)) for x in b_f32.tolist()])
    buf += struct.pack(">i", len(encoded_feats))
    for rec in encoded_feats:
        buf += rec

    gz_bytes = gzip.compress(bytes(buf), compresslevel=9)
    return {
        "raw_bytes": len(buf),
        "gz_bytes": gz_bytes,
        "active_features": len(encoded_feats),
        "nonzero_weights": nnz_total,
        "w_q_dict": w_q_dict,
        "intercepts_f32": b_f32.astype(np.float32),
    }


def mlcl_predict_parity(mlcl_dict: dict, vb: dict, merchant: str, text: str, classes: list[str]):
    """Exact pure-Python mirror of MlClassifier.java prediction using quantized MLCL weights."""
    combined = f"{merchant or ''}\n{text or ''}"
    words = f4.java_parity_words(combined)
    if not words:
        return None
    w_lookup = vb["w_idx"]
    c_lookup = vb["c_idx"]
    word_count = len(vb["w_vocab"])
    idf = np.concatenate([np.asarray(vb["w_idf"], dtype=np.float32), np.asarray(vb["c_idf"], dtype=np.float32)])
    w_q = mlcl_dict["w_q_dict"]

    w_counts = {}
    for i, w in enumerate(words):
        kw = f"w:{w}"
        gi = w_lookup.get(kw)
        if gi is not None and gi in w_q:
            w_counts[gi] = w_counts.get(gi, 0) + 1
        if i + 1 < len(words):
            kbi = f"w:{w} {words[i + 1]}"
            gi2 = w_lookup.get(kbi)
            if gi2 is not None and gi2 in w_q:
                w_counts[gi2] = w_counts.get(gi2, 0) + 1

    c_counts = {}
    for w in words:
        padded = f" {w} "
        L = len(padded)
        for n in (3, 4, 5):
            if L < n:
                break
            for st in range(0, L - n + 1):
                kc = f"c:{padded[st : st + n]}"
                li = c_lookup.get(kc)
                if li is not None:
                    gi = word_count + li
                    if gi in w_q:
                        c_counts[gi] = c_counts.get(gi, 0) + 1

    if not w_counts and not c_counts:
        return None

    logits = mlcl_dict["intercepts_f32"].astype(np.float64).copy()
    for cnt_map in (w_counts, c_counts):
        if not cnt_map:
            continue
        vals = []
        norm_sq = 0.0
        for f_idx, cnt in cnt_map.items():
            val = (1.0 + math.log(cnt)) * float(np.float32(idf[f_idx]))
            vals.append((f_idx, val))
            norm_sq += val * val
        if norm_sq <= 0.0:
            continue
        inv_norm = 1.0 / math.sqrt(norm_sq)
        for f_idx, val in vals:
            sc = val * inv_norm
            scale, pairs = w_q[f_idx]
            for cls_i, qv in pairs:
                logits[cls_i] += sc * float(np.float32(qv * scale))

    m_l = float(np.max(logits))
    exps = np.exp(logits - m_l)
    probs = exps / np.sum(exps)
    return logits, probs


def adaptive_mlcl_calibrate(W_in, b_in, classes, vb, X_cal_r, X_cal_b, y_cal, max_gz_bytes=MAX_MLCL_GZ_BYTES, fixed_temperature=None):
    """Finds smallest prune threshold keeping MLCL v1 gz < max_gz_bytes, then calibrates temperature on Cal."""
    nz_abs = np.abs(W_in[W_in != 0])
    hi = max(0.20, float(np.quantile(nz_abs, 0.90))) if nz_abs.size else 0.1
    lo = 0.0
    best_th = hi
    # Fast binary search using full quantization size check
    for _ in range(9):
        mid = round((lo + hi) / 2.0, 4)
        cand = serialize_mlcl_bin_gz(W_in, b_in, classes, vb, extra_prune_th=mid)
        if len(cand["gz_bytes"]) <= max_gz_bytes:
            best_th = mid
            hi = mid
        else:
            lo = mid

    Wp = W_in.copy()
    if best_th > 0.0:
        Wp[np.abs(Wp) < best_th] = 0.0
    l_comb = np.vstack([X_cal_r @ Wp.T + b_in, X_cal_b @ Wp.T + b_in])
    y_comb = np.concatenate([y_cal, y_cal])
    pre_cal = f4.compute_calibration_metrics(l_comb, y_comb, classes)
    T_post = float(fixed_temperature) if fixed_temperature is not None else f4.fit_temperature(l_comb, y_comb)
    post_cal = f4.compute_calibration_metrics(l_comb / T_post, y_comb, classes)

    export = serialize_mlcl_bin_gz(Wp / T_post, b_in / T_post, classes, vb, extra_prune_th=0.0)
    if len(export["gz_bytes"]) > max_gz_bytes:
        th2 = max(best_th, 0.005)
        while len(export["gz_bytes"]) > max_gz_bytes:
            th2 = round(th2 * 1.15 + 0.002, 4)
            export = serialize_mlcl_bin_gz(W_in / T_post, b_in / T_post, classes, vb, extra_prune_th=th2 / T_post)
        best_th = th2
    export["temperature"] = round(T_post, 4)
    export["prune_threshold"] = best_th
    export["cal_metrics"] = {k: v for k, v in post_cal.items() if k != "preds"}
    export["pre_cal"] = {k: v for k, v in pre_cal.items() if k != "preds"}
    return export


def mlcl_predict_rows_batch(mlcl_dict, vb, rows_m_t, classes):
    n = len(rows_m_t)
    w_q = mlcl_dict["w_q_dict"]
    w_idx, c_idx = vb["w_idx"], vb["c_idx"]
    w_count = len(vb["w_vocab"])
    n_feats = w_count + len(vb["c_vocab"])
    # Build pruned W matrix aligned with vb feat order for fast vectorized scoring matching Java logic
    W_eff = np.zeros((len(classes), n_feats), dtype=np.float32)
    for f_idx, (scale, pairs) in w_q.items():
        for cls_i, qv in pairs:
            W_eff[cls_i, f_idx] = np.float32(qv * scale)
    b_eff = mlcl_dict["intercepts_f32"].astype(np.float64)
    texts = [f"{m}\n{t}" if m else t for m, t in rows_m_t]
    X_m = f4.transform_with_vocab(texts, vb)
    logits = (X_m @ W_eff.T) + b_eff
    sh = logits - logits.max(axis=1, keepdims=True)
    exps = np.exp(sh)
    probs = exps / exps.sum(axis=1, keepdims=True)
    outs = []
    for i in range(n):
        wc, cc = f4.featurize_counts(texts[i])
        sig_c = sum(1 for k in wc if w_idx.get(k) in w_q) + sum(1 for k in cc if k in c_idx and (w_count + c_idx[k]) in w_q)
        p = probs[i]
        top_p = float(p.max())
        s2 = float(np.partition(p, -2)[-2])
        outs.append({"probs": p, "prob": top_p, "margin": max(0.0, top_p - s2), "signals": sig_c})
    return outs


def synthesize_v6_qwen_rows(forbidden_keys: set, seen_texts: set):
    """Synthesizes ~12,000 targeted hard training examples on A100 using Qwen2.5-14B-Instruct (bf16)."""
    import gc
    import torch
    from transformers import AutoModelForCausalLM, AutoTokenizer

    # Target the 4 weakest slices from v5_hard_test (excluding test/holdout merchants cleanly!)
    slice_specs = [
        ("v6_multilingual_bm_cjk", "in natural Bahasa Melayu, colloquial Manglish, or Simplified Chinese bank/e-wallet push alerts (avoiding generic placeholder words)"),
        ("v6_disambiguation_lhdn", "focusing on tricky disambiguations: PTPTN SSPN vs PTPTN Study Loan, EPF i-Saraan vs PRS, Gym vs Sports retail, Health Checkup vs Clinic, Books vs Cafe, Takaful Medical Card vs Motor Insurance"),
        ("v6_digital_bnpl_fx", "via SPayLater, Atome, Boost PayFlex, GrabPayLater, DuitNow QR, JomPAY, FPX, GXBank, AEON Bank, or overseas foreign-currency MYR settlement alerts"),
        ("v6_truncated_gateway", "formatted with truncated 18-char uppercase payment-gateway merchant strings like PAY*..., DNQR*..., IPAY88*..., MYDEBIT*..., RAZERMS*..., or TNGD*... with branch suffixes"),
    ]

    vram_gb = (torch.cuda.get_device_properties(0).total_memory / 1e9) if torch.cuda.is_available() else 0.0
    cands = ("Qwen/Qwen2.5-14B-Instruct", "Qwen/Qwen2.5-7B-Instruct") if vram_gb >= 36.0 else ("Qwen/Qwen2.5-7B-Instruct", "Qwen/Qwen2.5-3B-Instruct")
    mdl, tok, mdl_id = None, None, None
    for cand in cands:
        try:
            update_status("V6_LLM_LOAD", model=cand, vram_gb=round(vram_gb, 1))
            print(f"Loading {cand} (VRAM={vram_gb:.1f}GB)...", flush=True)
            tok = AutoTokenizer.from_pretrained(cand, padding_side="left")
            if tok.pad_token is None:
                tok.pad_token = tok.eos_token
            dtype = torch.bfloat16 if torch.cuda.is_bf16_supported() else torch.float16
            mdl = AutoModelForCausalLM.from_pretrained(cand, torch_dtype=dtype, device_map="cuda:0" if torch.cuda.is_available() else "auto")
            mdl.eval()
            mdl_id = cand
            print(f"Loaded {cand} successfully", flush=True)
            break
        except Exception as exc:
            print(f"Could not load {cand}: {exc}", flush=True)
            tok, mdl = None, None
            gc.collect()
            torch.cuda.empty_cache()

    if mdl is None:
        return [], "none"

    rng = random.Random(20261127)
    # Ensure balanced coverage across all 74 non-Misc pairs + LHDN/BNPL pairs
    non_misc_pairs = [p for p in CLASSES_75 if p != f5.MISC_PAIR]
    jobs = []
    # ~14,800 raw prompts -> ~11,000–13,000 filtered unique samples
    for rep in range(5):
        for ec, style_desc in slice_specs:
            for pair in non_misc_pairs:
                cat, sub = pair.split("||", 1)
                amt = f"RM {rng.uniform(4.5, 480.0):.2f}"
                src_type = "receipt" if rng.random() < 0.15 else "notification"
                prompt = (
                    f"Generate 3 DISTINCT realistic Malaysian {'thermal receipt snippets (3 short lines each)' if src_type=='receipt' else 'single-line payment notifications'} "
                    f"for Category='{cat}', Subcategory='{sub}', around '{amt}', {style_desc}.\n"
                    f"Invent realistic distinct Malaysian merchant names belonging ONLY to '{sub}'. Do NOT include category labels or explanations.\n"
                    f"Output strictly 3 blocks formatted:\nTEXT: <text 1>\nMERCHANT: <name 1>\nTEXT: <text 2>\nMERCHANT: <name 2>\nTEXT: <text 3>\nMERCHANT: <name 3>"
                )
                jobs.append((cat, sub, ec, amt, "thermal_receipt" if src_type == "receipt" else "bank_notification", prompt))
        # Double weight on hardest edge slices
        for ec in ("v6_multilingual_bm_cjk", "v6_truncated_gateway", "v6_disambiguation_lhdn"):
            style_desc = dict(slice_specs)[ec]
            for pair in non_misc_pairs:
                cat, sub = pair.split("||", 1)
                amt = f"RM {rng.uniform(5.0, 350.0):.2f}"
                prompt = (
                    f"Write 3 DISTINCT realistic Malaysian bank or e-wallet notifications for '{sub}' ({cat}), around '{amt}', {style_desc}.\n"
                    f"Output strictly 3 blocks:\nTEXT: <notification 1>\nMERCHANT: <merchant 1>\nTEXT: <notification 2>\nMERCHANT: <merchant 2>\nTEXT: <notification 3>\nMERCHANT: <merchant 3>"
                )
                jobs.append((cat, sub, ec, amt, "bank_notification", prompt))

    rng.shuffle(jobs)
    # 2,590 prompts x 3 examples/prompt (~6k-7.5k unique filtered rows on A100 in ~6-7 min)
    jobs = jobs[: (2590 if vram_gb >= 36.0 else 1800)]
    rows_out = []
    bs = 64 if vram_gb >= 36.0 else 20

    for st in range(0, len(jobs), bs):
        chunk = jobs[st : st + bs]
        prompts = [
            tok.apply_chat_template(
                [{"role": "system", "content": "You synthesize clean, realistic Malaysian transaction notifications and receipts."},
                 {"role": "user", "content": j[5]}],
                tokenize=False, add_generation_prompt=True
            )
            for j in chunk
        ]
        enc = tok(prompts, return_tensors="pt", padding=True, truncation=True, max_length=320).to(mdl.device)
        with torch.no_grad():
            out = mdl.generate(**enc, max_new_tokens=260, do_sample=True, temperature=0.85, top_p=0.92, pad_token_id=tok.pad_token_id)
        dec = tok.batch_decode(out[:, enc["input_ids"].shape[1] :], skip_special_tokens=True)
        for j, gen in zip(chunk, dec):
            cat, sub, ec, amt, src_type, _ = j
            if FORBIDDEN_SCRIPT_RE.search(gen):
                continue
            pairs_found = re.findall(r"TEXT:\s*(.+?)\s*MERCHANT:\s*(.+?)(?=(?:\n\s*TEXT:|\Z))", gen, flags=re.S)
            if not pairs_found:
                m_match = re.search(r"MERCHANT:\s*(.+)", gen)
                if m_match:
                    body_single = re.sub(r"MERCHANT:.*", "", gen, flags=re.S).replace("TEXT:", "").strip()
                    pairs_found = [(body_single, m_match.group(1))]
            for raw_body, raw_merch in pairs_found:
                merch = raw_merch.strip().splitlines()[0].strip().strip('"').strip("'")[:60]
                body = raw_body.strip()
                body = " ".join(l.strip() for l in body.splitlines() if l.strip()) if src_type == "bank_notification" else "\n".join(l.strip() for l in body.splitlines() if l.strip())
                if len(merch) < 3 or len(body) < 12 or len(body) > 260:
                    continue
                if "category" in body.lower() or "subcategory" in body.lower():
                    continue
                # Strict holdout merchant collision exclusion!
                if any(k in forbidden_keys for k in f5.merchant_collision_keys(merch)):
                    continue
                dk = dedup_key(body)
                if dk in seen_texts:
                    continue
                seen_texts.add(dk)
                rows_out.append({
                    "raw_text": body,
                    "expected_merchant": merch,
                    "expected_amount": amt,
                    "category": cat,
                    "subcategory": sub,
                    "source_type": src_type,
                    "edge_case": ec,
                })
        if (st // bs) % 15 == 0:
            update_status("V6_LLM_SYNTH", done=min(st + bs, len(jobs)), total=len(jobs), kept=len(rows_out), model=mdl_id)
            print(f"[v6_synth] {min(st + bs, len(jobs))}/{len(jobs)} kept={len(rows_out)}", flush=True)

    del mdl, tok
    gc.collect()
    torch.cuda.empty_cache()
    return rows_out, mdl_id


def train_highdim_300k_oof(samples, splits):
    """Sparse 300k-vocab Word+Char TF-IDF 5-fold OOF linear teacher on GPU (75 classes)."""
    from sklearn.feature_extraction.text import TfidfVectorizer
    from sklearn.model_selection import StratifiedKFold
    from scipy.sparse import hstack, vstack as sp_vstack

    train_idx = np.array(splits["train_idx"], dtype=np.int64)
    tr_rows = [samples[i] for i in train_idx]
    y_tr = f5._rows_labels_75(tr_rows)
    w_vec = TfidfVectorizer(ngram_range=(1, 2), min_df=2, max_features=160000, sublinear_tf=True, lowercase=True)
    c_vec = TfidfVectorizer(analyzer="char_wb", ngram_range=(3, 5), min_df=2, max_features=140000, sublinear_tf=True, lowercase=True)

    all_both_tr = [f"{r['expected_merchant']}\n{r['raw_text']}" for r in tr_rows]
    w_vec.fit(all_both_tr)
    c_vec.fit(all_both_tr)

    def feat(txts):
        return hstack([w_vec.transform(txts), c_vec.transform(txts)], format="csr").astype(np.float32)

    Xb = feat(all_both_tr)
    Xr = feat([r["raw_text"] for r in tr_rows])
    skf = StratifiedKFold(n_splits=5, shuffle=True, random_state=2026)
    oof_b = np.zeros((len(samples), 75), dtype=np.float32)
    oof_r = np.zeros((len(samples), 75), dtype=np.float32)

    non_tr_mask = np.ones(len(samples), dtype=bool)
    non_tr_mask[train_idx] = False
    non_tr_idx = np.flatnonzero(non_tr_mask)
    X_all_b_nt = feat([f"{samples[i]['expected_merchant']}\n{samples[i]['raw_text']}" for i in non_tr_idx])
    X_all_r_nt = feat([samples[i]["raw_text"] for i in non_tr_idx])

    preds_non_tr_b = np.zeros((len(non_tr_idx), 75), dtype=np.float64)
    preds_non_tr_r = np.zeros((len(non_tr_idx), 75), dtype=np.float64)

    f5.update_status("TRAINING_TEACHER", backbone="HighDim-300k-5Fold-OOF")
    t0 = time.perf_counter()
    for _fold, (tr_i, va_i) in enumerate(skf.split(Xb, y_tr)):
        X_fit = sp_vstack([Xb[tr_i], Xr[tr_i]], format="csr")
        y_fit = np.tile(y_tr[tr_i], 2)
        evals, _, _ = f5._embedding_bag_linear_gpu(
            X_fit, y_fit, [Xb[va_i], Xr[va_i], X_all_b_nt, X_all_r_nt],
            num_classes=75, epochs=12, batch_size=512, lr=2e-2, wd=2e-4,
        )
        oof_b[train_idx[va_i]] = evals[0]
        oof_r[train_idx[va_i]] = evals[1]
        preds_non_tr_b += evals[2] / 5.0
        preds_non_tr_r += evals[3] / 5.0

    oof_b[non_tr_idx] = preds_non_tr_b.astype(np.float32)
    oof_r[non_tr_idx] = preds_non_tr_r.astype(np.float32)
    print(f"[HighDim-300k-OOF] Finished 5-fold on GPU in {round(time.perf_counter() - t0, 1)}s", flush=True)
    return {"backbone": "HighDim-300k-5Fold-OOF", "z_both": oof_b.astype(np.float16), "z_raw": oof_r.astype(np.float16)}


def _build_stage1_weights(train_rows, clean_rows, ens_both_clean):
    """Builds ensemble-disagreement-filtered sample weights for Stage-1 training."""
    n_clean = len(clean_rows)
    eb = ens_both_clean.astype(np.float32)
    eb_sh = eb - eb.max(axis=1, keepdims=True)
    eb_p = np.exp(eb_sh) / np.exp(eb_sh).sum(axis=1, keepdims=True)
    y_clean = f5._rows_labels_75(clean_rows)
    p_true = eb_p[np.arange(n_clean), y_clean]
    p_max = eb_p.max(axis=1)
    downweight_mask = (p_true < 0.05) & (p_max >= 0.85)
    base_sw = np.array(
        [
            0.85 if r.get("edge_case") == "ood_misc" else (
                (0.25 if (i < n_clean and downweight_mask[i]) else 1.0)
                * (1.8 if r["source_type"] == "thermal_receipt" else 1.0)
                * (1.5 if r.get("edge_case") == "seed_v3" else 1.0)
                * (1.25 if str(r.get("edge_case", "")).startswith("v6_") else 1.0)
            )
            for i, r in enumerate(train_rows)
        ],
        dtype=np.float32,
    )
    sw_4x = np.concatenate([base_sw, base_sw, base_sw * 0.7, base_sw * 0.7])
    return base_sw, sw_4x, int(np.sum(downweight_mask))


def _fit_distill_s1(vb, sw_4x, n_base, n_teacher_valid, alpha=0.50, lr=1.5e-2, wd=1.5e-4, epochs=16):
    """GPU soft-label KL + balanced CE distillation for sparse TF-IDF Stage-1 linear classifier."""
    import torch
    import torch.nn as nn
    import torch.nn.functional as F

    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    N_all, D_s1 = vb["X"].shape
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

    for _ in range(epochs):
        perm = rng.permutation(N_all)
        for st in range(0, N_all, bs):
            bi = perm[st : st + bs]
            xb = torch.from_numpy(vb["X"][bi].toarray()).to(device)
            yb = torch.from_numpy(vb["y"][bi]).to(device)
            logits = layer(xb)
            ce = (F.cross_entropy(logits, yb, weight=cw, reduction="none") * sw_t[bi].to(device)).mean()
            soft_mask = (bi < n_teacher_valid) | ((bi >= n_base) & (bi < n_base + n_teacher_valid))
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


def _compute_grouped_oof_s1(train_rows, vb, sw_4x, kind, T_s1):
    """5-fold strict merchant-grouped OOF Stage-1 probabilities matching run_foundry_v5."""
    from sklearn.svm import LinearSVC

    rng_f = random.Random(9001)
    tr_grps = sorted({f4.normalize_merchant_group(r["expected_merchant"]) for r in train_rows})
    rng_f.shuffle(tr_grps)
    g2f = {g: k % 5 for k, g in enumerate(tr_grps)}
    row_folds = [g2f[f4.normalize_merchant_group(r["expected_merchant"])] for r in train_rows]

    N_r = len(train_rows)
    oof_probs = np.zeros((3, N_r, 75), dtype=np.float32)
    X_both_tr = f4.transform_with_vocab([f"{r['expected_merchant']}\n{r['raw_text']}" for r in train_rows], vb)
    X_raw_tr = f4.transform_with_vocab([r["raw_text"] for r in train_rows], vb)
    X_surf_tr = f4.transform_with_vocab([f"{f5.infer_surface_merchant(r)}\n{r['raw_text']}" for r in train_rows], vb)

    for fold in range(5):
        fit_pos = [k for k, f in enumerate(row_folds) if f != fold]
        val_pos = [k for k, f in enumerate(row_folds) if f == fold]
        fit_4x = fit_pos + [N_r + k for k in fit_pos] + [2 * N_r + k for k in fit_pos] + [3 * N_r + k for k in fit_pos]
        if kind == "svc":
            clf_f = LinearSVC(C=0.30, dual="auto", class_weight="balanced", max_iter=2000, random_state=42)
            clf_f.fit(vb["X"][fit_4x], vb["y"][fit_4x], sample_weight=sw_4x[fit_4x])
            Wf, bf = clf_f.coef_.astype(np.float64), clf_f.intercept_.astype(np.float64)
            T_f = T_s1
        else:
            _, Wf, bf = f5._embedding_bag_linear_gpu(
                vb["X"][fit_4x], vb["y"][fit_4x], [X_both_tr[val_pos]], epochs=12, batch_size=512, lr=1.5e-2, wd=2e-4, sample_weights=sw_4x[fit_4x]
            )
            T_f = 1.0
        for vi, Xv in enumerate((X_both_tr, X_raw_tr, X_surf_tr)):
            logits_v = (Xv[val_pos] @ Wf.T + bf) / T_f
            sh = logits_v - logits_v.max(axis=1, keepdims=True)
            ex = np.exp(sh)
            oof_probs[vi, val_pos] = (ex / ex.sum(axis=1, keepdims=True)).astype(np.float32)
    return oof_probs


def run_stage1_v6(samples, splits, ens_both, ens_raw, ood_train):
    from sklearn.svm import LinearSVC

    update_status("TRAINING_STAGE1_V6")
    train_idx = splits["train_idx"]
    cal_idx = splits["cal_idx"]
    clean_rows = [samples[i] for i in train_idx]
    train_rows = clean_rows + list(ood_train)
    cal_rows = [samples[i] for i in cal_idx]
    y_cal = f5._rows_labels_75(cal_rows)

    n_clean = len(train_idx)
    base_sw, sw_4x, n_down = _build_stage1_weights(train_rows, clean_rows, ens_both[train_idx])
    tz_b = np.vstack([ens_both[train_idx], np.zeros((len(ood_train), 75), dtype=np.float16)])
    tz_r = np.vstack([ens_raw[train_idx], np.zeros((len(ood_train), 75), dtype=np.float16)])

    vb = f4.build_tfidf_vocab_and_matrix(train_rows, CLASSES_75, CLS2ID_75, teacher_z_both=tz_b, teacher_z_raw=tz_r, max_w_feats=16000, max_c_feats=14000)
    X_c_r = f4.transform_with_vocab([r["raw_text"] for r in cal_rows], vb)
    X_c_b = f4.transform_with_vocab([f"{r['expected_merchant']}\n{r['raw_text']}" for r in cal_rows], vb)

    def eval_cand(name, cfg, W_raw, b_raw):
        l_r, l_b = X_c_r @ W_raw.T + b_raw, X_c_b @ W_raw.T + b_raw
        T = f4.fit_temperature(np.vstack([l_r, l_b]), np.concatenate([y_cal, y_cal]))
        m_r = f4.compute_calibration_metrics(l_r / T, y_cal, CLASSES_75)
        m_b = f4.compute_calibration_metrics(l_b / T, y_cal, CLASSES_75)
        sc = 0.5 * (m_r["subcat_macro_f1"] + m_b["subcat_macro_f1"])
        nll = 0.5 * (m_r["nll"] + m_b["nll"])
        print(f"[Stage1-v6 {name}] cal_f1={sc:.4f} cal_nll={nll:.4f} T={T:.3f}", flush=True)
        return sc, -nll, W_raw, b_raw, cfg, name

    svc = LinearSVC(C=0.30, dual="auto", class_weight="balanced", max_iter=2500, random_state=42)
    svc.fit(vb["X"], vb["y"], sample_weight=sw_4x)
    best = eval_cand("svc_bal_C0.30", {"kind": "svc", "C": 0.30}, svc.coef_.astype(np.float64), svc.intercept_.astype(np.float64))

    for alpha in (0.45, 0.55):
        Wd, bd = _fit_distill_s1(vb, sw_4x, len(train_rows), n_clean, alpha=alpha)
        cand = eval_cand(f"distill_kl_a{alpha}", {"kind": "distill", "alpha": alpha}, Wd, bd)
        if cand[:2] > best[:2]:
            best = cand

    _, _, W_best, b_best, winner_cfg, winner_name = best
    print(f"[Stage1-v6 WINNER] {winner_name} (downweighted={n_down})", flush=True)

    hold_export = adaptive_mlcl_calibrate(W_best, b_best, CLASSES_75, vb, X_c_r, X_c_b, y_cal, max_gz_bytes=MAX_MLCL_GZ_BYTES)
    S1_HOLD_BIN_GZ.write_bytes(hold_export["gz_bytes"])
    assert len(hold_export["gz_bytes"]) < 1_000_000

    oof_probs = _compute_grouped_oof_s1(train_rows, vb, sw_4x, winner_cfg["kind"], float(hold_export["temperature"]))
    return {
        "winner_name": winner_name,
        "winner_cfg": winner_cfg,
        "hold_export": hold_export,
        "vb": vb,
        "train_rows": train_rows,
        "sw_4x": sw_4x,
        "base_sw": base_sw,
        "oof_probs": oof_probs,
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--distill-only", action="store_true")
    args = parser.parse_args()
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    t_all = time.perf_counter()
    try:
        v3_h = json.loads(HOLDOUT_V3_PATH.read_text(encoding="utf-8"))
        v4_h = json.loads(HOLDOUT_V4_PATH.read_text(encoding="utf-8"))
        v5_hard_rows = json.loads(V5_HARD_TEST_PATH.read_text(encoding="utf-8"))
        ood_test_rows = json.loads(OOD_MISC_TEST_PATH.read_text(encoding="utf-8"))

        base_samples = f5.read_jsonl_any(V5_DATASET_GZ)
        base_splits = f5.build_v5_splits(base_samples, v3_h, v4_h)
        seen_texts = {dedup_key(r["raw_text"]) for r in base_samples} | {dedup_key(r["raw_text"]) for r in ood_test_rows}
        forbidden_merch = f5.build_forbidden_merchant_keys(v3_h, v4_h)
        for r in v5_hard_rows:
            forbidden_merch |= f5.merchant_collision_keys(r["expected_merchant"])

        if (OUT_DIR / "v6_synth.jsonl.gz").exists():
            synth_rows = f5.read_jsonl_any(OUT_DIR / "v6_synth.jsonl.gz")
            qwen_model = "cached"
        else:
            synth_rows, qwen_model = synthesize_v6_qwen_rows(forbidden_merch, seen_texts)
            with gzip.open(OUT_DIR / "v6_synth.jsonl.gz", "wt", encoding="utf-8") as gz:
                for r in synth_rows:
                    gz.write(json.dumps(r, ensure_ascii=False) + "\n")

        # Append v6_synth STRICTLY to train_idx (never touching cal/v3/v4/v5_hard_test!)
        samples = list(base_samples) + synth_rows
        splits = dict(base_splits)
        splits["train_idx"] = list(base_splits["train_idx"]) + list(range(len(base_samples), len(samples)))
        splits["v6_synth_n"] = len(synth_rows)
        print(f"v6 Splits: base={len(base_samples)} synth={len(synth_rows)} train={len(splits['train_idx'])} cal={len(splits['cal_idx'])} v5_hard={len(splits['v5_hard_test_idx'])}", flush=True)

        ood_train, ood_cal, ood_test = f5.build_ood_misc_split(n_train=900, n_cal=120, n_test=200, seed=20261123)

        if TEACHER_CACHE_PATH.exists():
            npz = np.load(TEACHER_CACHE_PATH)
            ens_both, ens_raw = npz["ens_both"], npz["ens_raw"]
            ens_summary = json.loads(npz["ens_summary_json"].item())
        else:
            f5.update_status = update_status
            t1 = f5.train_hf_teacher("BAAI/bge-m3", samples, splits, epochs=3, max_len=176)
            t2 = f5.train_hf_teacher("intfloat/multilingual-e5-large", samples, splits, epochs=3, max_len=176)
            t3 = train_highdim_300k_oof(samples, splits)
            ens_both, ens_raw, ens_summary = f5.blend_teacher_logits(t1, t2, t3, samples, splits)
            np.savez_compressed(TEACHER_CACHE_PATH, ens_both=ens_both, ens_raw=ens_raw, ens_summary_json=json.dumps(ens_summary))

        s1_bundle = run_stage1_v6(samples, splits, ens_both, ens_raw, ood_train)

        # Monkey-patch stage1_predict_rows + OUT_DIR on f5 so f5.train_stage2_reranker works seamlessly with MLCL!
        f5.stage1_predict_rows = lambda exp, vb_arg, rows_mt: mlcl_predict_rows_batch(exp, vb_arg, rows_mt, CLASSES_75)
        f5.RERANKER_HOLDOUT_GZ = S2_HOLD_BIN_GZ
        hold_s2 = f5.train_stage2_reranker(s1_bundle, samples, splits, ens_both, ens_raw, ood_train, ood_cal, s1_drop_rate=0.35)

        # Refit final production Stage-1 & Stage-2 on all train + cal + ood (zero test holdouts!)
        update_status("TRAINING_FULL_PROD")
        prod_idx = list(splits["train_idx"]) + list(splits["cal_idx"])
        clean_prod_rows = [samples[i] for i in prod_idx]
        prod_rows = clean_prod_rows + list(ood_train) + list(ood_cal)
        base_sw_p, sw_4p, _ = _build_stage1_weights(prod_rows, clean_prod_rows, ens_both[prod_idx])
        n_ood_p = len(ood_train) + len(ood_cal)
        tz_bp = np.vstack([ens_both[prod_idx], np.zeros((n_ood_p, 75), dtype=np.float16)])
        tz_rp = np.vstack([ens_raw[prod_idx], np.zeros((n_ood_p, 75), dtype=np.float16)])
        vb_p = f4.build_tfidf_vocab_and_matrix(prod_rows, CLASSES_75, CLS2ID_75, teacher_z_both=tz_bp, teacher_z_raw=tz_rp, max_w_feats=16000, max_c_feats=14000)

        wcfg = s1_bundle["winner_cfg"]
        if wcfg["kind"] == "svc":
            from sklearn.svm import LinearSVC
            svc_p = LinearSVC(C=wcfg.get("C", 0.30), dual="auto", class_weight="balanced", max_iter=2500, random_state=42)
            svc_p.fit(vb_p["X"], vb_p["y"], sample_weight=sw_4p)
            Wp, bp = svc_p.coef_.astype(np.float64), svc_p.intercept_.astype(np.float64)
        else:
            Wp, bp = _fit_distill_s1(vb_p, sw_4p, len(prod_rows), len(prod_idx), alpha=float(wcfg.get("alpha", 0.50)))

        cal_rows = [samples[i] for i in splits["cal_idx"]]
        y_cal = f5._rows_labels_75(cal_rows)
        prod_s1 = adaptive_mlcl_calibrate(
            Wp, bp, CLASSES_75, vb_p,
            f4.transform_with_vocab([r["raw_text"] for r in cal_rows], vb_p),
            f4.transform_with_vocab([f"{r['expected_merchant']}\n{r['raw_text']}" for r in cal_rows], vb_p),
            y_cal, max_gz_bytes=MAX_MLCL_GZ_BYTES, fixed_temperature=s1_bundle["hold_export"]["temperature"],
        )
        S1_PROD_BIN_GZ.write_bytes(prod_s1["gz_bytes"])
        assert len(prod_s1["gz_bytes"]) < 1_000_000

        full_splits = dict(splits)
        full_splits["train_idx"] = prod_idx
        prod_oof = _compute_grouped_oof_s1(prod_rows, vb_p, sw_4p, "distill", 1.0)
        prod_s1_bundle = {
            "winner_name": s1_bundle["winner_name"],
            "winner_cfg": s1_bundle["winner_cfg"],
            "hold_export": prod_s1,
            "vb": vb_p,
            "train_rows": prod_rows,
            "sw_4x": sw_4p,
            "base_sw": base_sw_p,
            "oof_probs": prod_oof,
        }
        f5.RERANKER_HOLDOUT_GZ = OUT_DIR / "_tmp_prod_s2.bin.gz"
        prod_s2 = f5.train_stage2_reranker(prod_s1_bundle, samples, full_splits, ens_both, ens_raw, [], ood_cal, s1_drop_rate=0.35)
        final_s2_gz = f5.serialize_reranker_bin_gz(prod_s2["weights"], CLASSES_75, hold_s2["temperature"], hold_s2["ood_misc_threshold"])
        S2_PROD_BIN_GZ.write_bytes(final_s2_gz)
        assert len(final_s2_gz) < MAX_MLRR_GZ_BYTES

        # Export exact 100 Stage-1 + 100 Stage-2 Python-Java parity vectors from Cal
        s1_vecs, s2_vecs = [], []
        prod_s1_preds = mlcl_predict_rows_batch(prod_s1, vb_p, [(r["expected_merchant"], r["raw_text"]) for r in cal_rows[:100]], CLASSES_75)
        md_s2 = f5.load_reranker_bin_gz(final_s2_gz)
        s2_raw_l, s2_sc_p = f5.reranker_int8_predict_batch(md_s2, [(cal_rows[i]["expected_merchant"], cal_rows[i]["raw_text"], prod_s1_preds[i]) for i in range(min(100, len(cal_rows)))])
        for i in range(min(100, len(cal_rows))):
            r = cal_rows[i]
            out_mlcl = mlcl_predict_parity(prod_s1, vb_p, r["expected_merchant"], r["raw_text"], CLASSES_75)
            if out_mlcl is not None:
                lg, pr = out_mlcl
                s1_vecs.append({"merchant": r["expected_merchant"], "text": r["raw_text"], "bestCat": CLASSES_75[int(np.argmax(pr))].split("||")[0], "bestProb": round(float(np.max(pr)), 6), "probs": [round(float(x), 6) for x in pr], "logits": [round(float(x), 6) for x in lg[:10]]})
            s2_vecs.append({"merchant": r["expected_merchant"], "text": r["raw_text"], "s1": {"prob": round(float(prod_s1_preds[i]["prob"]), 6), "margin": round(float(prod_s1_preds[i]["margin"]), 6), "signals": int(prod_s1_preds[i]["signals"]), "probs": [round(float(x), 6) for x in prod_s1_preds[i]["probs"]]}, "raw_logits": [round(float(x), 6) for x in s2_raw_l[i]]})
        PARITY_PATH.write_text(json.dumps({"mlcl": s1_vecs, "mlrr": s2_vecs}, ensure_ascii=False), encoding="utf-8")

        report = {
            "total_seconds": round(time.perf_counter() - t_all, 1),
            "qwen_model": qwen_model,
            "v6_synth_kept": len(synth_rows),
            "ensemble": ens_summary,
            "stage1_holdout_gz": len(s1_bundle["hold_export"]["gz_bytes"]),
            "stage1_prod_gz": len(prod_s1["gz_bytes"]),
            "stage1_prod_features": prod_s1["active_features"],
            "stage1_cal_metrics": prod_s1["cal_metrics"],
            "stage2_holdout_gz": len(hold_s2["hold_gz"]),
            "stage2_prod_gz": len(final_s2_gz),
            "stage2_temp": hold_s2["temperature"],
            "stage2_ood_thresh": hold_s2["ood_misc_threshold"],
            "stage2_best_cal_macro_f1": hold_s2["best_cal_macro_f1"],
        }
        REPORT_PATH.write_text(json.dumps(report, indent=2), encoding="utf-8")
        update_status("DONE", stage1_gz=len(prod_s1["gz_bytes"]), stage2_gz=len(final_s2_gz), f1=hold_s2["best_cal_macro_f1"])
        print("FOUNDRY V6 DONE:", json.dumps(report), flush=True)
    except Exception as exc:
        update_status("ERROR", error=str(exc))
        raise


if __name__ == "__main__":
    main()
