#!/usr/bin/env python3
"""
Combined Phase B A100/L4/T4 Pipeline for MoneyLens:
  Sub-stage A: SemanticSearch v3 distilled from intfloat/multilingual-e5-large (1024-dim)
  Sub-stage B: MerchantCanonicalizer v2 with clean v6_synth merchants (strictly zero-leakage)
  Sub-stage C: ReceiptFieldTagger v2 Linear-Chain CRF with expanded vocab (max_lex_feats=8500, min_df=3, < 260 KB gz)
"""
import gc
import gzip
import json
import random
import shutil
import sys
import time
import traceback
from pathlib import Path

COLAB_ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(COLAB_ROOT))

OUT_DIR = Path("/content/outputs_phaseb_v2")
STATUS_PATH = OUT_DIR / "phaseb_v2_status.json"
SUMMARY_REPORT_PATH = OUT_DIR / "phaseb_v2_report.json"


def update_status(stage: str, **extra):
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    payload = {"stage": stage, "ts": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())}
    payload.update(extra)
    STATUS_PATH.write_text(json.dumps(payload, indent=2, ensure_ascii=False), encoding="utf-8")


def clear_cuda():
    gc.collect()
    try:
        import torch
        if torch.cuda.is_available():
            torch.cuda.empty_cache()
    except Exception:
        pass


def run_stage_a_search_v3():
    import run_track4_foundry_v1 as t4

    update_status("STAGE_A_SEARCH_V3_START")
    t0 = time.perf_counter()

    frozen_dir = Path("/content/frozen_track4_v1")
    frozen_sem_p = frozen_dir / "semantic_search_blind_test.json"
    frozen_qe_p = frozen_dir / "quick_entry_blind_test.json"
    assert frozen_sem_p.exists() and frozen_qe_p.exists(), "Missing frozen Track 4 blind holdouts"

    frozen_sem = json.loads(frozen_sem_p.read_text(encoding="utf-8"))
    frozen_qe = json.loads(frozen_qe_p.read_text(encoding="utf-8"))
    frozen_q = {str(q.get("query", "")).strip().lower() for q in frozen_sem.get("queries", [])}
    frozen_ood = {str(q.get("query", "")).strip().lower() for q in frozen_sem.get("ood_queries", [])}
    qe_blind_txt = {str(c.get("text", "")).strip().lower() for c in frozen_qe.get("cases", [])}

    cache_src = Path("/content/track4_llm_synth_cache.json")
    cache_dst = OUT_DIR / "track4_llm_synth_cache.json"
    if cache_src.exists() and not cache_dst.exists():
        shutil.copy2(cache_src, cache_dst)

    t4.OUT_DIR = OUT_DIR
    t4.STATUS_PATH = OUT_DIR / "search_v3_status.json"
    t4.REPORT_PATH = OUT_DIR / "search_v3_report.json"
    t4.MODEL_BIN_GZ_PATH = OUT_DIR / "moneylens_search_v3.bin.gz"
    t4.SEMANTIC_BLIND_PATH = OUT_DIR / "_candidate_semantic_blind.json"
    t4.QUICK_BLIND_PATH = OUT_DIR / "_candidate_quick_blind.json"
    t4.PARITY_VECTORS_PATH = OUT_DIR / "search_v3_parity_vectors.json"
    t4.GEN_CACHE_PATH = cache_dst
    t4.E5_TEACHER_ID = "intfloat/multilingual-e5-large"

    actual_train_queries = set()
    orig_build_train = t4.build_training_examples

    def checked_build_training_examples(train_ents, verified_search_phrases, synth_qe, blind_exclude_queries, blind_exclude_qe, seed=20261124):
        ex_q = set(blind_exclude_queries) | frozen_q | frozen_ood
        ex_qe = set(blind_exclude_qe) | qe_blind_txt
        res = orig_build_train(train_ents, verified_search_phrases, synth_qe, ex_q, ex_qe, seed=seed)
        for row in res["pair_pos"]:
            actual_train_queries.add(str(row[0]).strip().lower())
        for txt, _ in res["proto_examples"]:
            actual_train_queries.add(str(txt).strip().lower())
        return res

    t4.build_training_examples = checked_build_training_examples

    print("\n=== [Sub-stage A] SemanticSearch v3 (intfloat/multilingual-e5-large) ===", flush=True)
    t4.run_self_test()
    t4.train_and_export()

    cand_sem = json.loads(t4.SEMANTIC_BLIND_PATH.read_text(encoding="utf-8"))
    cand_qe = json.loads(t4.QUICK_BLIND_PATH.read_text(encoding="utf-8"))
    cand_q = {str(q.get("query", "")).strip().lower() for q in cand_sem.get("queries", [])}
    cand_ood = {str(q.get("query", "")).strip().lower() for q in cand_sem.get("ood_queries", [])}

    assert actual_train_queries, "Expected non-empty actual_train_queries"
    assert not (actual_train_queries & frozen_q), f"Train query leaked into frozen blind queries: {len(actual_train_queries & frozen_q)}"
    assert not (actual_train_queries & frozen_ood), f"Train query leaked into frozen OOD queries: {len(actual_train_queries & frozen_ood)}"
    assert not (actual_train_queries & qe_blind_txt), f"Train query leaked into frozen quick blind: {len(actual_train_queries & qe_blind_txt)}"

    bin_sz = t4.MODEL_BIN_GZ_PATH.stat().st_size
    assert bin_sz < 524_288, f"Search v3 bin too large: {bin_sz}"
    rep = json.loads(t4.REPORT_PATH.read_text(encoding="utf-8"))
    rep["frozen_blind_match"] = {
        "queries_jaccard": round(len(frozen_q & cand_q) / max(1, len(frozen_q | cand_q)), 4),
        "ood_jaccard": round(len(frozen_ood & cand_ood) / max(1, len(frozen_ood | cand_ood)), 4),
        "quick_cases_frozen": len(frozen_qe.get("cases", [])),
        "quick_cases_cand": len(cand_qe.get("cases", [])),
        "zero_train_leakage": True,
    }
    rep["stage_seconds"] = round(time.perf_counter() - t0, 1)
    t4.REPORT_PATH.write_text(json.dumps(rep, indent=2, ensure_ascii=False), encoding="utf-8")
    print(f"[Sub-stage A DONE] size={bin_sz:,} B in {rep['stage_seconds']}s", flush=True)
    clear_cuda()
    return rep


