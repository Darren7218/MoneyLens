#!/usr/bin/env python3
"""MoneyLens Track 2 (Colab A100) — NotificationGate v2 (44k+ Train, Qwen2.5-14B + bge-m3 QAT, drop-in MLNG v1 < 220 KB)."""
import gc
import gzip
import json
import math
import random
import re
import struct
import sys
import time
from pathlib import Path

import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F

sys.path.insert(0, "/content")
import run_track34_notif_gate_v1 as t34  # noqa: E402

# Upgrade bucket capacity cleanly to 5632 (< 230 KB gz, drop-in MLNG v1 compatible)
V2_BUCKETS = 5632
t34.NUM_BUCKETS = V2_BUCKETS

OUT_DIR = Path("/content/outputs_notif_gate_v2")
OUT_DIR.mkdir(parents=True, exist_ok=True)
STATUS_PATH = OUT_DIR / "notif_gate_v2_status.json"
BIN_GZ_PATH = OUT_DIR / "moneylens_notif_gate.bin.gz"
PARITY_PATH = OUT_DIR / "notif_gate_v2_parity_vectors.json"
REPORT_PATH = OUT_DIR / "notif_gate_v2_report.json"

BLIND_T3_PATH = Path("/content/track3_hard_blind.json")
BLIND_T5_PATH = Path("/content/track5_adversarial_blind.json")

CLASSES = t34.LABELS
LABEL_TO_ID = t34.LABEL_TO_ID


def update_status(stage: str, **extra):
    payload = {"stage": stage, "ts": int(time.time()), **extra}
    STATUS_PATH.write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")


def norm_key(text: str) -> str:
    return re.sub(r"\s+", " ", (text or "").lower()).strip()


def load_blind_exclusion_set():
    excl = set()
    for p in (BLIND_T3_PATH, BLIND_T5_PATH):
        if p.exists():
            obj = json.loads(p.read_text(encoding="utf-8"))
            cases = obj.get("cases", []) if isinstance(obj, dict) else obj
            for c in cases:
                if "text" in c:
                    excl.add(norm_key(c["text"]))
                if "raw_text" in c:
                    excl.add(norm_key(c["raw_text"]))
    return excl


