#!/usr/bin/env python3
"""MoneyLens Track 5 — Adversarial Red-Team Synthesizer (Dev ~20k + Strictly Disjoint Blind ~4.4k).

Synthesizes Malaysian multilingual (EN/BM/Manglish/ZH) adversarial cases via Qwen2.5-7B-Instruct
plus structured red-team generators across 5 offline MoneyLens surfaces:
  1. notif_gate     (spam/OTP/failed/pre-auth/bill-reminder traps vs noisy completed alerts)
  2. twin_dedup     (cross-app FX-drift / gateway-vs-store / truncated twins vs hard non-dups)
  3. lhdn_tax       (YA 2025/2026 relief reliefs vs boundary traps: Sports Toto, Popular Cafe, etc.)
  4. parser_receipt (noisy thermal/MyInvois receipts + OOD Misc SMEs + multi-amount decoys)
  5. quick_search   (quick-entry model/unit/non-refundable traps + finance-trivia OOD search)
"""
import argparse
import gc
import json
import random
import re
import sys
import time
from collections import Counter
from pathlib import Path

OUT_DIR = Path("/content/outputs_track5_v1")
STATUS_PATH = OUT_DIR / "track5_adversarial_v1_status.json"
CACHE_PATH = OUT_DIR / "track5_llm_synth_cache.json"
DEV_PATH = OUT_DIR / "track5_adversarial_dev.json"
BLIND_PATH = OUT_DIR / "track5_adversarial_blind.json"
REPORT_PATH = OUT_DIR / "track5_adversarial_report.json"

QWEN_CANDIDATES = [
    "Qwen/Qwen2.5-7B-Instruct",
    "Qwen/Qwen2.5-3B-Instruct",
]

FORBIDDEN_SCRIPT_RE = re.compile(
    r"[\u0400-\u04FF\u0600-\u06FF\u0900-\u097F\u0E00-\u0E7F\u3040-\u30FF\uAC00-\uD7AF\u1100-\u11FF]"
)

APPS_WALLET_BANK = [
    ("my.com.tngdigital.ewallet", "Touch 'n Go eWallet", "tng"),
    ("com.maybank2u.life", "MAE by Maybank2u", "mae"),
    ("com.cimb2u.octo", "CIMB OCTO MY", "cimb"),
    ("my.com.gxbank.mobile", "GXBank", "gxbank"),
    ("com.shopee.pay.my", "ShopeePay MY", "shopeepay"),
    ("my.com.publicbank.mypb", "MyPB by Public Bank", "pbb"),
    ("my.com.rhb.mobilebank", "RHB Mobile Banking", "rhb"),
    ("com.grabtaxi.passenger", "GrabPay", "grabpay"),
]

# Strictly disjoint merchant pools for DEV vs BLIND
DEV_MERCHANTS = [
    ("Kedai Kopi Ah Seng", "Food & drinks", "Hawker & Mamak"),
    ("Restoran Serai Damansara", "Food & drinks", "Restaurant & Cafe"),
    ("Mamak Taj Curry House", "Food & drinks", "Hawker & Mamak"),
    ("ZUS Coffee KLCC", "Food & drinks", "Coffee, Tea & Drinks"),
    ("Bistro Harmoni Subang", "Food & drinks", "Restaurant & Cafe"),
    ("Pasar Segar Sejahtera", "Groceries", "Supermarket & Hypermarket"),
    ("99 Speedmart PJ", "Groceries", "Convenience Store (99 Speedmart / 7-Eleven / FamilyMart)"),
    ("FamilyMart Sunway", "Groceries", "Convenience Store (99 Speedmart / 7-Eleven / FamilyMart)"),
    ("Farmasi Mesra Rakyat", "Health & medical", "Pharmacy & Medicine"),
    ("Klinik Keluarga Impian", "Health & medical", "Clinic & GP"),
    ("Pusat Pakar Medis Puteri", "Health & medical", "Hospital & Specialist"),
    ("POPULAR Book Co", "Education", "Books & Stationery"),
    ("MPH Bookstores", "Education", "Books & Stationery"),
    ("Machines Mid Valley", "Shopping", "Electronics & Gadgets"),
    ("Watsons Malaysia", "Health & medical", "Pharmacy & Medicine"),
    ("PETRONAS Setia Alam", "Transport", "Petrol & EV Charging"),
    ("Shell LDP", "Transport", "Petrol & EV Charging"),
    ("Gentari EV Hub", "Transport", "Petrol & EV Charging"),
    ("Unifi Fibre", "Bills & utilities", "Home Internet & WiFi (Unifi / Time)"),
    ("Tenaga Nasional", "Bills & utilities", "Electricity (TNB)"),
    ("Shopee MY", "Shopping", "Online Marketplace (Shopee / Lazada / TikTok)"),
    ("Lazada Malaysia", "Shopping", "Online Marketplace (Shopee / Lazada / TikTok)"),
]

BLIND_MERCHANTS = [
    ("Kopitiam Sinaran Pagi", "Food & drinks", "Hawker & Mamak"),
    ("Warung Nasi Kandar Pelita Baru", "Food & drinks", "Hawker & Mamak"),
    ("Cafe Embun Pagi Cheras", "Food & drinks", "Restaurant & Cafe"),
    ("Tealive Pavilion Bukit Jalil", "Food & drinks", "Coffee, Tea & Drinks"),
    ("Dapur Nusantara Bangi", "Food & drinks", "Restaurant & Cafe"),
    ("Borong Mart Kajang", "Groceries", "Supermarket & Hypermarket"),
    ("KK Super Mart Cheras", "Groceries", "Convenience Store (99 Speedmart / 7-Eleven / FamilyMart)"),
    ("7-Eleven Mont Kiara", "Groceries", "Convenience Store (99 Speedmart / 7-Eleven / FamilyMart)"),
    ("Farmasi Bahagia Ampang", "Health & medical", "Pharmacy & Medicine"),
    ("Poliklinik Sihat Sri Petaling", "Health & medical", "Clinic & GP"),
    ("Hospital Pakar Mutiara", "Health & medical", "Hospital & Specialist"),
    ("BookXcess Tamarind Square", "Education", "Books & Stationery"),
    ("Kinokuniya KLCC", "Education", "Books & Stationery"),
    ("Switch Apple Reseller", "Shopping", "Electronics & Gadgets"),
    ("CARiNG Pharmacy Kepong", "Health & medical", "Pharmacy & Medicine"),
    ("BHPetrol Kuantan", "Transport", "Petrol & EV Charging"),
    ("Caltex Seremban", "Transport", "Petrol & EV Charging"),
    ("JomCharge Station PJ", "Transport", "Petrol & EV Charging"),
    ("TIME Fibre Broadband", "Bills & utilities", "Home Internet & WiFi (Unifi / Time)"),
    ("Air Selangor", "Bills & utilities", "Water & Sewerage (Air Selangor / IWK)"),
    ("TikTok Shop MY", "Shopping", "Online Marketplace (Shopee / Lazada / TikTok)"),
    ("Zalora Malaysia", "Shopping", "Online Marketplace (Shopee / Lazada / TikTok)"),
]

DEV_TRUNC_PAIRS = [
    ("CARiNG Pharmacy", "PAY*CRNGPHARM-TRX"),
    ("99 Speedmart", "DNQR*99SPDM-KJ"),
    ("Watsons Pharmacy", "MYDEBIT*WSNSPHARM-TRX"),
    ("Guardian Pharmacy", "TNGD*GRDNPHARM-TRX"),
    ("Easybook Express", "TNGD*EZBKXPRSS"),
]

BLIND_TRUNC_PAIRS = [
    ("Trip.com Flights", "IPAY88*TRPCOM FLT"),
    ("MR DIY", "PAY*MRDY-SLNGR"),
    ("FamilyMart", "DNQR*FMLYMRT-KL"),
    ("MIXUE", "TNGD*MXUE-BKTJLL"),
    ("ZUS Coffee", "PAY*ZUSCFF-KLCC"),
]

