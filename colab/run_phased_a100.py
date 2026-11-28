#!/usr/bin/env python3
"""
Phase D Colab A100 Run (never H100):
  Sub-stage A: ReceiptFieldTagger CRF v2 (OCR glued SSM + typo augmentation + L2 sweep, < 240 KB)
  Sub-stage B: SemanticSearch v5 (single e5-large 1024-d + BM/ZH/MG synonyms + 3-seed checkpoint + OOD<=0.015, < 512 KB)
  Sub-stage C: NotificationGate v4 (clean balanced 1.0x + 2-teacher bge-m3+e5-large logit fusion + mild OOF mining, <= 240 KB)
"""
import gc
import gzip
import json
import math
import os
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

OUT_DIR = Path("/content/outputs_phased")
STATUS_PATH = OUT_DIR / "phased_run_status.json"
SUMMARY_REPORT_PATH = OUT_DIR / "phased_run_report.json"


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


def norm_key(text: str) -> str:
    return re.sub(r"\s+", " ", (text or "").lower()).strip()


# ===========================================================================
# Sub-stage A: ReceiptFieldTagger CRF v2 (< 240 KB)
# ===========================================================================
def _augment_crf_receipt(row, rng: random.Random):
    lines = list(row["lines"])
    labels = list(row["labels"])
    m_idx = next((i for i, lb in enumerate(labels) if lb == "MERCHANT"), -1)
    if m_idx >= 0:
        op = rng.random()
        if op < 0.45:
            ssm = f"({rng.randint(100000, 999999)}-{rng.choice('ABCDEFGHJKMNPQRSTUVWXYZ')})"
            lines[m_idx] = f"SSM Reg No: {ssm}  {lines[m_idx]}"
        elif op < 0.72:
            lines[m_idx] = f"{lines[m_idx]} {rng.randint(2020010, 2025099)}"
        elif op < 0.90:
            lines[m_idx] = f"{lines[m_idx]} SDN BH"
    new_lines = []
    for ln, lb in zip(lines, labels):
        if lb == "HEADER_META" and re.search(r"\b[0-9]{5}\b", ln) and rng.random() < 0.6:
            ln = re.sub(r"\b([0-9]{4})0\s+", r"\1o", ln, count=1)
        elif lb in ("GRAND_TOTAL", "SUBTOTAL", "TAX_SST") and rng.random() < 0.35:
            ln = re.sub(r"\bRM\b", rng.choice(["Rm", "RN1", "MYR"]), ln, count=1)
        elif lb == "DATE" and rng.random() < 0.35:
            ln = ln.replace("Oct", "0ct").replace("Ogos", "0gos").replace("Nov", "n0v")
        new_lines.append(ln)
    out = dict(row)
    out["id"] = f"{row.get('id', 'r')}_aug_{rng.randint(1000, 999999)}"
    out["lines"] = new_lines
    return out


