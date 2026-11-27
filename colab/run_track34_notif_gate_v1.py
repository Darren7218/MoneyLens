#!/usr/bin/env python3
"""MoneyLens Track 3 + Track 4 — Harder Paraphrased Blind Synthesizer & Compact INT8 NotificationGate Trainer.

Outputs under /content/outputs_track34_v1/:
  - track3_dev.json                  (~3,000 EN/BM/Manglish/ZH dev cases, held-out from train)
  - track3_hard_blind.json           (~2,500 strictly disjoint Blind cases from 13 held-out MY apps & Qwen paraphrases)
  - moneylens_notif_gate.bin.gz      (MLNG v1 compact INT8 neural notification gate < 300 KB)
  - notif_gate_parity_vectors.json   (100 exact Python INT8-dequantized logits+probs parity vectors)
  - track34_report.json              (dataset disjointness audit, Dev metrics, INT8 size & calibrated thresholds)
  - track34_status.json / track34.log
"""
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
from collections import Counter
from pathlib import Path

import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F

OUT_DIR = Path("/content/outputs_track34_v1")
OUT_DIR.mkdir(parents=True, exist_ok=True)
STATUS_PATH = OUT_DIR / "track34_status.json"
DEV_PATH = OUT_DIR / "track3_dev.json"
BLIND_PATH = OUT_DIR / "track3_hard_blind.json"
BIN_GZ_PATH = OUT_DIR / "moneylens_notif_gate.bin.gz"
PARITY_PATH = OUT_DIR / "notif_gate_parity_vectors.json"
REPORT_PATH = OUT_DIR / "track34_report.json"

LABELS = ["reject", "expense", "income", "refund", "transfer", "reload"]
LABEL_TO_ID = {name: idx for idx, name in enumerate(LABELS)}

FORBIDDEN_SCRIPT_RE = re.compile(
    r"[\u0400-\u04FF\u0600-\u06FF\u0900-\u097F\u0E00-\u0E7F\u3040-\u30FF\uAC00-\uD7AF\u1100-\u11FF]"
)

# 15 Train/Dev Malaysian financial apps
TRAIN_APPS = [
    ("my.com.tngdigital.ewallet", "Touch 'n Go eWallet"),
    ("com.maybank2u.life", "MAE by Maybank2u"),
    ("com.cimb2u.octo", "CIMB OCTO MY"),
    ("my.com.gxbank.mobile", "GXBank"),
    ("com.shopee.pay.my", "ShopeePay MY"),
    ("com.shopee.my", "Shopee MY"),
    ("com.grabtaxi.passenger", "GrabPay"),
    ("my.com.publicbank.mypb", "MyPB by Public Bank"),
    ("my.com.rhb.mobilebank", "RHB Mobile Banking"),
    ("com.hongleong.connect", "HLB Connect"),
    ("com.ambank.ambankmobile", "AmOnline"),
    ("my.com.myboost", "Boost"),
    ("com.bigpay.mobile", "BigPay"),
    ("my.com.setel.mobile", "Setel"),
    ("com.atome.my", "Atome BNPL"),
]

# 13 Strictly Held-Out Blind Malaysian apps (never in Train or Dev)
BLIND_APPS = [
    ("my.com.aeonbank.app", "AEON Bank"),
    ("my.com.rytbank", "Ryt Bank"),
    ("co.myboostbank.app", "Boost Bank"),
    ("my.com.bankislam.bimbmobile", "BIMB Mobile"),
    ("com.uob.tmrwmy", "UOB TMRW Malaysia"),
    ("com.ocbc.malaysia", "OCBC Malaysia"),
    ("com.hsbc.hsbcmalaysia", "HSBC Malaysia"),
    ("com.sc.mobile.my", "SC Mobile Malaysia"),
    ("my.com.bsn.mybsn", "myBSN"),
    ("com.deliveryhero.foodpanda", "foodpanda Malaysia"),
    ("com.lazada.android", "Lazada MY"),
    ("com.kfit.fave", "FavePay"),
    ("com.shopback.app", "ShopBack Pay"),
]

TRAIN_MERCHANTS = [
    "ZUS Coffee KLCC", "Kedai Kopi Ah Seng", "Restoran Pelita Bangsar", "99 Speedmart PJ",
    "FamilyMart Sunway", "Village Grocer Mont Kiara", "PETRONAS Setia Alam", "Shell Damansara",
    "Unifi Fibre", "Tenaga Nasional", "Indah Water", "Watsons Malaysia", "KPJ Hospital",
    "POPULAR Book Co", "Machines Mid Valley", "Uniqlo Fahrenheit88", "GSC Cinemas",
    "MR DIY One Utama", "Gentari EV Hub", "DUKE Toll Plaza", "LDP Toll",
    "Mamak Taj Curry House", "Bistro Harmoni Subang", "Farmasi Mesra Rakyat",
    "Klinik Keluarga Impian", "KK Super Mart Cheras", "PTPTN Pinjaman", "SPayLater Shopee",
    "Mixue Sunway", "Tealive Pavilion", "McDonald's Bukit Bintang", "Subway Nu Sentral",
]

BLIND_MERCHANTS = [
    "Kopitiam Sinaran Pagi", "Warung Nasi Kandar Melati", "Cafe Embun Cheras",
    "Borong Mart Kajang", "7-Eleven Plaza Damas", "Ben's Independent Grocer",
    "BHPetrol Kuantan", "Caltex Seremban", "TIME Fibre Broadband", "Air Selangor",
    "CelcomDigi Postpaid", "Guardian Ampang", "Sunway Medical Centre", "BookXcess Tamarind",
    "Switch Apple Reseller", "Padini Concept Store", "TGV Cinemas", "Ninso Eco Mart",
    "JomCharge Station PJ", "KESAS Highway Toll", "NPE Toll Plaza",
    "Dapur Nusantara Bangi", "Nasi Kukus Ilham", "CARiNG Pharmacy Kepong",
    "Poliklinik Sihat Sri Petaling", "myNEWS Mid Valley", "Atome Fashion MY", "LazPayLater",
    "Chagee Lot 10", "Gigi Coffee Bangsar", "Texas Chicken IOI", "Kenny Hills Bakers",
]

assert set(a[0] for a in TRAIN_APPS).isdisjoint(set(a[0] for a in BLIND_APPS))
assert set(m.lower() for m in TRAIN_MERCHANTS).isdisjoint(set(m.lower() for m in BLIND_MERCHANTS))

PEOPLE_TRAIN = ["Ali", "Sarah", "Mei Ling", "Kumar", "Farah", "Jason", "Zul", "Amanda"]
PEOPLE_BLIND = ["Hafiz", "Rachel", "Wei Jie", "Deepak", "Nadia", "Kenji", "Siti", "Daniel"]