def _norm_block(mc, s: str) -> str:
    if not s:
        return ""
    return mc.normalize_surface(str(s))


def run_stage_b_merchant_canon_v2():
    import run_merchant_canon_v1 as mc

    update_status("STAGE_B_MERCHANT_CANON_V2_START")
    t0 = time.perf_counter()
    print("\n=== [Sub-stage B] MerchantCanonicalizer v2 (+clean v6_synth) ===", flush=True)

    # Collect strictly blocked merchant surfaces from all holdouts + blind sets
    blocked_raw = set()
    blocked_norm = set()

    def block_text(txt: str):
        if not txt:
            return
        t = str(txt).strip()
        if not t:
            return
        blocked_raw.add(t.lower())
        nk = _norm_block(mc, t)
        if len(nk) >= 3:
            blocked_norm.add(nk)

    h4_p = Path("/content/holdout_v4_details.json")
    if h4_p.exists():
        for row in json.loads(h4_p.read_text(encoding="utf-8")):
            if isinstance(row, dict):
                block_text(row.get("merchant", ""))
                block_text(row.get("expected_merchant", ""))
                block_text(row.get("raw_text", ""))

    h5_p = Path("/content/v5_hard_test.json")
    if h5_p.exists():
        for row in json.loads(h5_p.read_text(encoding="utf-8")):
            if isinstance(row, dict):
                block_text(row.get("merchant", ""))
                block_text(row.get("expected_merchant", ""))
                block_text(row.get("raw_text", ""))

    v1_pairs_p = Path("/content/merchant_canon_v1_test_pairs.json")
    v1_pair_surfaces = set()
    v1_pair_norms = set()
    if v1_pairs_p.exists():
        for row in json.loads(v1_pairs_p.read_text(encoding="utf-8")):
            for k in ("a", "b"):
                val = str(row.get(k, "")).strip()
                if val:
                    v1_pair_surfaces.add(val.lower())
                    nk_p = _norm_block(mc, val)
                    if len(nk_p) >= 3:
                        v1_pair_norms.add(nk_p)
                    block_text(val)

    frozen_dir = Path("/content/frozen_track4_v1")
    sem_p = frozen_dir / "semantic_search_blind_test.json"
    if sem_p.exists():
        sem_d = json.loads(sem_p.read_text(encoding="utf-8"))
        for tx in sem_d.get("transactions", []):
            block_text(tx.get("merchant", ""))
    qe_p = frozen_dir / "quick_entry_blind_test.json"
    if qe_p.exists():
        qe_d = json.loads(qe_p.read_text(encoding="utf-8"))
        for cs in qe_d.get("cases", []):
            block_text(cs.get("expected_merchant", ""))

    # Keep vanilla DATASET_V5_GZ_PATH so orig_builder produces the exact v1 noncat/val/test split!
    mc.OUT_DIR = OUT_DIR
    mc.DATASET_V5_GZ_PATH = Path("/content/dataset_malaysian_v5.jsonl.gz")
    mc.STATUS_PATH = OUT_DIR / "merchant_canon_v2_status.json"
    mc.REPORT_PATH = OUT_DIR / "merchant_canon_v2_report.json"
    mc.TEST_PAIRS_PATH = OUT_DIR / "merchant_canon_v2_test_pairs.json"
    mc.MODEL_BIN_GZ_PATH = OUT_DIR / "moneylens_merchant_canon_v2.bin.gz"

    v6_stats = {"v6_cat_pairs_added": 0, "v6_sme_added": 0, "v6_sme_pairs_added": 0, "v6_skipped_leak": 0, "v6_skipped_short": 0}
    orig_builder = mc.build_catalog_and_datasets

    def checked_builder(*args, **kwargs):
        bundle = orig_builder(*args, **kwargs)
        # Also block every val/test surface & entity generated by orig_builder before appending v6_synth
        for nm in bundle.get("noncat_val", []) + bundle.get("noncat_test", []):
            block_text(nm)
        for p in bundle.get("val_pairs", []) + bundle.get("test_pairs", []):
            block_text(p[0])
            block_text(p[1])
        for q in bundle.get("val_catalog_queries", []) + bundle.get("test_catalog_queries", []):
            block_text(q[0])
            block_text(q[2])

        cat_by_norm = {
            _norm_block(mc, c[0]): (idx, c[0], c[1], c[2])
            for idx, c in enumerate(bundle["catalog_entries"])
            if len(_norm_block(mc, c[0])) >= 3
        }
        v6_p = Path("/content/v6_synth.jsonl.gz")
        if v6_p.exists():
            rng_v6 = random.Random(20261128)
            seen_cat_pairs = {(str(p[0]).strip().lower(), int(p[5])) for p in bundle["train_pairs"] if len(p) >= 6 and p[5] >= 0}
            seen_new_smes = set()
            with gzip.open(v6_p, "rt", encoding="utf-8") as v6_fh:
                for line in v6_fh:
                    s = line.strip()
                    if not s:
                        continue
                    row = json.loads(s)
                    em = str(row.get("expected_merchant", "")).strip()
                    rt = str(row.get("raw_text", "")).strip()
                    cat = str(row.get("category", "")).strip()
                    sub = str(row.get("subcategory", "")).strip() or "General"
                    nk = _norm_block(mc, em)
                    if len(em) < 3 or len(nk) < 3 or cat in ("Miscellaneous", "Other", "Income", ""):
                        v6_stats["v6_skipped_short"] += 1
                        continue
                    if em.lower() in blocked_raw or nk in blocked_norm or rt.lower() in blocked_raw:
                        v6_stats["v6_skipped_leak"] += 1
                        continue
                    if nk in cat_by_norm:
                        cat_idx, cname, cat_c, sub_c = cat_by_norm[nk]
                        key = (em.lower(), cat_idx)
                        if key not in seen_cat_pairs:
                            seen_cat_pairs.add(key)
                            bundle["train_pairs"].append((em, cname, cat_c, sub_c, True, cat_idx))
                            v6_stats["v6_cat_pairs_added"] += 1
                    elif nk not in seen_new_smes and len(seen_new_smes) < 600:
                        seen_new_smes.add(nk)
                        v6_stats["v6_sme_added"] += 1
                        added_any = False
                        for surf in mc.synthesize_terminal_corruptions(em, rng_v6, n=4):
                            snk = _norm_block(mc, surf)
                            if surf.lower() in blocked_raw or (len(snk) >= 3 and snk in blocked_norm):
                                continue
                            bundle["train_pairs"].append((surf, em, cat, sub, False, -1))
                            v6_stats["v6_sme_pairs_added"] += 1
                            added_any = True
                        if added_any:
                            bundle["by_cat_entities"][cat].append(em)

        print(f"[Merged v6_synth post-split] {json.dumps(v6_stats)}", flush=True)
        for p in bundle["train_pairs"]:
            for side in (p[0], p[1]):
                sl = str(side).strip().lower()
                snk = _norm_block(mc, str(side))
                assert sl not in v1_pair_surfaces, f"Train pair raw leaked into v1 blind test pairs: {sl}"
                if len(snk) >= 3:
                    assert snk not in v1_pair_norms, f"Train pair norm leaked into v1 blind test pairs: {snk} ({side})"
        return bundle

    mc.build_catalog_and_datasets = checked_builder
    mc.run_self_test()
    mc.train_and_export()

    bin_sz = mc.MODEL_BIN_GZ_PATH.stat().st_size
    assert bin_sz < 512_000, f"Canon v2 bin too large: {bin_sz}"
    rep = json.loads(mc.REPORT_PATH.read_text(encoding="utf-8"))
    rep["v6_synth_merge"] = {**v6_stats, "zero_v1_pair_leakage": True}
    rep["stage_seconds"] = round(time.perf_counter() - t0, 1)
    mc.REPORT_PATH.write_text(json.dumps(rep, indent=2, ensure_ascii=False), encoding="utf-8")
    print(f"[Sub-stage B DONE] size={bin_sz:,} B in {rep['stage_seconds']}s", flush=True)
    clear_cuda()
    return rep