def run_stage_a_crf_v2():
    import run_receipt_foundry_v1 as rf

    update_status("STAGE_A_CRF_V2_START")
    t0 = time.perf_counter()
    print("\n=== [Sub-stage A] ReceiptFieldTagger CRF v2 ===", flush=True)

    holdout_p = Path("/content/receipt_holdout_real_ocr.json")
    ds_p = Path("/content/receipt_vision_v1_dataset.jsonl.gz")
    assert holdout_p.exists() and ds_p.exists(), "Missing receipt dataset or holdout"

    holdout_rows = json.loads(holdout_p.read_text(encoding="utf-8"))
    holdout_ids = {str(r.get("id", "")) for r in holdout_rows}
    holdout_raw = {norm_key(r.get("raw_text", "")) for r in holdout_rows if r.get("raw_text")}

    all_rows = []
    with gzip.open(ds_p, "rt", encoding="utf-8") as f:
        for ln in f:
            s = ln.strip()
            if s:
                all_rows.append(json.loads(s))

    train_rows = [
        r for r in all_rows
        if r.get("split") == "train"
        and r.get("aligned_ok")
        and r.get("lines")
        and len(r["lines"]) == len(r.get("labels", []))
        and str(r.get("id", "")) not in holdout_ids
        and norm_key(r.get("raw_text", "")) not in holdout_raw
    ]
    val_rows = [
        r for r in all_rows
        if r.get("split") == "val"
        and r.get("aligned_ok")
        and r.get("lines")
        and len(r["lines"]) == len(r.get("labels", []))
        and str(r.get("id", "")) not in holdout_ids
        and norm_key(r.get("raw_text", "")) not in holdout_raw
    ]
    assert train_rows and val_rows, "Empty CRF train/val splits"
    assert not ({norm_key(r.get("raw_text", "")) for r in train_rows} & holdout_raw), "Leakage into holdout"

    rng = random.Random(20261128)
    aug_pool = rng.sample(train_rows, min(3500, len(train_rows)))
    aug_rows = [_augment_crf_receipt(r, rng) for r in aug_pool]
    full_train = train_rows + aug_rows
    rng.shuffle(full_train)

    rf.MAX_TAGGER_GZ_BYTES = 240_000
    vocab, f2id = rf.build_crf_vocab(full_train, max_lex_feats=4800, min_df=3)
    print(f"[CRF v2] train={len(full_train)} (+{len(aug_rows)} aug), val={len(val_rows)}, vocab={len(vocab)}", flush=True)

    train_enc = rf.featurize_dataset(full_train, f2id)
    val_enc = rf.featurize_dataset(val_rows, f2id)

    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    C = len(rf.LABELS)
    V = len(vocab)

    class LinearChainCRFV2(nn.Module):
        def __init__(self):
            super().__init__()
            self.emb = nn.EmbeddingBag(V, C, mode="sum")
            nn.init.zeros_(self.emb.weight)
            self.start = nn.Parameter(torch.zeros(C))
            self.end = nn.Parameter(torch.zeros(C))
            self.trans = nn.Parameter(torch.zeros(C, C))

        def nll_batch(self, batch_items):
            B = len(batch_items)
            lengths = [len(it[0]) for it in batch_items]
            T = max(lengths)
            emits = torch.zeros((B, T, C), dtype=torch.float32, device=device)
            gold = torch.zeros((B, T), dtype=torch.long, device=device)
            mask = torch.zeros((B, T), dtype=torch.bool, device=device)
            flat, offsets, b_idxs, t_idxs = [], [], [], []
            for b_i, (seqs, y, _) in enumerate(batch_items):
                L = len(seqs)
                mask[b_i, :L] = True
                gold[b_i, :L] = torch.tensor(y, dtype=torch.long, device=device)
                for t_i, idxs in enumerate(seqs):
                    if idxs:
                        offsets.append(len(flat))
                        flat.extend(idxs)
                        b_idxs.append(b_i)
                        t_idxs.append(t_i)
            if flat:
                emb_vecs = self.emb(
                    torch.tensor(flat, dtype=torch.long, device=device),
                    torch.tensor(offsets, dtype=torch.long, device=device),
                )
                emits[
                    torch.tensor(b_idxs, dtype=torch.long, device=device),
                    torch.tensor(t_idxs, dtype=torch.long, device=device),
                ] = emb_vecs

            alpha = self.start.unsqueeze(0) + emits[:, 0, :]
            last_idx = torch.tensor([L - 1 for L in lengths], dtype=torch.long, device=device)
            batch_ar = torch.arange(B, device=device)
            gold_score = self.start[gold[:, 0]] + emits[batch_ar, 0, gold[:, 0]]
            for t in range(1, T):
                m_t = mask[:, t]
                scores = alpha.unsqueeze(2) + self.trans.unsqueeze(0)
                next_alpha = torch.logsumexp(scores, dim=1) + emits[:, t, :]
                alpha = torch.where(m_t.unsqueeze(1), next_alpha, alpha)
                step_gold = self.trans[gold[:, t - 1], gold[:, t]] + emits[batch_ar, t, gold[:, t]]
                gold_score = gold_score + torch.where(m_t, step_gold, torch.zeros_like(step_gold))
            alpha = alpha + self.end.unsqueeze(0)
            gold_score = gold_score + self.end[gold[batch_ar, last_idx]]
            log_z = torch.logsumexp(alpha, dim=1)
            return (log_z - gold_score).sum()

    best_overall = None
    configs = [
        {"name": "c2_low_2e-4", "wd": 2e-4, "l1": 8e-5, "epochs": 16, "seed": 42},
        {"name": "c2_mid_8e-4", "wd": 8e-4, "l1": 6e-5, "epochs": 16, "seed": 1337},
        {"name": "c2_high_2e-3", "wd": 2e-3, "l1": 5e-5, "epochs": 16, "seed": 2026},
    ]

    for cfg in configs:
        torch.manual_seed(cfg["seed"])
        rng_ep = random.Random(cfg["seed"])
        model = LinearChainCRFV2().to(device)
        opt = torch.optim.AdamW(model.parameters(), lr=0.04, weight_decay=cfg["wd"])
        sched = torch.optim.lr_scheduler.CosineAnnealingLR(opt, T_max=cfg["epochs"], eta_min=0.004)
        best_val_sc, best_st = -1.0, None
        for ep in range(1, cfg["epochs"] + 1):
            model.train()
            order = list(range(len(train_enc)))
            rng_ep.shuffle(order)
            for b0 in range(0, len(order), 128):
                opt.zero_grad()
                b_items = [train_enc[i] for i in order[b0 : b0 + 128]]
                loss = (model.nll_batch(b_items) / len(b_items)) + cfg["l1"] * model.emb.weight.abs().sum() / V
                loss.backward()
                torch.nn.utils.clip_grad_norm_(model.parameters(), 5.0)
                opt.step()
            sched.step()
            s_np = model.start.detach().cpu().numpy()
            e_np = model.end.detach().cpu().numpy()
            t_np = model.trans.detach().cpu().numpy()
            em_np = model.emb.weight.detach().cpu().numpy()
            vm = rf.eval_crf_numpy(val_enc, s_np, e_np, t_np, em_np)
            macro_f1 = float(np.mean([vm["per_label"][lb]["f1"] for lb in rf.LABELS]))
            sc = 0.40 * vm["line_acc"] + 0.35 * vm["exact_grand_total_amt"] + 0.25 * macro_f1
            if sc > best_val_sc:
                best_val_sc = sc
                best_st = (s_np.copy(), e_np.copy(), t_np.copy(), em_np.copy())

        s_np, e_np, t_np, em_np = best_st
        lo, hi, best_th = 0.005, 0.25, 0.25
        for _ in range(12):
            mid = round((lo + hi) / 2.0, 4)
            ep_p = em_np.copy()
            ep_p[np.abs(ep_p) < mid] = 0.0
            _, gz_b, _ = rf.serialize_crf(s_np, e_np, t_np, ep_p, vocab)
            if len(gz_b) <= rf.MAX_TAGGER_GZ_BYTES:
                best_th = mid
                hi = mid
            else:
                lo = mid
        ep_p = em_np.copy()
        ep_p[np.abs(ep_p) < best_th] = 0.0
        raw_j, gz_b, f_stats = rf.serialize_crf(s_np, e_np, t_np, ep_p, vocab, best_th)
        vm_p = rf.eval_crf_numpy(val_enc, s_np, e_np, t_np, ep_p)
        mf1_p = float(np.mean([vm_p["per_label"][lb]["f1"] for lb in rf.LABELS]))
        pruned_sc = 0.40 * vm_p["line_acc"] + 0.35 * vm_p["exact_grand_total_amt"] + 0.25 * mf1_p
        print(f"[CRF v2 {cfg['name']}] pruned_score={pruned_sc:.4f} line={vm_p['line_acc']:.4f} amt={vm_p['exact_grand_total_amt']:.4f} macro_f1={mf1_p:.4f} gz={len(gz_b):,}", flush=True)
        if best_overall is None or pruned_sc > best_overall["score"]:
            best_overall = {
                "cfg": cfg, "score": pruned_sc, "val_metrics": vm_p, "macro_f1": round(mf1_p, 4),
                "gz_bytes": gz_b, "raw_json": raw_j, "feat_stats": f_stats, "prune_th": best_th,
            }

    out_gz = OUT_DIR / "moneylens_receipt_tagger_v2.json.gz"
    out_gz.write_bytes(best_overall["gz_bytes"])
    assert len(best_overall["gz_bytes"]) < 245_760, f"CRF v2 too large: {len(best_overall['gz_bytes'])}"
    rep = {
        "stage_seconds": round(time.perf_counter() - t0, 1),
        "winner": best_overall["cfg"],
        "gz_bytes": len(best_overall["gz_bytes"]),
        "prune_threshold": best_overall["prune_th"],
        "feat_stats": best_overall["feat_stats"],
        "val_macro_f1": best_overall["macro_f1"],
        "val_metrics": best_overall["val_metrics"],
        "train_rows": len(full_train),
        "val_rows": len(val_rows),
        "zero_holdout_leakage": True,
    }
    (OUT_DIR / "receipt_tagger_v2_report.json").write_text(json.dumps(rep, indent=2, ensure_ascii=False), encoding="utf-8")
    print(f"[Sub-stage A DONE] gz={rep['gz_bytes']:,} B | val={json.dumps(rep['val_metrics'])}", flush=True)
    clear_cuda()
    return rep


