#!/usr/bin/env python3
"""
Phase C Colab A100 Run 1:
  Sub-stage 1A: Foundry v7 (MlClassifier MLCL v1 < 1.0 MB + QAT INT8 MlNeuralReranker v7 < 1.38 MB)
  Sub-stage 1B: NotificationGate v3 (BAAI/bge-m3 distilled 5,632-bucket INT8 <= 240 KB)
"""
import gc
import gzip
import json
import math
import random
import re
import shutil
import struct
import sys
import time
import traceback
from pathlib import Path

import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F

sys.path.insert(0, "/content")

OUT_DIR = Path("/content/outputs_phasec")
STATUS_PATH = OUT_DIR / "phasec_run1_status.json"
SUMMARY_REPORT_PATH = OUT_DIR / "phasec_run1_report.json"


def update_status(stage: str, **extra):
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    payload = {"stage": stage, "ts": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())}
    payload.update(extra)
    STATUS_PATH.write_text(json.dumps(payload, indent=2, ensure_ascii=False), encoding="utf-8")


def clear_cuda():
    gc.collect()
    try:
        if torch.cuda.is_available():
            torch.cuda.empty_cache()
    except Exception:
        pass


def synthesize_v7_targeted_rows(f5, f6, forbidden_keys: set, seen_texts: set):
    """Synthesizes ~5,000 hard-contrast rows focusing 50% on bare truncated gateways, 30% BM/CJK, 20% LHDN."""
    from transformers import AutoModelForCausalLM, AutoTokenizer

    vram_gb = (torch.cuda.get_device_properties(0).total_memory / 1e9) if torch.cuda.is_available() else 0.0
    cands = ("Qwen/Qwen2.5-14B-Instruct", "Qwen/Qwen2.5-7B-Instruct") if vram_gb >= 36.0 else ("Qwen/Qwen2.5-7B-Instruct", "Qwen/Qwen2.5-3B-Instruct")
    mdl, tok, mdl_id = None, None, "none"
    for cand in cands:
        try:
            update_status("V7_LLM_LOAD", model=cand, vram_gb=round(vram_gb, 1))
            print(f"[v7_synth] Loading {cand} (VRAM={vram_gb:.1f}GB)...", flush=True)
            tok = AutoTokenizer.from_pretrained(cand, padding_side="left")
            if tok.pad_token is None:
                tok.pad_token = tok.eos_token
            dtype = torch.bfloat16 if torch.cuda.is_bf16_supported() else torch.float16
            mdl = AutoModelForCausalLM.from_pretrained(cand, torch_dtype=dtype, device_map="cuda:0" if torch.cuda.is_available() else "auto")
            mdl.eval()
            mdl_id = cand
            break
        except Exception as exc:
            print(f"[v7_synth] Could not load {cand}: {exc}", flush=True)
            tok, mdl = None, None
            clear_cuda()

    if mdl is None:
        return [], "none"

    rng = random.Random(20261128)
    non_misc = [p for p in f6.CLASSES_75 if p != f5.MISC_PAIR]
    slice_weights = [
        ("v7_truncated_gateway", 5,
         "formatted ONLY as bare abbreviated 14-24 char Malaysian terminal gateway strings like RAZERMS*<ABBR> PJ RM<amt>, PAY*<ABBR> TRX <amt> MYR, DNQR*<ABBR> BNGS RM<amt>, FPX*<ABBR> S/B RM<amt>, IPAY88*<ABBR>, BILLPLZ*<ABBR>, or MYDEBIT*<ABBR> KL (heavily consonant-abbreviated brand tokens + branch suffix, NO prose sentences!)"),
        ("v7_multilingual_bm_cjk", 3,
         "written in natural colloquial Bahasa Melayu, Manglish, or Simplified Chinese bank/DuitNow/e-wallet push alerts with specific Malaysian SME names and real-life purpose clues"),
        ("v7_disambiguation_lhdn", 2,
         "highlighting exact disambiguation boundaries: Simpan SSPN vs PTPTN Study Loan repayment, EPF i-Saraan vs PRS, Gym/Fitness membership vs Sportswear apparel, Full Medical Checkup vs Clinic GP, Bookstore vs Cafe, Medical Card vs Road Tax/Auto Takaful"),
    ]
    jobs = []
    for ec, rep_w, style_desc in slice_weights:
        for _ in range(rep_w):
            for pair in non_misc:
                cat, sub = pair.split("||", 1)
                amt = f"RM {rng.uniform(4.5, 420.0):.2f}"
                src_type = "bank_notification"
                prompt = (
                    f"Generate 3 DISTINCT realistic Malaysian payment notifications for Category='{cat}', Subcategory='{sub}', amount ~'{amt}', {style_desc}.\n"
                    f"Invent realistic distinct Malaysian merchants belonging ONLY to '{sub}'.\n"
                    f"Output strictly 3 blocks:\nTEXT: <notification 1>\nMERCHANT: <merchant 1>\nTEXT: <notification 2>\nMERCHANT: <merchant 2>\nTEXT: <notification 3>\nMERCHANT: <merchant 3>"
                )
                jobs.append((cat, sub, ec, amt, src_type, prompt))

    rng.shuffle(jobs)
    jobs = jobs[: (1900 if vram_gb >= 36.0 else 1350)]
    bs = 64 if vram_gb >= 36.0 else 24
    rows_out = []

    for st in range(0, len(jobs), bs):
        chunk = jobs[st : st + bs]
        prompts = [
            tok.apply_chat_template(
                [{"role": "system", "content": "You synthesize crisp, realistic Malaysian payment notifications and truncated terminal strings."},
                 {"role": "user", "content": j[5]}],
                tokenize=False, add_generation_prompt=True,
            )
            for j in chunk
        ]
        enc = tok(prompts, return_tensors="pt", padding=True, truncation=True, max_length=320).to(mdl.device)
        with torch.no_grad():
            out = mdl.generate(**enc, max_new_tokens=240, do_sample=True, temperature=0.85, top_p=0.92, pad_token_id=tok.pad_token_id)
        dec = tok.batch_decode(out[:, enc["input_ids"].shape[1] :], skip_special_tokens=True)
        for j, gen in zip(chunk, dec):
            cat, sub, ec, amt, src_type, _ = j
            if f6.FORBIDDEN_SCRIPT_RE.search(gen):
                continue
            pairs_found = re.findall(r"TEXT:\s*(.+?)\s*MERCHANT:\s*(.+?)(?=(?:\n\s*TEXT:|\Z))", gen, flags=re.S)
            for raw_body, raw_merch in pairs_found:
                merch = raw_merch.strip().splitlines()[0].strip().strip('"').strip("'")[:60]
                body = " ".join(l.strip() for l in raw_body.splitlines() if l.strip())
                if len(merch) < 3 or len(body) < 10 or len(body) > 250:
                    continue
                if "category" in body.lower() or "subcategory" in body.lower():
                    continue
                if any(k in forbidden_keys for k in f5.merchant_collision_keys(merch)):
                    continue
                dk = f6.dedup_key(body)
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
        if (st // bs) % 10 == 0:
            update_status("V7_LLM_SYNTH", done=min(st + bs, len(jobs)), total=len(jobs), kept=len(rows_out), model=mdl_id)
            print(f"[v7_synth] {min(st + bs, len(jobs))}/{len(jobs)} kept={len(rows_out)}", flush=True)

    del mdl, tok
    clear_cuda()
    return rows_out, mdl_id


def run_stage1a_foundry_v7():
    import run_foundry_v4 as f4
    import run_foundry_v5 as f5
    import run_foundry_v6_a100 as f6

    update_status("STAGE_1A_FOUNDRY_V7_START")
    t_all = time.perf_counter()

    f6.OUT_DIR = OUT_DIR
    f6.STATUS_PATH = OUT_DIR / "foundry_v7_status.json"
    f6.V5_DATASET_GZ = Path("/content/dataset_malaysian_v5.jsonl.gz")
    f6.HOLDOUT_V3_PATH = Path("/content/holdout_v3.json")
    f6.HOLDOUT_V4_PATH = Path("/content/holdout_v4.json")
    f6.V5_HARD_TEST_PATH = Path("/content/v5_hard_test.json")
    f6.OOD_MISC_TEST_PATH = Path("/content/ood_misc_test.json")
    f6.TEACHER_CACHE_PATH = OUT_DIR / "teacher_logits_v7.npz"
    f6.S1_HOLD_BIN_GZ = OUT_DIR / "moneylens_classifier_v7_holdout.bin.gz"
    f6.S1_PROD_BIN_GZ = OUT_DIR / "moneylens_classifier_v7.bin.gz"
    f6.S2_HOLD_BIN_GZ = OUT_DIR / "moneylens_reranker_v7_holdout.bin.gz"
    f6.S2_PROD_BIN_GZ = OUT_DIR / "moneylens_reranker_v7.bin.gz"
    f6.PARITY_PATH = OUT_DIR / "foundry_v7_parity_vectors.json"
    f6.REPORT_PATH = OUT_DIR / "foundry_v7_report.json"

    v3_h = json.loads(f6.HOLDOUT_V3_PATH.read_text(encoding="utf-8"))
    v4_h = json.loads(f6.HOLDOUT_V4_PATH.read_text(encoding="utf-8"))
    v5_hard_rows = json.loads(f6.V5_HARD_TEST_PATH.read_text(encoding="utf-8"))
    ood_test_rows = json.loads(f6.OOD_MISC_TEST_PATH.read_text(encoding="utf-8"))

    base_samples = f5.read_jsonl_any(f6.V5_DATASET_GZ)
    base_splits = f5.build_v5_splits(base_samples, v3_h, v4_h)
    v6_synth_p = Path("/content/v6_synth.jsonl.gz")
    v6_rows = f5.read_jsonl_any(v6_synth_p) if v6_synth_p.exists() else []

    seen_texts = (
        {f6.dedup_key(r["raw_text"]) for r in base_samples}
        | {f6.dedup_key(r["raw_text"]) for r in v6_rows}
        | {f6.dedup_key(r["raw_text"]) for r in ood_test_rows}
        | {f6.dedup_key(r["raw_text"]) for r in v3_h}
        | {f6.dedup_key(r["raw_text"]) for r in v4_h}
        | {f6.dedup_key(r["raw_text"]) for r in v5_hard_rows}
    )
    forbidden_merch = f5.build_forbidden_merchant_keys(v3_h, v4_h)
    for r in v5_hard_rows:
        forbidden_merch |= f5.merchant_collision_keys(r["expected_merchant"])

    v7_cache_p = OUT_DIR / "v7_synth.jsonl.gz"
    if v7_cache_p.exists():
        v7_rows = f5.read_jsonl_any(v7_cache_p)
        qwen_model = "cached"
    else:
        v7_rows, qwen_model = synthesize_v7_targeted_rows(f5, f6, forbidden_merch, seen_texts)
        with gzip.open(v7_cache_p, "wt", encoding="utf-8") as gz:
            for r in v7_rows:
                gz.write(json.dumps(r, ensure_ascii=False) + "\n")

    # Zero-leakage check against all holdouts
    hold_texts = {f6.dedup_key(r["raw_text"]) for r in (v3_h + v4_h + v5_hard_rows + ood_test_rows)}
    for r in v6_rows + v7_rows:
        assert f6.dedup_key(r["raw_text"]) not in hold_texts, "Synthetic text collided with blind holdout!"

    all_synth = v6_rows + v7_rows
    samples = list(base_samples) + all_synth
    splits = dict(base_splits)
    splits["train_idx"] = list(base_splits["train_idx"]) + list(range(len(base_samples), len(samples)))
    print(f"[Foundry v7] base={len(base_samples)} v6={len(v6_rows)} v7={len(v7_rows)} train={len(splits['train_idx'])} cal={len(splits['cal_idx'])}", flush=True)

    ood_train, ood_cal, _ = f5.build_ood_misc_split(n_train=900, n_cal=120, n_test=200, seed=20261123)

    if f6.TEACHER_CACHE_PATH.exists():
        npz = np.load(f6.TEACHER_CACHE_PATH)
        ens_both, ens_raw = npz["ens_both"], npz["ens_raw"]
        ens_summary = json.loads(npz["ens_summary_json"].item())
    else:
        f5.update_status = f6.update_status
        t1 = f5.train_hf_teacher("BAAI/bge-m3", samples, splits, epochs=3, max_len=176)
        t2 = f5.train_hf_teacher("intfloat/multilingual-e5-large", samples, splits, epochs=3, max_len=176)
        t3 = f6.train_highdim_300k_oof(samples, splits)
        ens_both, ens_raw, ens_summary = f5.blend_teacher_logits(t1, t2, t3, samples, splits)
        np.savez_compressed(f6.TEACHER_CACHE_PATH, ens_both=ens_both, ens_raw=ens_raw, ens_summary_json=json.dumps(ens_summary))
        del t1, t2, t3
        clear_cuda()

    # Boost sample weight for v7_ hard-contrast rows (propagates to both Stage 1 & Stage 2)
    def _build_v7_weights(train_rows, clean_rows, ens_both_clean):
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
                    * (2.0 if str(r.get("edge_case", "")).startswith("v7_") else 1.0)
                )
                for i, r in enumerate(train_rows)
            ],
            dtype=np.float32,
        )
        sw_4x = np.concatenate([base_sw, base_sw, base_sw * 0.7, base_sw * 0.7])
        return base_sw, sw_4x, int(np.sum(downweight_mask))

    f6._build_stage1_weights = _build_v7_weights
    s1_bundle = f6.run_stage1_v6(samples, splits, ens_both, ens_raw, ood_train)

    f5.stage1_predict_rows = lambda exp, vb_arg, rows_mt: f6.mlcl_predict_rows_batch(exp, vb_arg, rows_mt, f6.CLASSES_75)
    f5.RERANKER_HOLDOUT_GZ = f6.S2_HOLD_BIN_GZ
    hold_s2 = f5.train_stage2_reranker(s1_bundle, samples, splits, ens_both, ens_raw, ood_train, ood_cal, s1_drop_rate=0.42)

    update_status("V7_TRAINING_FULL_PROD")
    prod_idx = list(splits["train_idx"]) + list(splits["cal_idx"])
    clean_prod_rows = [samples[i] for i in prod_idx]
    prod_rows = clean_prod_rows + list(ood_train) + list(ood_cal)
    base_sw_p, sw_4p, _ = _build_v7_weights(prod_rows, clean_prod_rows, ens_both[prod_idx])
    n_ood_p = len(ood_train) + len(ood_cal)
    tz_bp = np.vstack([ens_both[prod_idx], np.zeros((n_ood_p, 75), dtype=np.float16)])
    tz_rp = np.vstack([ens_raw[prod_idx], np.zeros((n_ood_p, 75), dtype=np.float16)])
    vb_p = f4.build_tfidf_vocab_and_matrix(prod_rows, f6.CLASSES_75, f6.CLS2ID_75, teacher_z_both=tz_bp, teacher_z_raw=tz_rp, max_w_feats=16000, max_c_feats=14000)

    wcfg = s1_bundle["winner_cfg"]
    if wcfg["kind"] == "svc":
        from sklearn.svm import LinearSVC
        svc_p = LinearSVC(C=wcfg.get("C", 0.30), dual="auto", class_weight="balanced", max_iter=2500, random_state=42)
        svc_p.fit(vb_p["X"], vb_p["y"], sample_weight=sw_4p)
        Wp, bp = svc_p.coef_.astype(np.float64), svc_p.intercept_.astype(np.float64)
    else:
        Wp, bp = f6._fit_distill_s1(vb_p, sw_4p, len(prod_rows), len(prod_idx), alpha=float(wcfg.get("alpha", 0.50)))

    cal_rows = [samples[i] for i in splits["cal_idx"]]
    y_cal = f5._rows_labels_75(cal_rows)
    prod_s1 = f6.adaptive_mlcl_calibrate(
        Wp, bp, f6.CLASSES_75, vb_p,
        f4.transform_with_vocab([r["raw_text"] for r in cal_rows], vb_p),
        f4.transform_with_vocab([f"{r['expected_merchant']}\n{r['raw_text']}" for r in cal_rows], vb_p),
        y_cal, max_gz_bytes=f6.MAX_MLCL_GZ_BYTES, fixed_temperature=s1_bundle["hold_export"]["temperature"],
    )
    f6.S1_PROD_BIN_GZ.write_bytes(prod_s1["gz_bytes"])
    assert len(prod_s1["gz_bytes"]) < 1_000_000

    full_splits = dict(splits)
    full_splits["train_idx"] = prod_idx
    prod_oof = f6._compute_grouped_oof_s1(prod_rows, vb_p, sw_4p, "distill", 1.0)
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
    f5.RERANKER_HOLDOUT_GZ = OUT_DIR / "_tmp_prod_v7_s2.bin.gz"
    prod_s2 = f5.train_stage2_reranker(prod_s1_bundle, samples, full_splits, ens_both, ens_raw, [], ood_cal, s1_drop_rate=0.42)
    final_s2_gz = f5.serialize_reranker_bin_gz(prod_s2["weights"], f6.CLASSES_75, hold_s2["temperature"], hold_s2["ood_misc_threshold"])
    f6.S2_PROD_BIN_GZ.write_bytes(final_s2_gz)
    assert len(final_s2_gz) < f6.MAX_MLRR_GZ_BYTES

    s1_vecs, s2_vecs = [], []
    prod_s1_preds = f6.mlcl_predict_rows_batch(prod_s1, vb_p, [(r["expected_merchant"], r["raw_text"]) for r in cal_rows[:100]], f6.CLASSES_75)
    md_s2 = f5.load_reranker_bin_gz(final_s2_gz)
    s2_raw_l, _ = f5.reranker_int8_predict_batch(md_s2, [(cal_rows[i]["expected_merchant"], cal_rows[i]["raw_text"], prod_s1_preds[i]) for i in range(min(100, len(cal_rows)))])
    for i in range(min(100, len(cal_rows))):
        r = cal_rows[i]
        out_mlcl = f6.mlcl_predict_parity(prod_s1, vb_p, r["expected_merchant"], r["raw_text"], f6.CLASSES_75)
        if out_mlcl is not None:
            lg, pr = out_mlcl
            s1_vecs.append({"merchant": r["expected_merchant"], "text": r["raw_text"], "bestCat": f6.CLASSES_75[int(np.argmax(pr))].split("||")[0], "bestProb": round(float(np.max(pr)), 6), "probs": [round(float(x), 6) for x in pr], "logits": [round(float(x), 6) for x in lg[:10]]})
        s2_vecs.append({"merchant": r["expected_merchant"], "text": r["raw_text"], "s1": {"prob": round(float(prod_s1_preds[i]["prob"]), 6), "margin": round(float(prod_s1_preds[i]["margin"]), 6), "signals": int(prod_s1_preds[i]["signals"]), "probs": [round(float(x), 6) for x in prod_s1_preds[i]["probs"]]}, "raw_logits": [round(float(x), 6) for x in s2_raw_l[i]]})
    f6.PARITY_PATH.write_text(json.dumps({"mlcl": s1_vecs, "mlrr": s2_vecs}, ensure_ascii=False), encoding="utf-8")

    rep = {
        "total_seconds": round(time.perf_counter() - t_all, 1),
        "qwen_model": qwen_model,
        "v6_synth_used": len(v6_rows),
        "v7_synth_kept": len(v7_rows),
        "ensemble": ens_summary,
        "stage1_prod_gz": len(prod_s1["gz_bytes"]),
        "stage1_prod_features": prod_s1["active_features"],
        "stage1_cal_metrics": prod_s1["cal_metrics"],
        "stage2_prod_gz": len(final_s2_gz),
        "stage2_temp": hold_s2["temperature"],
        "stage2_ood_thresh": hold_s2["ood_misc_threshold"],
        "stage2_best_cal_macro_f1": hold_s2["best_cal_macro_f1"],
        "zero_holdout_leakage": True,
    }
    f6.REPORT_PATH.write_text(json.dumps(rep, indent=2), encoding="utf-8")
    print("[Sub-stage 1A DONE] Foundry v7:", json.dumps(rep), flush=True)
    clear_cuda()
    return rep