DEV_DISTINCT_PAIRS = [
    ("Klinik Pergigian Harmoni", "Klinik Pergigian Sentosa"),
    ("Dooki Kitchen", "PERTIWI Soup Kitchen"),
    ("Restoran Maju Jaya", "Restoran Maju Setia"),
    ("Kedai Buku Ceria", "Kedai Buku Cahaya"),
]

BLIND_DISTINCT_PAIRS = [
    ("Klinik Perubatan Iman", "Klinik Perubatan Sinar"),
    ("Medan Selera Indah", "Medan Selera Putra"),
    ("Farmasi Mutiara", "Farmasi Seri Pagi"),
    ("Bengkel Kereta Cepat", "Bengkel Kereta Tepat"),
]

DEV_OOD_SMES = [
    "Perniagaan Teras Jaya", "Kreatif Maju Resources", "Syarikat Timur 88",
    "Bintang Usaha Trading", "Sinaran Abadi Enterprise", "Wawasan Berkat Solutions",
    "Delta Harmoni Holdings", "Cahaya Gemilang Services", "Sentosa Teguh Venture",
    "Permata Angkasa Sdn Bhd", "ABC Enterprise", "XYZ Trading 101",
    "Nusantara Prima Works", "Indah Permai Supply", "Mega Rimbun Venture",
]

BLIND_OOD_SMES = [
    "Lagenda Intan Trading", "Kencana Putra Resources", "Seri Melati Enterprise",
    "Citra Murni Holdings", "Bayu Sutera Services", "Mercu Jaya Venture",
    "Tunjang Bakti Solutions", "Pelangi Timur Enterprise", "Sejati Makmur Works",
    "Rimbunan Hijau Trading", "PQR Maju 77", "Vortex Utama Resources",
    "Kasturi Raya Supply", "Dinamik Utara Sdn Bhd", "Suria Mentari Enterprise",
]

DEV_PEOPLE = ["Ali", "Sarah", "Mei", "Kumar", "Farah", "Jason"]
BLIND_PEOPLE = ["Hafiz", "Rachel", "Wei", "Deepak", "Nadia", "Kenji"]

RAIL_GATEWAY_LABELS = [
    ("my.com.tngdigital.ewallet", "Touch 'n Go eWallet", "TNG Digital"),
    ("com.shopee.pay.my", "ShopeePay MY", "ShopeePay"),
    ("com.grabtaxi.passenger", "GrabPay", "GrabPay"),
    ("my.com.setel.mobile", "Setel", "Setel"),
]

BANK_GATEWAY_APPS = [
    ("com.maybank2u.life", "MAE by Maybank2u", "MAE"),
    ("com.cimb2u.octo", "CIMB OCTO MY", "CIMB OCTO"),
    ("my.com.gxbank.mobile", "GXBank", "GXBank"),
    ("my.com.publicbank.mypb", "MyPB by Public Bank", "Public Bank"),
    ("my.com.rhb.mobilebank", "RHB Mobile Banking", "RHB"),
]


def update_status(stage: str, **extra):
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    payload = {"stage": stage, "ts": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()), **extra}
    STATUS_PATH.write_text(json.dumps(payload, indent=2, ensure_ascii=False), encoding="utf-8")
    print(f"[STATUS] {stage} {extra}", flush=True)


def _strip_json_fence(raw: str) -> str:
    s = raw.strip()
    if s.startswith("```"):
        s = re.sub(r"^```(?:json)?\s*", "", s)
        s = re.sub(r"\s*```$", "", s)
    return s.strip()


def run_qwen_adversarial_synthesis():
    """Synthesizes diverse EN/BM/Manglish/ZH adversarial templates via Qwen2.5-7B on Colab GPU."""
    import torch

    if CACHE_PATH.exists():
        try:
            cached = json.loads(CACHE_PATH.read_text(encoding="utf-8"))
            if len(cached.get("promo_traps", [])) >= 40 and len(cached.get("search_trivia_ood", [])) >= 30:
                print(f"[CACHE] Reusing cached Qwen track5 synthesis from {CACHE_PATH}", flush=True)
                return cached
        except Exception:
            pass

    update_status("LOADING_QWEN_TEACHER")
    from transformers import AutoModelForCausalLM, AutoTokenizer

    vram_gb = torch.cuda.get_device_properties(0).total_memory / (1024 ** 3) if torch.cuda.is_available() else 0.0
    dtype = torch.bfloat16 if (torch.cuda.is_available() and torch.cuda.get_device_capability(0)[0] >= 8) else torch.float16
    cands = ["Qwen/Qwen2.5-3B-Instruct"] if vram_gb < 18.0 else QWEN_CANDIDATES
    model, tok, used_id = None, None, None
    for mid in cands:
        try:
            print(f"Loading Qwen red-team teacher: {mid} (VRAM={vram_gb:.1f}GB)...", flush=True)
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

    # All prompts emit templates with {M}, {AMT}, {BAL}, {CODE}, {DT} placeholders so Dev & Blind instantiate disjoint merchants
    specs = [
        ("promo_traps", 5,
         "Generate 12 diverse Malaysian bank/e-wallet/shopping notification TRAP messages (promos, cashback offers, vouchers, minimum spend challenges, lucky draws) that MUST NOT be captured as real payments.\n"
         "Use placeholders {M} for brand, {AMT} for RM amount like RM 50.00, {REWARD} for RM 8.00.\n"
         "Mix English, Bahasa Melayu, and Simplified Chinese across items.\n"
         "Return ONLY JSON: {\"items\": [\"...\", ...]}"),
        ("otp_traps", 4,
         "Generate 12 diverse Malaysian bank/e-wallet OTP, TAC, 3DS, and Secure2u approval request notifications containing {AMT} and {CODE} (6-digit OTP) and {M}, e.g. 'OTP {CODE} for payment of {AMT} at {M}'. None of them are completed payments.\n"
         "Mix English, Bahasa Melayu, and Simplified Chinese.\n"
         "Return ONLY JSON: {\"items\": [\"...\", ...]}"),
        ("failed_preauth_bill_traps", 5,
         "Generate 15 diverse non-completed Malaysian notifications covering: (a) failed/declined/cancelled/insufficient-balance payments, (b) pre-authorization/temporary card holds, and (c) upcoming bill due reminders / statement ready / plain balance inquiries. Use placeholders {M} and {AMT} and {DT}.\n"
         "Mix English, Bahasa Melayu, and Simplified Chinese.\n"
         "Return ONLY JSON: {\"items\": [\"...\", ...]}"),
        ("real_noisy_payments", 5,
         "Generate 12 genuine COMPLETED Malaysian payment notifications using {M} and {AMT} that include realistic distractors like 'Cashback earned: {CB}', 'Available balance: {BAL}', 'Earned 40 points', and 'Never share your OTP/TAC'.\n"
         "Mix English, Bahasa Melayu, and Simplified Chinese.\n"
         "Return ONLY JSON: {\"items\": [\"...\", ...]}"),
        ("search_trivia_ood", 5,
         "Generate 15 diverse finance/Malaysia general-knowledge or textbook trivia questions in English, Bahasa Melayu, and Simplified Chinese (e.g. compound interest formula, Bursa Malaysia history, how e-filing works, bank hours, SST definition) that do NOT represent personal ledger transaction searches.\n"
         "Return ONLY JSON: {\"items\": [\"...\", ...]}"),
    ]

    prompts, keys = [], []
    for key, reps, base_p in specs:
        for r in range(reps):
            keys.append(key)
            prompts.append(base_p + f"\nVariant batch #{r + 1}: make every wording distinct and realistic.")

    update_status("GENERATING_ADVERSARIAL_SYNTH", n_prompts=len(prompts), model=used_id)
    out_buckets = {k: [] for k, _, _ in specs}
    bs = 6
    n_batches = (len(prompts) + bs - 1) // bs
    for st in range(0, len(prompts), bs):
        print(f"[QWEN SYNTH] Batch {st // bs + 1}/{n_batches}...", flush=True)
        batch_p = prompts[st:st + bs]
        batch_k = keys[st:st + bs]
        msgs = [[{"role": "system", "content": "You are a red-team QA engineer generating Malaysian fintech test strings. Output strict JSON only."}, {"role": "user", "content": p}] for p in batch_p]
        txts = [tok.apply_chat_template(m, tokenize=False, add_generation_prompt=True) for m in msgs]
        enc = tok(txts, padding=True, truncation=True, max_length=768, return_tensors="pt").to(model.device)
        with torch.no_grad():
            gen = model.generate(**enc, max_new_tokens=512, do_sample=True, temperature=0.85, top_p=0.92, pad_token_id=tok.pad_token_id)
        in_len = enc["input_ids"].shape[1]
        for idx, seq in enumerate(gen):
            raw_out = tok.decode(seq[in_len:], skip_special_tokens=True)
            k = batch_k[idx]
            try:
                parsed = json.loads(_strip_json_fence(raw_out))
                items = parsed.get("items", []) if isinstance(parsed, dict) else []
                for it in items:
                    s = str(it).strip()
                    if 6 <= len(s) <= 240 and not FORBIDDEN_SCRIPT_RE.search(s):
                        out_buckets[k].append(s)
            except Exception:
                continue

    del model, tok
    gc.collect()
    if torch.cuda.is_available():
        torch.cuda.empty_cache()

    res = {"model_id": used_id, **{k: list(dict.fromkeys(v)) for k, v in out_buckets.items()}}
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    CACHE_PATH.write_text(json.dumps(res, indent=2, ensure_ascii=False), encoding="utf-8")
    print("[QWEN SYNTH] Counts:", {k: len(v) for k, v in res.items() if isinstance(v, list)}, flush=True)
    return res