# ===========================================================================
# Sub-stage B: SemanticSearch v5 (Single e5-large 1024-d, 3 seeds, OOD <= 0.015)
# ===========================================================================
EXTRA_SYNONYM_PAIRS = [
    ("kos ubat", ("Health & medical", "Pharmacy & Medicine")),
    ("ubat hospital", ("Health & medical", "Hospital & Specialist")),
    ("rawatan klinik", ("Health & medical", "Clinic & GP")),
    ("periksa doktor", ("Health & medical", "Clinic & GP")),
    ("pembaikan rumah", ("Housing & property", "Home Repair & Maintenance")),
    ("baiki paip bocor", ("Housing & property", "Home Repair & Maintenance")),
    ("servis penyaman udara", ("Housing & property", "Home Repair & Maintenance")),
    ("bil pembetungan", ("Bills & utilities", "Water & Sewerage (Air Selangor / IWK)")),
    ("排污费", ("Bills & utilities", "Water & Sewerage (Air Selangor / IWK)")),
    ("potongan cukai bulanan", ("Taxes & government", "LHDN Income Tax / PCB")),
    ("cukai pendapatan", ("Taxes & government", "LHDN Income Tax / PCB")),
    ("kursus kemahiran", ("Education", "Upskilling & Certification")),
    ("bengkel latihan", ("Education", "Higher Education & Tuition")),
    ("学费补习", ("Education", "Higher Education & Tuition")),
    ("barang dapur", ("Groceries", "Supermarket & Hypermarket")),
    ("sayur ikan segar", ("Groceries", "Wet Market & Fresh Produce")),
    ("sabun cuci baju", ("Groceries", "Household & Cleaning Supplies")),
    ("duit minyak kereta", ("Transport", "Petrol & EV Charging")),
    ("isi minyak", ("Transport", "Petrol & EV Charging")),
    ("bayar tol", ("Transport", "Toll (TNG / RFID / PayDirect)")),
    ("bayar parkir", ("Transport", "Parking")),
    ("servis kereta", ("Transport", "Vehicle Maintenance & Service")),
    ("tambang grab", ("Transport", "E-Hailing (Grab / AirAsia Ride)")),
    ("tiket tren", ("Transport", "Public Transit (MRT / LRT / Bus)")),
    ("yuran transaksi luar negara", ("Fees & charges", "Foreign Currency Fee")),
    ("caj lewat bayar", ("Fees & charges", "Late Payment Fee")),
    ("derma kebajikan", ("Family & gifts", "Charity & Donations")),
    ("elaun ibu bapa", ("Family & gifts", "Parents Allowance")),
    ("tiket kapal terbang", ("Entertainment & travel", "Flights & Transport")),
    ("tempahan hotel", ("Entertainment & travel", "Hotel & Holiday Stay")),
    ("barang permainan", ("Entertainment & travel", "Games & Hobbies")),
    ("peralatan sukan", ("Health & medical", "Gym, Sports & Fitness")),
    ("perabot rumah", ("Housing & property", "Furniture & Appliances")),
    ("pelaburan saham", ("Investments & savings", "Stocks, ETF & Crypto")),
]


