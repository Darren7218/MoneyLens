#!/usr/bin/env python3
"""Train calibrated <2 MB Word+Char sparse SVM student locally on Foundry v4 corpus while preserving Colab teacher telemetry."""
import json
import random
import re
import sys
import time
from collections import Counter, defaultdict
from pathlib import Path
import numpy as np
from sklearn.svm import LinearSVC

sys.path.insert(0, str(Path(__file__).resolve().parent))
import run_foundry_v4 as f4

OUT_DIR = Path(r"C:\Users\user\MoneyLens\colab\outputs_v4")
f4.OUT_DIR = OUT_DIR
f4.LEXICON_PATH = OUT_DIR / "merchant_lexicon_v4.json"
f4.HOLDOUT_V3_PATH = Path(r"C:\Users\user\MoneyLens\colab\outputs_v3\holdout_v3_details.json")

DATASET_PATH = OUT_DIR / "dataset_malaysian_v4.jsonl"
REPORT_PATH = OUT_DIR / "foundry_v4_report.json"
STATUS_PATH = OUT_DIR / "foundry_v4_status.json"
HOLDOUT_PATH = OUT_DIR / "holdout_v4_details.json"
MODEL_HOLDOUT_JSON = OUT_DIR / "moneylens_classifier_holdout.json"
MODEL_HOLDOUT_GZ = OUT_DIR / "moneylens_classifier_holdout.json.gz"
MODEL_FULL_JSON = OUT_DIR / "moneylens_classifier.json"
MODEL_FULL_GZ = OUT_DIR / "moneylens_classifier.json.gz"

MAX_GZ_BYTES = 1_880_000  # Safely under 2.0 MiB (2,097,152 bytes)

PROMPT_LEAK_RULES = [
    ("restoran nasi lemak", {"Food & drinks"}),
    ("nasi lemak wa", {"Food & drinks"}),
    ("99 speedmart", {"Groceries"}),
    ("99spdmrt", {"Groceries"}),
    ("99spdrt", {"Groceries"}),
    ("klinik medivir", {"Health & medical"}),
    ("klinikmedi", {"Health & medical"}),
    ("klnkmdvr", {"Health & medical"}),
    ("klnkmvdvr", {"Health & medical"}),
    ("klnmedvir", {"Health & medical"}),
    ("zus coffee", {"Food & drinks"}),
]
POS_MERCH_LEAKS = {"slurp!", "slurp", "feedme", "storehub", "qashier", "speedmart", "kopitiam"}


def is_prompt_leak(r):
    ec = r.get("edge_case", "")
    if ec == "seed_v3":
        return False
    m_low = r["expected_merchant"].strip().lower()
    blob = (m_low + " " + r["raw_text"]).lower()
    if ec == "thermal_receipt_noisy_ocr" and m_low in POS_MERCH_LEAKS and r["category"] not in {"Food & drinks", "Groceries"}:
        return True
    if ec == "truncated_merchant":
        for pat, ok_cats in PROMPT_LEAK_RULES:
            if pat in blob and r["category"] not in ok_cats:
                return True
    if ec == "split_bill_p2p" and "unifi + tnb" in blob and r["category"] != "Bills & utilities":
        return True
    return False