def run_stage_c_receipt_v2():
    import run_receipt_foundry_v1 as rf

    update_status("STAGE_C_RECEIPT_TAGGER_V2_START")
    t0 = time.perf_counter()
    print("\n=== [Sub-stage C] ReceiptFieldTagger v2 (max_lex_feats=8500, min_df=3, <260KB) ===", flush=True)

    rf.OUT_DIR = OUT_DIR
    rf.MAX_TAGGER_GZ_BYTES = 256_000
    rf.STATUS_PATH = OUT_DIR / "receipt_v2_status.json"
    rf.REPORT_PATH = OUT_DIR / "receipt_v2_report.json"
    rf.MODEL_JSON_PATH = OUT_DIR / "moneylens_receipt_tagger_v2.json"
    rf.MODEL_GZ_PATH = OUT_DIR / "moneylens_receipt_tagger_v2.json.gz"

    ds_p = Path("/content/receipt_vision_v1_dataset.jsonl.gz")
    ocr_p = Path("/content/receipt_holdout_real_ocr.json")
    txt_p = Path("/content/receipt_holdout_text.json")
    assert ds_p.exists() and ocr_p.exists() and txt_p.exists(), "Missing receipt datasets/holdouts"

    train_rows = []
    val_rows = []
    with gzip.open(ds_p, "rt", encoding="utf-8") as fh:
        for line in fh:
            if not line.strip():
                continue
            r = json.loads(line)
            sp = r.get("split")
            if sp == "train":
                train_rows.append(r)
            elif sp == "val":
                val_rows.append(r)

    test_ocr_rows = json.loads(ocr_p.read_text(encoding="utf-8"))
    test_txt_rows = json.loads(txt_p.read_text(encoding="utf-8"))

    # Zero leakage check between train ids and blind test ids
    train_ids = {r.get("id") for r in train_rows if r.get("id") is not None}
    test_ids = {r.get("id") for r in (test_ocr_rows + test_txt_rows) if r.get("id") is not None}
    assert not (train_ids & test_ids), f"Receipt train/test id overlap: {len(train_ids & test_ids)}"

    vocab, f2id = rf.build_crf_vocab(train_rows, max_lex_feats=8500, min_df=3)
    train_enc = rf.featurize_dataset(train_rows, f2id)
    val_enc = rf.featurize_dataset(val_rows, f2id)
    test_ocr_enc = rf.featurize_dataset(test_ocr_rows, f2id)
    test_txt_enc = rf.featurize_dataset(test_txt_rows, f2id)

    raw_json, gz_bytes, feat_stats, m_val, m_ocr, m_txt, _ = rf.train_and_export_crf(
        train_enc, val_enc, test_ocr_enc, test_txt_enc, vocab
    )
    assert len(gz_bytes) < 260_000, f"Receipt v2 gz too large: {len(gz_bytes)}"

    rf.MODEL_JSON_PATH.write_text(raw_json, encoding="utf-8")
    rf.MODEL_GZ_PATH.write_bytes(gz_bytes)

    rep = {
        "stage_seconds": round(time.perf_counter() - t0, 1),
        "vocab_config": {"max_lex_feats": 8500, "min_df": 3, "vocab_size": len(vocab)},
        "dataset_counts": {
            "train": len(train_rows),
            "val": len(val_rows),
            "test_ocr": len(test_ocr_rows),
            "test_txt": len(test_txt_rows),
        },
        "model_stats": {
            "raw_json_bytes": len(raw_json.encode("utf-8")),
            "gz_bytes": len(gz_bytes),
            **feat_stats,
        },
        "eval_val": m_val,
        "eval_blind_real_ocr": m_ocr,
        "eval_blind_text_augmented": m_txt,
    }
    rf.REPORT_PATH.write_text(json.dumps(rep, indent=2, ensure_ascii=False), encoding="utf-8")
    print(
        f"[Sub-stage C DONE] gz={len(gz_bytes):,} B | active_feats={feat_stats['active_features']} | "
        f"ocr line_acc={m_ocr['line_acc']:.4f} exact_amt={m_ocr['exact_grand_total_amt']:.4f} merch={m_ocr.get('merchant_tag_match', 0.0):.4f}",
        flush=True,
    )
    clear_cuda()
    return rep


def main():
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    t_all = time.perf_counter()
    try:
        rep_a = run_stage_a_search_v3()
        rep_b = run_stage_b_merchant_canon_v2()
        rep_c = run_stage_c_receipt_v2()
        summary = {
            "total_seconds": round(time.perf_counter() - t_all, 1),
            "stage_a_search_v3": rep_a,
            "stage_b_merchant_canon_v2": rep_b,
            "stage_c_receipt_v2": rep_c,
        }
        SUMMARY_REPORT_PATH.write_text(json.dumps(summary, indent=2, ensure_ascii=False), encoding="utf-8")
        update_status("DONE", total_seconds=summary["total_seconds"])
        print(f"\nALL PHASE B STAGES DONE in {summary['total_seconds']}s!", flush=True)
    except Exception as exc:
        traceback.print_exc()
        update_status("ERROR", error=repr(exc))
        raise


if __name__ == "__main__":
    main()