def gen_v2_hard_template_case(split: str, subtype: str, rng: random.Random):
    """Generates tricky BM traps, verbless slips, FPX/JomPAY formats, and subtle refunds/reloads from TRAIN pools only."""
    pkg, src_label = rng.choice(t34.TRAIN_APPS)
    merch = rng.choice(t34.TRAIN_MERCHANTS)
    person = rng.choice(t34.PEOPLE_TRAIN)
    minor = t34.rand_minor(rng)
    amt = t34.fmt_amt(minor, rng)
    bal = t34.fmt_amt(rng.randint(15000, 450000), rng)
    biller = str(rng.randint(1000, 9999))
    lang = rng.choice(["en", "bm", "zh", "manglish"])

    if subtype == "trap_fail_subtle":
        pool = {
            "bm": [
                f"Pembayaran {amt} di {merch} ditolak oleh bank pengeluar.",
                f"Transaksi {amt} ke {merch} tidak berjaya. Akaun anda tidak ditolak.",
                f"Bayaran {amt} di {merch} gagal; tiada potongan dibuat dari {src_label}.",
                f"Pesanan {merch} ({amt}) dibatalkan secara automatik. Wang tidak didebitkan.",
            ],
            "manglish": [
                f"Aiya, bayar {amt} kat {merch} tak lepas, baki tak cukup lah.",
                f"Transaction {amt} at {merch} kena reject by {src_label}, no money deducted.",
                f"Payment {amt} to {merch} stuck/failed, tak potong duit pun.",
            ],
            "en": [
                f"Hold released with zero charge for {amt} at {merch}.",
                f"Attempted debit of {amt} at {merch} was declined. Available balance: {bal}.",
                f"Order {amt} at {merch} cancelled before capture. Card not charged.",
            ],
            "zh": [
                f"交易被拒绝：{merch} {amt}，未从您的账户扣除。",
                f"预授权已撤销：{merch} {amt} 未实际扣款。",
            ],
        }
        return rng.choice(pool[lang]), pkg, src_label, lang, False, "reject", 0

    if subtype == "real_expense_rail_verbless":
        pool = {
            "en": [
                f"{amt} at {merch}.",
                f"{amt} - {merch}. Never share your TAC.",
                f"FPX payment of {amt}. Seller: {merch}.",
                f"JomPAY payment of {amt} to {merch} (Biller Code: {biller}) is successful.",
                f"PAY*{merch.upper()[:16]} {amt} approved via {src_label}.",
                f"DuitNow QR payment of {amt} to {merch} completed (TAC verified).",
            ],
            "bm": [
                f"Bayaran {amt} kepada {merch} telah ditolak dari akaun {src_label}.",
                f"Potongan berjaya: {amt} bagi {merch} selesai diproses. Baki kini {bal}.",
                f"Transaksi {merch} sebanyak {amt} disahkan melalui Secure2u.",
            ],
            "manglish": [
                f"Done bayar {amt} to {merch} pakai {src_label}, balance {bal}.",
                f"Settled {merch} bil {amt} via JomPAY, code {biller}.",
            ],
            "zh": [
                f"已成功从 {src_label} 扣款 {amt}，支付给 {merch}。余额 {bal}。",
                f"通过 {src_label} 完成支付 {amt} 于 {merch}（3DS 验证）。",
            ],
        }
        return rng.choice(pool[lang]), pkg, src_label, lang, True, "expense", minor

    if subtype == "real_refund_hard":
        pool = {
            "en": [
                f"Refund for cancelled order {amt} from {merch} credited back cleanly.",
                f"Merchant {merch} refunded {amt} into your {src_label}.",
            ],
            "bm": [
                f"Bayaran balik {amt} daripada {merch} untuk pesanan dibatalkan berjaya dikreditkan.",
                f"Wang sebanyak {amt} dari {merch} telah dipulangkan ke {src_label}.",
            ],
            "manglish": [
                f"Order cancelled at {merch}, refund {amt} dah masuk {src_label}!",
            ],
            "zh": [
                f"{merch} 已取消订单退款 {amt} 已退回至您的 {src_label}。",
            ],
        }
        return rng.choice(pool[lang]), pkg, src_label, lang, True, "refund", minor

    return t34.gen_disjoint_template_case("train", subtype, rng)