def run_stage_b_search_v5():
    import run_track4_foundry_v1 as t4

    update_status("STAGE_B_SEARCH_V5_START")
    t0 = time.perf_counter()
    print("\n=== [Sub-stage B] SemanticSearch v5 (Single e5-large 1024-d, 3 seeds) ===", flush=True)

    frozen_dir = Path("/content/frozen_track4_v1")
    frozen_sem_p = frozen_dir / "semantic_search_blind_test.json"
    frozen_qe_p = frozen_dir / "quick_entry_blind_test.json"
    assert frozen_sem_p.exists() and frozen_qe_p.exists(), "Missing frozen Track 4 holdouts"

    frozen_sem = json.loads(frozen_sem_p.read_text(encoding="utf-8"))
    frozen_qe = json.loads(frozen_qe_p.read_text(encoding="utf-8"))
    frozen_q = {str(q.get("query", "")).strip().lower() for q in frozen_sem.get("queries", [])}
    frozen_ood = {str(q.get("query", "")).strip().lower() for q in frozen_sem.get("ood_queries", [])}
    qe_blind_txt = {str(c.get("text", "")).strip().lower() for c in frozen_qe.get("cases", [])}

    cache_src = Path("/content/track4_llm_synth_cache.json")
    cache_dst = OUT_DIR / "track4_llm_synth_cache.json"
    if cache_src.exists() and not cache_dst.exists():
        shutil.copy2(cache_src, cache_dst)

    orig_load_teacher = t4.load_e5_teacher
    orig_build_train = t4.build_training_examples
    orig_eval_ledger = t4.eval_ledger_retrieval
    orig_build_ledgers = t4._build_blind_ledger_and_queries

    # Cache E5 embeddings across all 3 seeds so A100 runs all 3 seeds cleanly in ~2 mins!
    e5_cache = {}
    cached_encode_ref = [None]
    cached_cleanup_ref = [None]

    def cached_e5_loader():
        if cached_encode_ref[0] is None:
            enc_fn, cl_fn = orig_load_teacher()
            def cached_enc(texts, batch_size=64):
                missing = [t for t in texts if t not in e5_cache]
                if missing:
                    vecs = enc_fn(missing, batch_size=batch_size)
                    for txt, v in zip(missing, vecs):
                        e5_cache[txt] = v
                return np.vstack([e5_cache[t] for t in texts]).astype(np.float16) if texts else np.zeros((0, 1024), dtype=np.float16)
            cached_encode_ref[0] = cached_enc
            cached_cleanup_ref[0] = cl_fn
        return cached_encode_ref[0], (lambda: None)

    best_run = None
    seeds = [2026, 42, 1337]

    for seed in seeds:
        update_status("SEARCH_V5_SEED", seed=seed)
        stage_tmp = OUT_DIR / f"_tmp_search_seed_{seed}"
        stage_tmp.mkdir(parents=True, exist_ok=True)
        shutil.copy2(cache_dst, stage_tmp / "track4_llm_synth_cache.json")

        t4.OUT_DIR = stage_tmp
        t4.STATUS_PATH = stage_tmp / "status.json"
        t4.REPORT_PATH = stage_tmp / "report.json"
        t4.MODEL_BIN_GZ_PATH = stage_tmp / "model.bin.gz"
        t4.SEMANTIC_BLIND_PATH = stage_tmp / "sem_blind.json"
        t4.QUICK_BLIND_PATH = stage_tmp / "qe_blind.json"
        t4.PARITY_VECTORS_PATH = stage_tmp / "parity.json"
        t4.GEN_CACHE_PATH = stage_tmp / "track4_llm_synth_cache.json"
        t4.E5_TEACHER_ID = "intfloat/multilingual-e5-large"
        captured_ledgers = {}

        def capture_ledgers(verified_phrases, val_ents, test_ents):
            res = orig_build_ledgers(verified_phrases, val_ents, test_ents)
            captured_ledgers["val"] = res["val_ledger"]
            captured_ledgers["blind"] = res["blind_ledger"]
            return res

        def augmented_build_train(train_ents, verified_search_phrases, synth_qe, blind_exclude_queries, blind_exclude_qe, seed_inner=20261124):
            ex_q = set(blind_exclude_queries) | frozen_q | frozen_ood
            ex_qe = set(blind_exclude_qe) | qe_blind_txt
            res = orig_build_train(train_ents, verified_search_phrases, synth_qe, ex_q, ex_qe, seed=seed)
            docs_by_pair = res["docs_by_pair"]
            pair_pos = list(res["pair_pos"])
            proto_ex = list(res["proto_examples"])
            rng_s = random.Random(seed)
            for syn_q, pair in EXTRA_SYNONYM_PAIRS:
                qk = syn_q.strip().lower()
                if qk in ex_q:
                    continue
                pool = docs_by_pair.get(pair)
                if not pool:
                    continue
                pid = t4.PAIR_INDEX.get(pair, -1)
                if pid < 0:
                    continue
                picks = rng_s.sample(pool, min(3, len(pool)))
                for d_txt, d_tags in picks:
                    pair_pos.append((syn_q, d_txt, d_tags, pair[0], pair[1], pid, pair, pid))
                proto_ex.append((syn_q, pid))
            res["pair_pos"] = pair_pos
            res["proto_examples"] = proto_ex
            return res

        t4.load_e5_teacher = cached_e5_loader
        t4.build_training_examples = augmented_build_train
        t4._build_blind_ledger_and_queries = capture_ledgers
        random.seed(seed)
        np.random.seed(seed)
        torch.manual_seed(seed)
        if torch.cuda.is_available():
            torch.cuda.manual_seed_all(seed)

        try:
            t4.run_self_test()
            t4.train_and_export()
        finally:
            t4.load_e5_teacher = orig_load_teacher
            t4.build_training_examples = orig_build_train
            t4._build_blind_ledger_and_queries = orig_build_ledgers

        rep_s = json.loads(t4.REPORT_PATH.read_text(encoding="utf-8"))
        val_m = rep_s["val_retrieval"]
        bin_bytes = t4.MODEL_BIN_GZ_PATH.read_bytes()
        md = t4.load_search_bin_gz(bin_bytes)

        # Enforce strict val_ood_fpr <= 0.015 cleanly via real val_ledger evaluation & lossless header patch
        if float(val_m.get("ood_fpr", 0.0)) > 0.015 and "val" in captured_ledgers:
            cand_th = max(float(md["sem_th"]), 0.20)
            for sth in np.arange(cand_th, 0.37, 0.01):
                raw_buf = bytearray(gzip.decompress(bin_bytes))
                struct.pack_into(">f", raw_buf, struct.calcsize(">4s5i"), float(round(sth, 3)))
                patched_gz = gzip.compress(bytes(raw_buf), compresslevel=9)
                md_p = t4.load_search_bin_gz(patched_gz)
                m_p = orig_eval_ledger(md_p, captured_ledgers["val"])
                if m_p["ood_fpr"] <= 0.015:
                    rep_s["calibrated_thresholds"]["semanticThreshold"] = float(round(sth, 3))
                    rep_s["val_retrieval"] = m_p
                    rep_s["blind_retrieval"] = orig_eval_ledger(md_p, captured_ledgers["blind"])
                    val_m = m_p
                    t4.MODEL_BIN_GZ_PATH.write_bytes(patched_gz)
                    break

        score = float(val_m["recall_at_5"]) + 0.5 * float(val_m["mrr_at_10"]) - 0.5 * float(val_m["ood_fpr"])
        print(f"[Search v5 seed={seed}] val_score={score:.4f} val_r5={val_m['recall_at_5']:.4f} val_mrr={val_m['mrr_at_10']:.4f} val_ood={val_m['ood_fpr']:.4f} blind_r5={rep_s['blind_retrieval']['recall_at_5']:.4f} mrr={rep_s['blind_retrieval']['mrr_at_10']:.4f}", flush=True)
        if best_run is None or score > best_run["score"]:
            best_run = {
                "seed": seed,
                "score": score,
                "dir": stage_tmp,
                "report": rep_s,
            }

    if cached_cleanup_ref[0] is not None:
        cached_cleanup_ref[0]()
    e5_cache.clear()
    clear_cuda()

    win_dir = best_run["dir"]
    shutil.copy2(win_dir / "model.bin.gz", OUT_DIR / "moneylens_search_v5.bin.gz")
    shutil.copy2(win_dir / "parity.json", OUT_DIR / "search_v5_parity_vectors.json")
    rep_final = best_run["report"]
    rep_final["winner_seed"] = best_run["seed"]
    rep_final["stage_seconds"] = round(time.perf_counter() - t0, 1)
    rep_final["zero_train_leakage"] = True
    (OUT_DIR / "search_v5_report.json").write_text(json.dumps(rep_final, indent=2, ensure_ascii=False), encoding="utf-8")
    bin_sz = (OUT_DIR / "moneylens_search_v5.bin.gz").stat().st_size
    assert bin_sz < 524_288, f"Search v5 asset too large: {bin_sz}"
    print(f"[Sub-stage B DONE] seed={best_run['seed']} gz={bin_sz:,} B | val={json.dumps(rep_final['val_retrieval'])}", flush=True)
    return rep_final