def _builtin_synth_fallback():
    """Minimal deterministic templates for --self-test mode without GPU."""
    return {
        "model_id": "self-test-builtin",
        "promo_traps": [
            "Spend {AMT} at {M} to get {CB} cashback! Limited time offer.",
            "Flash sale at {M}! Enjoy {CB} voucher when you spend {AMT}.",
            "Belanja {AMT} di {M} dan menang pulangan tunai {CB}!",
            "在 {M} 消费满 {AMT} 立减 {CB} 优惠券！",
        ],
        "otp_traps": [
            "OTP {CODE} for payment of {AMT} at {M}. Do not share.",
            "TAC: {CODE} to approve your transfer of {AMT} at {M}.",
            "Kod pengesahan TAC {CODE} untuk transaksi {AMT} di {M}.",
            "验证码 {CODE}：用于支付 {AMT}（{M}），切勿泄露。",
        ],
        "failed_preauth_bill_traps": [
            "Payment of {AMT} at {M} was declined due to insufficient balance.",
            "Transaksi {AMT} di {M} gagal diproses.",
            "Pre-authorisation hold of {AMT} at {M} pending confirmation.",
            "Reminder: Your {M} bill of {AMT} is due on {DT}.",
            "Account balance at {M} is {AMT} as of {DT}.",
            "账单提醒：{M} 本期账单 {AMT} 将于 {DT} 到期。",
        ],
        "real_noisy_payments": [
            "You have paid {AMT} at {M}. Cashback earned: {CB}. Balance: {BAL}. Never share your OTP/TAC.",
            "Pembayaran berjaya {AMT} kepada {M}. Baki akaun: {BAL}.",
            "付款成功 {AMT} {M}。余额 {BAL}。",
        ],
        "search_trivia_ood": [
            "how to calculate compound interest formula",
            "what is lhdn tax relief maximum limit definition",
            "sejarah penubuhan bursa malaysia wikipedia",
            "cara kira kadar faedah pinjaman bank",
            "什么是复利计算公式",
            "马来西亚所得税免税额怎么计算",
        ],
    }


def _split_list_disjoint(items, split_name: str, salt: int):
    rng = random.Random(7777 + salt)
    clean = list(dict.fromkeys(x.strip() for x in items if x and len(x.strip()) >= 6))
    rng.shuffle(clean)
    cut = max(1, int(0.8 * len(clean)))
    return clean[:cut] if split_name == "dev" else clean[cut:]


def _fmt_rm(minor: int) -> str:
    return f"RM {minor / 100:,.2f}" if minor >= 100000 else f"RM {minor / 100:.2f}"


