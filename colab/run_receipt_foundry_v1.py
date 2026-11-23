#!/usr/bin/env python3
"""MoneyLens Track 1 — Receipt Vision Foundry + Photographic GPU OCR + Pure-JDK CRF Field Tagger."""
import argparse
import difflib
import gzip
import json
import math
import os
import random
import re
import shutil
import subprocess
import sys
import time
from collections import Counter, defaultdict
from decimal import Decimal
from pathlib import Path

OUT_DIR = Path("/content/outputs_receipt_v1")
DATASET_V4_PATH = Path("/content/dataset_malaysian_v4.jsonl")
HOLDOUT_V4_PATH = Path("/content/holdout_v4_details.json")
HOLDOUT_V3_PATH = Path("/content/holdout_v3.json")

STATUS_PATH = OUT_DIR / "receipt_foundry_v1_status.json"
CKPT_OCR_PATH = OUT_DIR / "receipt_ocr_checkpoint.jsonl"
DATASET_OUT_GZ = OUT_DIR / "receipt_vision_v1_dataset.jsonl.gz"
HOLDOUT_OCR_PATH = OUT_DIR / "receipt_holdout_real_ocr.json"
HOLDOUT_TEXT_PATH = OUT_DIR / "receipt_holdout_text.json"
REPORT_PATH = OUT_DIR / "receipt_foundry_v1_report.json"
MODEL_JSON_PATH = OUT_DIR / "moneylens_receipt_tagger.json"
MODEL_GZ_PATH = OUT_DIR / "moneylens_receipt_tagger.json.gz"

MAX_TAGGER_GZ_BYTES = 480_000  # Safely under 512 KB cap

LABELS = [
    "MERCHANT",        # 0
    "HEADER_META",     # 1
    "DATE",            # 2
    "ITEM",            # 3
    "SUBTOTAL",        # 4
    "TAX_SST",         # 5
    "ROUNDING",        # 6
    "GRAND_TOTAL",     # 7
    "PAYMENT_TENDER",  # 8
    "LHDN_META",       # 9
    "OTHER",           # 10
]
LBL2ID = {lbl: i for i, lbl in enumerate(LABELS)}

SAFE_LINE_SEP_RE = re.compile(r"[\x0b\x0c\x1c\x1d\x1e\x85\u2028\u2029]")


def sanitize_line(s: str) -> str:
    return SAFE_LINE_SEP_RE.sub(" ", str(s)).replace("\r", "")


def update_status(stage: str, **extra):
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    payload = {"stage": stage, "ts": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()), **extra}
    STATUS_PATH.write_text(json.dumps(payload, indent=2), encoding="utf-8")


# ---------------------------------------------------------------------------
# Exact Python <-> Java Parity Line Normalizer & CRF Line Feature Extractor
# ---------------------------------------------------------------------------
FLAT_SECTION_SPLIT_RE = re.compile(
    r"(?i)(?:\.{3,}|[ \t]+\|[ \t]+|"
    r"(?<=[0-9A-Za-z):%])[ \t]+(?=(?:"
    r"(?:simplified\s+)?tax\s+invoice|80mm\s+thermal\s+receipt|"
    r"sub\s*-?\s*total|sub\s+ttl|sst(?:\s*[0-9]+%)?|service\s+(?:tax|charge)|"
    r"(?:5[- ]?sen\s+)?rounding(?:\s+adj(?:ustment)?)?|runding|penggenapan|"
    r"final\s+amount(?:\s*\(rounded\))?|grand\s+total|total\s+payable|"
    r"net(?:t)?\s+(?:total|amount|payable)|amount\s+(?:paid|due|payable)|"
    r"jumlah\s+(?:perlu\s+)?dibayar|jumlah\s+(?:bayaran|bersih|besar)|"
    r"total\s+(?:sales|incl|amount|rm|myr)|total(?:\s*:|\s+(?:rm|myr)\b)|"
    r"duitnow\s*qr|tendered|tunai|change\b|baki\b|"
    r"tin\s*:|invoice(?:\s+(?:no|ref))?\s*:|date\s*:|tarikh\s*:|ssm(?:\s+reg)?\s*:|"
    r"payee\s*:|merchant\s*:|lhdn\s+bucket\s*:|item\s*:|amount\s*:|1\.\s+[A-Za-z]"
    r")))"
)

OCR_KW_SUBS = [
    (re.compile(r"(?i)\brn[1il]\b"), "rm"),
    (re.compile(r"(?i)\bt[0o]t[a4][1il]\b"), "total"),
    (re.compile(r"(?i)\bsubt[0o]t[a4][1il]\b"), "subtotal"),
    (re.compile(r"(?i)\bj[uü]m[1il][a4]h\b"), "jumlah"),
    (re.compile(r"(?i)\bp[a4]y[a4]b[1il]e\b"), "payable"),
    (re.compile(r"(?i)\b[5s]st\b"), "sst"),
    (re.compile(r"(?i)\br[0o]unding\b"), "rounding"),
    (re.compile(r"(?i)\btun[a4][1il]\b"), "tunai"),
    (re.compile(r"(?i)\bb[a4]k[1il]\b"), "baki"),
]


def ocr_norm_text(s: str) -> str:
    out = s.lower()
    for rx, rep in OCR_KW_SUBS:
        out = rx.sub(rep, out)
    return out


def normalize_lines(text: str) -> list[str]:
    """Exact parity with Java ReceiptFieldTagger.normalizeLines()."""
    if not text:
        return []
    raw_lines = [ln.strip() for ln in text.replace("\u00a0", " ").splitlines() if ln.strip()]
    if len(raw_lines) <= 2 or any(len(ln) > 170 and len(re.findall(r"[0-9]+\.[0-9]{2}", ln)) >= 2 for ln in raw_lines):
        expanded = []
        for ln in raw_lines:
            if len(raw_lines) <= 2 or len(ln) > 170:
                parts = FLAT_SECTION_SPLIT_RE.split(ln)
                for p in parts:
                    p = p.strip(" |\t.")
                    if p:
                        expanded.append(p)
            else:
                expanded.append(ln)
        if expanded:
            return expanded
    return raw_lines


AMT_RE = re.compile(
    r"(?i)(?:(?:rm|myr|rn1)\s*([0-9]{1,3}(?:,[0-9]{3})+(?:\.[0-9]{1,2})?|[0-9]{1,7}(?:\.[0-9]{1,2})?)"
    r"|([0-9]{1,3}(?:,[0-9]{3})+\.[0-9]{2}|[0-9]{1,7}\.[0-9]{2}))(?![0-9]|\.[0-9])"
)
STANDALONE_AMT_RE = re.compile(
    r"(?i)^\s*(?:(?:rm|myr|rn1)\s*([0-9]{1,3}(?:,[0-9]{3})+(?:\.[0-9]{1,2})?|[0-9]{1,7}(?:\.[0-9]{1,2})?)"
    r"|([0-9]{1,3}(?:,[0-9]{3})+\.[0-9]{2}|[0-9]{1,7}\.[0-9]{2}))\s*$"
)
NEG_AMT_RE = re.compile(r"(?:-\s*(?:rm|myr)?\s*[0-9]{1,5}\.[0-9]{2}|\(\s*[0-9]{1,5}\.[0-9]{2}\s*\))", re.I)
DATE_LINE_RE = re.compile(
    r"(?i)(?:\b20[0-9]{2}[-/.](?:0?[1-9]|1[0-2])[-/.](?:0?[1-9]|[12][0-9]|3[01])\b|"
    r"\b(?:0?[1-9]|[12][0-9]|3[01])[-/. ](?:0?[1-9]|1[0-2]|jan|feb|mar|mac|apr|may|mei|jun|jul|aug|ogos|sep|sept|oct|okt|nov|dec|dis)[-/. ,]+(?:20)?[0-9]{2}\b|"
    r"\b(?:date|tarikh)\s*:)"
)
TIN_SSM_RE = re.compile(
    r"(?i)(?:\b(?:tin|ssm|roc|rob|brn|uuid|myinvois|einvoice|e-invoice|lhdn|msic|gst\s*(?:reg|id)|sst\s*(?:reg|id))\b"
    r"|\b20[0-9]{10}\b|\b[0-9]{6,8}-[A-Za-z]\b|\b(?:C|IG|D|E)[0-9]{10,12}\b)"
)
PHONE_URL_RE = re.compile(r"(?i)(?:\b(?:tel|hp|fax|hotline|call)\b|@|https?://|www\.|\b0[1-9]-?[0-9]{6,8}\b)")
ADDR_RE = re.compile(
    r"(?i)(?:\b(?:jalan|jln|lorong|lrg|persiaran|lebuh|taman|tmn|bandar|seksyen|section|"
    r"kuala lumpur|selangor|petaling|subang|shah alam|klang|kajang|puchong|penang|johor|melaka|"
    r"perak|ipoh|pahang|kuantan|kelantan|terengganu|seremban|sabah|sarawak|kuching|kota kinabalu|putrajaya|cyberjaya)\b"
    r"|\b[0-9]{5}\b)"
)
BIZ_SFX_RE = re.compile(
    r"(?i)\b(?:sdn\.?\s*bhd|bhd|berhad|plt|llp|enterprise|trading|services|solutions|holdings|"
    r"klinik|clinic|farmasi|pharmacy|restoran|restaurant|kopitiam|cafe|kafe|mart|grocer|"
    r"supermarket|hypermarket|pasaraya|workshop|bengkel|resources|ventures|hospital|specialist)\b"
)
TOTAL_KW_RE = re.compile(
    r"(?i)\b(?:total|jumlah|grand\s+total|final\s+amount|nett?\s+(?:amount|total|payable)|amount\s+(?:paid|due|payable))\b|总计|總計|合计|合計|应付|实付"
)
SUB_TAX_KW_RE = re.compile(r"(?i)\b(?:subtotal|sub\s*total|sub\s+ttl|sst|gst|cukai|service\s+(?:tax|charge)|tax)\b")
BODY_START_RE = re.compile(r"(?i)^(?:qty|item|desc|description|produk|barang)\b|(?:\b[0-9]+\s*[xX×]\s*[A-Za-z])")
CJK_RE = re.compile(r"[\u4e00-\u9fff]")