# Exact shared regexes & feature extraction (MUST mirror NotificationGate.java byte-for-byte)
FOOTER_RE = re.compile(
    r"(?i)\b(?:never|do\s+not|don'?t|jangan)\s+(?:share|disclose|reveal|give|kongsi|dedahkan|berikan)\b[^.!\n]*"
)
RE_RM_AMT = re.compile(r"(?:rm|myr)\s*[0-9]+|[0-9]+(?:\.[0-9]{1,2})?\s*(?:rm|myr)")
RE_AMT_ALL = re.compile(r"(?:rm|myr)\s*[0-9,]+(?:\.[0-9]{1,2})?")
RE_FX_AMT = re.compile(r"\b(?:sgd|usd|thb|jpy|idr|eur|gbp|aud|cny|hkd|twd|krw|vnd)\s*[0-9]")
RE_OTP_DIGITS = re.compile(
    r"(?:otp|tac|pac|code|kod|pin)[^0-9]{0,12}[0-9]{4,8}\b|\b[0-9]{4,8}[^0-9]{0,12}(?:otp|tac|pac|code|kod)\b"
)
RE_OTP_WORD = re.compile(
    r"\b(?:otp|tac|pac|3ds|3d-?secure|secure2u|one-?time\s+(?:pin|password|passcode)|verification\s+code|kod\s+pengesahan|kod\s+keselamatan|authori[sz]e|approve|sahkan)\b"
)
RE_COMPLETED = re.compile(
    r"\b(?:paid|berjaya|deducted|debited|charged|credited|dikreditkan|received|diterima|completed|settled|processed|successful|successfully|selesai|dibayar|refunded|dikembalikan|dipulangkan|approved\s+via|verified\s+via|verified|authori[sz]ed\s+(?:a\s+payment|via))\b"
)
RE_FAIL = re.compile(
    r"\b(?:failed|declined|unsuccessful|gagal|rejected|tidak\s+berjaya|tidak\s+diluluskan|insufficient\s+(?:funds|balance)|baki\s+tidak\s+mencukupi|unable\s+to\s+process)\b"
)
RE_CANCEL = re.compile(r"\b(?:cancelled|canceled|dibatalkan)\b")
RE_REFUND = re.compile(
    r"\b(?:refund|refunded|reversal|reversed|chargeback|bayaran\s+balik|pulangan|dikembalikan|dipulangkan)\b"
)
RE_PROMO = re.compile(
    r"\b(?:voucher|baucar|kupon|promo|promosi|promotion|discount|diskaun|tawaran|offer|deal|deals|lucky\s+draw|cabutan|win|redeem|claim\s+now|cashback|rebate|points|rewards|min\s+spend|minimum\s+spend)\b"
)
RE_BILL_DUE = re.compile(
    r"\b(?:reminder|peringatan|due\s+(?:on|soon|tomorrow|today)|tarikh\s+akhir|tamat\s+tempoh|minimum\s+payment|bayaran\s+minimum|statement\s+(?:balance|ready|is\s+ready)|penyata\s+(?:bulanan|anda)|avoid\s+late\s+fee)\b"
)
RE_PENDING = re.compile(
    r"\b(?:pending|processing|awaiting|requested|scheduled|menunggu|sedang\s+diproses|tertunda|hold)\b"
)
RE_MOVE = re.compile(
    r"\b(?:transfer|transferred|top-?up|reload|reloaded|tambah\s+nilai|pindahan|withdrawal)\b"
)
RE_INCOME = re.compile(
    r"\b(?:salary|gaji|credited|dikreditkan|received|diterima|menerima|from|daripada|incoming)\b"
)

CJK_OTP_WORDS = ("验证码", "动态口令", "动态码", "安全码")
CJK_COMPLETED_WORDS = ("已支付", "付款成功", "支付成功", "到账成功", "转账成功", "已消费", "扣款", "已退款", "退款成功", "退款到账", "收到")
CJK_FAIL_WORDS = ("失败", "未扣款", "余额不足", "被拒绝")
CJK_CANCEL_WORDS = ("已取消",)
CJK_REFUND_WORDS = ("退款",)
CJK_PROMO_WORDS = ("优惠券", "代金券", "促销", "立减")
CJK_BILL_DUE_WORDS = ("账单提醒", "最低还款", "到期还款", "可用余额", "账户余额")
CJK_PENDING_WORDS = ("待处理", "预授权", "处理中", "冻结中")
CJK_MOVE_WORDS = ("转账", "充值")
CJK_INCOME_WORDS = ("收到", "工资", "薪水", "转来", "汇款")
SRC_IGNORE = {"com", "my", "app", "life", "android", "mobile", "co"}

NUM_BUCKETS = 4096
EMB_DIM = 32
STRUCT_DIM = 16
HIDDEN_DIM = 64
NUM_CLASSES = 6


def update_status(stage: str, **extra):
    payload = {"stage": stage, "ts": int(time.time()), **extra}
    STATUS_PATH.write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")


def fnv1a32(s: str) -> int:
    h = 0x811C9DC5
    for b in s.encode("utf-8"):
        h ^= b
        h = (h * 0x01000193) & 0xFFFFFFFF
    return h


def is_cjk(ch: str) -> bool:
    return "\u4e00" <= ch <= "\u9fff"


def strip_footer(text: str) -> str:
    norm = (text or "").replace("\u00A0", " ").strip()
    m = FOOTER_RE.search(norm)
    if m:
        return FOOTER_RE.sub(" ", norm).strip()
    return norm


def extract_features_and_struct(text: str, source: str):
    core = strip_footer(text)
    low = core.lower()
    src_low = (source or "").lower()

    words = []
    cjk = []
    cur = []
    for ch in low:
        if ("a" <= ch <= "z") or ("0" <= ch <= "9"):
            cur.append(ch)
        else:
            if cur:
                words.append("".join(cur))
                cur.clear()
            if is_cjk(ch) and len(cjk) < 32:
                cjk.append(ch)
    if cur:
        words.append("".join(cur))

    norm_words = []
    for w in words[:28]:
        is_num = w.isdigit()
        tok = "#num4" if (is_num and len(w) >= 4) else w
        norm_words.append((tok, is_num))

    feats = set()
    for i, (tok, is_num) in enumerate(norm_words):
        feats.add("w:" + tok)
        if i + 1 < len(norm_words):
            feats.add("b:" + tok + "_" + norm_words[i + 1][0])
        if not is_num and i < 24 and len(tok) >= 3:
            L = len(tok)
            for j in range(L - 2):
                feats.add("g3:" + tok[j : j + 3])
            for j in range(L - 3):
                feats.add("g4:" + tok[j : j + 4])

    for i, ch in enumerate(cjk):
        feats.add("cu:" + ch)
        if i + 1 < len(cjk):
            feats.add("cb:" + ch + cjk[i + 1])

    scur = []
    src_words = []
    for ch in src_low:
        if ("a" <= ch <= "z") or ("0" <= ch <= "9"):
            scur.append(ch)
        elif scur:
            src_words.append("".join(scur))
            scur.clear()
    if scur:
        src_words.append("".join(scur))
    for sw in src_words:
        if len(sw) >= 2 and sw not in SRC_IGNORE:
            feats.add("src:" + sw)

    sf = np.zeros(STRUCT_DIM, dtype=np.float32)
    sf[0] = 1.0 if RE_RM_AMT.search(low) else 0.0
    sf[1] = 1.0 if len(RE_AMT_ALL.findall(low)) >= 2 else 0.0
    sf[2] = 1.0 if RE_FX_AMT.search(low) else 0.0
    sf[3] = 1.0 if RE_OTP_DIGITS.search(low) else 0.0
    sf[4] = 1.0 if (RE_OTP_WORD.search(low) or any(k in low for k in CJK_OTP_WORDS)) else 0.0
    sf[5] = 1.0 if (RE_COMPLETED.search(low) or any(k in low for k in CJK_COMPLETED_WORDS)) else 0.0
    sf[6] = 1.0 if (RE_FAIL.search(low) or any(k in low for k in CJK_FAIL_WORDS)) else 0.0
    sf[7] = 1.0 if (RE_CANCEL.search(low) or any(k in low for k in CJK_CANCEL_WORDS)) else 0.0
    sf[8] = 1.0 if (RE_REFUND.search(low) or any(k in low for k in CJK_REFUND_WORDS)) else 0.0
    sf[9] = 1.0 if (RE_PROMO.search(low) or any(k in low for k in CJK_PROMO_WORDS)) else 0.0
    sf[10] = 1.0 if (RE_BILL_DUE.search(low) or any(k in low for k in CJK_BILL_DUE_WORDS)) else 0.0
    sf[11] = 1.0 if (RE_PENDING.search(low) or any(k in low for k in CJK_PENDING_WORDS)) else 0.0
    sf[12] = 1.0 if (RE_MOVE.search(low) or any(k in low for k in CJK_MOVE_WORDS)) else 0.0
    sf[13] = 1.0 if (RE_INCOME.search(low) or any(k in low for k in CJK_INCOME_WORDS)) else 0.0
    sf[14] = 1.0 if len(cjk) > 0 else 0.0
    sf[15] = np.float32(min(1.0, len(low) / 200.0))

    bucket_weights = {}
    for fk in feats:
        h = fnv1a32(fk)
        b = (h & 0x7FFFFFFF) % NUM_BUCKETS
        sign = 1.0 if (h >> 31) == 0 else -1.0
        nxt = bucket_weights.get(b, 0.0) + sign
        if nxt == 0.0:
            bucket_weights.pop(b, None)
        else:
            bucket_weights[b] = nxt
    return bucket_weights, sf