def generate_split_cases(split_name: str, synth: dict, counts: dict, seed: int):
    rng = random.Random(seed)
    is_dev = split_name == "dev"
    merch_pool = DEV_MERCHANTS if is_dev else BLIND_MERCHANTS
    trunc_pool = DEV_TRUNC_PAIRS if is_dev else BLIND_TRUNC_PAIRS
    dist_pool = DEV_DISTINCT_PAIRS if is_dev else BLIND_DISTINCT_PAIRS
    ood_pool = DEV_OOD_SMES if is_dev else BLIND_OOD_SMES
    people_pool = DEV_PEOPLE if is_dev else BLIND_PEOPLE
    city_suffix = ["PJ", "KL", "Subang", "Cheras", "Puchong"] if is_dev else ["Bangi", "Ampang", "Kepong", "Kajang", "Kuantan"]
    ref_pfx = "DEV" if is_dev else "BLD"

    promo_llm = [
        x for x in _split_list_disjoint(synth.get("promo_traps", []), split_name, 1)
        if ("{AMT}" in x or "{CB}" in x or "{REWARD}" in x) and not re.search(r"(?i)\b(?:you have paid|completed|berjaya dibayar)\b|付款成功|已支付", x)
    ]
    otp_llm = [
        x for x in _split_list_disjoint(synth.get("otp_traps", []), split_name, 2)
        if "{AMT}" in x and re.search(r"(?i)(?:\{CODE\}|otp|tac|secure2u|3ds|pengesahan|kod|验证码|动态|密码)", x)
    ]
    fail_llm = [
        x for x in _split_list_disjoint(synth.get("failed_preauth_bill_traps", []), split_name, 3)
        if "{AMT}" in x and not re.search(r"(?i)\b(?:you have paid|berjaya dibayar)\b|付款成功|已支付", x)
    ]
    real_llm = [
        x for x in _split_list_disjoint(synth.get("real_noisy_payments", []), split_name, 4)
        if "{AMT}" in x and re.search(r"(?i)(?:paid|completed|successful|berjaya|dibayar|approved|charged|deducted|付款成功|已支付|支付成功)", x)
        and not re.search(r"(?i)(?:declined|failed|gagal|失败|reminder|due on|pre-?auth|temporary hold|spend \{amt\} to)", x)
    ]
    trivia_llm = _split_list_disjoint(synth.get("search_trivia_ood", []), split_name, 5)

    def fill_tpl(tpl: str, merch: str, amt_minor: int):
        cb = _fmt_rm(rng.choice([120, 250, 500, 800, 1000]))
        bal = _fmt_rm(rng.randint(25000, 950000))
        code = str(rng.randint(100000, 999999))
        dt = rng.choice(["2026-11-28", "30 Nov 2026", "29/11/2026"])
        s = tpl.replace("{M}", merch).replace("{AMT}", _fmt_rm(amt_minor)).replace("{CB}", cb).replace("{REWARD}", cb).replace("{BAL}", bal).replace("{CODE}", code).replace("{DT}", dt)
        return s

    cases = []

    # 1. NOTIF_GATE
    n_notif = counts["notif_gate"]
    n_traps = int(round(n_notif * (5000 / 7000)))
    n_reals = n_notif - n_traps

    hard_promo = [
        "Congrats! Spend {AMT} at {M} to get {CB} cashback voucher!",
        "Exclusive promo: Pay with MAE at {M}, spend {AMT} & win up to {CB} bonus!",
        "Unlock {CB} discount when you checkout {AMT} at {M}. Claim now!",
        "Promosi hebat! Belanja {AMT} di {M} untuk dapatkan rebat {CB}.",
        "Baucar {CB} menanti anda untuk pembelian min spend {AMT} di {M}!",
        "限时优惠：在 {M} 消费满 {AMT} 立减 {CB}，点击领取优惠券！",
        "恭喜获得 {M} {CB} 现金回扣券，消费 {AMT} 即可使用。",
    ]
    hard_otp = [
        "OTP {CODE} for payment of {AMT} at {M}. Never share this code.",
        "TAC {CODE} to authorise transaction of {AMT} at {M}. Do not disclose.",
        "Secure2u approval requested: Payment of {AMT} to {M} (Ref {CODE}). Approve in app.",
        "3DS Verification Code {CODE} for {AMT} card purchase at {M}.",
        "Nombor TAC {CODE} untuk pengesahan bayaran {AMT} kepada {M}. Jangan kongsi.",
        "Sila sahkan pembayaran {AMT} ke {M}. Kod keselamatan OTP: {CODE}.",
        "验证码 {CODE}：您正在向 {M} 支付 {AMT}，请勿告诉他人。",
        "动态密码 {CODE} 用于确认 {AMT} 付款（{M}）。",
    ]
    hard_failed = [
        "Your payment of {AMT} at {M} was declined due to insufficient balance.",
        "Transaction of {AMT} at {M} failed. No funds were deducted.",
        "Payment {AMT} to {M} was cancelled / expired.",
        "Transaksi {AMT} di {M} tidak berjaya kerana baki tidak mencukupi.",
        "Pembayaran {AMT} kepada {M} telah dibatalkan oleh pihak bank.",
        "扣款失败：您在 {M} 的 {AMT} 交易因余额不足已被拒绝。",
        "交易已取消：{AMT}（{M}）未扣款。",
    ]
    hard_preauth = [
        "Pre-authorisation hold of {AMT} at {M}. Final amount settles on checkout.",
        "Temporary hold: {AMT} earmarked on your card at {M}.",
        "Pending authorization of {AMT} at {M} awaiting merchant confirmation.",
        "Pegangan sementara {AMT} di {M} sedang diproses.",
        "预授权冻结：{M} {AMT} 待商户最终结算。",
    ]
    hard_bill_bal = [
        "Reminder: Your {M} bill of {AMT} is due on {DT}. Pay before due date to avoid late fee.",
        "Your monthly statement from {M} is ready. Minimum payment: {AMT}, due {DT}.",
        "Account balance inquiry: Your savings balance at {M} is {AMT}.",
        "Credit limit update: Available limit is {AMT}.",
        "Peringatan bil: Bil {M} berjumlah {AMT} tarikh akhir {DT}. Bayaran minimum {CB}.",
        "Baki akaun semasa anda ialah {AMT}. Penyata bulanan telah tersedia.",
        "账单提醒：您的 {M} 账单 {AMT} 即将于 {DT} 到期，最低还款 {CB}。",
        "账户余额查询：当前可用余额 {AMT}。",
    ]

    trap_specs = [
        ("promo_spam", promo_llm + hard_promo),
        ("otp_tac_3ds", otp_llm + hard_otp),
        ("failed_declined", fail_llm[:max(1, len(fail_llm)//3)] + hard_failed),
        ("pending_preauth", hard_preauth),
        ("bill_reminder_balance", fail_llm[max(1, len(fail_llm)//3):] + hard_bill_bal),
    ]
    for i in range(n_traps):
        sub_t, pool = trap_specs[i % len(trap_specs)]
        tpl = rng.choice(pool)
        merch = rng.choice(merch_pool)[0] + f" {rng.choice(city_suffix)}"
        amt_minor = rng.randint(1500, 48000)
        pkg, src, _ = rng.choice(APPS_WALLET_BANK)
        txt = fill_tpl(tpl, merch, amt_minor)
        # Disambiguate subtypes neatly if LLM failed/preauth mixed
        if sub_t == "promo_spam" and any(w in txt.lower() for w in ("otp", "tac", "验证码")):
            txt = fill_tpl(rng.choice(hard_promo), merch, amt_minor)
        cases.append({
            "id": f"{ref_pfx}-NG-T{i:05d}",
            "surface": "notif_gate",
            "subtype": sub_t,
            "lang": "zh" if any("\u4e00" <= c <= "\u9fff" for c in txt) else "en",
            "text": txt,
            "source": src,
            "package": pkg,
            "expected_captured": False,
            "expected_amount_minor": None,
            "expected_kind": None,
        })

    real_amts = [850, 1450, 2390, 3800, 5240, 8990, 12500, 19900] if is_dev else [920, 1580, 2460, 3950, 5420, 9180, 13200, 20500]
    for i in range(n_reals):
        merch, _, _ = rng.choice(merch_pool)
        amt_minor = rng.choice(real_amts)
        cb_minor = rng.choice([100, 150, 200, 350])
        bal_minor = rng.randint(40000, 800000)
        pts = rng.randint(10, 120)
        pkg, src, _ = rng.choice(APPS_WALLET_BANK)
        mode = i % 6
        if mode == 0 and real_llm:
            txt = fill_tpl(rng.choice(real_llm), merch, amt_minor)
            kind = "expense"
            if any(w in txt.lower() for w in ("decline", "failed", "gagal", "reminder", "due on", "pre-auth", "spend ")):
                txt = f"You have paid {_fmt_rm(amt_minor)} at {merch}. Cashback earned: {_fmt_rm(cb_minor)}. Balance: {_fmt_rm(bal_minor)}. Never share your OTP/TAC."
        elif mode == 1:
            txt = f"Payment of {_fmt_rm(amt_minor)} at {merch} completed. Earned {pts} points. Avail bal: {_fmt_rm(bal_minor)}. Do not share your TAC."
            kind = "expense"
        elif mode == 2:
            txt = f"Pembayaran berjaya dibayar: {_fmt_rm(amt_minor)} kepada {merch}. Baki: {_fmt_rm(bal_minor)}. Jangan kongsi OTP/TAC."
            kind = "expense"
        elif mode == 3:
            txt = f"付款成功 {_fmt_rm(amt_minor)} {merch}。当前余额 {_fmt_rm(bal_minor)}。#{ref_pfx}{i}"
            kind = "expense"
        elif mode == 4:
            if i % 2 == 0:
                txt = f"Refund of {_fmt_rm(amt_minor)} from {merch} has been credited to your account."
                kind = "refund"
            else:
                txt = f"You have paid {_fmt_rm(amt_minor)} at {merch} (non-refundable booking). Confirmed."
                kind = "expense"
        else:
            if i % 2 == 0:
                p = rng.choice(people_pool)
                txt = f"DuitNow: You have received {_fmt_rm(amt_minor)} from {p} in your account."
                kind = "income"
            else:
                txt = f"You have transferred {_fmt_rm(amt_minor)} from MAE to Touch 'n Go eWallet successfully."
                kind = "transfer"
        cases.append({
            "id": f"{ref_pfx}-NG-R{i:05d}",
            "surface": "notif_gate",
            "subtype": "real_" + kind,
            "lang": "zh" if mode == 3 else ("bm" if mode == 2 else "en"),
            "text": txt,
            "source": src,
            "package": pkg,
            "expected_captured": True,
            "expected_amount_minor": amt_minor,
            "expected_kind": kind,
        })

    # 2. TWIN_DEDUP
    n_twin = counts["twin_dedup"]
    n_merge = n_twin // 2
    n_nondup = n_twin - n_merge
    base_ts = 1764000000000
    twin_merge_amts = [1250, 2490, 3850, 4890, 6500, 12800, 22400] if is_dev else [1340, 2570, 3920, 4960, 6680, 13100, 22900]
    for i in range(n_merge):
        amt_minor = rng.choice(twin_merge_amts)
        amt_str = f"{amt_minor / 100:.2f}"
        merch = rng.choice(merch_pool)[0]
        w_pkg, w_src, rail_nm = rng.choice(RAIL_GATEWAY_LABELS)
        b_pkg, b_src, _ = rng.choice(BANK_GATEWAY_APPS)
        t0 = base_ts + i * 5_000_000
        delay = rng.randint(15_000, 220_000)  # up to 3.6 min cross-app delay
        auto_rec = (i % 5 != 0)
        sub_m = i % 4
        if sub_m == 0:
            # Gateway vs Store
            txt_a = f"You have paid RM {amt_str} at {merch}"
            txt_b = f"DuitNow transfer of RM {amt_str} to {rail_nm} completed."
            kind_name = "gateway_vs_store"
        elif sub_m == 1:
            # Truncated vs Clean catalog merchant
            clean_m, trunc_m = rng.choice(trunc_pool)
            txt_a = f"You have paid RM {amt_str} at {clean_m}"
            txt_b = f"Card payment of RM {amt_str} at {trunc_m} approved."
            kind_name = "truncated_vs_clean"
        elif sub_m == 2:
            # FX conversion rounding drift across two apps (same USD/SGD + <= 2.5% MYR drift)
            usd = round(amt_minor / 445.0, 2)
            usd_s = f"{usd:.2f}"
            myr_a = round(usd * 4.44, 2)
            myr_b = round(usd * 4.51, 2)  # ~1.5% spread drift
            txt_a = f"Paid USD {usd_s} (RM {myr_a:.2f}) at {merch} completed."
            txt_b = f"Card charged USD {usd_s} (RM {myr_b:.2f}) at {merch} approved."
            kind_name = "fx_drift_twin"
        else:
            # CJK wallet alert vs EN bank rail
            txt_a = f"已支付 RM {amt_str} {merch}"
            txt_b = f"Card payment of RM {amt_str} at {rail_nm} approved."
            kind_name = "cjk_vs_rail_twin"
        cases.append({
            "id": f"{ref_pfx}-TD-M{i:05d}",
            "surface": "twin_dedup",
            "subtype": kind_name,
            "expected_merged": True,
            "auto_record": auto_rec,
            "alert_a": {"pkg": w_pkg, "source": w_src, "text": txt_a, "post_ms": t0},
            "alert_b": {"pkg": b_pkg, "source": b_src, "text": txt_b, "post_ms": t0 + delay},
        })

    twin_non_amts = [1850, 3200, 4900, 6500, 9500] if is_dev else [1940, 3350, 5120, 6780, 9840]
    for i in range(n_nondup):
        amt_minor = rng.choice(twin_non_amts)
        amt_str = f"{amt_minor / 100:.2f}"
        merch = rng.choice(merch_pool)[0]
        w_pkg, w_src, rail_nm = rng.choice(RAIL_GATEWAY_LABELS)
        b_pkg, b_src, _ = rng.choice(BANK_GATEWAY_APPS)
        t0 = base_ts + (n_merge + i) * 5_000_000
        sub_n = i % 5
        if sub_n == 0:
            # Same-app consecutive separate purchases with different refs (>5s apart)
            txt_a = f"You have paid RM {amt_str} at {merch} Ref:{ref_pfx}A{i}"
            txt_b = f"You have paid RM {amt_str} at {merch} Ref:{ref_pfx}B{i}"
            pa, pb, sa, sb, dly, kind_name = w_pkg, w_pkg, w_src, w_src, 120_000, "same_app_repeat"
        elif sub_n == 1:
            # Real Bank -> Wallet internal reload followed by Wallet -> Merchant spend
            txt_a = f"You have transferred RM {amt_str} from MAE to {rail_nm} successfully."
            txt_b = f"You have paid RM {amt_str} at {merch}"
            pa, pb, sa, sb, dly, kind_name = "com.maybank2u.life", w_pkg, "MAE by Maybank2u", w_src, 45_000, "reload_plus_spend"
        elif sub_n == 2:
            # Distinct SMEs sharing trade word
            m1, m2 = rng.choice(dist_pool)
            txt_a = f"You have paid RM {amt_str} at {m1}"
            txt_b = f"Card payment RM {amt_str} at {m2} approved."
            pa, pb, sa, sb, dly, kind_name = w_pkg, b_pkg, w_src, b_src, 45_000, "distinct_sme_conflict"
        elif sub_n == 3:
            # Different MYR amounts (non-FX)
            amt2_str = f"{(amt_minor + 230) / 100:.2f}"
            txt_a = f"You have paid RM {amt_str} at {merch}"
            txt_b = f"Card payment RM {amt2_str} at {merch} approved."
            pa, pb, sa, sb, dly, kind_name = w_pkg, b_pkg, w_src, b_src, 30_000, "different_amount"
        else:
            # Outside time window (e.g., 14 minutes apart)
            txt_a = f"You have paid RM {amt_str} at {merch}"
            txt_b = f"Card payment RM {amt_str} at {merch} approved."
            pa, pb, sa, sb, dly, kind_name = w_pkg, b_pkg, w_src, b_src, 840_000, "outside_window"
        cases.append({
            "id": f"{ref_pfx}-TD-N{i:05d}",
            "surface": "twin_dedup",
            "subtype": kind_name,
            "expected_merged": False,
            "auto_record": True,
            "alert_a": {"pkg": pa, "source": sa, "text": txt_a, "post_ms": t0},
            "alert_b": {"pkg": pb, "source": sb, "text": txt_b, "post_ms": t0 + dly},
        })

    # 3. LHDN_TAX
    n_lhdn = counts["lhdn_tax"]
    n_claim = n_lhdn // 2
    n_nonclaim = n_lhdn - n_claim

    claim_templates_dev = [
        ("Medical & Health", "expense", "Klinik Pergigian Harmoni", "Health & medical", "Dental & Optical", "Scaling and filling"),
        ("Medical & Health", "expense", "PathLab Screening", "Health & medical", "Full Medical Checkup (LHDN)", "Blood test panel"),
        ("Medical & Health", "expense", "Watsons Pharmacy", "Health & medical", "Pharmacy & Medicine", "Prescription antibiotic medicine"),
        ("Sports & Gym", "expense", "Al-Ikhsan Sports", "Health & medical", "Gym, Sports & Fitness", "Badminton racket & court"),
        ("Sports & Gym", "expense", "Decathlon PJ", "Health & medical", "Gym, Sports & Fitness", "Running shoes for marathon"),
        ("Education", "expense", "Sunway University", "Education", "Higher Education & Tuition", "Part-time degree tuition"),
        ("Education", "expense", "Coursera Upskilling", "Education", "Upskilling & Certification", "Data engineering certification"),
        ("Lifestyle (Tech / Books / WiFi)", "expense", "POPULAR Bookstore", "Education", "Books & Stationery", "Reference books"),
        ("Lifestyle (Tech / Books / WiFi)", "expense", "Machines Mid Valley", "Shopping", "Electronics & Gadgets", "MacBook Pro"),
        ("Lifestyle (Tech / Books / WiFi)", "expense", "Unifi Fibre", "Bills & utilities", "Home Internet & WiFi (Unifi / Time)", "Monthly home internet"),
        ("Medical & Edu Insurance", "expense", "Prudential BSN", "Insurance & Takaful", "Medical & Health Card", "Annual medical card"),
        ("Life Insurance / Takaful", "expense", "AIA Public Takaful", "Insurance & Takaful", "Life Insurance / Takaful", "Family life takaful"),
        ("SSPN / PRS / EV", "expense", "PTPTN SSPN Prime", "Investments & savings", "SSPN Savings", "SSPN-i monthly deposit"),
        ("SSPN / PRS / EV", "expense", "Gentari Charging", "Transport", "Petrol & EV Charging", "EV charging session"),
        ("Zakat / Fitrah Rebate", "expense", "PPZ Zakat", "Family & gifts", "Zakat & Fitrah", "Zakat pendapatan"),
        ("LHDN / PCB Paid", "expense", "LHDN ByrHasil", "Taxes & government", "LHDN Income Tax / PCB", "Monthly PCB installment"),
    ]
    claim_templates_blind = [
        ("Medical & Health", "expense", "Tiew Dental Clinic", "Health & medical", "Dental & Optical", "Dental crown checkup"),
        ("Medical & Health", "expense", "BP Healthcare Ampang", "Health & medical", "Full Medical Checkup (LHDN)", "Annual health screening"),
        ("Medical & Health", "expense", "CARiNG Pharmacy Kepong", "Health & medical", "Pharmacy & Medicine", "Flu prescription medicine & ubat"),
        ("Sports & Gym", "expense", "Sports Direct Bangi", "Health & medical", "Gym, Sports & Fitness", "Gym training equipment"),
        ("Sports & Gym", "expense", "Arena Futsal Seri", "Health & medical", "Gym, Sports & Fitness", "Weekly futsal court fee"),
        ("Education", "expense", "Taylor's University", "Education", "Higher Education & Tuition", "Masters tuition fee"),
        ("Education", "expense", "Udemy Bootcamp", "Education", "Upskilling & Certification", "Cloud architecture course"),
        ("Lifestyle (Tech / Books / WiFi)", "expense", "BookXcess Tamarind", "Education", "Books & Stationery", "Programming textbooks"),
        ("Lifestyle (Tech / Books / WiFi)", "expense", "Switch Kuantan", "Shopping", "Electronics & Gadgets", "iPad tablet"),
        ("Lifestyle (Tech / Books / WiFi)", "expense", "TIME Fibre", "Bills & utilities", "Home Internet & WiFi (Unifi / Time)", "Fibre home broadband"),
        ("Medical & Edu Insurance", "expense", "Great Eastern Takaful", "Insurance & Takaful", "Medical & Health Card", "Health insurance policy"),
        ("Life Insurance / Takaful", "expense", "Etiqa Family Takaful", "Insurance & Takaful", "Life Insurance / Takaful", "Life takaful premium"),
        ("SSPN / PRS / EV", "expense", "Public Mutual PRS", "Investments & savings", "PRS (Private Retirement Scheme)", "PRS contribution"),
        ("SSPN / PRS / EV", "expense", "JomCharge DC Hub", "Transport", "Petrol & EV Charging", "DC fast EV charging"),
        ("Zakat / Fitrah Rebate", "expense", "Lembaga Zakat Selangor", "Family & gifts", "Zakat & Fitrah", "Zakat fitrah payment"),
        ("LHDN / PCB Paid", "expense", "LHDN MyTax", "Taxes & government", "LHDN Income Tax / PCB", "CP500 income tax payment"),
    ]
    trap_templates_dev = [
        ("sports_toto", "expense", "Sports Toto 4D Outlet", "Entertainment & travel", "Games & Hobbies", "4D lottery ticket"),
        ("esports", "expense", "MLBB Esports Tournament", "Entertainment & travel", "Games & Hobbies", "Esports pass"),
        ("sport_rim", "expense", "Kedai Sport Rim Klang", "Transport", "Vehicle Maintenance & Service", "Car alloy rim"),
        ("popular_cafe", "expense", "Popular Cafe Damansara", "Food & drinks", "Restaurant & Cafe", "Popular Hainam kopitiam toast"),
        ("mph_parking", "expense", "MPH Plaza Parking", "Transport", "Parking", "Hourly mall parking"),
        ("three_course_meal", "expense", "Bistro Harmoni", "Food & drinks", "Restaurant & Cafe", "3-course dinner tasting menu"),
        ("golf_course", "expense", "Saujana Golf Course", "Entertainment & travel", "Games & Hobbies", "Weekend golf course green fee"),
        ("preschool_tadika", "expense", "Tadika Bintang Kecil", "Education", "School & Childcare Fees", "Preschool monthly fee"),
        ("motor_insurance", "expense", "Etiqa Motor Insurance", "Transport", "Road Tax & Motor Insurance", "Car insurance & roadtax"),
        ("travel_insurance", "expense", "Allianz Travel Care", "Insurance & Takaful", "Personal Accident", "Overseas travel insurance"),
        ("fire_insurance", "expense", "Zurich Home Fire Insurance", "Housing & property", "Home Repair & Maintenance", "House fire insurance policy"),
        ("vet_clinic", "expense", "Klinik Haiwan Vet PJ", "Shopping", "Home & Living", "Dog vaccination at animal clinic"),
        ("cosmetic_spa", "expense", "Beaute Aesthetic Clinic", "Shopping", "Beauty, Skincare & Grooming", "Botox & cosmetic facial spa"),
        ("watsons_cosmetics", "expense", "Watsons Sunway", "Health & medical", "Pharmacy & Medicine", "Lipstick, shampoo & sunscreen cosmetics"),
        ("home_appliance", "expense", "Senheng Mid Valley", "Shopping", "Electronics & Gadgets", "Washing machine & inverter fridge"),
        ("streaming_ps5", "expense", "Sony PS5 Store / Netflix", "Bills & utilities", "Streaming & App Subscriptions", "PS5 console & Netflix plan"),
        ("ptptn_loan", "expense", "PTPTN Loan Repayment", "Loans, BNPL & Credit", "PTPTN Study Loan", "Monthly PTPTN study loan repayment"),
        ("sspn_withdrawal", "expense", "PTPTN SSPN Prime", "Investments & savings", "SSPN Savings", "SSPN withdrawal pengeluaran"),
        ("ron95_petrol", "expense", "Shell Petrol Station", "Transport", "Petrol & EV Charging", "RON95 fuel pump"),
        ("charity_sadaqah", "expense", "Tabung Kebajikan Rakyat", "Family & gifts", "Charity & Donations", "Monthly charity donation derma"),
    ]
    trap_templates_blind = [
        ("sports_toto", "expense", "Sports Toto Jaya", "Entertainment & travel", "Games & Hobbies", "Toto jackpot slip"),
        ("esports", "expense", "Valorant Esports Arena", "Entertainment & travel", "Games & Hobbies", "Esports cyber cafe entry"),
        ("sport_rim", "expense", "Workshop Sport Rim Kajang", "Transport", "Vehicle Maintenance & Service", "Replacement sport rim"),
        ("popular_cafe", "expense", "Restoran Popular Seafood", "Food & drinks", "Restaurant & Cafe", "Popular butter prawns dinner"),
        ("mph_parking", "expense", "MPH Tower Autopay Parking", "Transport", "Parking", "Autopay parking ticket"),
        ("three_course_meal", "expense", "Embun Bistro", "Food & drinks", "Restaurant & Cafe", "5-course dinner anniversary"),
        ("golf_course", "expense", "Horizon Golf Course", "Entertainment & travel", "Games & Hobbies", "Golf course buggy fee"),
        ("preschool_tadika", "expense", "Taska Cahaya Kuantan", "Education", "School & Childcare Fees", "Nursery daycare fee"),
        ("motor_insurance", "expense", "Bjak Motor Takaful", "Transport", "Road Tax & Motor Insurance", "Vehicle insurance renewal"),
        ("travel_insurance", "expense", "AXA Travel Insurance", "Insurance & Takaful", "Personal Accident", "Flight & travel insurance"),
        ("fire_insurance", "expense", "MSIG Home & Fire Cover", "Housing & property", "Home Repair & Maintenance", "Property fire insurance"),
        ("vet_clinic", "expense", "Paws Veterinary Hospital", "Shopping", "Home & Living", "Cat checkup at vet clinic"),
        ("cosmetic_spa", "expense", "Glow Facial & Botox Salon", "Shopping", "Beauty, Skincare & Grooming", "Cosmetic laser & facial"),
        ("watsons_cosmetics", "expense", "Guardian Ampang", "Health & medical", "Pharmacy & Medicine", "Shampoo, cleanser, foundation & makeup"),
        ("home_appliance", "expense", "Harvey Norman Kuantan", "Shopping", "Electronics & Gadgets", "Aircond & smart TV television"),
        ("streaming_ps5", "expense", "Nintendo Switch / Spotify", "Bills & utilities", "Streaming & App Subscriptions", "Nintendo Switch & Spotify Duo"),
        ("ptptn_loan", "expense", "PTPTN Bayaran Balik Pinjaman", "Loans, BNPL & Credit", "PTPTN Study Loan", "Loan installment repayment"),
        ("sspn_withdrawal", "expense", "SSPN-i Withdrawal", "Investments & savings", "SSPN Savings", "SSPN account withdrawal refund"),
        ("ron95_petrol", "expense", "Petronas Primax 95", "Transport", "Petrol & EV Charging", "Diesel & RON95 petrol"),
        ("charity_sadaqah", "expense", "Amanah Kebajikan Malaysia", "Family & gifts", "Charity & Donations", "Sadaqah & charity contribution"),
    ]
    c_pool = claim_templates_dev if is_dev else claim_templates_blind
    t_pool = trap_templates_dev if is_dev else trap_templates_blind
    for i in range(n_claim):
        bkt, kd, merch, cat, sub, note = c_pool[i % len(c_pool)]
        cases.append({
            "id": f"{ref_pfx}-LHDN-C{i:05d}",
            "surface": "lhdn_tax",
            "subtype": bkt,
            "expected_claimable": True,
            "expected_bucket": bkt,
            "tx": {"kind": kd, "merchant": merch, "category": cat, "subcategory": sub, "note": note, "amount": rng.randint(5000, 180000)},
        })
    for i in range(n_nonclaim):
        sub_t, kd, merch, cat, sub, note = t_pool[i % len(t_pool)]
        cases.append({
            "id": f"{ref_pfx}-LHDN-N{i:05d}",
            "surface": "lhdn_tax",
            "subtype": sub_t,
            "expected_claimable": False,
            "expected_bucket": "",
            "tx": {"kind": kd, "merchant": merch, "category": cat, "subcategory": sub, "note": note, "amount": rng.randint(2500, 150000)},
        })

    # 4. PARSER_RECEIPT
    n_pr = counts["parser_receipt"]
    n_rcpt = int(n_pr * (1500 / 3500))
    n_ood_m = int(n_pr * (1000 / 3500))
    n_multi = n_pr - n_rcpt - n_ood_m

    for i in range(n_rcpt):
        merch = rng.choice(merch_pool)[0]
        sub_minor = rng.randint(1200, 18000)
        sst_minor = int(round(sub_minor * 0.06))
        raw_tot = sub_minor + sst_minor
        rem5 = raw_tot % 5
        round_delta = -rem5 if rem5 <= 2 else (5 - rem5)
        final_minor = raw_tot + round_delta
        mode = i % 5
        if mode == 0:
            # 5-sen rounding + subtotal + SST + cash/change trap
            cash_minor = ((final_minor // 1000) + 2) * 1000
            chg_minor = cash_minor - final_minor
            txt = f"{merch}\nDate: 14/11/2026\nSubtotal: RM {sub_minor/100:.2f}\nSST 6%: RM {sst_minor/100:.2f}\n5-Sen Rounding: {round_delta/100:+.2f}\nGrand Total: RM {final_minor/100:.2f}\nCash: RM {cash_minor/100:.2f}\nChange: RM {chg_minor/100:.2f}"
            exp_amt = final_minor
        elif mode == 1:
            # Ambiguous double Total without rounding cue -> MUST reject (0)
            alt = sub_minor + 500
            txt = f"{merch}\nDate: 15/11/2026\nTotal RM {sub_minor/100:.2f}\nTotal RM {alt/100:.2f}"
            exp_amt = 0
        elif mode == 2:
            # MyInvois flat pipe/thermal + Net Amount
            txt = f"{merch} | TIN: C{rng.randint(100000000,999999999)} | Date: 18/11/2026 | Subtotal: MYR {sub_minor/100:.2f} | Tax: MYR {sst_minor/100:.2f} | Net Amount: MYR {raw_tot/100:.2f}"
            exp_amt = raw_tot
        elif mode == 3:
            # Chinese + Latin receipt
            txt = f"{merch}\n日期: 2026-11-19\n小计 RM {sub_minor/100:.2f}\n合计 RM {raw_tot/100:.2f}\n谢谢光临"
            exp_amt = raw_tot
        else:
            # Malay Jumlah Dibayar with RN1 OCR typo or clean Tunai/Baki
            cash_minor = ((raw_tot // 1000) + 1) * 1000
            txt = f"{merch}\nTarikh: 20/11/2026\nSubtotal: RM {sub_minor/100:.2f}\nJumlah Dibayar: RM {raw_tot/100:.2f}\nTunai: RM {cash_minor/100:.2f}\nBaki: RM {(cash_minor - raw_tot)/100:.2f}"
            exp_amt = raw_tot
        cases.append({
            "id": f"{ref_pfx}-PR-R{i:05d}",
            "surface": "parser_receipt",
            "subtype": "receipt_ocr",
            "text": txt,
            "expected_amount_minor": exp_amt,
        })

    for i in range(n_ood_m):
        sm = ood_pool[i % len(ood_pool)]
        suf = rng.choice(["88", "II", "Venture", "Group", "Hub", "Corp"]) if is_dev else rng.choice(["99", "Plus", "Co", "Global", "Net", "Tech"])
        merch_name = f"{sm} {suf}"
        amt_str = f"{rng.randint(1200, 15000) / 100:.2f}"
        txt = f"You have paid RM {amt_str} at {merch_name}" if i % 2 == 0 else f"Payment RM {amt_str} to {merch_name} successful"
        cases.append({
            "id": f"{ref_pfx}-PR-O{i:05d}",
            "surface": "parser_receipt",
            "subtype": "ood_misc",
            "merchant": merch_name,
            "text": txt,
            "expected_category": "Miscellaneous",
        })

    for i in range(n_multi):
        merch = rng.choice(merch_pool)[0]
        paid_minor = rng.randint(1500, 28000)
        cb_minor = rng.randint(100, 800)
        bal_minor = rng.randint(50000, 900000)
        pts = rng.randint(15, 250)
        mode = i % 3
        if mode == 0:
            txt = f"Cashback earned: {_fmt_rm(cb_minor)}. Available balance: {_fmt_rm(bal_minor)}. You have paid {_fmt_rm(paid_minor)} at {merch} successfully."
        elif mode == 1:
            txt = f"Pembayaran {_fmt_rm(paid_minor)} kepada {merch} berjaya! Baki akaun: {_fmt_rm(bal_minor)}. Earned {pts} points and {_fmt_rm(cb_minor)} rebate."
        else:
            txt = f"Credit limit {_fmt_rm(bal_minor)}. Minimum spend RM 50.00 promo active. Payment of {_fmt_rm(paid_minor)} at {merch} approved."
        cases.append({
            "id": f"{ref_pfx}-PR-M{i:05d}",
            "surface": "parser_receipt",
            "subtype": "multi_amount",
            "source": rng.choice(APPS_WALLET_BANK)[1],
            "text": txt,
            "expected_amount_minor": paid_minor,
        })

    # 5. QUICK_SEARCH
    n_qs = counts["quick_search"]
    n_qe = int(n_qs * (2000 / 2500))
    n_triv = n_qs - n_qe

    qe_dev_traps = [
        ("iPhone 16 Pro 4899 tng", "expense", 489900, "tng"),
        ("PS5 Digital Edition 2299 mae", "expense", 229900, "mae"),
        ("100Plus isotonic 3.20 cash", "expense", 320, "cash"),
        ("2x nasi lemak 16 tng yesterday", "expense", 1600, "tng"),
        ("500ml mineral water 3.50 cash", "expense", 350, "cash"),
        ("3pcs fried chicken 18.50 mae", "expense", 1850, "mae"),
        ("24h clinic 65 cimb", "expense", 6500, "cimb"),
        ("Non-refundable hotel booking 250 mae yesterday", "expense", 25000, "mae"),
        ("Transfer fee RM 2.50 cimb", "expense", 250, "cimb"),
        ("2 packs coffee pods 45.90 gxbank", "expense", 4590, "gxbank"),
        ("Galaxy S24 3799 mae", "expense", 379900, "mae"),
        ("买3杯奶茶 42块 TNG 昨天", "expense", 4200, "tng"),
        ("帮Ali垫付午饭 28元 MAE", "shared", 2800, "mae"),
        ("从MAE充值 200块到TNG", "transfer", 20000, "mae"),
        ("收到Sarah转账 85块 CIMB", "income", 8500, "cimb"),
        ("不可退款机票 RM 380 MAE", "expense", 38000, "mae"),
    ]
    qe_blind_traps = [
        ("iPad Air 2699 mae", "expense", 269900, "mae"),
        ("Xbox Series X 2399 cimb", "expense", 239900, "cimb"),
        ("100Plus 3.50 tng", "expense", 350, "tng"),
        ("4x roti canai 12.00 cash semalam", "expense", 1200, "cash"),
        ("750ml olive oil 29.90 gxbank", "expense", 2990, "gxbank"),
        ("5pcs karipap 7.50 cash", "expense", 750, "cash"),
        ("24h pharmacy 48 tng", "expense", 4800, "tng"),
        ("Not refundable concert ticket 190 tng", "expense", 19000, "tng"),
        ("IBFT transfer fee 1.50 mae", "expense", 150, "mae"),
        ("2 boxes green tea 28.40 shopeepay", "expense", 2840, "shopeepay"),
        ("Pixel 9 Pro 4299 cimb", "expense", 429900, "cimb"),
        ("买2盒月饼 68块 MAE 前天", "expense", 6800, "mae"),
        ("帮Hafiz垫付咖啡 15元 TNG", "shared", 1500, "tng"),
        ("从CIMB充值 150块到TNG", "transfer", 15000, "cimb"),
        ("收到Rachel转账 120块 MAE", "income", 12000, "mae"),
        ("不可退酒店订金 RM 210 CIMB", "expense", 21000, "cimb"),
    ]
    qe_pool = qe_dev_traps if is_dev else qe_blind_traps
    date_opts = ["", " today", " yesterday", " semalam", " 昨天"]
    for i in range(n_qe):
        base_t, kd, amt_m, acct = qe_pool[i % len(qe_pool)]
        txt = base_t
        if i >= len(qe_pool):
            # Vary amounts & date suffix cleanly while preserving traps
            delta = rng.choice([0, 100, 250]) if amt_m > 500 else 0
            if delta and f"{amt_m // 100}" in txt and ".":
                pass
            else:
                txt = base_t
                delta = 0
            # Keep exact expected amount
        cases.append({
            "id": f"{ref_pfx}-QS-Q{i:05d}",
            "surface": "quick_search",
            "subtype": "qe_adversarial",
            "text": txt,
            "today": "2026-11-25",
            "expected_amount_minor": amt_m,
            "expected_kind": kd,
            "expected_account": acct,
        })

    builtin_triv_dev = [
        "what is the compound interest formula in math",
        "how to open a cds account on bursa malaysia",
        "meaning of overnight policy rate bnm",
        "cara kira dividen asb tahunan malaysia",
        "sejarah ringgit malaysia tahun 1967",
        "apa itu sistem percukaian sst di malaysia",
        "什么是央行隔夜政策利率",
        "如何计算复利收益率公式",
    ]
    builtin_triv_blind = [
        "how does inflation affect bond yields",
        "difference between islamic banking and conventional loan theory",
        "when did malaysia introduce goods and services tax",
        "apa maksud kadar dasar semalaman bank negara",
        "cara mohon kad kredit bank negara syarat umum",
        "berapa kadar maksimum potongan kwsp majikan undang undang",
        "通货膨胀为什么会导致货币贬值",
        "什么是股票市盈率pe概念",
    ]
    triv_pool = (trivia_llm + builtin_triv_dev) if is_dev else (trivia_llm + builtin_triv_blind)
    for i in range(n_triv):
        q = triv_pool[i % len(triv_pool)]
        cases.append({
            "id": f"{ref_pfx}-QS-S{i:05d}",
            "surface": "quick_search",
            "subtype": "search_ood_trivia",
            "query": q,
            "expected_max_hits": 0,
        })

    return cases


def audit_zero_leakage(dev_cases, blind_cases):
    """Enforces zero exact text leakage & zero entity overlap between Dev and Blind."""
    def norm_texts(cases):
        s = set()
        for c in cases:
            for k in ("text", "query"):
                if c.get(k):
                    s.add(re.sub(r"\s+", " ", c[k].lower().strip()))
            if c.get("alert_a"):
                s.add(re.sub(r"\s+", " ", c["alert_a"]["text"].lower().strip()))
            if c.get("alert_b"):
                s.add(re.sub(r"\s+", " ", c["alert_b"]["text"].lower().strip()))
        return s

    dev_m = {m[0].lower() for m in DEV_MERCHANTS} | {p[0].lower() for p in DEV_TRUNC_PAIRS} | {p[1].lower() for p in DEV_TRUNC_PAIRS} | {x.lower() for x in DEV_OOD_SMES}
    blind_m = {m[0].lower() for m in BLIND_MERCHANTS} | {p[0].lower() for p in BLIND_TRUNC_PAIRS} | {p[1].lower() for p in BLIND_TRUNC_PAIRS} | {x.lower() for x in BLIND_OOD_SMES}
    m_overlap = dev_m & blind_m
    assert not m_overlap, f"Merchant leakage Dev vs Blind: {m_overlap}"

    dt = norm_texts(dev_cases)
    bt = norm_texts(blind_cases)
    t_overlap = dt & bt
    # Allow only fixed quick-entry prompts if tiny? No! qe pools are strictly disjoint so overlap MUST be 0!
    assert not t_overlap, f"Exact text overlap Dev vs Blind ({len(t_overlap)}): {sorted(t_overlap)[:5]}"


def build_datasets(synth: dict, dev_counts: dict, blind_counts: dict):
    dev_cases = generate_split_cases("dev", synth, dev_counts, seed=20261125)
    blind_cases = generate_split_cases("blind", synth, blind_counts, seed=20269999)
    audit_zero_leakage(dev_cases, blind_cases)
    return dev_cases, blind_cases


def run_self_test():
    synth = _builtin_synth_fallback()
    dc = {"notif_gate": 140, "twin_dedup": 60, "lhdn_tax": 80, "parser_receipt": 70, "quick_search": 50}
    bc = {"notif_gate": 40, "twin_dedup": 20, "lhdn_tax": 20, "parser_receipt": 20, "quick_search": 20}
    dev, blind = build_datasets(synth, dc, bc)
    assert len(dev) == sum(dc.values()) and len(blind) == sum(bc.values())
    print(f"TRACK 5 SELF-TEST PASS: zero leakage verified (dev={len(dev)}, blind={len(blind)}, all 5 surfaces covered).")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        run_self_test()
        return
    try:
        synth = run_qwen_adversarial_synthesis()
        update_status("BUILDING_DISJOINT_SUITES")
        dev_counts = {"notif_gate": 7000, "twin_dedup": 3000, "lhdn_tax": 4000, "parser_receipt": 3500, "quick_search": 2500}
        blind_counts = {"notif_gate": 1600, "twin_dedup": 600, "lhdn_tax": 800, "parser_receipt": 800, "quick_search": 600}
        dev_cases, blind_cases = build_datasets(synth, dev_counts, blind_counts)

        OUT_DIR.mkdir(parents=True, exist_ok=True)
        DEV_PATH.write_text(json.dumps({"cases": dev_cases}, ensure_ascii=False), encoding="utf-8")
        BLIND_PATH.write_text(json.dumps({"cases": blind_cases}, ensure_ascii=False), encoding="utf-8")

        report = {
            "llm_teacher": synth.get("model_id", "unknown"),
            "llm_synth_counts": {k: len(v) for k, v in synth.items() if isinstance(v, list)},
            "dev_total": len(dev_cases),
            "blind_total": len(blind_cases),
            "dev_by_surface": dict(Counter(c["surface"] for c in dev_cases)),
            "blind_by_surface": dict(Counter(c["surface"] for c in blind_cases)),
            "zero_leakage_verified": True,
        }
        REPORT_PATH.write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")
        print("TRACK 5 ADVERSARIAL REPORT:", json.dumps(report, ensure_ascii=False), flush=True)
        update_status("DONE", dev_total=len(dev_cases), blind_total=len(blind_cases))
    except Exception as exc:
        import traceback
        tb = traceback.format_exc()
        print(tb, flush=True)
        update_status("ERROR", error=str(exc), traceback=tb)
        sys.exit(1)


if __name__ == "__main__":
    main()