def synthesize_ng3_qwen(t34, ng2, blind_excl: set):
    """Generates ~5,500 hard-contrast notifications (3 per prompt) for BM traps, refunds, transfers vs reloads."""
    from transformers import AutoModelForCausalLM, AutoTokenizer

    vram_gb = (torch.cuda.get_device_properties(0).total_memory / 1e9) if torch.cuda.is_available() else 0.0
    cands = ("Qwen/Qwen2.5-14B-Instruct", "Qwen/Qwen2.5-7B-Instruct") if vram_gb >= 36.0 else ("Qwen/Qwen2.5-7B-Instruct", "Qwen/Qwen2.5-3B-Instruct")
    mdl, tok, mdl_id = None, None, "none"
    for cand in cands:
        try:
            update_status("NG3_LLM_LOAD", model=cand, vram_gb=round(vram_gb, 1))
            print(f"[ng3_synth] Loading {cand} (VRAM={vram_gb:.1f}GB)...", flush=True)
            tok = AutoTokenizer.from_pretrained(cand, padding_side="left")
            if tok.pad_token is None:
                tok.pad_token = tok.eos_token
            dtype = torch.bfloat16 if torch.cuda.is_bf16_supported() else torch.float16
            mdl = AutoModelForCausalLM.from_pretrained(cand, torch_dtype=dtype, device_map="cuda:0" if torch.cuda.is_available() else "auto")
            mdl.eval()
            mdl_id = cand
            break
        except Exception as exc:
            print(f"[ng3_synth] Could not load {cand}: {exc}", flush=True)
            tok, mdl = None, None
            clear_cuda()

    if mdl is None:
        return [], "none"

    rng = random.Random(20261128)
    specs = [
        ("reject", "trap_fail", "Bahasa Melayu/Manglish declined or failed payment (gagal, ditolak, tidak berjaya, baki tidak mencukupi, tiada potongan, tak lepas, kena reject) or CJK upcoming auto-debit reminder (即将扣款, 请确保账户余额充足) where NO money left", 40),
        ("reject", "trap_otp", "active OTP/TAC/PAC/3DS/Secure2u authorization request asking user to verify/approve before debit", 18),
        ("reject", "trap_bill", "upcoming credit card statement due reminder or plain available balance alert with no transaction", 18),
        ("reject", "trap_promo", "marketing voucher, cashback spend-to-win campaign, or promo discount", 14),
        ("expense", "real_expense", "completed merchant payment in Manglish, colloquial BM, or verbless bank/FPX/JomPAY push slip", 34),
        ("income", "real_income", "incoming salary, DuitNow money received from a friend (masuk akaun / 到账 / 收到), or cashback reward credited", 22),
        ("refund", "real_refund", "completed refund credited back for cancelled order (bayaran balik dikembalikan, Order cancelled refund credited back, 退款到账)", 26),
        ("transfer", "real_transfer", "completed own-account transfer between user's Malaysian bank accounts (pindahan wang ke akaun sendiri, Moved RM ... into CIMB OCTO, 转账至您的账户)", 26),
        ("reload", "real_reload", "completed e-wallet reload / tambah nilai / top-up / 已为...充值 into TNG, ShopeePay, Boost, GrabPay, or MAE wallet", 22),
    ]
    jobs = []
    for expected_kind, subtype, desc, reps in specs:
        for _ in range(reps):
            for _b in range(10):
                pkg, src_label = rng.choice(t34.TRAIN_APPS)
                merch = rng.choice(t34.PEOPLE_TRAIN if expected_kind == "income" else t34.TRAIN_MERCHANTS)
                minor = t34.rand_minor(rng)
                amt = f"RM {minor/100:.2f}"
                lang = rng.choice(["English", "Bahasa Melayu", "Manglish", "Simplified Chinese"])
                u_msg = (
                    f"Write 3 DISTINCT realistic single-line Malaysian phone notifications from '{src_label}' in {lang}.\n"
                    f"Scenario: {desc}.\nCounterparty: {merch}. Include exact amount '{amt}'.\n"
                    f"Output strictly 3 numbered lines:\n1. <line 1>\n2. <line 2>\n3. <line 3>"
                )
                jobs.append((expected_kind, subtype, pkg, src_label, lang, minor, u_msg))

    rng.shuffle(jobs)
    bs = 64 if vram_gb >= 36.0 else 24
    kept = []
    seen_local = set(blind_excl)

    for st in range(0, len(jobs), bs):
        chunk = jobs[st : st + bs]
        prompts = [
            tok.apply_chat_template(
                [{"role": "system", "content": "You generate realistic single-line Malaysian bank and e-wallet push notifications."},
                 {"role": "user", "content": j[6]}],
                tokenize=False, add_generation_prompt=True,
            )
            for j in chunk
        ]
        enc = tok(prompts, return_tensors="pt", padding=True, truncation=True, max_length=256).to(mdl.device)
        with torch.no_grad():
            out = mdl.generate(**enc, max_new_tokens=160, do_sample=True, temperature=0.85, top_p=0.92, pad_token_id=tok.pad_token_id)
        dec = tok.batch_decode(out[:, enc["input_ids"].shape[1] :], skip_special_tokens=True)
        for j, gen in zip(chunk, dec):
            expected_kind, subtype, pkg, src_label, lang_name, minor, _ = j
            amt_s = f"{minor/100:.2f}"
            for raw_line in gen.splitlines():
                line = re.sub(r"^\s*[1-3][.)]\s*", "", raw_line).strip().strip('"').strip("'")
                if not line or len(line) < 12 or len(line) > 220 or t34.FORBIDDEN_SCRIPT_RE.search(line):
                    continue
                nk = ng2.norm_key(line)
                if nk in seen_local:
                    continue
                if amt_s not in line and f"{minor/100:,.2f}" not in line:
                    continue
                low = line.lower()
                if expected_kind == "reject":
                    if subtype == "trap_otp" and not (t34.RE_OTP_WORD.search(low) or t34.RE_OTP_DIGITS.search(low) or any(k in low for k in t34.CJK_OTP_WORDS)):
                        continue
                    if subtype == "trap_fail" and not (t34.RE_FAIL.search(low) or t34.RE_CANCEL.search(low) or any(k in low for k in ("ditolak", "tak lepas", "reject", "gagal", "未扣款", "失败", "即将扣款", "tidak berjaya"))):
                        continue
                    if subtype == "trap_bill" and not (t34.RE_BILL_DUE.search(low) or t34.RE_PENDING.search(low) or "balance" in low or "baki" in low or any(k in low for k in t34.CJK_BILL_DUE_WORDS + t34.CJK_PENDING_WORDS + ("即将", "余额"))):
                        continue
                    if subtype == "trap_promo" and not (t34.RE_PROMO.search(low) or any(k in low for k in t34.CJK_PROMO_WORDS)):
                        continue
                else:
                    if t34.RE_FAIL.search(low) or "tak lepas" in low or "tidak ditolak" in low or "即将扣款" in line or t34.RE_OTP_DIGITS.search(low) or any(k in low for k in t34.CJK_FAIL_WORDS + t34.CJK_OTP_WORDS):
                        continue
                    if t34.RE_BILL_DUE.search(low) or t34.RE_PROMO.search(low):
                        continue
                    if expected_kind == "refund" and not (t34.RE_REFUND.search(low) or any(k in low for k in t34.CJK_REFUND_WORDS)):
                        continue
                    if expected_kind == "reload" and not any(w in low for w in ("reload", "topup", "top-up", "top up", "tambah nilai", "充值")):
                        continue
                seen_local.add(nk)
                lang_code = {"English": "en", "Bahasa Melayu": "bm", "Manglish": "manglish", "Simplified Chinese": "zh"}[lang_name]
                kept.append({
                    "text": line, "source": pkg, "source_label": src_label, "lang": lang_code, "subtype": subtype,
                    "expected_captured": expected_kind != "reject", "expected_kind": expected_kind,
                    "expected_amount_minor": 0 if expected_kind == "reject" else minor, "is_llm_paraphrase": True,
                })
        if (st // bs) % 10 == 0:
            update_status("NG3_LLM_SYNTH", done=min(st + bs, len(jobs)), total=len(jobs), kept=len(kept), model=mdl_id)
            print(f"[ng3_synth] {min(st + bs, len(jobs))}/{len(jobs)} kept={len(kept)}", flush=True)

    del mdl, tok
    clear_cuda()
    return kept, mdl_id


def run_stage1b_notif_gate_v3():
    import run_track34_notif_gate_v1 as t34
    import run_notif_gate_v2_a100 as ng2

    update_status("STAGE_1B_NOTIF_GATE_V3_START")
    t0 = time.perf_counter()

    ng2.OUT_DIR = OUT_DIR
    ng2.STATUS_PATH = OUT_DIR / "notif_gate_v3_status.json"
    ng2.BIN_GZ_PATH = OUT_DIR / "moneylens_notif_gate_v3.bin.gz"
    ng2.PARITY_PATH = OUT_DIR / "notif_gate_v3_parity_vectors.json"
    ng2.REPORT_PATH = OUT_DIR / "notif_gate_v3_report.json"
    ng2.BLIND_T3_PATH = Path("/content/track3_hard_blind.json")
    ng2.BLIND_T5_PATH = Path("/content/track5_adversarial_blind.json")

    blind_excl = ng2.load_blind_exclusion_set()
    rng = random.Random(20261128)
    llm_rows, qwen_mdl = synthesize_ng3_qwen(t34, ng2, blind_excl)
    rng.shuffle(llm_rows)
    n_dev_llm = max(350, int(0.12 * len(llm_rows)))
    dev_llm, tr_llm = llm_rows[:n_dev_llm], llm_rows[n_dev_llm:]

    subtypes = [
        ("trap_otp", 0.11),
        ("trap_fail_subtle", 0.19),
        ("trap_fail", 0.08),
        ("trap_bill_promo_pending", 0.14),
        ("real_expense_rail_verbless", 0.14),
        ("real_expense", 0.10),
        ("real_income", 0.07),
        ("real_refund_hard", 0.07),
        ("real_transfer", 0.06),
        ("real_reload", 0.04),
    ]
    seen = set(blind_excl) | {ng2.norm_key(r["text"]) for r in llm_rows}

    def fill_templates(target_n):
        rows = []
        tries = 0
        while len(rows) < target_n and tries < target_n * 8:
            tries += 1
            r = rng.random()
            cum, st = 0.0, subtypes[-1][0]
            for nm, wt in subtypes:
                cum += wt
                if r <= cum:
                    st = nm
                    break
            txt, pkg, src_label, lang, cap, kind, amt = ng2.gen_v2_hard_template_case("train", st, rng)
            k = ng2.norm_key(txt)
            if k in seen:
                continue
            seen.add(k)
            rows.append({
                "text": txt, "source": pkg, "source_label": src_label, "lang": lang, "subtype": st,
                "expected_captured": cap, "expected_kind": kind, "expected_amount_minor": amt, "is_llm_paraphrase": False,
            })
        return rows

    train_rows = tr_llm + fill_templates(max(32000, 42000 - len(tr_llm)))
    dev_rows = dev_llm + fill_templates(max(3200, 4200 - len(dev_llm)))
    rng.shuffle(train_rows)
    rng.shuffle(dev_rows)
    assert {ng2.norm_key(r["text"]) for r in train_rows}.isdisjoint(blind_excl)
    assert {ng2.norm_key(r["text"]) for r in dev_rows}.isdisjoint(blind_excl)

    teacher_z_tr, teacher_dev_acc = ng2.train_bge_m3_teacher(train_rows, dev_rows)
    clear_cuda()

    update_status("NG3_DISTILL_QAT", train_n=len(train_rows), dev_n=len(dev_rows))
    tr_sp, tr_sf, tr_y = t34.precompute_features(train_rows)
    dv_sp, dv_sf, dv_y = t34.precompute_features(dev_rows)

    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    torch.manual_seed(20261128)
    net = ng2.QatNotifGateNet().to(device)
    cw = torch.tensor([1.55, 1.0, 1.0, 1.10, 1.10, 1.05], device=device)
    opt = torch.optim.AdamW(net.parameters(), lr=3e-3, weight_decay=2e-4)
    epochs = 32
    sched = torch.optim.lr_scheduler.CosineAnnealingLR(opt, T_max=epochs, eta_min=1e-4)

    tr_sf_t = torch.from_numpy(tr_sf).to(device)
    tr_y_t = torch.from_numpy(tr_y).to(device)
    tr_tz_t = torch.from_numpy(teacher_z_tr).to(device)
    dv_sf_t = torch.from_numpy(dv_sf).to(device)

    best_score, best_sd = -1.0, None
    bs = 256
    nr = np.random.default_rng(20261128)
    for ep in range(1, epochs + 1):
        qat = ep > (epochs - 7)
        net.train()
        perm = nr.permutation(len(tr_sp))
        for s in range(0, len(tr_sp), bs):
            idx = perm[s : s + bs]
            bsp = [tr_sp[int(i)] for i in idx]
            opt.zero_grad(set_to_none=True)
            lg = net(bsp, tr_sf_t[idx], qat=qat)
            ce = F.cross_entropy(lg, tr_y_t[idx], weight=cw, label_smoothing=0.02)
            kl = F.kl_div(F.log_softmax(lg / 2.0, dim=-1), F.softmax(tr_tz_t[idx] / 2.0, dim=-1), reduction="batchmean") * 4.0
            loss = 0.60 * ce + 0.40 * kl
            loss.backward()
            torch.nn.utils.clip_grad_norm_(net.parameters(), 2.0)
            opt.step()
        sched.step()
        net.eval()
        with torch.no_grad():
            dl = torch.cat([net(dv_sp[s : s + 512], dv_sf_t[s : s + 512], qat=qat).cpu() for s in range(0, len(dv_sp), 512)], dim=0)
            pr = dl.argmax(dim=1).numpy()
            sc = 0.45 * float(np.mean(pr[dv_y == 0] == 0)) + 0.35 * float(np.mean(pr[dv_y > 0] > 0)) + 0.20 * float(np.mean(pr == dv_y))
            if sc > best_score:
                best_score = sc
                best_sd = {k: v.detach().cpu().clone() for k, v in net.state_dict().items()}
        if ep in (1, 16, 26, epochs):
            print(f"[ng3 QAT={qat} ep {ep}/{epochs}] dev_score={sc:.4f}", flush=True)

    net.load_state_dict(best_sd)
    sd = {k: v.detach().cpu().numpy() for k, v in net.state_dict().items()}
    model_q = {
        "emb": t34.quantize_row_int8(sd["emb"]),
        "w1": t34.quantize_row_int8(sd["w1.weight"]),
        "b1": sd["w1.bias"].astype(np.float32),
        "ln1_w": sd["ln1.weight"].astype(np.float32),
        "ln1_b": sd["ln1.bias"].astype(np.float32),
        "w2": t34.quantize_row_int8(sd["w2.weight"]),
        "b2": sd["w2.bias"].astype(np.float32),
    }

    raw_dev_logits = np.stack([t34.dequantize_forward_single(bw, sf, model_q, 1.0)[0] for bw, sf in zip(dv_sp, dv_sf)], axis=0)
    best_temp, best_nll = 1.0, 1e9
    for tc in np.linspace(0.6, 2.2, 33):
        sc_l = raw_dev_logits / tc
        sc_l -= sc_l.max(axis=1, keepdims=True)
        pr_l = np.exp(sc_l) / np.exp(sc_l).sum(axis=1, keepdims=True)
        nll = -float(np.mean(np.log(np.clip(pr_l[np.arange(len(dv_y)), dv_y], 1e-7, 1.0))))
        if nll < best_nll:
            best_nll, best_temp = nll, float(tc)

    sc_l = raw_dev_logits / best_temp
    sc_l -= sc_l.max(axis=1, keepdims=True)
    dev_probs = np.exp(sc_l) / np.exp(sc_l).sum(axis=1, keepdims=True)
    dev_pred = np.argmax(dev_probs, axis=1)

    rej_th, ov_cap_th, ov_rej_th = 0.50, 0.88, 0.90
    buf = bytearray(b"MLNG")
    buf += struct.pack(">iiiiii", 1, ng2.V2_BUCKETS, t34.EMB_DIM, t34.STRUCT_DIM, t34.HIDDEN_DIM, t34.NUM_CLASSES)
    buf += struct.pack(">ffff", float(best_temp), rej_th, ov_cap_th, ov_rej_th)

    def pack_qmat(sc_a, q_a):
        r, c = q_a.shape
        return struct.pack(">ii", r, c) + struct.pack(f">{r}f", *sc_a.tolist()) + q_a.astype(np.int8).tobytes()

    buf += pack_qmat(*model_q["emb"])
    buf += pack_qmat(*model_q["w1"])
    buf += struct.pack(f">{len(model_q['b1'])}f", *model_q["b1"].tolist())
    buf += struct.pack(f">{len(model_q['ln1_w'])}f", *model_q["ln1_w"].tolist())
    buf += struct.pack(f">{len(model_q['ln1_b'])}f", *model_q["ln1_b"].tolist())
    buf += pack_qmat(*model_q["w2"])
    buf += struct.pack(f">{len(model_q['b2'])}f", *model_q["b2"].tolist())

    with gzip.open(ng2.BIN_GZ_PATH, "wb", compresslevel=9) as gz:
        gz.write(buf)
    gz_bytes = ng2.BIN_GZ_PATH.stat().st_size
    assert gz_bytes <= 240_000, f"NG v3 too large: {gz_bytes}"

    parity_vecs = []
    for i in range(min(100, len(dev_rows))):
        lg, pr = t34.dequantize_forward_single(dv_sp[i], dv_sf[i], model_q, best_temp)
        parity_vecs.append({
            "id": f"ng3-dev-{i}", "text": dev_rows[i]["text"], "source": dev_rows[i]["source"],
            "expected_kind": dev_rows[i]["expected_kind"],
            "logits": [round(float(x), 6) for x in lg], "probs": [round(float(x), 6) for x in pr],
        })
    ng2.PARITY_PATH.write_text(json.dumps(parity_vecs, ensure_ascii=False, indent=2), encoding="utf-8")

    rep = {
        "total_seconds": round(time.perf_counter() - t0, 1),
        "qwen_model": qwen_mdl,
        "llm_synth_kept": len(llm_rows),
        "teacher_backbone": "BAAI/bge-m3",
        "teacher_dev_acc": round(teacher_dev_acc, 4),
        "train_count": len(train_rows),
        "dev_count": len(dev_rows),
        "num_buckets": ng2.V2_BUCKETS,
        "gz_bytes": gz_bytes,
        "temperature": round(best_temp, 4),
        "dev_metrics_int8": {
            "accuracy": round(float(np.mean(dev_pred == dv_y)), 4),
            "trapRejection": round(float(np.mean(dev_pred[dv_y == 0] == 0)), 4),
            "realCaptureRecall": round(float(np.mean(dev_pred[dv_y > 0] > 0)), 4),
            "realKindExact": round(float(np.mean(dev_pred[dv_y > 0] == dv_y[dv_y > 0])), 4),
        },
        "blind_exclusion_verified": True,
    }
    ng2.REPORT_PATH.write_text(json.dumps(rep, indent=2), encoding="utf-8")
    print("[Sub-stage 1B DONE] NotifGate v3:", json.dumps(rep), flush=True)
    clear_cuda()
    return rep


def main():
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    t_all = time.perf_counter()
    try:
        rep_1a = run_stage1a_foundry_v7()
        rep_1b = run_stage1b_notif_gate_v3()
        summary = {
            "total_seconds": round(time.perf_counter() - t_all, 1),
            "stage_1a_foundry_v7": rep_1a,
            "stage_1b_notif_gate_v3": rep_1b,
        }
        SUMMARY_REPORT_PATH.write_text(json.dumps(summary, indent=2, ensure_ascii=False), encoding="utf-8")
        update_status("DONE", total_seconds=summary["total_seconds"])
        print(f"\nPHASE C RUN 1 DONE in {summary['total_seconds']}s!", flush=True)
    except Exception as exc:
        traceback.print_exc()
        update_status("ERROR", error=repr(exc))
        raise


if __name__ == "__main__":
    main()