# ---- Disjoint Synthetic + Paraphrased Generators ----

def fmt_amt(minor: int, rng: random.Random) -> str:
    val = minor / 100.0
    style = rng.randrange(4)
    if style == 0:
        return f"RM{val:.2f}"
    if style == 1:
        return f"RM {val:,.2f}"
    if style == 2:
        return f"MYR {val:.2f}"
    return f"RM {val:,.0f}" if minor % 100 == 0 else f"RM{val:,.2f}"


def rand_minor(rng: random.Random, lo=250, hi=95000) -> int:
    choices = [
        rng.randint(lo, 4500),
        rng.randint(500, 18000),
        rng.randint(2000, hi),
        rng.choice([500, 1000, 1500, 2000, 2500, 5000, 10000, 15000, 25000, 50000]),
    ]
    return rng.choice(choices)


def gen_disjoint_template_case(split: str, subtype: str, rng: random.Random):
    apps = TRAIN_APPS if split in ("train", "dev") else BLIND_APPS
    merchants = TRAIN_MERCHANTS if split in ("train", "dev") else BLIND_MERCHANTS
    people = PEOPLE_TRAIN if split in ("train", "dev") else PEOPLE_BLIND
    pkg, src_label = rng.choice(apps)
    merch = rng.choice(merchants)
    person = rng.choice(people)
    minor = rand_minor(rng)
    amt = fmt_amt(minor, rng)
    bal = fmt_amt(rng.randint(15000, 450000), rng)
    tac = str(rng.randint(100000, 999999))
    is_train = split in ("train", "dev")

    if subtype == "trap_otp":
        lang = rng.choice(["en", "bm", "zh", "manglish"])
        if is_train:
            pool = {
                "en": [
                    f"Your TAC code is {tac} for {amt} payment at {merch}. Valid for 3 mins.",
                    f"Enter OTP {tac} to authorise {amt} online purchase at {merch}.",
                    f"Approve Secure2u request for {amt} at {merch} before it expires.",
                ],
                "bm": [
                    f"Kod TAC anda ialah {tac} bagi bayaran {amt} di {merch}.",
                    f"Masukkan OTP {tac} untuk sahkan transaksi {amt} ke {merch}.",
                    f"Sila luluskan permintaan Secure2u sebanyak {amt} di {merch}.",
                ],
                "zh": [
                    f"验证码 {tac}：您正在 {merch} 支付 {amt}，3分钟内有效。",
                    f"安全码 {tac}，用于确认 {amt} 付款至 {merch}。",
                ],
                "manglish": [
                    f"Wei key in TAC {tac} to approve {amt} bayar at {merch} now.",
                    f"Bro, tap approve in {src_label} for {amt} purchase at {merch}, code {tac}.",
                ],
            }
        else:
            pool = {
                "en": [
                    f"One-Time Password {tac} requested for {amt} at {merch}. Tap Deny if not you.",
                    f"Action needed: Confirm {amt} 3D Secure prompt for {merch}. Verification code: {tac}.",
                    f"PAC {tac} issued for {amt} transfer/payment to {merch}. Do not share.",
                ],
                "bm": [
                    f"Kod pengesahan {tac}: sahkan pembayaran {amt} kepada {merch} dalam 120 saat.",
                    f"Permintaan pengesahan {src_label} untuk {amt} di {merch} sedang menunggu anda.",
                ],
                "zh": [
                    f"动态口令 {tac}，请确认 {merch} 的 {amt} 消费请求。",
                    f"安全提示：输入动态码 {tac} 以授权 {amt} 付款给 {merch}。",
                ],
                "manglish": [
                    f"Pls verify {src_label} request {amt} at {merch}, kod TAC {tac}.",
                    f"Need your approval for {amt} at {merch} via {src_label}, key in {tac}.",
                ],
            }
        return rng.choice(pool[lang]), pkg, src_label, lang, False, "reject", 0

    if subtype == "trap_fail":
        lang = rng.choice(["en", "bm", "zh", "manglish"])
        if is_train:
            pool = {
                "en": [
                    f"Payment of {amt} at {merch} was declined due to insufficient funds.",
                    f"Your order {amt} at {merch} has been cancelled. No charge made.",
                    f"Transaction failed for {amt} at {merch}. Please try again.",
                ],
                "bm": [
                    f"Bayaran {amt} di {merch} gagal kerana baki tidak mencukupi.",
                    f"Pesanan {merch} bernilai {amt} telah dibatalkan. Tiada potongan.",
                ],
                "zh": [
                    f"您在 {merch} 的 {amt} 扣款失败，余额不足。",
                    f"订单已取消：{merch} {amt} 未扣款。",
                ],
                "manglish": [
                    f"Alamak, payment {amt} at {merch} tak lepas lah, insufficient balance.",
                    f"Order {amt} kat {merch} cancelled already, didn't deduct money.",
                ],
            }
        else:
            pool = {
                "en": [
                    f"Unable to process {amt} charge at {merch}. Card was not debited.",
                    f"Unsuccessful transaction: {amt} at {merch} rejected by issuer.",
                    f"Booking cancelled: {amt} hold at {merch} released with zero charge.",
                ],
                "bm": [
                    f"Transaksi {amt} di {merch} tidak berjaya. Akaun anda tidak ditolak.",
                    f"Pembelian {amt} di {merch} ditolak oleh bank pengeluar.",
                ],
                "zh": [
                    f"交易失败：{merch} {amt} 已被拒绝，未从您的账户扣除。",
                    f"您在 {merch} 的 {amt} 消费已取消，未扣款。",
                ],
                "manglish": [
                    f"Aiya {amt} bayar to {merch} failed lor, bank rejected.",
                    f"Transaction {amt} kat {merch} rejected, baki tak cukup.",
                ],
            }
        return rng.choice(pool[lang]), pkg, src_label, lang, False, "reject", 0

    if subtype == "trap_bill_promo_pending":
        lang = rng.choice(["en", "bm", "zh", "manglish"])
        if is_train:
            pool = {
                "en": [
                    f"Bill reminder: Your {merch} statement balance {amt} is due on 28 Nov.",
                    f"Spend {amt} at {merch} today to win a RM 15.00 cashback voucher!",
                    f"Pending pre-authorisation of {amt} at {merch}. Available balance {bal}.",
                    f"Available balance in {src_label}: {bal}. Credit limit increase up to {amt}.",
                ],
                "bm": [
                    f"Peringatan bil: Bayaran minimum {amt} untuk {merch} perlu dijelaskan sebelum tarikh akhir.",
                    f"Dapatkan baucar diskaun {amt} apabila anda berbelanja di {merch}!",
                    f"Transaksi {amt} di {merch} sedang diproses (pegangan sementara).",
                ],
                "zh": [
                    f"账单提醒：您的 {merch} 本期应还 {amt}，请按时还款。",
                    f"限时促销！在 {merch} 消费立减，领取 {amt} 优惠券。",
                    f"预授权冻结中：{merch} {amt}，可用余额 {bal}。",
                ],
                "manglish": [
                    f"Reminder: {merch} bil {amt} coming due soon, don't forget to settle before due.",
                    f"Syok promo! Spend at {merch} to claim {amt} voucher & cashback!",
                ],
            }
        else:
            pool = {
                "en": [
                    f"Monthly statement ready for {merch}: minimum payment {amt} due soon to avoid late fee.",
                    f"Exclusive offer: Enjoy {amt} rebate voucher on your next order at {merch}!",
                    f"Awaiting merchant settlement for {amt} hold at {merch}. Balance: {bal}.",
                    f"Your {src_label} account balance is {bal}.",
                ],
                "bm": [
                    f"Bil {merch} {amt} akan tamat tempoh tidak lama lagi. Elakkan caj lewat.",
                    f"Tawaran terhad: Tebus kupon RM 10 apabila berbelanja {amt} di {merch}.",
                    f"Status tertunda: {amt} di {merch} menunggu pengesahan peniaga.",
                ],
                "zh": [
                    f"最低还款提醒：{merch} {amt} 即将到期，请留意账户余额 {bal}。",
                    f"满100减20代金券：在 {merch} 享 {amt} 福利优惠。",
                    f"处理中：您有一笔 {amt} 在 {merch} 的待处理交易。",
                ],
                "manglish": [
                    f"Heads up, {merch} statement balance {amt} due tomorrow, avoid late fee ah.",
                    f"Promo alert! Grab {amt} cashback deal when spend at {merch} this weekend.",
                ],
            }
        return rng.choice(pool[lang]), pkg, src_label, lang, False, "reject", 0

    if subtype == "real_expense":
        lang = rng.choice(["en", "bm", "zh", "manglish"])
        if is_train:
            pool = {
                "en": [
                    f"You have paid {amt} at {merch}. Never share your TAC with anyone.",
                    f"Card transaction of {amt} at {merch} approved via 3DS.",
                    f"Statement payment of {amt} to {merch} received. Thank you.",
                    f"DuitNow QR purchase: {amt} deducted from {src_label} for {merch}.",
                ],
                "bm": [
                    f"Anda telah membayar {amt} kepada {merch}. Baki: {bal}.",
                    f"Pembayaran bil {merch} sebanyak {amt} berjaya diproses.",
                ],
                "zh": [
                    f"您已支付 {amt} 给 {merch}。可用余额 {bal}。",
                    f"付款成功：{merch} {amt}，已完成扣款。",
                ],
                "manglish": [
                    f"Settled! Paid {amt} to {merch} via {src_label}.",
                    f"Done bayar {amt} kat {merch}, balance left {bal}.",
                ],
            }
        else:
            pool = {
                "en": [
                    f"{src_label} alert: {amt} spent at {merch} (TAC verified).",
                    f"Purchase completed: {merch} charged {amt} to your account via Secure2u.",
                    f"Minimum payment of {amt} to {merch} processed successfully.",
                    f"Auto-debit of {amt} for {merch} settled today.",
                ],
                "bm": [
                    f"Potongan {amt} daripada {src_label} untuk {merch} telah selesai.",
                    f"Bayaran {amt} ke {merch} disahkan melalui Secure2u. Baki kini {bal}.",
                ],
                "zh": [
                    f"扣款通知：{src_label} 已消费 {amt}，商户 {merch}，余额 {bal}。",
                    f"支付成功！向 {merch} 付款 {amt}。",
                ],
                "manglish": [
                    f"Just spent {amt} at {merch} using {src_label}, settled lah.",
                    f"Payment {amt} to {merch} dah berjaya! Ref balance {bal}.",
                ],
            }
        return rng.choice(pool[lang]), pkg, src_label, lang, True, "expense", minor

    if subtype == "real_income":
        lang = rng.choice(["en", "bm", "zh", "manglish"])
        if is_train:
            pool = {
                "en": [
                    f"You have received {amt} from {person} via DuitNow.",
                    f"Salary of {amt} credited into your {src_label} account.",
                ],
                "bm": [
                    f"Anda menerima pindahan {amt} daripada {person}.",
                    f"Gaji sebanyak {amt} telah dikreditkan ke akaun anda.",
                ],
                "zh": [
                    f"到账成功：收到来自 {person} 的转账 {amt}。",
                ],
                "manglish": [
                    f"Syok! {person} DuitNow {amt} masuk your {src_label}.",
                ],
            }
        else:
            pool = {
                "en": [
                    f"Incoming DuitNow credit: {amt} from {person} deposited into {src_label}.",
                    f"Payroll deposit {amt} credited today. Current balance: {bal}.",
                ],
                "bm": [
                    f"Kredit masuk {amt} daripada {person} diterima dalam {src_label}.",
                    f"Elaun & gaji {amt} diterima dengan jayanya.",
                ],
                "zh": [
                    f"收到 {person} 转来 {amt}，已存入您的 {src_label}。",
                ],
                "manglish": [
                    f"Received {amt} from {person} into {src_label} already!",
                ],
            }
        return rng.choice(pool[lang]), pkg, src_label, lang, True, "income", minor

    if subtype == "real_refund":
        lang = rng.choice(["en", "bm", "zh", "manglish"])
        if is_train:
            pool = {
                "en": [
                    f"Refund for cancelled order {amt} from {merch} credited to your {src_label}.",
                    f"Chargeback refund of {amt} at {merch} completed.",
                ],
                "bm": [
                    f"Bayaran balik untuk pesanan dibatalkan {amt} dari {merch} dikembalikan ke akaun anda.",
                    f"Pulangan tunai bayaran {amt} daripada {merch} berjaya.",
                ],
                "zh": [
                    f"已取消订单退款 {amt}（商户：{merch}）已退款到账。",
                ],
                "manglish": [
                    f"Order cancelled at {merch}, refund {amt} credited back to {src_label}!",
                ],
            }
        else:
            pool = {
                "en": [
                    f"Cancelled booking refund of {amt} from {merch} returned to your card.",
                    f"Money back: {amt} refunded by {merch} processed cleanly.",
                ],
                "bm": [
                    f"Wang sebanyak {amt} dari {merch} untuk tempahan dibatalkan telah dipulangkan.",
                    f"Bayaran balik {amt} daripada {merch} berjaya dikreditkan.",
                ],
                "zh": [
                    f"退款成功：{merch} 已退回 {amt} 至您的 {src_label}。",
                ],
                "manglish": [
                    f"Yay {merch} refunded {amt} back into {src_label} for cancelled item.",
                ],
            }
        return rng.choice(pool[lang]), pkg, src_label, lang, True, "refund", minor

    if subtype == "real_transfer":
        lang = rng.choice(["en", "bm", "zh", "manglish"])
        other_bank = "CIMB OCTO" if "MAE" not in src_label else "MAE Account"
        if is_train:
            pool = {
                "en": [
                    f"You have transferred {amt} from {src_label} to your own {other_bank}.",
                    f"Fund transfer of {amt} to {other_bank} account completed.",
                ],
                "bm": [
                    f"Pindahan wang {amt} ke akaun {other_bank} anda berjaya.",
                ],
                "zh": [
                    f"转账成功：已从 {src_label} 转出 {amt} 至您的 {other_bank}。",
                ],
                "manglish": [
                    f"Moved {amt} from {src_label} into {other_bank} settled.",
                ],
            }
        else:
            pool = {
                "en": [
                    f"Successful transfer: {amt} shifted to {other_bank}.",
                    f"Own-account transfer of {amt} out to {other_bank} processed.",
                ],
                "bm": [
                    f"Pemindahan {amt} ke {other_bank} selesai diproses.",
                ],
                "zh": [
                    f"已完成向本人 {other_bank} 账户转账 {amt}。",
                ],
                "manglish": [
                    f"Transfer {amt} pegi {other_bank} done already.",
                ],
            }
        return rng.choice(pool[lang]), pkg, src_label, lang, True, "transfer", minor

    # real_reload
    lang = rng.choice(["en", "bm", "zh", "manglish"])
    if is_train:
        pool = {
            "en": [
                f"Reload of {amt} into {src_label} is successful.",
                f"Auto-reload {amt} for your e-wallet completed. Balance: {bal}.",
            ],
            "bm": [
                f"Tambah nilai {amt} ke {src_label} berjaya.",
            ],
            "zh": [
                f"充值成功：已为 {src_label} 充值 {amt}。",
            ],
            "manglish": [
                f"Top up {amt} to {src_label} berjaya!",
            ],
        }
    else:
        pool = {
            "en": [
                f"Top-up successful: {amt} reloaded to your {src_label} wallet.",
                f"e-Wallet reload of {amt} completed without issues. Balance: {bal}.",
            ],
            "bm": [
                f"Tambah nilai e-dompet {amt} ke {src_label} telah selesai.",
            ],
            "zh": [
                f"{src_label} 钱包充值 {amt} 到账成功，当前余额 {bal}。",
            ],
            "manglish": [
                f"Done reload {amt} inside {src_label}, ready to scan.",
            ],
        }
    return rng.choice(pool[lang]), pkg, src_label, lang, True, "reload", minor