def build_ood_misc_rows(n=450, seed=2026):
    rng = random.Random(seed)
    gen_words = [
        "Syarikat", "Perniagaan", "Enterprise", "Trading", "Resources", "Ventures", "Holdings",
        "Services", "Solutions", "Marketing", "Industries", "Supply", "Distribution", "Group",
        "Network", "Partners", "Associates", "Works", "Agency", "Maju", "Jaya", "Harmoni",
        "Sinaran", "Bintang", "Utama", "Prima", "Sentosa", "Cahaya", "Wawasan", "Gemilang",
        "Global", "Pacific", "Sterling", "Summit", "Nexus", "Vector", "Apex", "Mega", "Multi", "Pro",
    ]
    suffixes = ["Sdn Bhd", "PLT", "88", "99", "123", "01", "MY", "KL", "PJ", "JB", "(M) Sdn Bhd", ""]
    syls = [
        "ba", "be", "bi", "bo", "bu", "da", "de", "di", "do", "fa", "fe", "ga", "go",
        "ka", "ke", "ki", "ko", "la", "le", "li", "lo", "ma", "me", "mi", "mo", "na",
        "ne", "ni", "no", "pa", "pe", "pi", "po", "ra", "re", "ri", "ro", "sa", "se",
        "si", "so", "ta", "te", "ti", "to", "va", "ve", "vi", "vo", "za", "ze", "zi", "zo",
    ]
    notif_templates = [
        "You have paid RM{amt} at {m}",
        "You have paid RM{amt} to {m}.",
        "Maybank MAE: DuitNow QR payment of RM {amt} to {m} was successful.",
        "CIMB OCTO: Payment of MYR {amt} at {m} approved.",
        "TNG eWallet: RM{amt} paid at {m}. Ref: {ref}",
        "RHB Now: FPX payment of RM{amt} to {m} completed.",
        "Public Bank: Card transaction RM {amt} at {m}. Available balance: RM {bal}",
        "Pembayaran berjaya sebanyak RM{amt} kepada {m}.",
        "Transaksi kad debit disahkan RM{amt} di {m}.",
        "GXBank: You spent RM{amt} at {m}.",
        "Boost: Paid RM {amt} to {m}.",
        "ShopeePay: Payment of RM{amt} at {m} successful.",
        "HLB Connect: DuitNow transfer RM{amt} to {m}.",
        "PAY*{m_short} RM{amt} KLCC",
        "DNQR*{m_short} MYR {amt} 2026-06-14",
    ]
    receipt_template = "{m}\nDate: 12/05/2026\nItem A  {amt}\nTOTAL RM {amt}\nThank you"

    misc_rows = []
    for i in range(n):
        kind = i % 5
        if kind == 0:
            acr = "".join(rng.choices("ABCDEFGHIJKLMNOPQRSTUVWXYZ", k=rng.choice([2, 3, 4])))
            m = f"{acr} {rng.choice(gen_words)} {rng.choice(suffixes)}".strip()
        elif kind == 1:
            acr = "".join(rng.choices("ABCDEFGHIJKLMNOPQRSTUVWXYZ", k=rng.choice([2, 3, 4])))
            num = str(rng.randint(10, 9999))
            m = f"{acr}{num}" if rng.random() < 0.6 else f"{acr} {num}"
        elif kind == 2:
            w1 = "".join(rng.choices(syls, k=rng.choice([2, 3]))).upper()
            w2 = "".join(rng.choices(syls, k=rng.choice([2, 3]))).upper()
            m = f"{w1} {w2} {rng.choice(['', 'SDN BHD', 'PLT', str(rng.randint(1, 99))])}".strip()
        elif kind == 3:
            w1, w2 = rng.sample(gen_words, 2)
            m = f"{w1} {w2} {rng.choice(suffixes)}".strip()
        else:
            a, b_ch = rng.sample("ABCDEFGHJKLMNPQRSTVWXYZ", 2)
            m = f"{a}&{b_ch} {rng.choice(gen_words)} {rng.choice(suffixes)}".strip()
        amt = f"{rng.choice([2.50, 4.50, 9.90, 12.00, 15.50, 19.90, 25.00, 38.50, 49.90, 65.00, 88.00, 120.00, 199.00, 350.00, 999.00]):.2f}"
        bal = f"{rng.randint(100, 4000)}.00"
        ref = f"TX{rng.randint(100000, 999999)}"
        m_short = re.sub(r"[^A-Z0-9 ]", "", m.upper())[:18].strip()
        if i % 8 == 0:
            raw = receipt_template.format(amt=amt, m=m)
            stype = "thermal_receipt"
        else:
            raw = rng.choice(notif_templates).format(amt=amt, m=m, bal=bal, ref=ref, m_short=m_short)
            stype = "payment_notification"
        misc_rows.append({
            "category": "Miscellaneous",
            "subcategory": "Auto-Captured (Uncategorized)",
            "source_type": stype,
            "edge_case": "ood_misc",
            "expected_merchant": m,
            "expected_total_myr": amt,
            "expected_date_iso": "2026-05-12",
            "raw_text": raw,
        })
    return misc_rows