# ===========================================================================
# Sub-stage C: NotificationGate v4 (2-teacher fusion + OOF hard-example mining)
# ===========================================================================
def train_ng_teacher(backbone: str, train_rows, dev_rows, prefix: str = ""):
    from torch.utils.data import DataLoader, TensorDataset
    from transformers import AutoModel, AutoTokenizer, get_cosine_schedule_with_warmup

    import run_track34_notif_gate_v1 as t34

    update_status("NG4_TEACHER", backbone=backbone, train_n=len(train_rows), dev_n=len(dev_rows))
    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    tok = AutoTokenizer.from_pretrained(backbone)
    enc = AutoModel.from_pretrained(backbone)
    native_bf16 = torch.cuda.is_available() and torch.cuda.get_device_capability(0)[0] >= 8
    amp_dtype = torch.bfloat16 if native_bf16 else torch.float16

    class NgTeacher(nn.Module):
        def __init__(self, e):
            super().__init__()
            self.e = e
            self.drop = nn.Dropout(0.15)
            self.head = nn.Linear(e.config.hidden_size, 6)

        def forward(self, ids, mask):
            out = self.e(input_ids=ids, attention_mask=mask).last_hidden_state
            m = mask.unsqueeze(-1).float()
            pooled = (out * m).sum(dim=1) / m.sum(dim=1).clamp(min=1e-6)
            return self.head(self.drop(pooled))

    mdl = NgTeacher(enc).to(device)
    bs = 64 if (torch.cuda.get_device_properties(0).total_memory / 1e9) >= 35 else 32
    tr_txts = [f"{prefix}{r['source']}: {r['text']}" for r in train_rows]
    tr_y = np.array([t34.LABEL_TO_ID[r["expected_kind"]] for r in train_rows], dtype=np.int64)
    tr_enc = tok(tr_txts, padding=True, truncation=True, max_length=128, return_tensors="pt")
    dl = DataLoader(TensorDataset(tr_enc["input_ids"], tr_enc["attention_mask"], torch.from_numpy(tr_y)), batch_size=bs, shuffle=True)

    dv_txts = [f"{prefix}{r['source']}: {r['text']}" for r in dev_rows]
    dv_y = np.array([t34.LABEL_TO_ID[r["expected_kind"]] for r in dev_rows], dtype=np.int64)
    dv_enc = tok(dv_txts, padding=True, truncation=True, max_length=128, return_tensors="pt")
    dv_dl = DataLoader(TensorDataset(dv_enc["input_ids"], dv_enc["attention_mask"]), batch_size=bs * 2)

    cw = torch.tensor([1.4, 1.0, 1.0, 1.05, 1.05, 1.05], device=device)
    crit = nn.CrossEntropyLoss(weight=cw, label_smoothing=0.03)
    opt = torch.optim.AdamW([{"params": mdl.e.parameters(), "lr": 2e-5}, {"params": mdl.head.parameters(), "lr": 3e-4}], weight_decay=0.01)
    epochs = 3
    sched = get_cosine_schedule_with_warmup(opt, num_warmup_steps=max(20, int(0.08 * len(dl) * epochs)), num_training_steps=len(dl) * epochs)
    scaler = torch.amp.GradScaler("cuda", enabled=(torch.cuda.is_available() and amp_dtype == torch.float16))

    best_acc, best_sd = -1.0, None
    for ep in range(1, epochs + 1):
        mdl.train()
        for ids, mask, y in dl:
            ids, mask, y = ids.to(device), mask.to(device), y.to(device)
            opt.zero_grad(set_to_none=True)
            with torch.amp.autocast("cuda", dtype=amp_dtype, enabled=torch.cuda.is_available()):
                loss = crit(mdl(ids, mask), y)
            scaler.scale(loss).backward()
            scaler.unscale_(opt)
            torch.nn.utils.clip_grad_norm_(mdl.parameters(), 1.0)
            scaler.step(opt)
            scaler.update()
            sched.step()
        mdl.eval()
        preds = []
        with torch.no_grad():
            for ids, mask in dv_dl:
                with torch.amp.autocast("cuda", dtype=amp_dtype, enabled=torch.cuda.is_available()):
                    preds.append(mdl(ids.to(device), mask.to(device)).float().cpu().numpy())
        dp = np.vstack(preds)
        acc = float(np.mean(np.argmax(dp, axis=1) == dv_y))
        print(f"[{backbone} ep {ep}/{epochs}] dev_acc={acc:.4f}", flush=True)
        if acc > best_acc:
            best_acc = acc
            best_sd = {k: v.cpu() for k, v in mdl.state_dict().items()}

    mdl.load_state_dict({k: v.to(device) for k, v in best_sd.items()})
    mdl.eval()
    infer_tr = DataLoader(TensorDataset(tr_enc["input_ids"], tr_enc["attention_mask"]), batch_size=bs * 2)
    z_tr = []
    with torch.no_grad():
        for ids, mask in infer_tr:
            with torch.amp.autocast("cuda", dtype=amp_dtype, enabled=torch.cuda.is_available()):
                z_tr.append(mdl(ids.to(device), mask.to(device)).float().cpu().numpy())
    z_tr = np.vstack(z_tr).astype(np.float32)

    z_dv = []
    with torch.no_grad():
        for ids, mask in dv_dl:
            with torch.amp.autocast("cuda", dtype=amp_dtype, enabled=torch.cuda.is_available()):
                z_dv.append(mdl(ids.to(device), mask.to(device)).float().cpu().numpy())
    z_dv = np.vstack(z_dv).astype(np.float32)

    del mdl, enc, best_sd, tr_enc, dl, dv_enc, dv_dl, tok
    clear_cuda()
    return z_tr, z_dv, best_acc