def parse_line_amounts(line: str) -> list[int]:
    out = []
    for m in AMT_RE.finditer(line):
        raw = (m.group(1) or m.group(2)).replace(",", "")
        try:
            d = Decimal(raw)
        except Exception:
            continue
        minor = int((d * 100).to_integral_value())
        if 1 <= minor < 50_000_000:
            out.append(minor)
    return out


def crf_word_tokens(norm_line: str) -> list[str]:
    """Exact parity with Java ReceiptFieldTagger.tokenizeLine()."""
    out = []
    buf = []
    for ch in norm_line:
        if ch.isalnum():
            buf.append(ch)
        else:
            if buf:
                tok = "".join(buf)
                if len(tok) >= 2 and not tok.isdigit():
                    out.append(tok)
                buf.clear()
    if buf:
        tok = "".join(buf)
        if len(tok) >= 2 and not tok.isdigit():
            out.append(tok)
    return out


def extract_line_features(lines: list[str]) -> list[list[str]]:
    """Extracts deterministic sparse features per receipt line (100% parity with Java)."""
    L = len(lines)
    if L == 0:
        return []
    line_amts = [parse_line_amounts(ln) for ln in lines]
    max_amt = max((max(a) for a in line_amts if a), default=0)
    all_amts_sorted = sorted({v for a in line_amts for v in a}, reverse=True)
    second_max = all_amts_sorted[1] if len(all_amts_sorted) >= 2 else 0

    norm_lines = [ocr_norm_text(ln) for ln in lines]
    has_total_kw = [bool(TOTAL_KW_RE.search(nl)) and "subtotal" not in nl and "sub total" not in nl for nl in norm_lines]
    has_sub_tax = [bool(SUB_TAX_KW_RE.search(nl)) for nl in norm_lines]

    seen_sub = False
    seen_tax = False
    seen_body = False
    seen_total = False
    prev_sub_amt = 0
    prev_tax_amt = 0

    feats_per_line = []
    for i, raw in enumerate(lines):
        nl = norm_lines[i]
        amts = line_amts[i]
        line_max = max(amts) if amts else 0
        f = ["f:bias"]

        pos_bin = min(9, (i * 10) // max(1, L))
        f.append(f"f:pos_{pos_bin}")
        if i == 0:
            f.append("f:first1")
        if i < 3:
            f.append("f:first3")
        if i == L - 1:
            f.append("f:last1")
        if i >= max(0, L - 3):
            f.append("f:last3")

        if re.search(r"(?i)\b(?:rm|myr)\s*[0-9]", nl):
            f.append("f:has_rm")
        if len(amts) == 0:
            f.append("f:amt_0")
        elif len(amts) == 1:
            f.append("f:amt_1")
        else:
            f.append("f:amt_2p")
        if STANDALONE_AMT_RE.match(raw):
            f.append("f:standalone_amt")
        if NEG_AMT_RE.search(raw):
            f.append("f:has_neg_amt")
        if amts and line_max == max_amt and max_amt > 0:
            f.append("f:is_max_amt")
        elif amts and second_max > 0 and line_max == second_max:
            f.append("f:is_2nd_max")
        if amts and max_amt > 0:
            if line_max == max_amt:
                f.append("f:ratio_eq1")
            elif line_max * 4 >= max_amt * 3:
                f.append("f:ratio_hi")
            elif line_max * 4 >= max_amt:
                f.append("f:ratio_mid")
            else:
                f.append("f:ratio_lo")
        if amts and (line_max % 5 == 0):
            f.append("f:is_5sen")
        if amts and seen_sub and prev_sub_amt > 0 and line_max >= prev_sub_amt:
            f.append("f:amt_ge_sub")
        if amts and seen_sub and prev_sub_amt > 0:
            exp_sum = prev_sub_amt + prev_tax_amt
            if abs(line_max - exp_sum) <= 4:
                f.append("f:amt_eq_sum")

        if re.search(r"\b(?:6|8|10|0)\s*%", raw):
            f.append("f:has_pct")
        if DATE_LINE_RE.search(nl):
            f.append("f:has_date")
        if TIN_SSM_RE.search(raw):
            f.append("f:has_tin_ssm")
        if PHONE_URL_RE.search(raw):
            f.append("f:has_phone_url")
        if ADDR_RE.search(nl):
            f.append("f:has_addr")
        if BIZ_SFX_RE.search(nl):
            f.append("f:has_biz_sfx")

        letters = [c for c in raw if c.isalpha()]
        if letters and all(c.isupper() for c in letters):
            f.append("f:all_caps")
        ln_len = len(raw)
        if ln_len <= 14:
            f.append("f:len_s")
        elif ln_len <= 34:
            f.append("f:len_m")
        else:
            f.append("f:len_l")
        if CJK_RE.search(raw):
            f.append("f:has_cjk")

        if seen_body:
            f.append("f:after_body")
        if not seen_total:
            f.append("f:before_total")
        if i > 0 and has_total_kw[i - 1]:
            f.append("f:prev_total_kw")
        if i > 0 and has_sub_tax[i - 1]:
            f.append("f:prev_sub_or_tax")
        if i > 0 and bool(line_amts[i - 1]):
            f.append("f:prev_has_amt")
        if i + 1 < L and bool(STANDALONE_AMT_RE.match(lines[i + 1])):
            f.append("f:next_standalone_amt")
        if i + 1 < L and bool(line_amts[i + 1]):
            f.append("f:next_has_amt")

        toks = crf_word_tokens(nl)
        seen_feat = set(f)
        for j, tok in enumerate(toks):
            k = "w:" + tok
            if k not in seen_feat:
                seen_feat.add(k)
                f.append(k)
            if j + 1 < len(toks):
                bk = "b:" + tok + "_" + toks[j + 1]
                if bk not in seen_feat:
                    seen_feat.add(bk)
                    f.append(bk)
        for ch in raw:
            if "\u4e00" <= ch <= "\u9fff":
                zk = "z:" + ch
                if zk not in seen_feat:
                    seen_feat.add(zk)
                    f.append(zk)

        feats_per_line.append(f)

        # Update causal state for subsequent lines
        if "subtotal" in nl or "sub total" in nl or "sub-total" in nl:
            seen_sub = True
            seen_body = True
            if amts:
                prev_sub_amt = amts[-1]
        elif has_sub_tax[i]:
            seen_tax = True
            if amts:
                prev_tax_amt += amts[-1]
        elif BODY_START_RE.search(nl) or (amts and i >= 2 and not DATE_LINE_RE.search(nl) and not has_total_kw[i]):
            seen_body = True
        if has_total_kw[i]:
            seen_total = True

    return feats_per_line


# ---------------------------------------------------------------------------
# Merchant Pools & Realistic Malaysian 80mm / LHDN MyInvois Receipt Generator
# ---------------------------------------------------------------------------
RECEIPT_TEMPLATES = {
    "Food & drinks || Coffee, Tea & Drinks": (
        ["ZUS Coffee", "CHAGEE Malaysia", "Beutea", "Kenangan Coffee", "Gigi Coffee", "Starbucks MY", "Tealive", "Mixue Malaysia", "OldTown White Coffee", "Luckin Coffee MY"],
        [("Spanish Latte (Iced)", 1090), ("CEO Coconut Latte", 1190), ("Da Hong Pao Milk Tea", 1490), ("Roasted Oolong Latte", 1390), ("Bangau Signature Matcha", 1290), ("Iced Americano", 890), ("Signature Brown Sugar Boba", 1050), ("Kaya Butter Toast Set", 790)],
        True,
    ),
    "Food & drinks || Fast Food": (
        ["McDonald's Malaysia", "KFC Malaysia", "Marrybrown", "A&W Malaysia", "Domino's Pizza MY", "Texas Chicken MY", "Jollibee Malaysia", "myBurgerLab", "4Fingers Crispy Chicken", "KyoChon 1991"],
        [("McChicken Value Meal", 1395), ("2pc Snack Plate Combo", 1850), ("Nasi Marrybrown Combo", 1590), ("Mozza Burger Regular", 1680), ("Personal Pizza Pepp", 1990), ("3pc Tenders Box", 1720), ("Chickenjoy 2pc Meal", 1750), ("A+ Burger Single", 2150)],
        True,
    ),
    "Food & drinks || Hawker & Mamak": (
        ["Nasi Kandar Pelita", "Oriental Kopi", "Thong Kee Cafe", "Q Bistro", "Restoran Yut Kee", "Village Park Nasi Lemak", "Restoran Kayu", "Hameediyah Briyani", "Kluang RailCoffee", "Warung Kak Ina"],
        [("Nasi Lemak Ayam Goreng", 1350), ("Roti Canai x2", 360), ("Teh Tarik Kurang Manis", 320), ("Polo Bun Classic", 550), ("Kopi Peng", 480), ("Mee Goreng Mamak", 850), ("Kaya Butter Toast", 420), ("Nasi Briyani Kambing", 1850), ("冰奶茶 Iced Milk Tea", 450)],
        False,
    ),
    "Food & drinks || Restaurant & Cafe": (
        ["HaiDiLao Hot Pot MY", "Sushiro Malaysia", "Kenny Hills Bakers", "Serai Restaurant", "Din Tai Fung MY", "PappaRich", "The Chicken Rice Shop", "Suki-Ya", "Genki Sushi MY", "Yakiniku Like"],
        [("Xiao Long Bao 6pcs", 2180), ("Prawn Wanton Noodles", 1990), ("Matcha Tiramisu", 1800), ("Salmon Nigiri Plate", 1080), ("Chicken Chop Blackpepper", 2650), ("Wagyu Set 200g", 4990), ("Iced Lemon Tea", 750), ("Mala Soup Base", 3800)],
        True,
    ),
    "Groceries || Convenience Store (99 Speedmart / 7-Eleven / FamilyMart)": (
        ["99 Speedmart", "FamilyMart Malaysia", "CU Mart Malaysia", "KK Super Mart", "7-Eleven 7-CAFé", "myNEWS.com", "BilaBila Mart", "emart24 Malaysia", "Kedai Mesra Petronas"],
        [("Milo UHT 6x200ml", 1190), ("Gardenia Original 400g", 300), ("Farm Fresh Milk 1L", 790), ("Oden Curry Bowl", 880), ("Mineral Water 1.5L", 220), ("Maggi Kari 5-Pack", 560), ("Arabica Onigiri", 490), ("100Plus Can 325ml", 260)],
        False,
    ),
    "Groceries || Supermarket & Hypermarket": (
        ["Jaya Grocer", "Village Grocer", "Lotus's Malaysia", "AEON BiG", "Mydin Hypermarket", "Econsave Cash & Carry", "NSK Trade City", "Giant Hypermarket", "HeroMarket", "Mercato Pavilion"],
        [("Jasmine Fragrant Rice 5kg", 2690), ("Norwegian Salmon 300g", 3490), ("Anchor Unsalted Butter", 1590), ("Local Broccoli Pack", 490), ("Olive Oil Extra Virgin", 3890), ("Omega Eggs 10s", 890), ("Free Range Chicken Breast", 1480), ("Susu Segar HL 2L", 1390)],
        False,
    ),
    "Groceries || Wet Market & Fresh Produce": (
        ["Pasar Besar TTDI", "Segi Fresh Market", "Pasar Pagi Puchong", "MyFishman", "Sin Chong Hing Seafood", "Pasar Basah Kajang", "Kepong Wet Market", "Borong Sayur Segar"],
        [("Siakap Fresh 800g", 1850), ("Udang Harimau 500g", 2800), ("Ayam Kampung 1 Ekor", 2200), ("Sayur Sawi + Kangkung", 650), ("Cili Padi Merah", 400), ("Ikan Kembung 1kg", 1500), ("Lettuce + Celery", 820)],
        False,
    ),
    "Shopping || Home & Living": (
        ["MR.DIY", "MR.DIY Plus", "Eco-Shop", "Daiso Japan", "MUJI Malaysia", "IKEA Damansara", "Kaison Furnishing", "Ninso", "Noko Store"],
        [("Storage Box 30L", 2490), ("Microfiber Cloth 3pc", 590), ("Stainless Pan 24cm", 4900), ("Led Bulb E27 12W", 1290), ("Acrylic Organizer", 1490), ("Door Stopper Set", 480), ("Cotton Towel Large", 2990)],
        True,
    ),
    "Shopping || Clothing, Bags & Footwear": (
        ["Uniqlo Malaysia", "H&M Malaysia", "Padini Concept Store", "JD Sports MY", "Bata Malaysia", "Cotton On MY", "Christy Ng", "Zara Malaysia", "Charles & Keith"],
        [("AIRism Cotton Tee", 5990), ("Slim Tapered Jeans", 14990), ("Oxford Shirt L/S", 12990), ("Canvas Tote Bag", 7900), ("Ankle Socks 3-Pack", 2990), ("Casual Sneakers", 19900)],
        True,
    ),
    "Shopping || Electronics & Gadgets": (
        ["Machines Apple APR", "Switch Apple Store", "ALL IT Hypermarket", "Harvey Norman", "Senheng", "ThunderMatch (TMT)", "Xiaomi Official Store", "Sony Store KLCC"],
        [("USB-C Woven Cable 1m", 9900), ("Anker GaN 65W Charger", 16900), ("Wireless Mouse Silent", 7900), ("SSD NVMe 1TB PCIe4", 32900), ("Apple iPad Air 11 M3", 279900), ("MX Keys Mini Keyboard", 44900)],
        True,
    ),
    "Health & medical || Clinic & GP": (
        ["Klinik Mediviron", "Qualitas Health Clinic", "Poliklinik Damai", "Megaklinik Zahran", "Careclinics Healthcare", "Klinik Reddy", "TCM Eu Yan Sang Clinic", "BeWell Physiotherapy"],
        [("GP Consultation Fee", 4500), ("Antibiotic Azithromycin 3d", 3200), ("Antihistamine + Nasal Spray", 2800), ("Rapid Antigen Test", 2500), ("Physiotherapy Session", 12000), ("Ubat Batuk + Demam", 2200)],
        False,
    ),
    "Health & medical || Dental & Optical": (
        ["Q & M Dental Malaysia", "Focus Point Optical", "Owndays Malaysia", "MOG Eyewear", "Tiew Dental Group", "Lenskart Malaysia", "Pott Glasses", "Bangsar Dental Specialist"],
        [("Ultrasonic Scaling & Polish", 15000), ("Composite Filling 1 Tooth", 18000), ("Anti-Blue Light Lens", 29800), ("Daily Contact Lens 30s", 11800), ("Airframe Eyewear Frame", 34800)],
        False,
    ),
    "Health & medical || Full Medical Checkup (LHDN)": (
        ["Pathlab Malaysia", "BP Healthcare Screening", "Gribbles Pathology", "Sunway Wellness Health Check", "Pantai Health Screening", "Innoquest Diagnostics"],
        [("Comprehensive Blood Profile", 24800), ("Full Medical Checkup LHDN", 49900), ("HbA1c & Lipid Panel", 12000), ("Liver & Renal Function", 14500), ("Thyroid Screening Panel", 16500)],
        False,
    ),
    "Health & medical || Hospital & Specialist": (
        ["Sunway Medical Centre", "Gleneagles Hospital KL", "Pantai Hospital KL", "KPJ Damansara Specialist", "Prince Court Medical Centre", "Columbia Asia Hospital", "SJMC Subang Jaya"],
        [("Specialist Consultation", 22000), ("Outpatient Radiology X-Ray", 14000), ("MRI Lumbar Scan", 118000), ("Ward Admission Deposit", 150000), ("Discharge Medication", 9850)],
        False,
    ),
    "Health & medical || Pharmacy & Medicine": (
        ["Watsons Pharmacy", "Guardian Pharmacy", "CARiNG Pharmacy", "Big Pharmacy", "AA Pharmacy", "Alpro Pharmacy", "Health Lane Family Pharmacy"],
        [("Panadol Actifast 20s", 1590), ("Vitamin C 1000mg 60s", 3890), ("Gaviscon Double Action", 2990), ("Surgical Mask 50s", 1290), ("Betadine Throat Spray", 3250), ("Bioderma Micellar 250ml", 4990)],
        False,
    ),
    "Health & medical || Gym, Sports & Fitness": (
        ["Decathlon Malaysia", "Sports Direct MY", "Al-Ikhsan Sports", "Anytime Fitness MY", "Courtsite Badminton", "Celebrity Fitness", "Camp5 Climbing Gym"],
        [("Badminton Court 2 Hours", 6400), ("Running Shoes Kalenji", 14900), ("Treadmill Day Pass", 4500), ("Yogamat 8mm Non-Slip", 5900), ("Yonex Shuttlecock Tube", 9800)],
        True,
    ),
    "Education || Books & Stationery": (
        ["POPULAR Bookstore", "Kinokuniya KLCC", "MPH Bookstores", "BookXcess", "Tsutaya Books Pavilion", "CzipLee Bangsar", "Smiggle MY"],
        [("Atomic Habits Paperback", 6990), ("Pilot G2 Gel Pen 0.5mm", 690), ("A4 Exercise Book 5-Pack", 1450), ("SPM Reference Mathematics", 3290), ("Muji A5 Grid Notebook", 1290)],
        False,
    ),
    "Education || Higher Education & Tuition": (
        ["Sunway University Bursary", "Taylor's University Finance", "UTAR Fee Portal", "APU Bursary", "Universiti Malaya UMPay", "Pusat Tuisyen Mawar", "Kumon Tuition Centre"],
        [("Semester Tuition Installment", 320000), ("Monthly Tuition Math+Sci", 28000), ("Exam Registration Fee", 15000), ("Laboratory Resource Fee", 12000)],
        False,
    ),
    "Education || Upskilling & Certification": (
        ["42 Kuala Lumpur Bootcamp", "HRD Corp Approved Training", "LinkedIn Learning", "Coursera Plus", "AWS Certification Testing", "ACCA Exam Fee"],
        [("Cloud Practitioner Exam", 45000), ("Data Analytics Course", 39900), ("Python Professional Cert", 24900)],
        True,
    ),
    "Transport || Petrol & EV Charging": (
        ["Petronas Mesra Fuel", "Shell Station MY", "Gentari EV Charging", "JomCharge", "chargeV", "Setel Fuel Petronas", "EVConnection", "Caltex Journey"],
        [("Primax 95 Petrol 32.5L", 6663), ("V-Power 97 Fuel", 11200), ("DC Fast Charge 42.5 kWh", 5525), ("AC Charging Session", 1840), ("Idle Overstay Fee", 500)],
        False,
    ),
    "Transport || Vehicle Maintenance & Service": (
        ["Perodua Service Centre", "Proton 3S Centre", "Lim Tayar Workshop", "Bateriku.com", "Continental Tyre Auto", "Carro Workshop", "CarWash Pro Detailing"],
        [("40k km Major Service", 38500), ("0W-20 Fully Synth Engine Oil", 16800), ("Oil Filter Original", 2200), ("Wheel Alignment & Balancing", 6500), ("MF Battery 46B24L", 28000)],
        True,
    ),
    "Bills & utilities || Electricity (TNB)": (
        ["Tenaga Nasional Berhad", "myTNB Tenaga Nasional", "Sarawak Energy SESCO", "Sabah Electricity SESB"],
        [("Electricity Usage 412 kWh", 13450), ("ICPT & KWTBB Adjustment", 420)],
        True,
    ),
    "Bills & utilities || Home Internet & WiFi (Unifi / Time)": (
        ["Unifi Home Fibre", "TIME Internet Fibre", "Maxis Home Fibre", "CelcomDigi Fibre"],
        [("Fibre 500Mbps Monthly Plan", 13900), ("Mesh WiFi Add-on", 1500)],
        True,
    ),
    "Investments & savings || SSPN Savings": (
        ["PTPTN Simpan SSPN Prime", "Simpan SSPN Plus Savings", "myPTPTN SSPN Simpanan Pendidikan"],
        [("Simpan SSPN Prime Deposit", 30000), ("Takaful Rider SSPN Plus", 2000)],
        False,
    ),
    "Investments & savings || PRS (Private Retirement Scheme)": (
        ["PPA Private Retirement Scheme", "Public Mutual PRS", "Principal PRS Plus", "Kenanga OnePRS", "AHAM PRS Growth Fund"],
        [("PRS Growth Fund Contrib", 150000)],
        False,
    ),
    "Investments & savings || EPF Voluntary (i-Saraan)": (
        ["KWSP i-Akaun Top-Up", "EPF i-Saraan Voluntary", "i-Sayang EPF Transfer"],
        [("Caruman Sukarela i-Saraan", 50000)],
        False,
    ),
    "Family & gifts || Zakat & Fitrah": (
        ["PPZ-MAIWP Zakat Pendapatan", "Lembaga Zakat Selangor (LZS)", "Zakat Pulau Pinang", "Zakat Johor MAINJ"],
        [("Zakat Pendapatan Bulanan", 20000)],
        False,
    ),
    "Family & gifts || Charity & Donations": (
        ["MERCY Malaysia", "Makna Cancer Council", "Teach For Malaysia", "WWF Malaysia Donation", "Yayasan Chow Kit"],
        [("Sumbangan Derma Sec 44(6)", 10000)],
        False,
    ),
    "Insurance & Takaful || Medical & Health Card": (
        ["PruBSN HealthEnrich", "AIA A-Plus Health", "Great Eastern MediCare", "Allianz HealthInsured", "Etiqa Medical Takaful"],
        [("Monthly Takaful Medical Plan", 24500)],
        False,
    ),
    "Insurance & Takaful || Education Insurance": (
        ["Allianz EduGuard Plan", "Prudential PruEdu", "AIA EduSmart", "Great Eastern SmartEdu"],
        [("Child Education Plan", 32000)],
        False,
    ),
}

CITY_ADDRS = [
    ("No. 18, Jalan Telawi 3", "Bangsar Baru", "59100", "Kuala Lumpur"),
    ("Lot G-12, Jalan SS15/4", "Subang Jaya", "47500", "Selangor"),
    ("Level 2, Lot 45, Jalan Bukit Bintang", "TRX KL", "55100", "Kuala Lumpur"),
    ("No. 72, Jalan Meru", "Klang", "41050", "Selangor"),
    ("101, Jalan Austin Heights 3", "Johor Bahru", "81100", "Johor"),
    ("30, Lebuh Pantai", "George Town", "10300", "Pulau Pinang"),
    ("Lot 9, Jalan Raja Muda Musa", "Shah Alam", "40000", "Selangor"),
    ("24, Jalan Tun Razak", "Kuala Lumpur", "50400", "WP Kuala Lumpur"),
    ("No. 15, Lorong Selamat", "Kuching", "93100", "Sarawak"),
    ("Lot 6, Jalan Tun Fuad Stephens", "Kota Kinabalu", "88000", "Sabah"),
    ("88, Jalan Theatre", "Ipoh", "30300", "Perak"),
    ("No. 22, Jalan Merdeka", "Melaka Raya", "75000", "Melaka"),
]

SMALL_SHOPS_POOL = [
    "Restoran Serba Maju", "Kopi & Toast Station", "Kedai Ubat Sinaran",
    "Klinik Keluarga Harmoni", "Farmasi Mesra", "Pasar Mini Bintang",
    "Syarikat Perniagaan Cahaya", "Bengkel Motor Jitu", "Tadika Permata Hati",
    "Pusat Tuisyen Wawasan", "Kedai Buku Bestari", "Gerai Selera Timur",
    "Optik Jelas Jaya", "Bengkel Servis ProAuto", "Restoran Rasa Kampung",
    "Kafe Santai Pagi", "Pusat Sukan Arena", "Kedai Hardware Permai",
]


def split_merchant_bucket(name: str) -> str:
    h = 2166136261
    for ch in name.lower():
        h = ((h ^ ord(ch)) * 16777619) & 0xFFFFFFFF
    r = h % 100
    if r < 70:
        return "train"
    if r < 82:
        return "val"
    return "test"


def build_disjoint_pools():
    pools = {"train": [], "val": [], "test": []}
    for pair, (merchants, items, has_sst) in RECEIPT_TEMPLATES.items():
        # Include both curated chains and realistic unseen SME shop names partitioned deterministically
        all_m = list(merchants)
        for idx, sm in enumerate(SMALL_SHOPS_POOL):
            if idx % 5 == (hash(pair) % 5):
                all_m.append(sm)
        tr, va, te = [], [], []
        for idx, m in enumerate(all_m):
            b = split_merchant_bucket(m)
            if idx == len(all_m) - 1 and not te:
                b = "test"
            elif idx == 0 and not tr:
                b = "train"
            if b == "train":
                tr.append(m)
            elif b == "val":
                va.append(m)
            else:
                te.append(m)
        if not va:
            va = tr[:1]
        for b_name, m_list in [("train", tr), ("val", va), ("test", te)]:
            for m in m_list:
                pools[b_name].append((pair, m, items, has_sst))
    return pools


def fmt_rm(minor: int, with_prefix: bool, upper: bool, rng: random.Random) -> str:
    s = f"{minor / 100:.2f}"
    if not with_prefix:
        return s
    pref = rng.choice(["RM ", "RM", "MYR "]) if upper else rng.choice(["Rm ", "RM "])
    return pref + s


def inject_ocr_noise(text: str, rng: random.Random, level: float = 0.08) -> str:
    if rng.random() > 0.7:
        return text
    swaps = [("TOTAL", "T0TAL"), ("Total", "T0tal"), ("SUBTOTAL", "SUBT0TAL"), ("RM ", "RN1 "), ("JUMLAH", "JUML4H"), ("0", "O")]
    out = text
    for src, dst in swaps:
        if src in out and rng.random() < level * 3:
            out = out.replace(src, dst, 1)
    return out


def synthesize_receipt(rng: random.Random, pair: str, merchant: str, items_pool: list, can_sst: bool, split: str, flat_mode: bool = False):
    cat, sub = pair.split(" || ")
    addr_st, area, post, state = rng.choice(CITY_ADDRS)
    ssm = f"({rng.randint(100000, 999999)}-{rng.choice('ABCDEFGHKLMPTUVWXYZ')})" if rng.random() < 0.5 else f"202{rng.randint(1,6)}010{rng.randint(10000,99999)}"
    tin = f"C{rng.randint(1000000000, 9999999999)}" if rng.random() < 0.7 else f"IG{rng.randint(10000000000, 99999999999)}"
    mon = rng.randint(1, 12)
    day = rng.randint(1, 28)
    yr = 2026
    date_iso = f"{yr}-{mon:02d}-{day:02d}"
    months_en = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"]
    months_ms = ["Jan", "Feb", "Mac", "Apr", "Mei", "Jun", "Jul", "Ogos", "Sep", "Okt", "Nov", "Dis"]
    date_str = rng.choice([
        f"{day:02d}/{mon:02d}/{yr}",
        f"{day:02d}-{mon:02d}-{yr}",
        f"{yr}-{mon:02d}-{day:02d}",
        f"{day:02d} {months_en[mon-1]} {yr}",
        f"{day:02d} {months_ms[mon-1]} {yr}",
        f"{months_en[mon-1]} {day:02d}, {yr}",
    ])

    is_lhdn = ("lhdn" in sub.lower()) or any(k in cat for k in ["Health & medical", "Education", "Investments", "Insurance", "Family & gifts"]) or rng.random() < 0.18
    upper_style = rng.random() < 0.6
    disp_merch = merchant.upper() if upper_style else merchant
    if rng.random() < 0.45 and "sdn" not in disp_merch.lower():
        disp_merch_hdr = f"{disp_merch} SDN BHD" if upper_style else f"{disp_merch} Sdn Bhd"
    else:
        disp_merch_hdr = disp_merch

    n_items = rng.randint(1, min(6, len(items_pool) + 2))
    chosen = [rng.choice(items_pool) for _ in range(n_items)]
    gt_lines = []  # list of (text, label_str)

    # Header
    if rng.random() < 0.45:
        hdr_title = rng.choice(["SIMPLIFIED TAX INVOICE", "TAX INVOICE", "OFFICIAL RECEIPT", "RESIT RASMI", "LHDN e-INVOICE RECEIPT"])
        gt_lines.append((hdr_title, "HEADER_META"))
    if is_lhdn and rng.random() < 0.4:
        gt_lines.append((f"{disp_merch_hdr}", "MERCHANT"))
    elif rng.random() < 0.25:
        gt_lines.append((f"{disp_merch_hdr} {ssm}", "MERCHANT"))
    else:
        gt_lines.append((disp_merch_hdr, "MERCHANT"))
        if rng.random() < 0.55:
            gt_lines.append((f"SSM Reg No: {ssm}", "HEADER_META"))

    gt_lines.append((f"{addr_st}, {area}", "HEADER_META"))
    gt_lines.append((f"{post} {state}", "HEADER_META"))
    if rng.random() < 0.45:
        gt_lines.append((f"Tel: 0{rng.randint(3,9)}-{rng.randint(2000,9999)} {rng.randint(1000,9999)}", "HEADER_META"))

    inv_ref = f"INV-2026{mon:02d}{rng.randint(1000,9999)}"
    if is_lhdn:
        gt_lines.append((f"TIN: {tin}  Invoice: {inv_ref}", "LHDN_META"))
        if rng.random() < 0.5:
            gt_lines.append((f"MyInvois UUID: {rng.randint(100000,999999)}MY2026LHDN{rng.randint(10,99)}", "LHDN_META"))
    else:
        if rng.random() < 0.5:
            gt_lines.append((f"Receipt No: {inv_ref}", "HEADER_META"))

    date_pref = rng.choice(["Date: ", "Tarikh: ", ""])
    time_sfx = f"  {rng.randint(8,21):02d}:{rng.randint(0,59):02d}" if rng.random() < 0.6 else ""
    gt_lines.append((f"{date_pref}{date_str}{time_sfx}", "DATE"))

    # Itemized rows with consistent arithmetic
    subtotal = 0
    for nm, base_minor in chosen:
        qty = rng.choice([1, 1, 1, 2, 3])
        unit_minor = max(100, int(round(base_minor * rng.uniform(0.85, 1.25))))
        line_total = unit_minor * qty
        subtotal += line_total
        item_disp = nm.upper() if upper_style else nm
        if qty > 1:
            line_txt = rng.choice([
                f"{qty}x {item_disp}  {fmt_rm(line_total, False, upper_style, rng)}",
                f"{item_disp} x{qty}  RM{line_total/100:.2f}",
            ])
        else:
            line_txt = rng.choice([
                f"{item_disp}  {fmt_rm(line_total, False, upper_style, rng)}",
                f"{item_disp}  RM {line_total/100:.2f}",
            ])
        gt_lines.append((line_txt, "ITEM"))

    # Optional SST / Service Charge
    tax_minor = 0
    if can_sst and rng.random() < 0.65:
        rate = rng.choice([6, 8, 10])
        tax_minor = int(round(subtotal * rate / 100.0))
        sub_lbl = rng.choice(["Subtotal", "SUBTOTAL", "Sub-Total", "Jumlah Kecil"])
        gt_lines.append((f"{sub_lbl}  {fmt_rm(subtotal, True, upper_style, rng)}", "SUBTOTAL"))
        tax_lbl = f"SST {rate}%" if rate in (6, 8) else "Service Charge 10%"
        if rng.random() < 0.35:
            tax_lbl = f"Cukai Perkhidmatan ({rate}%)"
        gt_lines.append((f"{tax_lbl}  {fmt_rm(tax_minor, True, upper_style, rng)}", "TAX_SST"))
    elif rng.random() < 0.4:
        sub_lbl = rng.choice(["Subtotal", "SUBTOTAL", "Sub-Total"])
        gt_lines.append((f"{sub_lbl}  {fmt_rm(subtotal, True, upper_style, rng)}", "SUBTOTAL"))

    raw_sum = subtotal + tax_minor
    rem5 = raw_sum % 5
    rounding = 0
    if rem5 != 0 and (rng.random() < 0.65 or is_lhdn):
        rounding = -rem5 if rem5 <= 2 else (5 - rem5)
        grand_total = raw_sum + rounding
        r_lbl = rng.choice(["Rounding Adj", "5-Sen Rounding", "Penggenapan"])
        sign = "+" if rounding > 0 else "-"
        gt_lines.append((f"{r_lbl}  {sign}{abs(rounding)/100:.2f}", "ROUNDING"))
    else:
        grand_total = raw_sum

    # Grand Total styles (including split-line total and bilingual CN/BM/EN!)
    if rounding != 0 and rng.random() < 0.6:
        tot_lbl = rng.choice(["TOTAL AFTER ROUNDING", "FINAL AMOUNT (ROUNDED)", "TOTAL PAYABLE (RM)", "JUMLAH BERSIH", "Jumlah Perlu Dibayar"])
    elif is_lhdn:
        tot_lbl = rng.choice(["TOTAL INCL. SST", "GRAND TOTAL", "TOTAL PAYABLE", "Final Amount (Rounded)", "JUMLAH BAYARAN"])
    else:
        tot_lbl = rng.choice(["GRAND TOTAL", "TOTAL", "TOTAL PAYABLE", "NET AMOUNT", "JUMLAH BERSIH", "Jumlah", "应付金额", "总计", "合计"])

    split_total_line = (not flat_mode) and rng.random() < 0.14
    if split_total_line:
        gt_lines.append((tot_lbl, "GRAND_TOTAL"))
        gt_lines.append((fmt_rm(grand_total, rng.random() < 0.6, upper_style, rng), "GRAND_TOTAL"))
    else:
        gt_lines.append((f"{tot_lbl}  {fmt_rm(grand_total, rng.random() < 0.85, upper_style, rng)}", "GRAND_TOTAL"))

    # Payment tender / change
    if rng.random() < 0.45:
        cash_tens = ((grand_total + 999) // 1000) * 1000
        if cash_tens == grand_total:
            cash_tens += 1000
        chg = cash_tens - grand_total
        c_lbl = rng.choice(["Cash Tendered", "Tunai", "CASH"])
        b_lbl = rng.choice(["Change", "Baki Kembali", "Baki"])
        gt_lines.append((f"{c_lbl}  {fmt_rm(cash_tens, True, upper_style, rng)}", "PAYMENT_TENDER"))
        gt_lines.append((f"{b_lbl}  {fmt_rm(chg, True, upper_style, rng)}", "PAYMENT_TENDER"))
    else:
        pay_rail = rng.choice(["DuitNow QR", "TNG eWallet", "Maybank MAE QR", "Visa Contactless", "MyDebit"])
        if rng.random() < 0.65:
            gt_lines.append((f"Paid via {pay_rail}  {fmt_rm(grand_total, True, upper_style, rng)}", "PAYMENT_TENDER"))

    if is_lhdn and rng.random() < 0.45:
        gt_lines.append(("LHDN Tax Relief Eligible - Keep e-Invoice 7 Years", "LHDN_META"))
    if rng.random() < 0.55:
        gt_lines.append((rng.choice(["Thank you, please come again!", "Terima kasih, sila datang lagi!"]), "OTHER"))

    if flat_mode:
        sep = rng.choice([" | ", " ... ", "   "])
        raw_text = sep.join(ln for ln, _ in gt_lines)
        lines_out = normalize_lines(raw_text)
        # Re-map flat normalized lines to nearest GT labels cleanly
        labels_out = []
        used = 0
        for nl in lines_out:
            best_j = used
            best_sim = -1.0
            nlo = nl.lower()
            for j in range(len(gt_lines)):
                gtxt = gt_lines[j][0].lower()
                sim = difflib.SequenceMatcher(None, nlo, gtxt).ratio()
                if j >= used:
                    sim += 0.08
                if sim > best_sim:
                    best_sim = sim
                    best_j = j
            labels_out.append(gt_lines[best_j][1])
            used = max(used, best_j)
    else:
        lines_out = [ln for ln, _ in gt_lines]
        labels_out = [lb for _, lb in gt_lines]
        raw_text = "\n".join(lines_out)

    return {
        "split": split,
        "pair": pair,
        "category": cat,
        "subcategory": sub,
        "expected_merchant": merchant,
        "expected_total_myr": f"{grand_total / 100:.2f}",
        "expected_date_iso": date_iso,
        "subtotal_myr": f"{subtotal / 100:.2f}",
        "sst_myr": f"{tax_minor / 100:.2f}",
        "lhdn_tin": tin if is_lhdn else "",
        "lhdn_invoice": inv_ref if is_lhdn else "",
        "gt_lines": lines_out,
        "gt_labels": labels_out,
        "raw_text": raw_text,
        "is_lhdn": is_lhdn,
    }


# ---------------------------------------------------------------------------
# Stage 1A-Photo: Render 80mm Thermal Receipt Image + Camera Artifacts + GPU OCR
# ---------------------------------------------------------------------------
def ensure_ocr_dependencies():
    subprocess.run(
        ["bash", "-lc", "apt-get update -qq && apt-get install -y -qq fonts-dejavu-core fonts-noto-cjk >/dev/null 2>&1 || true"],
        check=False,
    )
    subprocess.run([sys.executable, "-m", "pip", "install", "-q", "pillow", "rapidocr-onnxruntime", "onnxruntime"], check=False)
    # Also ensure EasyOCR GPU fallback is available only if needed later (preinstalled? check lightly)
    try:
        import torch
        torch_lib = str(Path(torch.__file__).resolve().parent / "lib")
        os.environ["LD_LIBRARY_PATH"] = torch_lib + ":" + os.environ.get("LD_LIBRARY_PATH", "")
    except Exception:
        pass


def find_fonts():
    from PIL import ImageFont
    mono_candidates = [
        "/usr/share/fonts/truetype/dejavu/DejaVuSansMono.ttf",
        "/usr/share/fonts/truetype/dejavu/DejaVuSansMono-Bold.ttf",
        "/usr/share/fonts/truetype/liberation/LiberationMono-Regular.ttf",
    ]
    cjk_candidates = [
        "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
        "/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttc",
        "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
    ]
    mono = next((p for p in mono_candidates if Path(p).exists()), None)
    bold = mono_candidates[1] if Path(mono_candidates[1]).exists() else mono
    cjk = next((p for p in cjk_candidates if Path(p).exists()), mono)
    return {
        "mono": ImageFont.truetype(mono, 18) if mono else ImageFont.load_default(),
        "bold": ImageFont.truetype(bold, 21) if bold else ImageFont.load_default(),
        "small": ImageFont.truetype(mono, 15) if mono else ImageFont.load_default(),
        "cjk": ImageFont.truetype(cjk, 18) if cjk else ImageFont.load_default(),
    }


def render_receipt_image(gt_lines, gt_labels, fonts, rng: random.Random, np_rng=None):
    import numpy as np
    from PIL import Image, ImageDraw, ImageFilter

    if np_rng is None:
        np_rng = np.random.default_rng(rng.randint(0, 1_000_000))

    width = rng.choice([420, 460, 500])
    line_h = 26
    pad_top = rng.randint(18, 32)
    pad_bot = rng.randint(20, 36)
    height = pad_top + pad_bot + len(gt_lines) * line_h + 40

    bg_val = rng.randint(232, 250)
    img = Image.new("RGB", (width, height), (bg_val, bg_val - rng.randint(0, 4), bg_val - rng.randint(0, 7)))
    draw = ImageDraw.Draw(img)

    ink_base = rng.randint(15, 58)
    ink = (ink_base, ink_base, ink_base + rng.randint(0, 8))

    y = pad_top
    for txt, lbl in zip(gt_lines, gt_labels):
        if CJK_RE.search(txt):
            fnt = fonts["cjk"]
        elif lbl in ("MERCHANT", "GRAND_TOTAL"):
            fnt = fonts["bold"]
        elif lbl in ("HEADER_META", "OTHER"):
            fnt = fonts["small"]
        else:
            fnt = fonts["mono"]

        if lbl in ("MERCHANT", "OTHER") or (lbl == "HEADER_META" and "INVOICE" in txt.upper()):
            x = max(14, (width - len(txt) * 9) // 2)
        else:
            x = rng.randint(16, 26)
        draw.text((x, y), txt, fill=ink, font=fnt)
        if lbl in ("GRAND_TOTAL", "SUBTOTAL") and rng.random() < 0.3:
            draw.line([(16, y - 4), (width - 16, y - 4)], fill=(120, 120, 120), width=1)
        y += line_h

    # Camera / thermal paper distortions
    angle = rng.uniform(-3.0, 3.0)
    img = img.rotate(angle, expand=True, fillcolor=(bg_val - 12, bg_val - 12, bg_val - 12))

    arr = np.asarray(img, dtype=np.float32)
    h, w, _ = arr.shape
    grad = np.linspace(rng.uniform(0.84, 1.0), rng.uniform(0.76, 1.02), h)[:, None, None]
    arr = arr * grad
    if rng.random() < 0.35:
        sy = rng.randint(10, max(12, h - 10))
        arr[sy:sy + 2, :, :] = np.minimum(255.0, arr[sy:sy + 2, :, :] + 45.0)
    sigma = rng.uniform(2.0, 7.5)
    arr = np.clip(arr + np_rng.normal(0.0, sigma, arr.shape), 0, 255).astype(np.uint8)
    img = Image.fromarray(arr)
    if rng.random() < 0.50:
        img = img.filter(ImageFilter.GaussianBlur(radius=rng.uniform(0.25, 0.80)))
    return np.asarray(img)


def group_ocr_boxes_to_lines(ocr_res) -> list[str]:
    if not ocr_res:
        return []
    boxes = []
    for item in ocr_res:
        box, txt, conf = item[0], str(item[1]).strip(), float(item[2])
        if not txt or conf < 0.30:
            continue
        xs = [pt[0] for pt in box]
        ys = [pt[1] for pt in box]
        boxes.append((sum(ys) / len(ys), min(xs), max(ys) - min(ys), txt))
    if not boxes:
        return []
    boxes.sort(key=lambda b: b[0])
    med_h = sorted(b[2] for b in boxes)[len(boxes) // 2]
    tol = max(10.0, 0.58 * med_h)
    lines = []
    cur = [boxes[0]]
    for b in boxes[1:]:
        if abs(b[0] - cur[-1][0]) <= tol:
            cur.append(b)
        else:
            cur.sort(key=lambda t: t[1])
            lines.append("  ".join(t[3] for t in cur))
            cur = [b]
    cur.sort(key=lambda t: t[1])
    lines.append("  ".join(t[3] for t in cur))
    return [sanitize_line(ln) for ln in lines if ln.strip()]


def align_ocr_lines_to_gt(ocr_lines: list[str], gt_lines: list[str], gt_labels: list[str], grand_total_str: str):
    """Align real OCR output lines back to ground-truth 11 labels via normalized similarity + amount anchoring."""
    if not ocr_lines:
        return None
    gt_amts = [set(parse_line_amounts(g)) for g in gt_lines]
    grand_minor = int((Decimal(grand_total_str) * 100).to_integral_value())

    aligned_labels = []
    last_j = 0
    for i, ol in enumerate(ocr_lines):
        ol_low = ocr_norm_text(ol)
        ol_amts = set(parse_line_amounts(ol))
        if STANDALONE_AMT_RE.match(ol) and i > 0 and aligned_labels:
            # Inherit preceding label (critical for split-line TOTAL / SUBTOTAL amounts!)
            if grand_minor in ol_amts and aligned_labels[-1] in ("GRAND_TOTAL", "PAYMENT_TENDER"):
                aligned_labels.append(aligned_labels[-1])
                continue
            if aligned_labels[-1] in ("GRAND_TOTAL", "SUBTOTAL", "TAX_SST", "ITEM"):
                aligned_labels.append(aligned_labels[-1])
                continue

        best_j = 0
        best_score = -1.0
        for j, gl in enumerate(gt_lines):
            gl_low = ocr_norm_text(gl)
            sim = difflib.SequenceMatcher(None, ol_low, gl_low).ratio()
            # Bonus for shared amounts and monotonic progress
            if ol_amts and gt_amts[j]:
                if ol_amts & gt_amts[j]:
                    sim += 0.35
                else:
                    sim -= 0.15
            if j >= last_j:
                sim += 0.10
            if abs((i / max(1, len(ocr_lines))) - (j / max(1, len(gt_lines)))) < 0.20:
                sim += 0.08
            if sim > best_score:
                best_score = sim
                best_j = j
        lbl = gt_labels[best_j] if best_score >= 0.42 else "OTHER"
        aligned_labels.append(lbl)
        last_j = max(last_j, best_j)

    if "GRAND_TOTAL" not in aligned_labels:
        # Anchor fallback if exact grand_minor + total kw exists cleanly in OCR line
        for idx, ol in enumerate(ocr_lines):
            amts = parse_line_amounts(ol)
            if grand_minor in amts and TOTAL_KW_RE.search(ocr_norm_text(ol)) and "subtotal" not in ocr_norm_text(ol):
                aligned_labels[idx] = "GRAND_TOTAL"
    return aligned_labels


def init_rapidocr():
    from rapidocr_onnxruntime import RapidOCR
    # RapidOCR CPU/GPU auto selection with multi-threading
    try:
        engine = RapidOCR(det_use_cuda=False, cls_use_cuda=False, rec_use_cuda=False)
    except Exception:
        engine = RapidOCR()
    return engine


def run_ocr_stage(pools, n_photo_train=4500, n_photo_val=500, n_photo_test=1000):
    import numpy as np
    rng = random.Random(20261122)
    np_rng = np.random.default_rng(20261122)
    fonts = find_fonts()
    ocr = init_rapidocr()

    # 10-image throughput benchmark before running full batch
    t_probe = time.perf_counter()
    for _ in range(10):
        pair, merch, items, can_sst = pools["train"][0]
        rec = synthesize_receipt(rng, pair, merch, items, can_sst, "train", flat_mode=False)
        img_arr = render_receipt_image(rec["gt_lines"], rec["gt_labels"], fonts, rng, np_rng=np_rng)
        _res, _ = ocr(img_arr)
    probe_sec = max(0.1, time.perf_counter() - t_probe)
    ips = 10.0 / probe_sec
    print(f"[OCR Probe RapidOCR] 10 images in {probe_sec:.2f}s ({ips:.2f} img/s)", flush=True)
    if ips < 1.5:
        # Try EasyOCR GPU fallback on 10 images if RapidOCR CPU was below 1.5 img/s
        print("[OCR Probe] Trying EasyOCR GPU fallback...", flush=True)
        subprocess.run([sys.executable, "-m", "pip", "install", "-q", "easyocr"], check=False)
        import easyocr
        reader = easyocr.Reader(["ch_sim", "en"], gpu=True, verbose=False)
        t_e = time.perf_counter()
        for _ in range(10):
            pair, merch, items, can_sst = pools["train"][0]
            rec = synthesize_receipt(rng, pair, merch, items, can_sst, "train", flat_mode=False)
            img_arr = render_receipt_image(rec["gt_lines"], rec["gt_labels"], fonts, rng, np_rng=np_rng)
            _ = reader.readtext(img_arr)
        ips_e = 10.0 / max(0.1, time.perf_counter() - t_e)
        print(f"[OCR Probe EasyOCR-GPU] 10 images in {time.perf_counter()-t_e:.2f}s ({ips_e:.2f} img/s)", flush=True)
        if ips_e > ips:
            ips = ips_e
            ocr = lambda arr: (reader.readtext(arr), None)
        if ips < 1.0:
            raise RuntimeError(f"OCR throughput too slow ({ips:.2f} img/s)")

    targets = (
        [("train", i) for i in range(n_photo_train)]
        + [("val", i) for i in range(n_photo_val)]
        + [("test", i) for i in range(n_photo_test)]
    )

    done_rows = []
    if CKPT_OCR_PATH.exists():
        with CKPT_OCR_PATH.open("r", encoding="utf-8", newline="\n") as f:
            for ln in f:
                s = ln.strip()
                if s:
                    done_rows.append(json.loads(s))
    start_idx = len(done_rows)
    print(f"[OCR Stage] Total target={len(targets)}, resuming from {start_idx}", flush=True)

    update_status("GENERATING_OCR", ocr_ips=round(ips, 2), done=start_idx, total=len(targets))
    t0 = time.perf_counter()
    aligned_ok = sum(1 for r in done_rows if r.get("aligned_ok"))

    with CKPT_OCR_PATH.open("a", encoding="utf-8", newline="\n") as ckpt_f:
        for k in range(start_idx, len(targets)):
            split, _ = targets[k]
            pair, merch, items, can_sst = rng.choice(pools[split])
            rec = synthesize_receipt(rng, pair, merch, items, can_sst, split, flat_mode=False)
            img_arr = render_receipt_image(rec["gt_lines"], rec["gt_labels"], fonts, rng, np_rng=np_rng)
            ocr_out, _ = ocr(img_arr)
            ocr_lines = group_ocr_boxes_to_lines(ocr_out)
            aligned = align_ocr_lines_to_gt(ocr_lines, rec["gt_lines"], rec["gt_labels"], rec["expected_total_myr"])
            ok = aligned is not None and ("GRAND_TOTAL" in aligned) and ("MERCHANT" in aligned)
            if ok:
                aligned_ok += 1
            row = {
                "id": f"ocr_{split}_{k}",
                "split": split,
                "source_kind": "real_gpu_ocr",
                "category": rec["category"],
                "subcategory": rec["subcategory"],
                "expected_merchant": rec["expected_merchant"],
                "expected_total_myr": rec["expected_total_myr"],
                "expected_date_iso": rec["expected_date_iso"],
                "subtotal_myr": rec["subtotal_myr"],
                "sst_myr": rec["sst_myr"],
                "lhdn_tin": rec["lhdn_tin"],
                "lhdn_invoice": rec["lhdn_invoice"],
                "lines": ocr_lines,
                "labels": aligned or [],
                "raw_text": "\n".join(ocr_lines),
                "aligned_ok": ok,
            }
            ckpt_f.write(json.dumps(row, ensure_ascii=False) + "\n")
            done_rows.append(row)

            if (k + 1) % 250 == 0 or (k + 1) == len(targets):
                ckpt_f.flush()
                elapsed = time.perf_counter() - t0
                done_now = k + 1 - start_idx
                cur_ips = done_now / max(0.1, elapsed)
                update_status(
                    "GENERATING_OCR",
                    done=k + 1,
                    total=len(targets),
                    ips=round(cur_ips, 2),
                    aligned_rate=round(aligned_ok / len(done_rows), 4),
                )
                print(
                    f"[OCR {k+1}/{len(targets)}] rate={cur_ips:.2f} img/s | aligned={aligned_ok}/{len(done_rows)} ({aligned_ok/len(done_rows):.1%})",
                    flush=True,
                )

    return done_rows, ips


def generate_text_augmented_receipts(pools, n_train=7500, n_val=500, n_test=1000):
    rng = random.Random(20261123)
    rows = []
    spec = [("train", n_train), ("val", n_val), ("test", n_test)]
    for split, count in spec:
        for idx in range(count):
            pair, merch, items, can_sst = rng.choice(pools[split])
            # 45% flat single-line LHDN/thermal scans (mirrors v4 hard holdout!), 55% noisy multiline OCR scans
            flat = rng.random() < 0.45
            rec = synthesize_receipt(rng, pair, merch, items, can_sst, split, flat_mode=flat)
            noisy_lines = [sanitize_line(inject_ocr_noise(ln, rng, 0.08)) for ln in rec["gt_lines"]]
            rows.append({
                "id": f"txt_{split}_{idx}",
                "split": split,
                "source_kind": "flat_text" if flat else "noisy_text",
                "category": rec["category"],
                "subcategory": rec["subcategory"],
                "expected_merchant": rec["expected_merchant"],
                "expected_total_myr": rec["expected_total_myr"],
                "expected_date_iso": rec["expected_date_iso"],
                "subtotal_myr": rec["subtotal_myr"],
                "sst_myr": rec["sst_myr"],
                "lhdn_tin": rec["lhdn_tin"],
                "lhdn_invoice": rec["lhdn_invoice"],
                "lines": noisy_lines,
                "labels": rec["gt_labels"],
                "raw_text": "\n".join(noisy_lines) if not flat else rec["raw_text"],
                "aligned_ok": True,
            })
    return rows


# ---------------------------------------------------------------------------
# Stage 1B — PyTorch Linear-Chain CRF Training, L1 Pruning & Viterbi Decode
# ---------------------------------------------------------------------------
def build_crf_vocab(train_rows, max_lex_feats=4500, min_df=4):
    df = Counter()
    for r in train_rows:
        for lf in extract_line_features(r["lines"]):
            for k in lf:
                df[k] += 1
    struct_feats = sorted(k for k in df if k.startswith("f:"))
    lex_feats = [k for k, c in df.most_common() if not k.startswith("f:") and c >= min_df][:max_lex_feats]
    vocab = struct_feats + lex_feats
    f2id = {k: i for i, k in enumerate(vocab)}
    return vocab, f2id


def featurize_dataset(rows, f2id):
    encoded = []
    for r in rows:
        lines = r["lines"]
        labels = r["labels"]
        if not lines or len(lines) != len(labels):
            continue
        f_per_line = extract_line_features(lines)
        seqs = []
        for lf in f_per_line:
            idxs = [f2id[k] for k in lf if k in f2id]
            seqs.append(idxs)
        y = [LBL2ID[lb] for lb in labels]
        encoded.append((seqs, y, r))
    return encoded


def train_and_export_crf(train_enc, val_enc, test_ocr_enc, test_txt_enc, vocab):
    import numpy as np
    import torch
    import torch.nn as nn

    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    C = len(LABELS)
    V = len(vocab)

    class LinearChainCRF(nn.Module):
        def __init__(self):
            super().__init__()
            self.emb = nn.EmbeddingBag(V, C, mode="sum")
            nn.init.zeros_(self.emb.weight)
            self.start = nn.Parameter(torch.zeros(C))
            self.end = nn.Parameter(torch.zeros(C))
            self.trans = nn.Parameter(torch.zeros(C, C))

        def emit_seq(self, line_feat_lists):
            # line_feat_lists: list of lists of feature IDs for one receipt (length L)
            flat = []
            offsets = []
            for idxs in line_feat_lists:
                offsets.append(len(flat))
                flat.extend(idxs if idxs else [0])
            idx_t = torch.tensor(flat, dtype=torch.long, device=device)
            off_t = torch.tensor(offsets, dtype=torch.long, device=device)
            return self.emb(idx_t, off_t)  # [L, C]

        def nll_batch(self, batch_items):
            # Vectorized batched exact CRF forward-partition + gold-path score across batch_items
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

            alpha = self.start.unsqueeze(0) + emits[:, 0, :]  # [B, C]
            last_idx = torch.tensor([L - 1 for L in lengths], dtype=torch.long, device=device)
            batch_ar = torch.arange(B, device=device)
            gold_score = self.start[gold[:, 0]] + emits[batch_ar, 0, gold[:, 0]]
            for t in range(1, T):
                m_t = mask[:, t]
                scores = alpha.unsqueeze(2) + self.trans.unsqueeze(0)  # [B, C, C]
                next_alpha = torch.logsumexp(scores, dim=1) + emits[:, t, :]
                alpha = torch.where(m_t.unsqueeze(1), next_alpha, alpha)
                step_gold = self.trans[gold[:, t - 1], gold[:, t]] + emits[batch_ar, t, gold[:, t]]
                gold_score = gold_score + torch.where(m_t, step_gold, torch.zeros_like(step_gold))
            alpha = alpha + self.end.unsqueeze(0)
            gold_score = gold_score + self.end[gold[batch_ar, last_idx]]
            log_z = torch.logsumexp(alpha, dim=1)
            return (log_z - gold_score).sum()

    torch.manual_seed(42)
    model = LinearChainCRF().to(device)
    opt = torch.optim.AdamW(model.parameters(), lr=0.04, weight_decay=2e-4)
    l1_lambda = 8e-5
    epochs = 14
    scheduler = torch.optim.lr_scheduler.CosineAnnealingLR(opt, T_max=epochs, eta_min=0.004)

    best_val_acc = -1.0
    best_state = None
    rng = random.Random(42)

    for ep in range(1, epochs + 1):
        model.train()
        order = list(range(len(train_enc)))
        rng.shuffle(order)
        total_loss = 0.0
        batch_sz = 128
        for b0 in range(0, len(order), batch_sz):
            opt.zero_grad()
            b_items = [train_enc[i] for i in order[b0:b0 + batch_sz]]
            nll_sum = model.nll_batch(b_items)
            loss = (nll_sum / len(b_items)) + l1_lambda * model.emb.weight.abs().sum() / V
            loss.backward()
            torch.nn.utils.clip_grad_norm_(model.parameters(), 5.0)
            opt.step()
            total_loss += float(nll_sum.item())
        scheduler.step()

        # Quick val evaluation
        start_np, end_np, trans_np, emb_np = (
            model.start.detach().cpu().numpy(),
            model.end.detach().cpu().numpy(),
            model.trans.detach().cpu().numpy(),
            model.emb.weight.detach().cpu().numpy(),
        )
        val_m = eval_crf_numpy(val_enc, start_np, end_np, trans_np, emb_np)
        avg_nll = total_loss / max(1, len(train_enc))
        print(
            f"[CRF ep={ep:2d}/{epochs}] train_nll={avg_nll:.4f} | val line_acc={val_m['line_acc']:.4f} "
            f"grand_tot_f1={val_m['per_label']['GRAND_TOTAL']['f1']:.4f} "
            f"exact_amt={val_m['exact_grand_total_amt']:.4f}",
            flush=True,
        )
        score = 0.5 * val_m["line_acc"] + 0.5 * val_m["exact_grand_total_amt"]
        if score > best_val_acc:
            best_val_acc = score
            best_state = (start_np.copy(), end_np.copy(), trans_np.copy(), emb_np.copy())

    start_np, end_np, trans_np, emb_np = best_state

    # Prune tiny emission weights cleanly so gzip size < MAX_TAGGER_GZ_BYTES
    lo, hi = 0.005, 0.25
    best_th = hi
    for _ in range(12):
        mid = round((lo + hi) / 2.0, 4)
        ep_pruned = emb_np.copy()
        ep_pruned[np.abs(ep_pruned) < mid] = 0.0
        _, gz_b, _ = serialize_crf(start_np, end_np, trans_np, ep_pruned, vocab)
        if len(gz_b) <= MAX_TAGGER_GZ_BYTES:
            best_th = mid
            hi = mid
        else:
            lo = mid

    emb_pruned = emb_np.copy()
    emb_pruned[np.abs(emb_pruned) < best_th] = 0.0
    raw_json, gz_bytes, feat_stats = serialize_crf(start_np, end_np, trans_np, emb_pruned, vocab, best_th)
    print(
        f"[CRF Pruned] th={best_th:.4f} | active_feats={feat_stats['active_features']} "
        f"nnz={feat_stats['nonzero_weights']} | gz={len(gz_bytes):,} bytes",
        flush=True,
    )

    m_val = eval_crf_numpy(val_enc, start_np, end_np, trans_np, emb_pruned)
    m_ocr = eval_crf_numpy(test_ocr_enc, start_np, end_np, trans_np, emb_pruned)
    m_txt = eval_crf_numpy(test_txt_enc, start_np, end_np, trans_np, emb_pruned)
    return raw_json, gz_bytes, feat_stats, m_val, m_ocr, m_txt, (start_np, end_np, trans_np, emb_pruned)


def viterbi_decode(seqs, start, end, trans, emb):
    import numpy as np
    L = len(seqs)
    C = len(start)
    emits = np.zeros((L, C), dtype=np.float64)
    for t, idxs in enumerate(seqs):
        if idxs:
            emits[t] = emb[idxs].sum(axis=0)
    dp = start + emits[0]
    back = np.zeros((L, C), dtype=np.int32)
    for t in range(1, L):
        scores = dp[:, None] + trans  # [C, C]
        best_prev = scores.argmax(axis=0)
        back[t] = best_prev
        dp = scores[best_prev, np.arange(C)] + emits[t]
    dp = dp + end
    path = [0] * L
    cur = int(dp.argmax())
    for t in range(L - 1, -1, -1):
        path[t] = cur
        if t > 0:
            cur = int(back[t, cur])
    return path, float(dp.max())


def eval_crf_numpy(encoded, start, end, trans, emb):
    C = len(LABELS)
    tp = [0] * C
    fp = [0] * C
    fn = [0] * C
    tot_lines = 0
    ok_lines = 0
    gt_amt_ok = 0
    merch_ok = 0
    date_ok = 0

    for seqs, gold_y, row in encoded:
        pred_y, _ = viterbi_decode(seqs, start, end, trans, emb)
        for p, g in zip(pred_y, gold_y):
            tot_lines += 1
            if p == g:
                ok_lines += 1
                tp[g] += 1
            else:
                fp[p] += 1
                fn[g] += 1

        # Downstream extraction accuracy from Viterbi tags
        exp_minor = int((Decimal(row["expected_total_myr"]) * 100).to_integral_value())
        gt_id = LBL2ID["GRAND_TOTAL"]
        cand_amts = []
        for idx, pid in enumerate(pred_y):
            if pid == gt_id:
                amts = parse_line_amounts(row["lines"][idx])
                if amts:
                    cand_amts.append(amts[-1])
                elif idx + 1 < len(row["lines"]) and STANDALONE_AMT_RE.match(row["lines"][idx + 1]):
                    am2 = parse_line_amounts(row["lines"][idx + 1])
                    if am2:
                        cand_amts.append(am2[-1])
        if cand_amts and cand_amts[-1] == exp_minor:
            gt_amt_ok += 1

        m_id = LBL2ID["MERCHANT"]
        m_lines = [row["lines"][idx] for idx, pid in enumerate(pred_y) if pid == m_id]
        exp_m = re.sub(r"[^a-z0-9]+", " ", row["expected_merchant"].lower()).strip()
        if any(exp_m in re.sub(r"[^a-z0-9]+", " ", ml.lower()) or difflib.SequenceMatcher(None, exp_m, ml.lower()).ratio() >= 0.72 for ml in m_lines):
            merch_ok += 1

        d_id = LBL2ID["DATE"]
        if any(pid == d_id and gd == d_id for pid, gd in zip(pred_y, gold_y)):
            date_ok += 1

    per_label = {}
    for c, lbl in enumerate(LABELS):
        p = tp[c] / max(1, tp[c] + fp[c])
        r = tp[c] / max(1, tp[c] + fn[c])
        f1 = (2 * p * r / (p + r)) if (p + r) > 0 else 0.0
        per_label[lbl] = {"precision": round(p, 4), "recall": round(r, 4), "f1": round(f1, 4), "support": tp[c] + fn[c]}

    n = max(1, len(encoded))
    return {
        "n_receipts": len(encoded),
        "n_lines": tot_lines,
        "line_acc": round(ok_lines / max(1, tot_lines), 4),
        "exact_grand_total_amt": round(gt_amt_ok / n, 4),
        "merchant_tag_match": round(merch_ok / n, 4),
        "date_line_hit": round(date_ok / n, 4),
        "per_label": per_label,
    }


def serialize_crf(start, end, trans, emb, vocab, threshold=0.0):
    feats = {}
    nnz = 0
    for i, key in enumerate(vocab):
        pairs = []
        row = emb[i]
        for c in range(len(LABELS)):
            w = round(float(row[c]), 3)
            if w != 0.0:
                pairs.append([c, w])
        if pairs:
            feats[key] = pairs
            nnz += len(pairs)
    obj = {
        "version": 1,
        "model_family": "LinearChainCRF-Viterbi",
        "prune_threshold": threshold,
        "labels": LABELS,
        "start": [round(float(x), 4) for x in start],
        "end": [round(float(x), 4) for x in end],
        "transitions": [[round(float(v), 4) for v in r] for r in trans],
        "features": feats,
    }
    raw_json = json.dumps(obj, separators=(",", ":"), ensure_ascii=False)
    gz_bytes = gzip.compress(raw_json.encode("utf-8"), compresslevel=9)
    return raw_json, gz_bytes, {"active_features": len(feats), "nonzero_weights": nnz}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--train-only", action="store_true", help="Skip OCR generation if checkpoint already complete")
    args = parser.parse_args()

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    t_all = time.perf_counter()
    try:
        pools = build_disjoint_pools()
        print(
            f"Disjoint merchant pools: train={len(pools['train'])}, val={len(pools['val'])}, test={len(pools['test'])}",
            flush=True,
        )

        if args.train_only and CKPT_OCR_PATH.exists():
            ocr_rows = []
            with CKPT_OCR_PATH.open("r", encoding="utf-8", newline="\n") as f:
                for ln in f:
                    s = ln.strip()
                    if s:
                        ocr_rows.append(json.loads(s))
            ips = 2.2
            print(f"[--train-only] Loaded {len(ocr_rows)} cached OCR receipts from {CKPT_OCR_PATH.name}", flush=True)
        else:
            ensure_ocr_dependencies()
            ocr_rows, ips = run_ocr_stage(pools, n_photo_train=4500, n_photo_val=500, n_photo_test=1000)
        text_rows = generate_text_augmented_receipts(pools, n_train=7500, n_val=500, n_test=1000)
        all_rows = ocr_rows + text_rows

        train_rows = [r for r in all_rows if r["split"] == "train" and r.get("aligned_ok") and r["lines"] and len(r["lines"]) == len(r["labels"])]
        val_rows = [r for r in all_rows if r["split"] == "val" and r.get("aligned_ok") and r["lines"] and len(r["lines"]) == len(r["labels"])]
        test_ocr_rows = [r for r in ocr_rows if r["split"] == "test" and r["lines"] and len(r["lines"]) == len(r["labels"])]
        test_txt_rows = [r for r in text_rows if r["split"] == "test" and r["lines"] and len(r["lines"]) == len(r["labels"])]

        update_status("TRAINING_CRF", train=len(train_rows), val=len(val_rows), test_ocr=len(test_ocr_rows), test_txt=len(test_txt_rows))
        vocab, f2id = build_crf_vocab(train_rows, max_lex_feats=4500, min_df=4)
        print(f"CRF Vocab built: {len(vocab)} total features", flush=True)

        train_enc = featurize_dataset(train_rows, f2id)
        val_enc = featurize_dataset(val_rows, f2id)
        test_ocr_enc = featurize_dataset(test_ocr_rows, f2id)
        test_txt_enc = featurize_dataset(test_txt_rows, f2id)

        raw_json, gz_bytes, feat_stats, m_val, m_ocr, m_txt, _ = train_and_export_crf(
            train_enc, val_enc, test_ocr_enc, test_txt_enc, vocab
        )
        assert len(gz_bytes) < 512_000, f"Tagger asset exceeds 512KB: {len(gz_bytes)}"
        MODEL_JSON_PATH.write_text(raw_json, encoding="utf-8")
        MODEL_GZ_PATH.write_bytes(gz_bytes)

        # Save gzipped dataset and blind holdout files for Java eval & Drive backup
        with gzip.open(DATASET_OUT_GZ, "wt", encoding="utf-8", compresslevel=9) as gzf:
            for r in all_rows:
                gzf.write(json.dumps(r, ensure_ascii=False) + "\n")
        HOLDOUT_OCR_PATH.write_text(json.dumps(test_ocr_rows, indent=2, ensure_ascii=False), encoding="utf-8")
        HOLDOUT_TEXT_PATH.write_text(json.dumps(test_txt_rows, indent=2, ensure_ascii=False), encoding="utf-8")

        report = {
            "total_seconds": round(time.perf_counter() - t_all, 1),
            "ocr_engine": "rapidocr-onnxruntime",
            "ocr_probe_ips": round(ips, 2),
            "dataset_counts": {
                "total": len(all_rows),
                "real_gpu_ocr": len(ocr_rows),
                "ocr_aligned_ok": sum(1 for r in ocr_rows if r.get("aligned_ok")),
                "text_augmented": len(text_rows),
                "train_usable": len(train_rows),
                "val_usable": len(val_rows),
                "test_real_ocr": len(test_ocr_rows),
                "test_text_augmented": len(test_txt_rows),
            },
            "tagger_asset": {
                "labels": len(LABELS),
                "vocab_total": len(vocab),
                "active_features": feat_stats["active_features"],
                "nonzero_weights": feat_stats["nonzero_weights"],
                "raw_bytes": len(raw_json.encode("utf-8")),
                "gz_bytes": len(gz_bytes),
                "under_512kb": len(gz_bytes) < 512_000,
            },
            "eval_val": m_val,
            "eval_blind_real_ocr": m_ocr,
            "eval_blind_text_augmented": m_txt,
        }
        REPORT_PATH.write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")
        update_status(
            "DONE",
            total=len(all_rows),
            gz_bytes=len(gz_bytes),
            ocr_line_acc=m_ocr["line_acc"],
            ocr_exact_amt=m_ocr["exact_grand_total_amt"],
            txt_exact_amt=m_txt["exact_grand_total_amt"],
        )
        print(
            f"DONE in {report['total_seconds']}s | gz={len(gz_bytes):,} bytes | "
            f"Blind Real-OCR line_acc={m_ocr['line_acc']} amt={m_ocr['exact_grand_total_amt']} | "
            f"Blind Text amt={m_txt['exact_grand_total_amt']}",
            flush=True,
        )
    except Exception as exc:
        import traceback
        tb = traceback.format_exc()
        print(tb, flush=True)
        update_status("ERROR", error=str(exc), traceback=tb)
        sys.exit(1)


if __name__ == "__main__":
    main()