def build_sample_weights(rows):
    base = np.array(
        [
            0.8 if r.get("edge_case") == "ood_misc" else (
                (2.0 if r["source_type"] == "thermal_receipt" else 1.0)
                * (1.8 if r.get("edge_case") == "seed_v3" else 1.0)
            )
            for r in rows
        ],
        dtype=np.float32,
    )
    return np.concatenate([base, base, base * 0.7, base * 0.7])


def topk_prune(W, k=450000):
    flat = np.abs(W).ravel()
    if flat.size <= k:
        return W.copy(), 0.0
    th = float(np.partition(flat, flat.size - k)[flat.size - k])
    Wp = W.copy()
    Wp[np.abs(Wp) < th] = 0.0
    return Wp, th


def adaptive_prune_calibrate(W, b, classes, vb, X_cal_r, X_cal_b, y_cal, max_gz_bytes=MAX_GZ_BYTES, fixed_temperature=None):
    lo, hi = 0.010, max(0.25, float(np.quantile(np.abs(W[W != 0]), 0.92)))
    best_thresh = hi
    for _ in range(11):
        mid = round((lo + hi) / 2.0, 4)
        Wp = W.copy()
        Wp[np.abs(Wp) < mid] = 0.0
        _, gz, _, _ = f4.serialize_sparse_model(Wp, b, classes, vb, w_decimals=3)
        if len(gz) <= max_gz_bytes:
            best_thresh = mid
            hi = mid
        else:
            lo = mid

    Wp = W.copy()
    Wp[np.abs(Wp) < best_thresh] = 0.0

    l_comb = np.vstack([X_cal_r @ Wp.T + b, X_cal_b @ Wp.T + b])
    y_comb = np.concatenate([y_cal, y_cal])

    pre_cal = f4.compute_calibration_metrics(l_comb, y_comb, classes)
    T_post = float(fixed_temperature) if fixed_temperature is not None else f4.fit_temperature(l_comb, y_comb)
    post_cal = f4.compute_calibration_metrics(l_comb / T_post, y_comb, classes)

    W_final = Wp / T_post
    b_final = b / T_post
    thresh_used = best_thresh
    for _ in range(15):
        meta = {
            "model_family": "LinearSVC-Calibrated",
            "prune_min_weight": thresh_used,
            "temperature": round(T_post, 4),
            "cal_nll_before": pre_cal["nll"],
            "cal_nll_after": post_cal["nll"],
            "cal_ece_before": pre_cal["ece"],
            "cal_ece_after": post_cal["ece"],
        }
        raw_json, gz_bytes, n_feats, nnz = f4.serialize_sparse_model(
            W_final, b_final, classes, vb, extra_meta=meta, w_decimals=3
        )
        if len(gz_bytes) <= max_gz_bytes:
            break
        thresh_used = round(max(thresh_used + 0.004, thresh_used * 1.12), 4)
        Wp = W.copy()
        Wp[np.abs(Wp) < thresh_used] = 0.0
        W_final = Wp / T_post

    return {
        "W_final": W_final,
        "b_final": b_final,
        "prune_threshold": thresh_used,
        "temperature": round(T_post, 4),
        "pre_cal": {k: v for k, v in pre_cal.items() if k != "preds"},
        "post_cal": {k: v for k, v in post_cal.items() if k != "preds"},
        "raw_json": raw_json,
        "gz_bytes": gz_bytes,
        "active_features": n_feats,
        "nonzero_weights": nnz,
    }