def synthesize_v2_qwen(blind_excl: set):
    from transformers import AutoModelForCausalLM, AutoTokenizer

    vram_gb = (torch.cuda.get_device_properties(0).total_memory / 1e9) if torch.cuda.is_available() else 0.0
    if vram_gb >= 36.0:
        cands = ("Qwen/Qwen2.5-14B-Instruct", "Qwen/Qwen2.5-7B-Instruct")
    elif vram_gb >= 20.0:
        cands = ("Qwen/Qwen2.5-7B-Instruct", "Qwen/Qwen2.5-3B-Instruct")
    else:
        cands = ("Qwen/Qwen2.5-3B-Instruct", "Qwen/Qwen2.5-1.5B-Instruct")
    mdl, tok, mdl_id = None, None, None
    for cand in cands:
        try:
            update_status("V2_LLM_LOAD", model=cand, vram_gb=round(vram_gb, 1))
            print(f"Loading {cand} (VRAM={vram_gb:.1f}GB)...", flush=True)
            tok = AutoTokenizer.from_pretrained(cand, padding_side="left")
            if tok.pad_token is None:
                tok.pad_token = tok.eos_token
            dtype = torch.bfloat16 if torch.cuda.is_bf16_supported() else torch.float16
            mdl = AutoModelForCausalLM.from_pretrained(cand, torch_dtype=dtype, device_map="cuda:0" if torch.cuda.is_available() else "auto")
            mdl.eval()
            mdl_id = cand
            break
        except Exception as exc:
            print(f"Could not load {cand}: {exc}", flush=True)
            tok, mdl = None, None
            gc.collect()
            torch.cuda.empty_cache()

    if mdl is None:
        return [], "none"

    rng = random.Random(20261127)
    specs = [
        ("reject", "trap_otp", "active OTP/TAC/PAC/3DS/Secure2u prompt asking user to verify/approve before payment"),
        ("reject", "trap_fail", "failed/declined/ditolak oleh bank/tak lepas/unpaid-cancelled payment where NO money left"),
        ("reject", "trap_bill", "upcoming bill reminder, minimum payment due soon, pre-auth hold, or plain balance notice"),
        ("reject", "trap_promo", "marketing voucher, cashback ad, win-prize, or credit limit increase promo"),
        ("expense", "real_expense", "completed payment, verbless bank slip, FPX seller receipt, JomPAY biller payment, or telah ditolak dari akaun deduction"),
        ("income", "real_income", "incoming salary, DuitNow money received, or friend payback credited"),
        ("refund", "real_refund", "completed refund credited back for cancelled order or returned purchase"),
        ("transfer", "real_transfer", "completed transfer between user's own bank accounts"),
        ("reload", "real_reload", "completed e-wallet reload / tambah nilai / top-up alert"),
    ]
    jobs = []
    for expected_kind, subtype, desc in specs:
        reps = 68 if expected_kind in ("reject", "expense") else 45
        for _ in range(reps):
            for _b in range(12):
                pkg, src_label = rng.choice(t34.TRAIN_APPS)
                merch = rng.choice(t34.PEOPLE_TRAIN if expected_kind == "income" else t34.TRAIN_MERCHANTS)
                minor = t34.rand_minor(rng)
                amt = f"RM {minor/100:.2f}"
                lang = rng.choice(["English", "Bahasa Melayu", "Manglish", "Simplified Chinese"])
                u_msg = (
                    f"Write ONE realistic short Malaysian phone notification from '{src_label}' in {lang}.\n"
                    f"Scenario: {desc}.\nCounterparty: {merch}. Exact amount: {amt}.\n"
                    f"Output ONLY the single-line notification text, no quotes."
                )
                jobs.append((expected_kind, subtype, pkg, src_label, lang, minor, amt, u_msg))

    rng.shuffle(jobs)
    bs = 48 if torch.cuda.is_available() and (torch.cuda.get_device_properties(0).total_memory / 1e9) >= 35 else 24
    kept = []
    for st in range(0, len(jobs), bs):
        chunk = jobs[st : st + bs]
        prompts = [
            tok.apply_chat_template(
                [{"role": "system", "content": "You generate realistic single-line Malaysian bank and e-wallet push notifications."},
                 {"role": "user", "content": j[7]}],
                tokenize=False, add_generation_prompt=True
            )
            for j in chunk
        ]
        enc = tok(prompts, return_tensors="pt", padding=True, truncation=True, max_length=256).to(mdl.device)
        with torch.no_grad():
            out = mdl.generate(**enc, max_new_tokens=64, do_sample=True, temperature=0.85, top_p=0.92, pad_token_id=tok.pad_token_id)
        dec = tok.batch_decode(out[:, enc["input_ids"].shape[1] :], skip_special_tokens=True)
        for j, gen in zip(chunk, dec):
            expected_kind, subtype, pkg, src_label, lang_name, minor, _, _ = j
            line = " ".join(gen.strip().strip('"').strip("'").splitlines()).strip()
            if not line or len(line) < 12 or len(line) > 220 or t34.FORBIDDEN_SCRIPT_RE.search(line):
                continue
            if norm_key(line) in blind_excl:
                continue
            if f"{minor/100:.2f}" not in line and f"{minor/100:,.2f}" not in line:
                continue
            low = line.lower()
            if expected_kind == "reject":
                if subtype == "trap_otp" and not (t34.RE_OTP_WORD.search(low) or t34.RE_OTP_DIGITS.search(low) or any(k in low for k in t34.CJK_OTP_WORDS)):
                    continue
                if subtype == "trap_fail" and not (t34.RE_FAIL.search(low) or t34.RE_CANCEL.search(low) or any(k in low for k in ("ditolak", "tak lepas", "reject", "gagal", "未扣款", "失败"))):
                    continue
                if subtype == "trap_bill" and not (t34.RE_BILL_DUE.search(low) or t34.RE_PENDING.search(low) or "balance" in low or "baki" in low or any(k in low for k in t34.CJK_BILL_DUE_WORDS + t34.CJK_PENDING_WORDS)):
                    continue
                if subtype == "trap_promo" and not (t34.RE_PROMO.search(low) or any(k in low for k in t34.CJK_PROMO_WORDS)):
                    continue
            else:
                if t34.RE_FAIL.search(low) or "tak lepas" in low or "tidak ditolak" in low or t34.RE_OTP_DIGITS.search(low) or any(k in low for k in t34.CJK_FAIL_WORDS + t34.CJK_OTP_WORDS):
                    continue
                if t34.RE_BILL_DUE.search(low) or t34.RE_PROMO.search(low):
                    continue
                if expected_kind == "refund" and not (t34.RE_REFUND.search(low) or any(k in low for k in t34.CJK_REFUND_WORDS)):
                    continue
                if expected_kind == "reload" and not any(w in low for w in ("reload", "topup", "top-up", "top up", "tambah nilai", "充值")):
                    continue
            lang_code = {"English": "en", "Bahasa Melayu": "bm", "Manglish": "manglish", "Simplified Chinese": "zh"}[lang_name]
            kept.append({
                "text": line, "source": pkg, "source_label": src_label, "lang": lang_code, "subtype": subtype,
                "expected_captured": expected_kind != "reject", "expected_kind": expected_kind,
                "expected_amount_minor": 0 if expected_kind == "reject" else minor, "is_llm_paraphrase": True,
            })
        if (st // bs) % 15 == 0:
            update_status("V2_LLM_SYNTH", done=min(st + bs, len(jobs)), total=len(jobs), kept=len(kept), model=mdl_id)
            print(f"[ng2_synth] {min(st + bs, len(jobs))}/{len(jobs)} kept={len(kept)}", flush=True)

    del mdl, tok
    gc.collect()
    torch.cuda.empty_cache()
    return kept, mdl_id


def train_bge_m3_teacher(train_rows, dev_rows):
    from torch.utils.data import DataLoader, TensorDataset
    from transformers import AutoModel, AutoTokenizer, get_cosine_schedule_with_warmup

    backbone = "BAAI/bge-m3"
    update_status("V2_TEACHER", backbone=backbone, train_n=len(train_rows), dev_n=len(dev_rows))
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
    tr_txts = [f"{r['source']}: {r['text']}" for r in train_rows]
    tr_y = np.array([LABEL_TO_ID[r["expected_kind"]] for r in train_rows], dtype=np.int64)
    tr_enc = tok(tr_txts, padding=True, truncation=True, max_length=128, return_tensors="pt")
    dl = DataLoader(TensorDataset(tr_enc["input_ids"], tr_enc["attention_mask"], torch.from_numpy(tr_y)), batch_size=bs, shuffle=True)

    dv_txts = [f"{r['source']}: {r['text']}" for r in dev_rows]
    dv_y = np.array([LABEL_TO_ID[r["expected_kind"]] for r in dev_rows], dtype=np.int64)
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
        print(f"[bge-m3 teacher ep {ep}/{epochs}] dev_acc={acc:.4f}", flush=True)
        if acc > best_acc:
            best_acc = acc
            best_sd = {k: v.cpu() for k, v in mdl.state_dict().items()}

    mdl.load_state_dict({k: v.to(device) for k, v in best_sd.items()})
    mdl.eval()
    infer_dl = DataLoader(TensorDataset(tr_enc["input_ids"], tr_enc["attention_mask"]), batch_size=bs * 2)
    z_tr = []
    with torch.no_grad():
        for ids, mask in infer_dl:
            with torch.amp.autocast("cuda", dtype=amp_dtype, enabled=torch.cuda.is_available()):
                z_tr.append(mdl(ids.to(device), mask.to(device)).float().cpu().numpy())
    z_tr = np.vstack(z_tr).astype(np.float32)

    del mdl, enc, best_sd, tr_enc, dl
    gc.collect()
    torch.cuda.empty_cache()
    return z_tr, best_acc


class QatNotifGateNet(nn.Module):
    def __init__(self):
        super().__init__()
        self.emb = nn.Parameter(torch.randn(V2_BUCKETS, t34.EMB_DIM) * 0.05)
        self.w1 = nn.Linear(t34.EMB_DIM + t34.STRUCT_DIM, t34.HIDDEN_DIM)
        self.ln1 = nn.LayerNorm(t34.HIDDEN_DIM, eps=1e-5)
        self.drop = nn.Dropout(0.15)
        self.w2 = nn.Linear(t34.HIDDEN_DIM, t34.NUM_CLASSES)

    def fake_q(self, w, qat: bool):
        if not qat:
            return w
        with torch.no_grad():
            sc = w.abs().amax(dim=1, keepdim=True).clamp(min=1e-8) / 127.0
            wq = torch.clamp(torch.round(w / sc), -127.0, 127.0) * sc
        return w + (wq - w).detach()

    def encode_sparse(self, sparse_rows, device, qat: bool):
        Ew = self.fake_q(self.emb, qat)
        e0 = torch.zeros(len(sparse_rows), t34.EMB_DIM, device=device, dtype=torch.float32)
        flat_r, flat_b, flat_w = [], [], []
        for r_idx, bw in enumerate(sparse_rows):
            for b, w in bw.items():
                flat_r.append(r_idx)
                flat_b.append(b)
                flat_w.append(w)
        if flat_r:
            idx_r = torch.tensor(flat_r, device=device, dtype=torch.long)
            idx_b = torch.tensor(flat_b, device=device, dtype=torch.long)
            wts = torch.tensor(flat_w, device=device, dtype=torch.float32).unsqueeze(1)
            e0.index_add_(0, idx_r, Ew[idx_b] * wts)
        norm = torch.clamp(torch.sqrt((e0 * e0).sum(dim=1, keepdim=True)), min=1e-6)
        return e0 / norm

    def forward(self, sparse_rows, struct_x, qat: bool = False):
        e0 = self.encode_sparse(sparse_rows, struct_x.device, qat)
        x = torch.cat([e0, struct_x], dim=1)
        h = F.linear(x, self.fake_q(self.w1.weight, qat), self.w1.bias)
        h = F.gelu(self.ln1(h), approximate="tanh")
        h = self.drop(h)
        return F.linear(h, self.fake_q(self.w2.weight, qat), self.w2.bias)


def main():
    t0 = time.perf_counter()
    try:
        blind_excl = load_blind_exclusion_set()
        rng = random.Random(20261127)
        llm_rows, qwen_mdl = synthesize_v2_qwen(blind_excl)
        rng.shuffle(llm_rows)
        n_dev_llm = max(300, int(0.12 * len(llm_rows)))
        dev_llm, tr_llm = llm_rows[:n_dev_llm], llm_rows[n_dev_llm:]

        subtypes = [
            ("trap_otp", 0.13),
            ("trap_fail_subtle", 0.15),
            ("trap_fail", 0.08),
            ("trap_bill_promo_pending", 0.16),
            ("real_expense_rail_verbless", 0.16),
            ("real_expense", 0.12),
            ("real_income", 0.07),
            ("real_refund_hard", 0.05),
            ("real_transfer", 0.04),
            ("real_reload", 0.04),
        ]

        seen = set(blind_excl)

        def unique_filter(items):
            out = []
            for it in items:
                k = norm_key(it["text"])
                if k not in seen:
                    seen.add(k)
                    out.append(it)
            return out

        tr_llm = unique_filter(tr_llm)
        dev_llm = unique_filter(dev_llm)

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
                txt, pkg, src_label, lang, cap, kind, amt = gen_v2_hard_template_case("train", st, rng)
                k = norm_key(txt)
                if k in seen:
                    continue
                seen.add(k)
                rows.append({
                    "text": txt, "source": pkg, "source_label": src_label, "lang": lang, "subtype": st,
                    "expected_captured": cap, "expected_kind": kind, "expected_amount_minor": amt, "is_llm_paraphrase": False,
                })
            return rows

        train_rows = tr_llm + fill_templates(max(32000, 40000 - len(tr_llm)))
        dev_rows = dev_llm + fill_templates(max(3000, 4000 - len(dev_llm)))
        rng.shuffle(train_rows)
        rng.shuffle(dev_rows)
        assert {norm_key(r["text"]) for r in train_rows}.isdisjoint(blind_excl)
        assert {norm_key(r["text"]) for r in dev_rows}.isdisjoint(blind_excl)

        teacher_z_tr, teacher_dev_acc = train_bge_m3_teacher(train_rows, dev_rows)

        update_status("V2_DISTILL_QAT", train_n=len(train_rows), dev_n=len(dev_rows))
        tr_sp, tr_sf, tr_y = t34.precompute_features(train_rows)
        dv_sp, dv_sf, dv_y = t34.precompute_features(dev_rows)

        device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
        torch.manual_seed(20261127)
        net = QatNotifGateNet().to(device)
        cw = torch.tensor([1.5, 1.0, 1.0, 1.05, 1.05, 1.05], device=device)
        opt = torch.optim.AdamW(net.parameters(), lr=3e-3, weight_decay=2e-4)
        epochs = 30
        sched = torch.optim.lr_scheduler.CosineAnnealingLR(opt, T_max=epochs, eta_min=1e-4)

        tr_sf_t = torch.from_numpy(tr_sf).to(device)
        tr_y_t = torch.from_numpy(tr_y).to(device)
        tr_tz_t = torch.from_numpy(teacher_z_tr).to(device)
        dv_sf_t = torch.from_numpy(dv_sf).to(device)

        best_score, best_sd = -1.0, None
        bs = 256
        nr = np.random.default_rng(20261127)
        for ep in range(1, epochs + 1):
            qat = ep > (epochs - 6)
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
            if ep in (1, 15, 25, epochs):
                print(f"[ng2 QAT={qat} ep {ep}/{epochs}] dev_score={sc:.4f}", flush=True)

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
        buf += struct.pack(">iiiiii", 1, V2_BUCKETS, t34.EMB_DIM, t34.STRUCT_DIM, t34.HIDDEN_DIM, t34.NUM_CLASSES)
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

        with gzip.open(BIN_GZ_PATH, "wb", compresslevel=9) as gz:
            gz.write(buf)
        gz_bytes = BIN_GZ_PATH.stat().st_size
        assert gz_bytes < 230_000, f"NG v2 too large: {gz_bytes}"

        parity_vecs = []
        for i in range(min(100, len(dev_rows))):
            lg, pr = t34.dequantize_forward_single(dv_sp[i], dv_sf[i], model_q, best_temp)
            parity_vecs.append({
                "id": f"ng2-dev-{i}", "text": dev_rows[i]["text"], "source": dev_rows[i]["source"],
                "expected_kind": dev_rows[i]["expected_kind"],
                "logits": [round(float(x), 6) for x in lg], "probs": [round(float(x), 6) for x in pr],
            })
        PARITY_PATH.write_text(json.dumps(parity_vecs, ensure_ascii=False, indent=2), encoding="utf-8")

        rep = {
            "total_seconds": round(time.perf_counter() - t0, 1),
            "qwen_model": qwen_mdl,
            "teacher_backbone": "BAAI/bge-m3",
            "teacher_dev_acc": round(teacher_dev_acc, 4),
            "train_count": len(train_rows),
            "dev_count": len(dev_rows),
            "num_buckets": V2_BUCKETS,
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
        REPORT_PATH.write_text(json.dumps(rep, indent=2), encoding="utf-8")
        update_status("DONE", gz_bytes=gz_bytes, dev_acc=rep["dev_metrics_int8"]["accuracy"])
        print("NOTIF GATE V2 DONE:", json.dumps(rep), flush=True)
    except Exception as exc:
        update_status("ERROR", error=str(exc))
        raise


if __name__ == "__main__":
    main()