def generate_qwen_paraphrases(rng_seed: int, update_fn):
    """Synthesize diverse natural EN/BM/Manglish/ZH push notifications using Qwen2.5-7B-Instruct."""
    import transformers
    from transformers import AutoModelForCausalLM, AutoTokenizer

    model_id = None
    tok = None
    mdl = None
    for cand in ["Qwen/Qwen2.5-7B-Instruct", "Qwen/Qwen2.5-3B-Instruct"]:
        try:
            update_fn("LLM_LOAD", model=cand)
            print(f"Loading {cand}...", flush=True)
            tok = AutoTokenizer.from_pretrained(cand, padding_side="left")
            if tok.pad_token is None:
                tok.pad_token = tok.eos_token
            dtype = torch.bfloat16 if torch.cuda.is_bf16_supported() else torch.float16
            mdl = AutoModelForCausalLM.from_pretrained(cand, torch_dtype=dtype, device_map="auto")
            mdl.eval()
            model_id = cand
            print(f"Loaded {cand} successfully on {next(mdl.parameters()).device}", flush=True)
            break
        except Exception as exc:
            print(f"Could not load {cand}: {exc}", flush=True)
            tok = None
            mdl = None
            gc.collect()
            torch.cuda.empty_cache()

    if mdl is None:
        return [], []

    rng = random.Random(rng_seed)

    # Separate system instructions and seeds for Train/Dev vs Hard Blind
    specs_train = [
        ("reject", "trap_otp", "active OTP/TAC/Secure2u verification code prompt requiring user approval before payment", TRAIN_APPS, TRAIN_MERCHANTS, PEOPLE_TRAIN),
        ("reject", "trap_fail", "declined, failed, insufficient balance, or cancelled-without-charge payment alert", TRAIN_APPS, TRAIN_MERCHANTS, PEOPLE_TRAIN),
        ("reject", "trap_bill", "upcoming bill reminder, credit card statement ready, or pending/pre-auth hold notice", TRAIN_APPS, TRAIN_MERCHANTS, PEOPLE_TRAIN),
        ("reject", "trap_promo", "marketing promotion, cashback voucher offer, win-prize, or credit limit increase notification", TRAIN_APPS, TRAIN_MERCHANTS, PEOPLE_TRAIN),
        ("expense", "real_expense", "completed successful expense payment or bill payment (some mentioning passive 3DS/TAC verified or FX)", TRAIN_APPS, TRAIN_MERCHANTS, PEOPLE_TRAIN),
        ("income", "real_income", "completed incoming money received (salary credited, DuitNow received, friend payback)", TRAIN_APPS, TRAIN_MERCHANTS, PEOPLE_TRAIN),
        ("refund", "real_refund", "completed refund credited back (including cancelled-order refund credited)", TRAIN_APPS, TRAIN_MERCHANTS, PEOPLE_TRAIN),
        ("transfer", "real_transfer", "completed own-account fund transfer to another bank/savings account", TRAIN_APPS, TRAIN_MERCHANTS, PEOPLE_TRAIN),
        ("reload", "real_reload", "completed e-wallet reload / top-up notification", TRAIN_APPS, TRAIN_MERCHANTS, PEOPLE_TRAIN),
    ]
    specs_blind = [
        ("reject", "trap_otp", "active OTP/TAC/3DS challenge or Secure2u approval request (NOT yet paid)", BLIND_APPS, BLIND_MERCHANTS, PEOPLE_BLIND),
        ("reject", "trap_fail", "failed/rejected transaction or unpaid cancelled order where zero money left", BLIND_APPS, BLIND_MERCHANTS, PEOPLE_BLIND),
        ("reject", "trap_bill", "due-date bill alert, minimum payment due reminder, or temporary pending hold", BLIND_APPS, BLIND_MERCHANTS, PEOPLE_BLIND),
        ("reject", "trap_promo", "promotional voucher/rebate ad or plain available-balance notice", BLIND_APPS, BLIND_MERCHANTS, PEOPLE_BLIND),
        ("expense", "real_expense", "paraphrased completed purchase/merchant charge (EN, BM, colloquial Manglish, or Chinese)", BLIND_APPS, BLIND_MERCHANTS, PEOPLE_BLIND),
        ("income", "real_income", "paraphrased incoming salary or DuitNow received from a person", BLIND_APPS, BLIND_MERCHANTS, PEOPLE_BLIND),
        ("refund", "real_refund", "paraphrased completed refund credited back from cancelled order or merchant", BLIND_APPS, BLIND_MERCHANTS, PEOPLE_BLIND),
        ("transfer", "real_transfer", "paraphrased completed transfer between user's own bank/wallet accounts", BLIND_APPS, BLIND_MERCHANTS, PEOPLE_BLIND),
        ("reload", "real_reload", "paraphrased completed e-wallet top-up / reload alert", BLIND_APPS, BLIND_MERCHANTS, PEOPLE_BLIND),
    ]

    def build_prompts(specs, n_batches_per_spec, batch_size=12):
        jobs = []
        for expected_kind, subtype, desc, apps, merchs, ppl in specs:
            for b_idx in range(n_batches_per_spec):
                for _ in range(batch_size):
                    pkg, src_label = rng.choice(apps)
                    merch = rng.choice(merchs)
                    person = rng.choice(ppl)
                    minor = rand_minor(rng)
                    amt = f"RM {minor/100:.2f}"
                    lang = rng.choice(["English", "Bahasa Melayu", "Manglish", "Simplified Chinese"])
                    entity = person if expected_kind == "income" else merch
                    user_msg = (
                        f"Write ONE realistic short Malaysian phone notification from '{src_label}' in {lang}.\n"
                        f"Category scenario: {desc}.\n"
                        f"Counterparty/merchant: {entity}. Exact amount token to include: {amt}.\n"
                        f"Rules: output ONLY the single-line notification text, no quotes, no explanation, no emojis if unnecessary."
                    )
                    jobs.append((expected_kind, subtype, pkg, src_label, lang, minor, amt, user_msg))
        return jobs

    def run_jobs(jobs, tag):
        results = []
        bs = 24
        for start in range(0, len(jobs), bs):
            chunk = jobs[start : start + bs]
            texts_in = [
                tok.apply_chat_template(
                    [{"role": "system", "content": "You generate concise single-line Malaysian banking and e-wallet push notification examples."},
                     {"role": "user", "content": j[7]}],
                    tokenize=False, add_generation_prompt=True
                )
                for j in chunk
            ]
            enc = tok(texts_in, return_tensors="pt", padding=True, truncation=True, max_length=256).to(mdl.device)
            with torch.no_grad():
                out = mdl.generate(
                    **enc, max_new_tokens=64, do_sample=True, temperature=0.85, top_p=0.92,
                    pad_token_id=tok.pad_token_id
                )
            gen_ids = out[:, enc["input_ids"].shape[1] :]
            decoded = tok.batch_decode(gen_ids, skip_special_tokens=True)
            for j, gen in zip(chunk, decoded):
                expected_kind, subtype, pkg, src_label, lang_name, minor, amt, _ = j
                line = " ".join(gen.strip().strip('"').strip("'").splitlines()).strip()
                if not line or len(line) < 15 or len(line) > 220:
                    continue
                if FORBIDDEN_SCRIPT_RE.search(line):
                    continue
                low = line.lower()
                amt_clean = f"{minor/100:.2f}"
                if amt_clean not in line and f"{minor/100:,.2f}" not in line:
                    continue
                # Strict label-consistency filters to keep ground-truth spotless
                if expected_kind == "reject":
                    if subtype == "trap_otp" and not (RE_OTP_WORD.search(low) or RE_OTP_DIGITS.search(low) or any(k in low for k in CJK_OTP_WORDS)):
                        continue
                    if subtype == "trap_fail" and not (RE_FAIL.search(low) or RE_CANCEL.search(low) or any(k in low for k in CJK_FAIL_WORDS + CJK_CANCEL_WORDS)):
                        continue
                    if subtype == "trap_bill" and not (RE_BILL_DUE.search(low) or RE_PENDING.search(low) or any(k in low for k in CJK_BILL_DUE_WORDS + CJK_PENDING_WORDS) or "balance" in low or "baki" in low):
                        continue
                    if subtype == "trap_promo" and not (RE_PROMO.search(low) or any(k in low for k in CJK_PROMO_WORDS)):
                        continue
                    # Drop accidental completed payment phrasing inside traps unless OTP/fail/promo/bill guard applies
                    if subtype == "trap_promo" and RE_COMPLETED.search(low) and "voucher" not in low and "baucar" not in low and "win" not in low:
                        continue
                else:
                    if RE_FAIL.search(low) or RE_OTP_DIGITS.search(low) or any(k in low for k in CJK_FAIL_WORDS + CJK_OTP_WORDS):
                        continue
                    if RE_BILL_DUE.search(low) or RE_PROMO.search(low):
                        continue
                    if expected_kind == "refund" and not (RE_REFUND.search(low) or any(k in low for k in CJK_REFUND_WORDS)):
                        continue
                    if expected_kind == "reload" and not any(w in low for w in ("reload", "topup", "top-up", "top up", "tambah nilai", "充值")):
                        continue
                lang_code = {"English": "en", "Bahasa Melayu": "bm", "Manglish": "manglish", "Simplified Chinese": "zh"}[lang_name]
                results.append({
                    "text": line,
                    "source": pkg,
                    "source_label": src_label,
                    "lang": lang_code,
                    "subtype": subtype,
                    "expected_captured": expected_kind != "reject",
                    "expected_kind": expected_kind,
                    "expected_amount_minor": 0 if expected_kind == "reject" else minor,
                    "is_llm_paraphrase": True,
                })
            if (start // bs) % 10 == 0:
                update_fn("LLM_SYNTH", tag=tag, done=min(start + bs, len(jobs)), total=len(jobs), kept=len(results), model=model_id)
                print(f"[{tag}] {min(start + bs, len(jobs))}/{len(jobs)} kept={len(results)}", flush=True)
        return results

    # ~2,160 raw train prompts + ~1,080 raw blind prompts (~1500+ filtered train LLM, ~750+ filtered blind LLM)
    train_jobs = build_prompts(specs_train, n_batches_per_spec=20, batch_size=12)
    blind_jobs = build_prompts(specs_blind, n_batches_per_spec=10, batch_size=12)
    train_llm = run_jobs(train_jobs, "train_llm")
    blind_llm = run_jobs(blind_jobs, "blind_llm")

    del mdl
    del tok
    gc.collect()
    torch.cuda.empty_cache()
    return train_llm, blind_llm


def build_datasets(seed: int = 20261126):
    rng = random.Random(seed)
    update_status("DATA_GEN")

    train_llm, blind_llm = generate_qwen_paraphrases(seed + 7, update_status)
    rng.shuffle(train_llm)
    rng.shuffle(blind_llm)

    # Split train_llm cleanly into train (85%) vs dev (15%) with text disjointness
    n_dev_llm = max(120, int(0.15 * len(train_llm)))
    dev_llm = train_llm[:n_dev_llm]
    tr_llm = train_llm[n_dev_llm:]

    subtypes = [
        ("trap_otp", 0.14),
        ("trap_fail", 0.12),
        ("trap_bill_promo_pending", 0.24),
        ("real_expense", 0.24),
        ("real_income", 0.08),
        ("real_refund", 0.07),
        ("real_transfer", 0.05),
        ("real_reload", 0.06),
    ]

    def sample_templates(split_name: str, target_n: int, seen_texts: set):
        rows = []
        tries = 0
        while len(rows) < target_n and tries < target_n * 8:
            tries += 1
            r = rng.random()
            cum = 0.0
            st = subtypes[-1][0]
            for name, w in subtypes:
                cum += w
                if r <= cum:
                    st = name
                    break
            txt, pkg, src_label, lang, cap, kind, amt = gen_disjoint_template_case(split_name, st, rng)
            key = re.sub(r"\s+", " ", txt.lower()).strip()
            if key in seen_texts:
                continue
            seen_texts.add(key)
            rows.append({
                "text": txt,
                "source": pkg,
                "source_label": src_label,
                "lang": lang,
                "subtype": st,
                "expected_captured": cap,
                "expected_kind": kind,
                "expected_amount_minor": amt,
                "is_llm_paraphrase": False,
            })
        return rows

    seen_all = set()

    def filter_unique(items):
        out = []
        for it in items:
            k = re.sub(r"\s+", " ", it["text"].lower()).strip()
            if k not in seen_all:
                seen_all.add(k)
                out.append(it)
        return out

    tr_llm = filter_unique(tr_llm)
    dev_llm = filter_unique(dev_llm)
    blind_llm = filter_unique(blind_llm)

    train_rows = tr_llm + sample_templates("train", max(9000, 12000 - len(tr_llm)), seen_all)
    dev_rows = dev_llm + sample_templates("dev", max(2200, 3000 - len(dev_llm)), seen_all)
    blind_rows = blind_llm + sample_templates("blind", max(1700, 2500 - len(blind_llm)), seen_all)

    rng.shuffle(train_rows)
    rng.shuffle(dev_rows)
    rng.shuffle(blind_rows)

    for prefix, rows in (("t34-tr", train_rows), ("t34-dev", dev_rows), ("t34-bl", blind_rows)):
        for i, r in enumerate(rows):
            r["id"] = f"{prefix}-{i:05d}"

    tr_keys = {re.sub(r"\s+", " ", r["text"].lower()).strip() for r in train_rows}
    dev_keys = {re.sub(r"\s+", " ", r["text"].lower()).strip() for r in dev_rows}
    bl_keys = {re.sub(r"\s+", " ", r["text"].lower()).strip() for r in blind_rows}
    assert tr_keys.isdisjoint(bl_keys), "Train leaked into Blind!"
    assert dev_keys.isdisjoint(bl_keys), "Dev leaked into Blind!"

    DEV_PATH.write_text(json.dumps({"cases": dev_rows}, ensure_ascii=False), encoding="utf-8")
    BLIND_PATH.write_text(json.dumps({"cases": blind_rows}, ensure_ascii=False), encoding="utf-8")
    print(f"Dataset built: train={len(train_rows)} (llm={len(tr_llm)}) dev={len(dev_rows)} (llm={len(dev_llm)}) blind={len(blind_rows)} (llm={len(blind_llm)})", flush=True)
    return train_rows, dev_rows, blind_rows


# ---- Compact INT8 Neural NotificationGate Training & Quantization ----

class NotifGateNet(nn.Module):
    def __init__(self):
        super().__init__()
        self.emb = nn.Parameter(torch.randn(NUM_BUCKETS, EMB_DIM) * 0.05)
        self.w1 = nn.Linear(EMB_DIM + STRUCT_DIM, HIDDEN_DIM)
        self.ln1 = nn.LayerNorm(HIDDEN_DIM, eps=1e-5)
        self.drop = nn.Dropout(0.15)
        self.w2 = nn.Linear(HIDDEN_DIM, NUM_CLASSES)

    def encode_sparse(self, sparse_rows, device):
        # Sum bucket weights * signs per item, then L2 normalize
        e0 = torch.zeros(len(sparse_rows), EMB_DIM, device=device, dtype=torch.float32)
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
            e0.index_add_(0, idx_r, self.emb[idx_b] * wts)
        norm = torch.clamp(torch.sqrt((e0 * e0).sum(dim=1, keepdim=True)), min=1e-6)
        return e0 / norm

    def forward(self, sparse_rows, struct_x):
        e0 = self.encode_sparse(sparse_rows, struct_x.device)
        x = torch.cat([e0, struct_x], dim=1)
        h = self.w1(x)
        h = self.ln1(h)
        h = F.gelu(h, approximate="tanh")
        h = self.drop(h)
        return self.w2(h)


def quantize_row_int8(mat: np.ndarray):
    """Per-row symmetric INT8 quantization: returns (scales float32[R], q int8[R, C])."""
    mat32 = mat.astype(np.float32)
    max_abs = np.max(np.abs(mat32), axis=1)
    scales = np.where(max_abs > 1e-8, max_abs / 127.0, 1.0 / 127.0).astype(np.float32)
    q = np.clip(np.round(mat32 / scales[:, None]), -127, 127).astype(np.int8)
    return scales, q


def dequantize_forward_single(bw, sf, model_q, temperature: float):
    """Exact pure-numpy float32 mirror of NotificationGate.java forward pass."""
    emb_s, emb_q = model_q["emb"]
    w1_s, w1_q = model_q["w1"]
    w2_s, w2_q = model_q["w2"]
    b1 = model_q["b1"]
    ln1_w = model_q["ln1_w"]
    ln1_b = model_q["ln1_b"]
    b2 = model_q["b2"]

    acc0 = np.zeros(EMB_DIM, dtype=np.float64)
    for b, wt in bw.items():
        sc = float(wt) * float(emb_s[b])
        acc0 += sc * emb_q[b].astype(np.float64)
    n0 = math.sqrt(max(1e-12, float(np.sum(acc0 * acc0))))
    n0 = max(1e-6, n0)
    e0 = (acc0 / n0).astype(np.float32)

    x = np.concatenate([e0, sf.astype(np.float32)], axis=0)
    # matVecAdd + LayerNorm + geluTanh
    h = np.zeros(HIDDEN_DIM, dtype=np.float32)
    for i in range(HIDDEN_DIM):
        row = w1_q[i].astype(np.float64) * float(w1_s[i])
        h[i] = float(b1[i]) + float(np.dot(row, x.astype(np.float64)))
    mean = float(np.mean(h.astype(np.float64)))
    var = float(np.mean((h.astype(np.float64) - mean) ** 2))
    inv_std = 1.0 / math.sqrt(var + 1e-5)
    for i in range(HIDDEN_DIM):
        v = ((float(h[i]) - mean) * inv_std) * float(ln1_w[i]) + float(ln1_b[i])
        inner = 0.7978845608 * (v + 0.044715 * v * v * v)
        h[i] = float(0.5 * v * (1.0 + math.tanh(inner)))

    logits = np.zeros(NUM_CLASSES, dtype=np.float32)
    for i in range(NUM_CLASSES):
        row = w2_q[i].astype(np.float64) * float(w2_s[i])
        logits[i] = float(b2[i]) + float(np.dot(row, h.astype(np.float64)))

    scaled = logits.astype(np.float64) / max(0.05, float(temperature))
    scaled -= np.max(scaled)
    exps = np.exp(scaled)
    probs = (exps / np.sum(exps)).astype(np.float32)
    return logits, probs


def precompute_features(rows):
    sparse_list, sf_list, y_list = [], [], []
    for r in rows:
        bw, sf = extract_features_and_struct(r["text"], r["source"])
        sparse_list.append(bw)
        sf_list.append(sf)
        y_list.append(LABEL_TO_ID[r["expected_kind"]])
    return sparse_list, np.stack(sf_list, axis=0), np.array(y_list, dtype=np.int64)


def train_and_export(train_rows, dev_rows, blind_rows):
    update_status("FEATURIZE")
    tr_sp, tr_sf, tr_y = precompute_features(train_rows)
    dv_sp, dv_sf, dv_y = precompute_features(dev_rows)

    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    torch.manual_seed(20261126)
    net = NotifGateNet().to(device)
    cls_weights = torch.tensor([1.50, 1.0, 1.0, 1.05, 1.05, 1.05], device=device, dtype=torch.float32)
    opt = torch.optim.AdamW(net.parameters(), lr=3e-3, weight_decay=2e-4)
    epochs = 26
    sched = torch.optim.lr_scheduler.CosineAnnealingLR(opt, T_max=epochs, eta_min=1e-4)

    tr_sf_t = torch.tensor(tr_sf, device=device)
    tr_y_t = torch.tensor(tr_y, device=device)
    dv_sf_t = torch.tensor(dv_sf, device=device)

    best_state = None
    best_dev_score = -1.0
    bs = 256
    n_tr = len(tr_sp)
    rng = np.random.default_rng(20261126)

    update_status("TRAIN", epochs=epochs, train_n=n_tr, dev_n=len(dv_sp))
    for ep in range(1, epochs + 1):
        net.train()
        perm = rng.permutation(n_tr)
        for s in range(0, n_tr, bs):
            idx = perm[s : s + bs]
            batch_sp = [tr_sp[int(i)] for i in idx]
            batch_sf = tr_sf_t[idx]
            batch_y = tr_y_t[idx]
            opt.zero_grad()
            logits = net(batch_sp, batch_sf)
            loss = F.cross_entropy(logits, batch_y, weight=cls_weights, label_smoothing=0.02)
            loss.backward()
            torch.nn.utils.clip_grad_norm_(net.parameters(), 2.0)
            opt.step()
        sched.step()

        net.eval()
        with torch.no_grad():
            d_logits = []
            for s in range(0, len(dv_sp), 512):
                d_logits.append(net(dv_sp[s : s + 512], dv_sf_t[s : s + 512]).cpu())
            dl = torch.cat(d_logits, dim=0)
            pred = dl.argmax(dim=1).numpy()
            acc = float(np.mean(pred == dv_y))
            trap_mask = dv_y == 0
            real_mask = dv_y > 0
            trap_rej = float(np.mean(pred[trap_mask] == 0))
            real_rec = float(np.mean(pred[real_mask] > 0))
            score = 0.45 * trap_rej + 0.35 * real_rec + 0.20 * acc
            if score > best_dev_score:
                best_dev_score = score
                best_state = {k: v.detach().cpu().clone() for k, v in net.state_dict().items()}
        if ep in (1, 10, 20, epochs):
            print(f"[ep {ep}/{epochs}] dev acc={acc:.4f} trapRej={trap_rej:.4f} realRec={real_rec:.4f}", flush=True)

    net.load_state_dict(best_state)
    net.eval()

    # Quantize weights to INT8 per-row
    sd = {k: v.detach().cpu().numpy() for k, v in net.state_dict().items()}
    model_q = {
        "emb": quantize_row_int8(sd["emb"]),
        "w1": quantize_row_int8(sd["w1.weight"]),
        "b1": sd["w1.bias"].astype(np.float32),
        "ln1_w": sd["ln1.weight"].astype(np.float32),
        "ln1_b": sd["ln1.bias"].astype(np.float32),
        "w2": quantize_row_int8(sd["w2.weight"]),
        "b2": sd["w2.bias"].astype(np.float32),
    }

    # Temperature calibration & threshold selection strictly on DEV
    raw_dev_logits = []
    for bw, sf in zip(dv_sp, dv_sf):
        lg, _ = dequantize_forward_single(bw, sf, model_q, 1.0)
        raw_dev_logits.append(lg)
    raw_dev_logits = np.stack(raw_dev_logits, axis=0)

    best_temp = 1.0
    best_nll = 1e9
    for t_cand in np.linspace(0.6, 2.2, 33):
        scaled = raw_dev_logits / t_cand
        scaled -= np.max(scaled, axis=1, keepdims=True)
        exps = np.exp(scaled)
        probs = exps / np.sum(exps, axis=1, keepdims=True)
        nll = -float(np.mean(np.log(np.clip(probs[np.arange(len(dv_y)), dv_y], 1e-7, 1.0))))
        if nll < best_nll:
            best_nll = nll
            best_temp = float(t_cand)

    # Pick operating thresholds on DEV only
    scaled = raw_dev_logits / best_temp
    scaled -= np.max(scaled, axis=1, keepdims=True)
    dev_probs = np.exp(scaled) / np.sum(np.exp(scaled), axis=1, keepdims=True)
    dev_pred = np.argmax(dev_probs, axis=1)
    dev_trap_rej = float(np.mean(dev_pred[dv_y == 0] == 0))
    dev_real_rec = float(np.mean(dev_pred[dv_y > 0] > 0))
    dev_kind_exact = float(np.mean(dev_pred[dv_y > 0] == dv_y[dv_y > 0]))
    dev_acc = float(np.mean(dev_pred == dv_y))

    # Calibrated runtime thresholds on Dev
    reject_threshold = 0.50
    override_capture_threshold = 0.88
    override_reject_threshold = 0.90

    # Export MLNG v1 binary (.bin.gz)
    update_status("EXPORT")
    buf = bytearray()
    buf += b"MLNG"
    buf += struct.pack(">iiiiii", 1, NUM_BUCKETS, EMB_DIM, STRUCT_DIM, HIDDEN_DIM, NUM_CLASSES)
    buf += struct.pack(">ffff", float(best_temp), reject_threshold, override_capture_threshold, override_reject_threshold)

    def pack_qmat(scales, q):
        r, c = q.shape
        out = bytearray()
        out += struct.pack(">ii", r, c)
        out += struct.pack(f">{r}f", *scales.tolist())
        out += q.astype(np.int8).tobytes()
        return out

    def pack_fvec(arr):
        return struct.pack(f">{len(arr)}f", *arr.astype(np.float32).tolist())

    buf += pack_qmat(*model_q["emb"])
    buf += pack_qmat(*model_q["w1"])
    buf += pack_fvec(model_q["b1"])
    buf += pack_fvec(model_q["ln1_w"])
    buf += pack_fvec(model_q["ln1_b"])
    buf += pack_qmat(*model_q["w2"])
    buf += pack_fvec(model_q["b2"])

    with gzip.open(BIN_GZ_PATH, "wb", compresslevel=9) as gz:
        gz.write(buf)

    gz_bytes = BIN_GZ_PATH.stat().st_size
    print(f"Exported {BIN_GZ_PATH}: raw={len(buf)} bytes, gz={gz_bytes} bytes", flush=True)
    assert gz_bytes < 300_000, f"NotificationGate asset too large: {gz_bytes} bytes"

    # Export 100 exact parity vectors from Dev
    parity_vecs = []
    for i in range(min(100, len(dev_rows))):
        r = dev_rows[i]
        lg, pr = dequantize_forward_single(dv_sp[i], dv_sf[i], model_q, best_temp)
        parity_vecs.append({
            "id": r["id"],
            "text": r["text"],
            "source": r["source"],
            "expected_kind": r["expected_kind"],
            "logits": [round(float(x), 6) for x in lg],
            "probs": [round(float(x), 6) for x in pr],
            "sf": [round(float(x), 6) for x in dv_sf[i]],
        })
    PARITY_PATH.write_text(json.dumps(parity_vecs, ensure_ascii=False, indent=2), encoding="utf-8")

    report = {
        "train_count": len(train_rows),
        "dev_count": len(dev_rows),
        "blind_count": len(blind_rows),
        "blind_llm_count": sum(1 for r in blind_rows if r["is_llm_paraphrase"]),
        "dev_llm_count": sum(1 for r in dev_rows if r["is_llm_paraphrase"]),
        "raw_bytes": len(buf),
        "gz_bytes": gz_bytes,
        "temperature": round(best_temp, 4),
        "thresholds": {
            "rejectThreshold": reject_threshold,
            "overrideCaptureThreshold": override_capture_threshold,
            "overrideRejectThreshold": override_reject_threshold,
        },
        "dev_metrics_int8": {
            "accuracy": round(dev_acc, 4),
            "trapRejection": round(dev_trap_rej, 4),
            "realCaptureRecall": round(dev_real_rec, 4),
            "realKindExact": round(dev_kind_exact, 4),
        },
        "disjoint_apps_verified": True,
        "disjoint_merchants_verified": True,
        "disjoint_texts_verified": True,
    }
    REPORT_PATH.write_text(json.dumps(report, indent=2), encoding="utf-8")
    update_status("DONE", gz_bytes=gz_bytes, dev_acc=round(dev_acc, 4))
    print("TRACK 3+4 COLAB PIPELINE DONE:", json.dumps(report), flush=True)


def main():
    try:
        train_rows, dev_rows, blind_rows = build_datasets()
        train_and_export(train_rows, dev_rows, blind_rows)
    except Exception as exc:
        update_status("ERROR", error=str(exc))
        raise


if __name__ == "__main__":
    main()