def main():
    t0 = time.perf_counter()
    raw_samples = f4.read_jsonl(DATASET_PATH)
    samples = [r for r in raw_samples if not is_prompt_leak(r)]
    prompt_leaks_removed = len(raw_samples) - len(samples)
    if prompt_leaks_removed > 0:
        tmp_path = DATASET_PATH.with_suffix(".jsonl.tmp")
        with tmp_path.open("w", encoding="utf-8", newline="\n") as fh:
            for r in samples:
                fh.write(json.dumps(r, ensure_ascii=False) + "\n")
        tmp_path.replace(DATASET_PATH)
        print(f"Removed {prompt_leaks_removed} prompt-leak rows -> {len(samples)} clean rows persisted to {DATASET_PATH.name}", flush=True)

    existing_report = json.loads(REPORT_PATH.read_text(encoding="utf-8")) if REPORT_PATH.exists() else {}
    existing_holdout = json.loads(HOLDOUT_PATH.read_text(encoding="utf-8")) if HOLDOUT_PATH.exists() else []
    xlmr_map = {
        (h["expected_merchant"], h["raw_text"]): h.get("xlmr_v4_raw_pred", "")
        for h in existing_holdout
    }

    splits = f4.build_zero_leakage_splits(samples)
    train_idx = splits["train_idx"]
    cal_idx = splits["cal_idx"]
    v4_test_idx = splits["v4_test_idx"]
    v3_hold_idx = splits["v3_holdout_idx"]
    classes74 = sorted({f"{r['category']}||{r['subcategory']}" for r in samples})
    ood_misc_rows = build_ood_misc_rows(n=450, seed=2026)
    classes = sorted(classes74 + ["Miscellaneous||Auto-Captured (Uncategorized)"])
    lbl2id = {l: i for i, l in enumerate(classes)}

    train_rows = [samples[i] for i in train_idx] + ood_misc_rows
    cal_rows = [samples[i] for i in cal_idx]
    print(
        f"Training calibrated LinearSVC locally on {len(train_rows)} train rows "
        f"({len(train_idx)} clean + {len(ood_misc_rows)} OOD misc; "
        f"cal={len(cal_rows)}, v4_test={len(v4_test_idx)}, v3_holdout={len(v3_hold_idx)}, classes={len(classes)})",
        flush=True,
    )

    vb = f4.build_tfidf_vocab_and_matrix(train_rows, classes, lbl2id, max_w_feats=16000, max_c_feats=14000)
    X_tr, y_tr = vb["X"], vb["y"]
    sw_tr = build_sample_weights(train_rows)

    X_cal_r = f4.transform_with_vocab([r["raw_text"] for r in cal_rows], vb)
    X_cal_b = f4.transform_with_vocab([f"{r['expected_merchant']}\n{r['raw_text']}" for r in cal_rows], vb)
    y_cal = np.array([lbl2id[f"{r['category']}||{r['subcategory']}"] for r in cal_rows], dtype=np.int64)

    candidates_spec = [
        ("svc_bal_C0.30", {"kind": "LinearSVC", "C": 0.30, "class_weight": "balanced"}),
        ("svc_bal_C0.50", {"kind": "LinearSVC", "C": 0.50, "class_weight": "balanced"}),
        ("svc_bal_C0.75", {"kind": "LinearSVC", "C": 0.75, "class_weight": "balanced"}),
        ("svc_unbal_C0.50", {"kind": "LinearSVC", "C": 0.50, "class_weight": None}),
    ]
    candidates_log = []
    best = None

    for name, cfg in candidates_spec:
        t_fit = time.perf_counter()
        clf = LinearSVC(C=cfg["C"], dual="auto", class_weight=cfg["class_weight"], max_iter=2000, random_state=42)
        clf.fit(X_tr, y_tr, sample_weight=sw_tr)
        W_raw = clf.coef_.astype(np.float64)
        b_raw = clf.intercept_.astype(np.float64)
        Wp, th = topk_prune(W_raw, k=450000)

        l_r = X_cal_r @ Wp.T + b_raw
        l_b = X_cal_b @ Wp.T + b_raw
        T = f4.fit_temperature(np.vstack([l_r, l_b]), np.concatenate([y_cal, y_cal]))
        m_r = f4.compute_calibration_metrics(l_r / T, y_cal, classes)
        m_b = f4.compute_calibration_metrics(l_b / T, y_cal, classes)
        mean_f1 = 0.5 * (m_r["subcat_macro_f1"] + m_b["subcat_macro_f1"])
        mean_nll = 0.5 * (m_r["nll"] + m_b["nll"])
        rec = {
            "name": name,
            "config": cfg,
            "fit_seconds": round(time.perf_counter() - t_fit, 1),
            "topk_prune_threshold": round(th, 4),
            "temperature": round(T, 4),
            "cal_mean_macro_f1": round(mean_f1, 4),
            "cal_mean_nll": round(mean_nll, 4),
            "cal_raw": {k: v for k, v in m_r.items() if k != "preds"},
            "cal_both": {k: v for k, v in m_b.items() if k != "preds"},
        }
        candidates_log.append(rec)
        print(
            f"[{name}] fit={rec['fit_seconds']}s score={mean_f1:.4f} nll={mean_nll:.4f} "
            f"raw={m_r['cat_acc']}/{m_r['subcat_acc']} (ece={m_r['ece']}) "
            f"both={m_b['cat_acc']}/{m_b['subcat_acc']} T={T:.4f}",
            flush=True,
        )
        key = (round(mean_f1, 4), -round(mean_nll, 4))
        if best is None or key > best[0]:
            best = (key, name, cfg, W_raw, b_raw)

    _, best_name, best_cfg, W_best, b_best = best
    print(f"Selected winning candidate: {best_name} {best_cfg}", flush=True)

    hold_export = adaptive_prune_calibrate(W_best, b_best, classes, vb, X_cal_r, X_cal_b, y_cal)
    MODEL_HOLDOUT_JSON.write_text(hold_export["raw_json"], encoding="utf-8")
    MODEL_HOLDOUT_GZ.write_bytes(hold_export["gz_bytes"])
    assert len(hold_export["gz_bytes"]) < 2_097_152

    def eval_exported(indices):
        rows = [samples[i] for i in indices]
        ys = np.array([lbl2id[f"{r['category']}||{r['subcategory']}"] for r in rows], dtype=np.int64)
        Xr = f4.transform_with_vocab([r["raw_text"] for r in rows], vb)
        Xb = f4.transform_with_vocab([f"{r['expected_merchant']}\n{r['raw_text']}" for r in rows], vb)
        Xm = f4.transform_with_vocab([r["expected_merchant"] for r in rows], vb)
        mr = f4.compute_calibration_metrics(Xr @ hold_export["W_final"].T + hold_export["b_final"], ys, classes)
        mb = f4.compute_calibration_metrics(Xb @ hold_export["W_final"].T + hold_export["b_final"], ys, classes)
        mm = f4.compute_calibration_metrics(Xm @ hold_export["W_final"].T + hold_export["b_final"], ys, classes)

        by_edge = {}
        by_edge_idx = defaultdict(list)
        for k, r in enumerate(rows):
            by_edge_idx[r.get("edge_case", "unknown")].append(k)
        for ec, ks in sorted(by_edge_idx.items()):
            cat_ok = sum(1 for k in ks if mr["preds"][k].split("||")[0] == rows[k]["category"])
            sub_ok = sum(1 for k in ks if mr["preds"][k] == f"{rows[k]['category']}||{rows[k]['subcategory']}")
            by_edge[ec] = {
                "n": len(ks),
                "raw_cat_acc": round(cat_ok / len(ks), 4),
                "raw_subcat_acc": round(sub_ok / len(ks), 4),
            }
        raw_abstain = round(sum(1 for p in mr["preds"] if p.startswith("Miscellaneous||")) / len(rows), 4)
        both_abstain = round(sum(1 for p in mb["preds"] if p.startswith("Miscellaneous||")) / len(rows), 4)
        return {
            "n": len(rows),
            "raw_only": {k: v for k, v in mr.items() if k != "preds"},
            "with_merchant": {k: v for k, v in mb.items() if k != "preds"},
            "merchant_only": {k: v for k, v in mm.items() if k != "preds"},
            "raw_misc_abstain_rate": raw_abstain,
            "both_misc_abstain_rate": both_abstain,
            "by_edge_case": by_edge,
            "raw_preds": mr["preds"],
        }

    sparse_v3_eval = eval_exported(v3_hold_idx)
    sparse_v4_eval = eval_exported(v4_test_idx)
    print(
        f"Holdout eval -> v3_holdout raw={sparse_v3_eval['raw_only']['cat_acc']}/{sparse_v3_eval['raw_only']['subcat_acc']} "
        f"(F1={sparse_v3_eval['raw_only']['subcat_macro_f1']}, ECE={sparse_v3_eval['raw_only']['ece']}, miscFP={sparse_v3_eval['raw_misc_abstain_rate']}) | "
        f"v4_test raw={sparse_v4_eval['raw_only']['cat_acc']}/{sparse_v4_eval['raw_only']['subcat_acc']} "
        f"both={sparse_v4_eval['with_merchant']['cat_acc']}/{sparse_v4_eval['with_merchant']['subcat_acc']} "
        f"(miscFP={sparse_v4_eval['raw_misc_abstain_rate']}/{sparse_v4_eval['both_misc_abstain_rate']})",
        flush=True,
    )

    # Production model refit on 100% of clean samples + OOD Miscellaneous rows using winning config + holdout-derived T
    prod_rows = samples + ood_misc_rows
    print(f"Refitting production model on {len(prod_rows)} samples ({len(samples)} clean + {len(ood_misc_rows)} OOD misc)...", flush=True)
    vb_full = f4.build_tfidf_vocab_and_matrix(prod_rows, classes, lbl2id, max_w_feats=16000, max_c_feats=14000)
    sw_full = build_sample_weights(prod_rows)
    clf_full = LinearSVC(C=best_cfg["C"], dual="auto", class_weight=best_cfg["class_weight"], max_iter=2000, random_state=42)
    clf_full.fit(vb_full["X"], vb_full["y"], sample_weight=sw_full)

    X_full_cal_r = f4.transform_with_vocab([r["raw_text"] for r in cal_rows], vb_full)
    X_full_cal_b = f4.transform_with_vocab([f"{r['expected_merchant']}\n{r['raw_text']}" for r in cal_rows], vb_full)
    prod_export = adaptive_prune_calibrate(
        clf_full.coef_.astype(np.float64),
        clf_full.intercept_.astype(np.float64),
        classes,
        vb_full,
        X_full_cal_r,
        X_full_cal_b,
        y_cal,
        max_gz_bytes=MAX_GZ_BYTES,
        fixed_temperature=hold_export["temperature"],
    )
    MODEL_FULL_JSON.write_text(prod_export["raw_json"], encoding="utf-8")
    MODEL_FULL_GZ.write_bytes(prod_export["gz_bytes"])
    assert len(prod_export["gz_bytes"]) < 2_097_152

    lex_count = f4.extract_lexicon(samples)

    holdout_rows = []
    v4_s_preds = sparse_v4_eval["raw_preds"]
    for k, idx in enumerate(v4_test_idx):
        s = samples[idx]
        holdout_rows.append({
            "source_type": s["source_type"],
            "edge_case": s.get("edge_case", "unknown"),
            "expected_merchant": s["expected_merchant"],
            "expected_total_myr": s["expected_total_myr"],
            "true_category": s["category"],
            "true_subcategory": s["subcategory"],
            "lhdn_bucket": s.get("lhdn_bucket"),
            "sparse_v4_raw_pred": v4_s_preds[k],
            "xlmr_v4_raw_pred": xlmr_map.get((s["expected_merchant"], s["raw_text"]), ""),
            "raw_text": s["raw_text"],
        })
    HOLDOUT_PATH.write_text(json.dumps(holdout_rows, indent=2, ensure_ascii=False), encoding="utf-8")

    seed_cnt = sum(1 for r in samples if r.get("edge_case") == "seed_v3")
    new_cnt = len(samples) - seed_cnt
    edge_counts = Counter(r.get("edge_case", "unknown") for r in samples)
    new_edge_counts = {k: v for k, v in edge_counts.items() if k != "seed_v3"}
    pair_counts = Counter(f"{r['category']}||{r['subcategory']}" for r in samples)

    existing_report["local_refit_seconds"] = round(time.perf_counter() - t0, 1)
    if "generation" in existing_report:
        existing_report["generation"]["seed_v3_kept"] = seed_cnt
        existing_report["generation"]["new_valid"] = new_cnt
        existing_report["generation"]["total_combined"] = len(samples)
        existing_report["generation"]["classes_covered"] = len(pair_counts)
        existing_report["generation"]["min_class_count"] = min(pair_counts.values()) if pair_counts else 0
        existing_report["generation"]["edge_case_counts"] = dict(new_edge_counts)
        existing_report["generation"]["prompt_leaks_removed"] = prompt_leaks_removed
    existing_report["splits"] = {
        "total": len(samples),
        "train": len(train_idx),
        "train_with_ood_misc": len(train_rows),
        "ood_misc_train_rows": len(ood_misc_rows),
        "cal": len(cal_idx),
        "v4_test": len(v4_test_idx),
        "v3_holdout": len(v3_hold_idx),
        "v3_group_shadow_in_test": splits["v3_shadow_in_test"],
    }
    existing_report["dataset_counts"] = {
        "by_source": dict(Counter(r["source_type"] for r in samples)),
        "by_edge_case": dict(edge_counts),
        "by_category": dict(Counter(r["category"] for r in samples)),
        "lhdn_non_null": sum(1 for r in samples if r.get("lhdn_bucket")),
        "classes_covered": len(pair_counts),
        "exported_model_classes": len(classes),
    }
    existing_report["sparse_candidates_on_cal"] = candidates_log
    existing_report["winning_sparse_student"] = {
        "name": best_name,
        "config": best_cfg,
        "vocab_word": len(vb["w_vocab"]),
        "vocab_char": len(vb["c_vocab"]),
        "holdout_model_prune_threshold": hold_export["prune_threshold"],
        "holdout_model_temperature": hold_export["temperature"],
        "holdout_model_export_bytes": len(hold_export["raw_json"].encode("utf-8")),
        "holdout_model_gz_bytes": len(hold_export["gz_bytes"]),
        "holdout_model_active_features": hold_export["active_features"],
        "holdout_model_nonzero_weights": hold_export["nonzero_weights"],
        "cal_before_calibration": hold_export["pre_cal"],
        "cal_after_calibration": hold_export["post_cal"],
        "eval_on_frozen_v3_holdout": {k: v for k, v in sparse_v3_eval.items() if k != "raw_preds"},
        "eval_on_v4_test": {k: v for k, v in sparse_v4_eval.items() if k != "raw_preds"},
    }
    existing_report["production_asset"] = {
        "vocab_word": len(vb_full["w_vocab"]),
        "vocab_char": len(vb_full["c_vocab"]),
        "exported_classes": len(classes),
        "active_features": prod_export["active_features"],
        "nonzero_weights": prod_export["nonzero_weights"],
        "prune_threshold": prod_export["prune_threshold"],
        "temperature": prod_export["temperature"],
        "export_bytes": len(prod_export["raw_json"].encode("utf-8")),
        "gz_bytes": len(prod_export["gz_bytes"]),
        "under_2mb": len(prod_export["gz_bytes"]) < 2_097_152,
        "lexicon_subcategories_covered": lex_count,
    }
    REPORT_PATH.write_text(json.dumps(existing_report, indent=2, ensure_ascii=False), encoding="utf-8")
    STATUS_PATH.write_text(json.dumps({
        "stage": "DONE",
        "ts": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "total_samples": len(samples),
        "new_valid": new_cnt,
        "v4_test": len(v4_test_idx),
        "gz_bytes": len(prod_export["gz_bytes"]),
        "winner": best_name,
    }, indent=2), encoding="utf-8")
    print(f"DONE in {round(time.perf_counter() - t0, 1)}s | prod gz={len(prod_export['gz_bytes']):,} bytes", flush=True)


if __name__ == "__main__":
    main()