def run_stage_c_notif_gate_v4():
    import run_track34_notif_gate_v1 as t34
    import run_notif_gate_v2_a100 as ng2

    update_status("STAGE_C_NOTIF_GATE_V4_START")
    t0 = time.perf_counter()
    print("\n=== [Sub-stage C] NotificationGate v4 (2-Teacher Fusion + Mild OOF Mining) ===", flush=True)

    V4_BUCKETS = 5632
    t34.NUM_BUCKETS = V4_BUCKETS

    blind_excl = set()
    for p in (Path("/content/track3_hard_blind.json"), Path("/content/track5_adversarial_blind.json")):
        if p.exists():
            obj = json.loads(p.read_text(encoding="utf-8"))
            cases = obj.get("cases", []) if isinstance(obj, dict) else obj
            for c in cases:
                if "text" in c:
                    blind_excl.add(norm_key(c["text"]))
                if "raw_text" in c:
                    blind_excl.add(norm_key(c["raw_text"]))

    dev_pool = []
    seen = set(blind_excl)
    for p in (Path("/content/track3_dev.json"), Path("/content/track5_adversarial_dev.json")):
        if p.exists():
            obj = json.loads(p.read_text(encoding="utf-8"))
            cases = obj.get("cases", []) if isinstance(obj, dict) else obj
            for c in cases:
                txt = c.get("text") or c.get("raw_text") or ""
                k = norm_key(txt)
                if not txt or k in seen:
                    continue
                seen.add(k)
                cap = bool(c.get("expected_captured", c.get("captured", False)))
                kind = str(c.get("expected_kind", c.get("kind", "reject" if not cap else "expense")))
                if kind not in t34.LABEL_TO_ID:
                    continue
                dev_pool.append({
                    "text": txt,
                    "source": str(c.get("source", c.get("package", "my.com.tngdigital.ewallet"))),
                    "source_label": str(c.get("source_label", "TNG eWallet")),
                    "lang": str(c.get("lang", "en")),
                    "subtype": str(c.get("subtype", "dev")),
                    "expected_captured": cap,
                    "expected_kind": kind,
                    "expected_amount_minor": int(c.get("expected_amount_minor", 0)),
                    "is_llm_paraphrase": bool(c.get("is_llm_paraphrase", False)),
                })

    rng = random.Random(20261128)
    subtypes = [
        ("trap_otp", 0.13),
        ("trap_fail_subtle", 0.17),
        ("trap_fail", 0.08),
        ("trap_bill_promo_pending", 0.16),
        ("real_expense_rail_verbless", 0.15),
        ("real_expense", 0.10),
        ("real_income", 0.07),
        ("real_refund_hard", 0.06),
        ("real_transfer", 0.04),
        ("real_reload", 0.04),
    ]

    def sample_templates(target_n):
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
            k = norm_key(txt)
            if k in seen:
                continue
            seen.add(k)
            rows.append({
                "text": txt, "source": pkg, "source_label": src_label, "lang": lang, "subtype": st,
                "expected_captured": cap, "expected_kind": kind, "expected_amount_minor": amt, "is_llm_paraphrase": False,
            })
        return rows

    train_rows = sample_templates(40000)
    rng.shuffle(dev_pool)
    n_dev_keep = min(3500, len(dev_pool))
    dev_rows = dev_pool[:n_dev_keep] + sample_templates(max(600, 4000 - n_dev_keep))
    rng.shuffle(train_rows)
    rng.shuffle(dev_rows)
    assert {norm_key(r["text"]) for r in train_rows}.isdisjoint(blind_excl)
    assert {norm_key(r["text"]) for r in dev_rows}.isdisjoint(blind_excl)

    z_bge_tr, z_bge_dv, acc_bge = train_ng_teacher("BAAI/bge-m3", train_rows, dev_rows, prefix="")
    z_e5_tr, z_e5_dv, acc_e5 = train_ng_teacher("intfloat/multilingual-e5-large", train_rows, dev_rows, prefix="query: ")

    z_bge_tr_c = z_bge_tr - z_bge_tr.mean(axis=1, keepdims=True)
    z_e5_tr_c = z_e5_tr - z_e5_tr.mean(axis=1, keepdims=True)
    teacher_z_tr = (0.55 * z_bge_tr_c + 0.45 * z_e5_tr_c).astype(np.float32)

    z_dv_fused = 0.55 * (z_bge_dv - z_bge_dv.mean(axis=1, keepdims=True)) + 0.45 * (z_e5_dv - z_e5_dv.mean(axis=1, keepdims=True))
    dv_y_arr = np.array([t34.LABEL_TO_ID[r["expected_kind"]] for r in dev_rows], dtype=np.int64)
    ens_dev_acc = float(np.mean(np.argmax(z_dv_fused, axis=1) == dv_y_arr))
    print(f"[NG4 Teacher Ensemble] bge={acc_bge:.4f} e5={acc_e5:.4f} fused_dev={ens_dev_acc:.4f}", flush=True)

    # Mild OOF hard-example weighting (1.45x on teacher low-margin / disagreed examples)
    tr_sp, tr_sf, tr_y = t34.precompute_features(train_rows)
    dv_sp, dv_sf, dv_y = t34.precompute_features(dev_rows)
    tr_probs_t = np.exp(teacher_z_tr - teacher_z_tr.max(axis=1, keepdims=True))
    tr_probs_t = tr_probs_t / tr_probs_t.sum(axis=1, keepdims=True)
    gold_p = tr_probs_t[np.arange(len(tr_y)), tr_y]
    sample_w = np.where((np.argmax(teacher_z_tr, axis=1) != tr_y) | (gold_p < 0.72), 1.45, 1.0).astype(np.float32)

    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    torch.manual_seed(20261128)
    net = ng2.QatNotifGateNet().to(device)
    cw = torch.tensor([1.5, 1.0, 1.0, 1.05, 1.05, 1.05], device=device)
    opt = torch.optim.AdamW(net.parameters(), lr=3e-3, weight_decay=2e-4)
    epochs = 32
    sched = torch.optim.lr_scheduler.CosineAnnealingLR(opt, T_max=epochs, eta_min=1e-4)

    tr_sf_t = torch.from_numpy(tr_sf).to(device)
    tr_y_t = torch.from_numpy(tr_y).to(device)
    tr_tz_t = torch.from_numpy(teacher_z_tr).to(device)
    tr_sw_t = torch.from_numpy(sample_w).to(device)
    dv_sf_t = torch.from_numpy(dv_sf).to(device)

    best_score, best_sd = -1.0, None
    bs = 256
    nr = np.random.default_rng(20261128)
    for ep in range(1, epochs + 1):
        qat = ep > (epochs - 8)
        net.train()
        perm = nr.permutation(len(tr_sp))
        for s in range(0, len(tr_sp), bs):
            idx = perm[s : s + bs]
            bsp = [tr_sp[int(i)] for i in idx]
            opt.zero_grad(set_to_none=True)
            lg = net(bsp, tr_sf_t[idx], qat=qat)
            ce_vec = F.cross_entropy(lg, tr_y_t[idx], weight=cw, label_smoothing=0.02, reduction="none")
            ce = (ce_vec * tr_sw_t[idx]).mean()
            kl = F.kl_div(F.log_softmax(lg / 2.0, dim=-1), F.softmax(tr_tz_t[idx] / 2.0, dim=-1), reduction="none").sum(dim=-1)
            kl = (kl * tr_sw_t[idx]).mean() * 4.0
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
            print(f"[ng4 QAT={qat} ep {ep}/{epochs}] dev_score={sc:.4f}", flush=True)

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
    buf += struct.pack(">iiiiii", 1, V4_BUCKETS, t34.EMB_DIM, t34.STRUCT_DIM, t34.HIDDEN_DIM, t34.NUM_CLASSES)
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

    bin_p = OUT_DIR / "moneylens_notif_gate_v4.bin.gz"
    with gzip.open(bin_p, "wb", compresslevel=9) as gz:
        gz.write(buf)
    gz_bytes = bin_p.stat().st_size
    assert gz_bytes <= 245_760, f"NG v4 too large: {gz_bytes}"

    parity_vecs = []
    for i in range(min(100, len(dev_rows))):
        lg, pr = t34.dequantize_forward_single(dv_sp[i], dv_sf[i], model_q, best_temp)
        parity_vecs.append({
            "id": f"ng4-dev-{i}",
            "text": dev_rows[i]["text"],
            "source": dev_rows[i]["source"],
            "expected_kind": dev_rows[i]["expected_kind"],
            "logits": [round(float(x), 6) for x in lg],
            "probs": [round(float(x), 6) for x in pr],
        })
    (OUT_DIR / "notif_gate_v4_parity_vectors.json").write_text(json.dumps(parity_vecs, ensure_ascii=False, indent=2), encoding="utf-8")

    rep = {
        "stage_seconds": round(time.perf_counter() - t0, 1),
        "teachers": {"bge_m3_dev_acc": round(acc_bge, 4), "e5_large_dev_acc": round(acc_e5, 4), "ensemble_dev_acc": round(ens_dev_acc, 4)},
        "train_count": len(train_rows),
        "dev_count": len(dev_rows),
        "hard_mined_count": int(np.sum(sample_w > 1.0)),
        "num_buckets": V4_BUCKETS,
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
    (OUT_DIR / "notif_gate_v4_report.json").write_text(json.dumps(rep, indent=2, ensure_ascii=False), encoding="utf-8")
    clear_cuda()
    print(f"[Sub-stage C DONE] gz={gz_bytes:,} B | dev={json.dumps(rep['dev_metrics_int8'])}", flush=True)
    return rep


def main():
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    t_all = time.perf_counter()
    try:
        rep_a = run_stage_a_crf_v2()
        rep_b = run_stage_b_search_v5()
        rep_c = run_stage_c_notif_gate_v4()
        summary = {
            "total_seconds": round(time.perf_counter() - t_all, 1),
            "stage_a_crf_v2": rep_a,
            "stage_b_search_v5": rep_b,
            "stage_c_notif_gate_v4": rep_c,
        }
        SUMMARY_REPORT_PATH.write_text(json.dumps(summary, indent=2, ensure_ascii=False), encoding="utf-8")
        update_status("DONE", total_seconds=summary["total_seconds"])
        print(f"\nPHASE D RUN DONE in {summary['total_seconds']}s!", flush=True)
    except Exception as exc:
        traceback.print_exc()
        update_status("ERROR", error=repr(exc))
        raise


if __name__ == "__main__":
    main()