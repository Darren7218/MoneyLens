#!/usr/bin/env python3
"""MoneyLens Foundry v4 — 25,000+ Synthetic Malaysian Transaction Distillation & Calibrated <2MB Student."""
import argparse
import gzip
import json
import math
import os
import random
import re
import subprocess
import sys
import time
from collections import Counter, defaultdict
from pathlib import Path

OUT_DIR = Path("/content/outputs_v4")
SEED_V3_PATH = Path("/content/seed_v3.jsonl")
HOLDOUT_V3_PATH = Path("/content/holdout_v3.json")

STATUS_PATH = OUT_DIR / "foundry_v4_status.json"
CHECKPOINT_PATH = OUT_DIR / "gen_v4_checkpoint.jsonl"
PROGRESS_PATH = OUT_DIR / "gen_v4_progress.json"
DATASET_PATH = OUT_DIR / "dataset_malaysian_v4.jsonl"
REPORT_PATH = OUT_DIR / "foundry_v4_report.json"
HOLDOUT_PATH = OUT_DIR / "holdout_v4_details.json"
LEXICON_PATH = OUT_DIR / "merchant_lexicon_v4.json"
MODEL_HOLDOUT_JSON = OUT_DIR / "moneylens_classifier_holdout.json"
MODEL_HOLDOUT_GZ = OUT_DIR / "moneylens_classifier_holdout.json.gz"
MODEL_FULL_JSON = OUT_DIR / "moneylens_classifier.json"
MODEL_FULL_GZ = OUT_DIR / "moneylens_classifier.json.gz"

MAX_GZ_BYTES = 1_880_000  # Strict safety cap well below 2.0 MiB (2,097,152 bytes)

CANONICAL_TAXONOMY = {
    "Food & drinks": [
        "Coffee, Tea & Drinks",
        "Fast Food",
        "Food Delivery (GrabFood / Foodpanda)",
        "Groceries & Snacks",
        "Hawker & Mamak",
        "Restaurant & Cafe",
    ],
    "Groceries": [
        "Convenience Store (99 Speedmart / 7-Eleven / FamilyMart)",
        "Household & Cleaning Supplies",
        "Supermarket & Hypermarket",
        "Wet Market & Fresh Produce",
    ],
    "Transport": [
        "Car Loan Installment",
        "E-Hailing (Grab / AirAsia Ride)",
        "Parking",
        "Petrol & EV Charging",
        "Public Transit (MRT / LRT / Bus)",
        "Road Tax & Motor Insurance",
        "Toll (TNG / RFID / PayDirect)",
        "Vehicle Maintenance & Service",
    ],
    "Bills & utilities": [
        "Condo Maintenance & Sinking Fund",
        "Electricity (TNB)",
        "Home Internet & WiFi (Unifi / Time)",
        "Mobile Postpaid & Prepaid",
        "Streaming & App Subscriptions",
        "Water & Sewerage (Air Selangor / IWK)",
    ],
    "Loans, BNPL & Credit": [
        "BNPL (SPayLater / GrabPayLater / Atome)",
        "Credit Card Bill",
        "Hire Purchase / Installment",
        "PTPTN Study Loan",
        "Personal Loan",
    ],
    "Housing & property": [
        "Cukai Pintu & Cukai Tanah",
        "Furniture & Appliances",
        "Home Loan / Mortgage",
        "Home Repair & Maintenance",
        "Rent",
    ],
    "Shopping": [
        "Beauty, Skincare & Grooming",
        "Clothing, Bags & Footwear",
        "Electronics & Gadgets",
        "Home & Living",
        "Online Marketplace (Shopee / Lazada / TikTok)",
    ],
    "Health & medical": [
        "Clinic & GP",
        "Dental & Optical",
        "Full Medical Checkup (LHDN)",
        "Gym, Sports & Fitness",
        "Hospital & Specialist",
        "Pharmacy & Medicine",
    ],
    "Insurance & Takaful": [
        "Education Insurance",
        "Life Insurance / Takaful",
        "Medical & Health Card",
        "Personal Accident",
    ],
    "Education": [
        "Books & Stationery",
        "Higher Education & Tuition",
        "School & Childcare Fees",
        "Upskilling & Certification",
    ],
    "Family & gifts": [
        "Charity & Donations",
        "Childcare & Family Support",
        "Festive (Angpao / Duit Raya) & Gifts",
        "Parents Allowance",
        "Zakat & Fitrah",
    ],
    "Entertainment & travel": [
        "Flights & Transport",
        "Games & Hobbies",
        "Hotel & Holiday Stay",
        "Movies, Concerts & Events",
    ],
    "Investments & savings": [
        "ASB / Tabung Haji",
        "EPF Voluntary (i-Saraan)",
        "PRS (Private Retirement Scheme)",
        "SSPN Savings",
        "Stocks, ETF & Crypto",
    ],
    "Taxes & government": [
        "Government Service & Stamp Duty",
        "JPJ / Renewals / Summons",
        "LHDN Income Tax / PCB",
    ],
    "Fees & charges": [
        "Bank & Transfer Fee",
        "Card Annual Fee",
        "Foreign Currency Fee",
        "Late Payment Fee",
    ],
}

MERCHANT_SEEDS_2026 = {
    "Food & drinks || Coffee, Tea & Drinks": [
        "ZUS Coffee", "CHAGEE Malaysia", "Beutea", "Kenangan Coffee", "Luckin Coffee MY",
        "Gigi Coffee", "HWC Coffee", "Bask Bear Coffee", "Mixue Malaysia", "Ai-Cha",
        "Richiamo Coffee", "Koppiku", "The Coffee Bean & Tea Leaf", "Starbucks MY",
        "Tealive", "OldTown White Coffee", "Niko Neko Matcha", "% Arabica KL",
    ],
    "Food & drinks || Fast Food": [
        "Jollibee Malaysia", "Richeese Factory MY", "Shake Shack TRX", "Wingstop MY",
        "KyoChon 1991", "4Fingers Crispy Chicken", "Marrybrown", "Woodfire Burger",
        "myBurgerLab", "KGB Killer Gourmet Burgers", "McDonald's Malaysia", "KFC Malaysia",
        "A&W Malaysia", "Domino's Pizza MY", "Texas Chicken MY", "SugarBun Sarawak",
    ],
    "Food & drinks || Food Delivery (GrabFood / Foodpanda)": [
        "GrabFood", "foodpanda Malaysia", "ShopeeFood", "Beep Delivery", "AirAsia Food",
        "MisiRakyat Food", "Lalamove Food Delivery", "Bungkusit Food", "Halo Express Delivery",
        "McDelivery Malaysia", "KFC Delivery", "Domino's Delivery", "Pizza Hut Delivery",
    ],
    "Food & drinks || Groceries & Snacks": [
        "Beryl's Chocolate", "Famous Amos MY", "Auntie Anne's", "Inside Scoop",
        "Christine's Bakery", "Lavender Bakery", "RT Pastry", "Mix.com.my",
        "Garrett Popcorn", "Royce' Chocolate", "Komugi Bakery", "llaollao MY",
        "Chateraise Patisserie", "Rotiboy Bakeshoppe", "J.CO Donuts", "Dunkin' Malaysia",
    ],
    "Food & drinks || Hawker & Mamak": [
        "Oriental Kopi", "Thong Kee Cafe", "Hock Kee Kopitiam", "Nasi Kandar Pelita",
        "Q Bistro", "Restoran Yut Kee", "Kafe Ali Muthu & Ah Hock", "Madam Kwan's",
        "Village Park Nasi Lemak", "Restoran Kayu", "Hameediyah Briyani", "Nasi Kandar Line Clear",
        "Uncle Soon Fried Rice", "Kluang RailCoffee", "Murni Discovery", "Warung Kak Ina",
    ],
    "Food & drinks || Restaurant & Cafe": [
        "HaiDiLao Hot Pot MY", "Sushiro Malaysia", "Suki-Ya", "Kenny Hills Bakers",
        "Serai Restaurant", "Yakiniku Like", "Antipodean Cafe", "Nanyang Cafe",
        "Dookki Topokki", "Nippori Bistro", "Two Sons Bistro", "Bar.B.Q Plaza",
        "Din Tai Fung MY", "PappaRich", "The Chicken Rice Shop", "Genki Sushi MY",
    ],
    "Groceries || Convenience Store (99 Speedmart / 7-Eleven / FamilyMart)": [
        "99 Speedmart", "FamilyMart Malaysia", "CU Mart Malaysia", "emart24 Malaysia",
        "BilaBila Mart", "7-Eleven 7-CAFé", "KK Super Mart", "myNEWS.com",
        "Mix Store Convenience", "Ecolite Mini Mart", "Kedai Mesra Petronas", "Shell Select",
        "Happy Mart", "Kedai Runcit Pak Mat",
    ],
    "Groceries || Household & Cleaning Supplies": [
        "MR.DIY", "Ninso", "Eco-Shop Malaysia", "Daiso Japan MY", "Dobiqueen Coin Laundry",
        "CleanPro Express Dobi", "SC Johnson Outlet", "Dettol Care Store",
        "Top Clean Supplies", "Kedai Dobi Layan Diri", "WashingPro Laundromat",
        "SuperClean Hardware & Soap",
    ],
    "Groceries || Supermarket & Hypermarket": [
        "Jaya Grocer", "Village Grocer", "Lotus's Malaysia", "Qra Foods",
        "Ben's Independent Grocer", "Mercato Pavilion", "ST Rosyam Mart", "AEON BiG",
        "Mydin Hypermarket", "NSK Trade City", "TF Value-Mart", "HeroMarket",
        "Econsave Cash & Carry", "Giant Hypermarket", "Everrise Sarawak", "Servay Sabah",
        "Don Don Donki MY", "Checkers Hypermarket",
    ],
    "Groceries || Wet Market & Fresh Produce": [
        "Pasar Besar TTDI", "Pasar Chow Kit Fresh", "MyFishman", "Segi Fresh Market",
        "Pasar Borong Selangor", "Pasar Pagi Puchong", "Kepong Wet Market",
        "Pasar Awam Taman Connaught", "Sin Chong Hing Seafood", "Pasar Basah Kajang",
        "Borong Sayur Segar", "Pasar Tani Putrajaya",
    ],
    "Transport || Car Loan Installment": [
        "Maybank Hire Purchase-i", "Public Bank Auto Finance", "Hong Leong Auto Loan",
        "RHB Vehicle Financing", "CIMB Auto Finance", "AmBank Auto Financing",
        "Bank Islam Vehicle Financing-i", "Toyota Capital Malaysia", "AFFIN Auto Finance",
        "BSN Sewa Beli Kenderaan",
    ],
    "Transport || E-Hailing (Grab / AirAsia Ride)": [
        "GrabCar", "inDrive Malaysia", "Bolt Ride Malaysia", "AirAsia MOVE Ride",
        "Maxim E-Hailing", "MyCar E-Hailing", "Lalamove Express", "GrabShare KL",
        "Mula Ride Penang", "Kumpool Van On-Demand",
    ],
    "Transport || Parking": [
        "FlexiParking", "JomParking", "Setel Parking", "TNG eWallet Parking",
        "Suria KLCC Autopay", "Pavilion KL Parking", "Sunway Pyramid Autopay",
        "MBPJ Smart Parking", "Smart Selangor Parking", "Mid Valley Megamall Parking",
        "1 Utama Parking Autopay", "TRX Mall Valet & Parking",
    ],
    "Transport || Petrol & EV Charging": [
        "Gentari EV Charging", "JomCharge", "chargEV", "DC Handal", "ChargeSini",
        "Shell Recharge", "Setel Fuel Petronas", "Tesla Supercharger MY", "TNB Electron",
        "EVConnection", "Petron Miles", "Caltex Journey", "BHPetrol Infiniti",
        "Petronas Mesra Fuel", "Shell Station MY",
    ],
    "Transport || Public Transit (MRT / LRT / Bus)": [
        "RapidKL My50 Pass", "Prasarana MRT", "KLIA Ekspres ERL", "KLIA Transit",
        "KTMB ETS Ticket", "KTMB Komuter", "MyRapid Bus", "Rapid Penang",
        "LRT Kelana Jaya Line", "Aerobus KLIA2", "Causeway Link JB", "Rapid On-Demand",
    ],
    "Transport || Road Tax & Motor Insurance": [
        "BJAK Motor Takaful", "MyEG Road Tax Renewal", "PolicyStreet Auto Insurance",
        "Etiqa Motor Takaful", "Allianz Road Warrior", "Takaful Ikhlas Motor",
        "Zurich General Takaful", "Pos Malaysia Road Tax", "Kurnia Insurans Motor",
        "Qoala Malaysia Auto",
    ],
    "Transport || Toll (TNG / RFID / PayDirect)": [
        "Touch 'n Go RFID", "TNG PayDirect PLUS", "DUKE Highway Toll", "LDP Expressway Toll",
        "MEX Highway Toll", "SUKE Expressway", "NKVE Toll Plaza", "DASH Highway",
        "AKLEH Toll", "KESAS Highway", "SPRINT Expressway", "NPE Toll", "WCE Expressway",
    ],
    "Transport || Vehicle Maintenance & Service": [
        "Perodua Service Centre", "Proton 3S Centre", "Bateriku.com", "Lim Tayar Workshop",
        "Continental Tyre Auto", "Carro Workshop", "Carsome Service Center",
        "Honda Malaysia Service", "AutoDetailer Studio", "Pusat Servis Tayar Seng",
        "CarWash Pro Detailing", "Bengkel Kereta Ah Seng",
    ],
    "Bills & utilities || Condo Maintenance & Sinking Fund": [
        "JMB Condo Maintenance", "Management Corporation Sinking Fund", "Residensi Strata Fee",
        "Pinnacle Condo Management", "Arte Mont Kiara JMB", "i-Neighbour Condo Billing",
        "The Tamarind JMB", "SkyResidence Strata MC", "D'Sands Condo Maintenance",
        "Setia Sky Residences MC",
    ],
    "Bills & utilities || Electricity (TNB)": [
        "myTNB Tenaga Nasional", "Tenaga Nasional Berhad", "Sarawak Energy SESCO",
        "Sabah Electricity SESB", "SEB Cares Sarawak", "JomPAY TNB Bil Elektrik",
    ],
    "Bills & utilities || Home Internet & WiFi (Unifi / Time)": [
        "Unifi Home Fibre", "TIME Internet Fibre", "Maxis Home Fibre", "CelcomDigi Fibre",
        "Astro Fibre", "Allo City Broadband", "Starlink Malaysia", "TM Unifi Biz Fibre",
        "U Mobile Home 5G WiFi",
    ],
    "Bills & utilities || Mobile Postpaid & Prepaid": [
        "CelcomDigi Postpaid", "Maxis Postpaid", "Hotlink Prepaid", "U Mobile UPostpaid",
        "Yes 5G Infinite", "redONE Mobile", "Tune Talk", "ONEXOX Black",
        "XOX Prepaid Reload", "Unifi Mobile Bebas",
    ],
    "Bills & utilities || Streaming & App Subscriptions": [
        "ChatGPT Plus OpenAI", "Claude Pro Anthropic", "Google One Gemini Advanced",
        "Cursor Pro", "Perplexity Pro", "GitHub Copilot", "Canva Pro",
        "Netflix Malaysia", "Spotify Duo MY", "sooka Malaysia", "iCloud+ Apple",
        "YouTube Premium", "Disney+ Hotstar MY", "Astro Go", "Microsoft 365 Personal",
        "Adobe Creative Cloud", "NordVPN", "Apple App Store Subscription",
    ],
    "Bills & utilities || Water & Sewerage (Air Selangor / IWK)": [
        "Air Selangor", "Indah Water Konsortium (IWK)", "Ranhill SAJ Johor",
        "PBAPP Penang Water", "SADA Kedah", "Lembaga Air Perak (LAP)", "SAMB Melaka",
        "SAINS Negeri Sembilan", "SATU Water Terengganu", "Air Kelantan AKSB",
        "Kuching Water Board", "LAKU Management Sarawak",
    ],
    "Loans, BNPL & Credit || BNPL (SPayLater / GrabPayLater / Atome)": [
        "Shopee SPayLater", "GrabPayLater", "Atome Malaysia", "RiiPay BNPL",
        "Boost PayFlex", "LazPayLater", "TikTok PayLater", "Split PayLater",
    ],
    "Loans, BNPL & Credit || Credit Card Bill": [
        "Maybank Visa Signature Bill", "CIMB OCTO Card Payment", "GXBank FlexiCredit",
        "AEON Bank Credit-i", "Public Bank Quantum Card", "RHB World Mastercard",
        "Hong Leong Mach Card", "UOB PRVI Miles Card", "HSBC Amanah Card",
        "AmBank Visa Platinum Bill", "Standard Chartered Card Bill",
    ],
    "Loans, BNPL & Credit || Hire Purchase / Installment": [
        "AEON Credit Service", "Courts FlexiCredit", "Senheng PlusOne Installment",
        "Singer Malaysia Installment", "Harvey Norman FlexiPay", "Chan Furniture Installment",
        "Switch 0% Easy Payment Plan",
    ],
    "Loans, BNPL & Credit || PTPTN Study Loan": [
        "PTPTN myPTPTN Direct Debit", "PTPTN Education Loan Repayment",
        "JomPAY PTPTN Loan", "FPX PTPTN Bayaran Balik Pinjaman", "Potongan Gaji PTPTN",
    ],
    "Loans, BNPL & Credit || Personal Loan": [
        "GXBank FlexiLoan", "Boost Credit Aspirasi", "RHB Easy Personal Loan",
        "CIMB Cash Plus Loan", "Maybank Personal Financing-i", "AEON Express Personal Loan",
        "Bank Rakyat Pembiayaan Peribadi-i", "Hong Leong Personal Loan",
    ],
    "Housing & property || Cukai Pintu & Cukai Tanah": [
        "DBKL Cukai Taksiran", "MBPJ Cukai Pintu", "MBSJ Assessment Tax",
        "MPKJ eBayar Cukai Pintu", "PTG Selangor Cukai Tanah", "MBPP Assessment Tax",
        "MBJB Cukai Harta", "MPAJ Cukai Taksiran", "DBKK Assessment Rate",
    ],
    "Housing & property || Furniture & Appliances": [
        "IKEA Damansara", "IKEA Cheras", "IKEA Tebrau", "Coway Malaysia",
        "Cuckoo International", "LG PuriCare Rental", "SSF Home", "HOOGA Malaysia",
        "Akemi Uchi", "King Koil Boutique", "SK Magic Water Purifier", "Ogawa Malaysia",
    ],
    "Housing & property || Home Loan / Mortgage": [
        "Maybank Housing Loan-i", "Public Bank Mortgage", "CIMB HomeFlexi",
        "Hong Leong Mortgage", "RHB Home Financing-i", "AmBank Home Loan",
        "Bank Islam Baiti Home Financing-i", "LPPSA Housing Loan",
    ],
    "Housing & property || Home Repair & Maintenance": [
        "Recomn.com Plumber", "Kaodim Aircond Service", "Mister Paint KL",
        "CoolServe Aircond Repair", "Klang Valley Wiring & Plumbing", "Rentokil Pest Control",
        "Mr Handyman Repairs", "Syarikat Tukang Paip & Elektrik", "Locksmith 24 Jam",
    ],
    "Housing & property || Rent": [
        "SPEEDHOME Rental", "iBilik Room Rent", "Monthly House Rental Transfer",
        "Wetopia Co-Living Rent", "Sewa Rumah Bulanan", "Utopia Co-Living",
        "Bilik Sewa Residensi", "Owner Tenancy Rental Transfer",
    ],
    "Shopping || Beauty, Skincare & Grooming": [
        "Sephora Malaysia", "Watsons SkinLab", "Olive Young Global MY", "Innisfree MY",
        "The Body Shop", "Bath & Body Works", "L'Occitane", "Skintific Official",
        "Number76 Hair Salon", "A Cut Above Salon", "Thai Odyssey Spa", "HealthLand Wellness",
        "Joe's Barbershop", "SaSa Malaysia",
    ],
    "Shopping || Clothing, Bags & Footwear": [
        "Uniqlo Malaysia", "JD Sports MY", "H&M Malaysia", "Padini Concept Store",
        "Cotton On MY", "Christy Ng", "Charles & Keith", "Nike Store TRX",
        "Adidas Originals KL", "Love, Bonito", "Skechers MY", "Bata Malaysia",
        "Zara Malaysia", "MUJI Apparel", "Bonia", "Brands Outlet",
    ],
    "Shopping || Electronics & Gadgets": [
        "Machines Apple APR", "Switch Apple Store", "ALL IT Hypermarket",
        "ThunderMatch (TMT)", "Harvey Norman", "Senheng", "Samsung Experience Store",
        "Xiaomi Official Store", "Anker Official MY", "Sony Store KLCC", "TMT Megastore",
        "Dyson Demo Store", "Ideal Tech PC", "Vivid Concepts Apple",
    ],
    "Shopping || Home & Living": [
        "MR.DIY Plus", "Eco-Shop", "Daiso Japan", "MUJI Malaysia", "Kaison Furnishing",
        "Noko Store", "Objet Malaysia", "Ninso", "Spotlight Malaysia", "Living Cabin",
        "MR.DOLLAR", "H&M Home",
    ],
    "Shopping || Online Marketplace (Shopee / Lazada / TikTok)": [
        "Shopee Malaysia", "Lazada Malaysia", "TikTok Shop MY", "Temu MY",
        "Taobao Malaysia", "Shein MY", "Zalora Malaysia", "PG Mall", "PrestoMall",
    ],
    "Health & medical || Clinic & GP": [
        "Qualitas Health Clinic", "Mediviron Poliklinik", "Klinik Mediviron",
        "DoctorOnCall Telehealth", "Megaklinik Zahran", "Careclinics Healthcare",
        "Klinik Kesihatan Kerajaan", "Klinik Reddy", "Poliklinik Damai",
        "BeWell Physiotherapy", "Alty Chiropractic", "TCM Eu Yan Sang Clinic",
    ],
    "Health & medical || Dental & Optical": [
        "Q & M Dental Malaysia", "Owndays Malaysia", "Focus Point Optical",
        "MOG Eyewear", "A-Look Eyewear", "Tiew Dental Group", "Lenskart Malaysia",
        "Smile Avenue Dental", "Bangsar Dental Specialist", "Vision Space Optometrist",
        "Pott Glasses", "ANC Dental",
    ],
    "Health & medical || Full Medical Checkup (LHDN)": [
        "BP Healthcare Screening", "Pathlab Malaysia", "Gribbles Pathology",
        "Beacon Wellness Screening", "Sunway Wellness Health Check", "Innoquest Diagnostics",
        "Tung Shin Executive Checkup", "Pantai Health Screening", "IJN Wellness Screening",
    ],
    "Health & medical || Gym, Sports & Fitness": [
        "Pickleball Court Booking KL", "Anytime Fitness MY", "Decathlon Malaysia",
        "Sports Direct MY", "Babel Fit", "Celebrity Fitness", "Al-Ikhsan Sports",
        "Flyproject", "Fitness First MY", "Courtsite Badminton", "Reclub Sports Court",
        "Fire Fitness", "Camp5 Climbing Gym",
    ],
    "Health & medical || Hospital & Specialist": [
        "Sunway Medical Centre", "Gleneagles Hospital KL", "Pantai Hospital KL",
        "KPJ Damansara Specialist", "Prince Court Medical Centre", "Columbia Asia Hospital",
        "SJMC Subang Jaya", "Thomson Hospital Kota Damansara", "IJN National Heart Institute",
        "Assunta Hospital", "Tung Shin Hospital", "Avisena Specialist Hospital",
        "Ara Damansara Medical Centre", "Penang Adventist Hospital",
    ],
    "Health & medical || Pharmacy & Medicine": [
        "CARiNG Pharmacy", "Big Pharmacy", "AA Pharmacy", "Alpro Pharmacy",
        "Health Lane Family Pharmacy", "Guardian Pharmacy", "Watsons Pharmacy",
        "Multicare Pharmacy", "Georgetown Pharmacy", "Constant Pharmacy",
        "Sunway Multicare", "AM PM Pharmacy",
    ],
    "Insurance & Takaful || Education Insurance": [
        "Prudential PruEdu", "AIA EduSmart", "Great Eastern SmartEdu",
        "Takaful Malaysia Education Plan", "Hong Leong MSIG EduCare", "PruBSN Ilmu Education",
        "Allianz EduGuard Plan",
    ],
    "Insurance & Takaful || Life Insurance / Takaful": [
        "Prudential Assurance Malaysia", "PruBSN Takaful", "AIA Malaysia",
        "Great Eastern Takaful", "Zurich Takaful", "Manulife Malaysia",
        "FWD Takaful", "Takaful Ikhlas Family", "Hong Leong Assurance", "MCIS Life",
        "AmMetLife Insurance", "Sun Life Malaysia",
    ],
    "Insurance & Takaful || Medical & Health Card": [
        "PruBSN HealthEnrich", "AIA A-Plus Health", "Great Eastern MediCare",
        "Allianz HealthInsured", "Etiqa Medical Takaful", "Zurich Medica Care",
        "Prudential PruValue Med", "FWD Medical Card Takaful",
    ],
    "Insurance & Takaful || Personal Accident": [
        "Tune Protect PA", "Allianz Shield Plus", "Chubb Personal Accident",
        "Senang.io PA Cover", "MSIG Personal Accident", "Lonpac PA Protection",
        "Sompo PA Guard",
    ],
    "Education || Books & Stationery": [
        "Kinokuniya KLCC", "Tsutaya Books Pavilion Bukit Jalil", "POPULAR Bookstore",
        "MPH Bookstores", "BookXcess", "Eslite Spectrum Starhill", "Smiggle MY",
        "Typo Stationery", "CzipLee Bangsar", "Kedai Buku Hasani",
    ],
    "Education || Higher Education & Tuition": [
        "Sunway University Bursary", "Taylor's University Finance", "Monash University Malaysia",
        "UTAR Fee Portal", "APU Bursary", "MMU Fee Collection", "INTI International University",
        "Universiti Malaya UMPay", "UiTM Student Portal", "HELP University Bursary",
        "Pusat Tuisyen Mawar", "Kumon Tuition Centre",
    ],
    "Education || School & Childcare Fees": [
        "Real Kids Kindergarten", "Q-dees Preschool", "Smart Reader Kids",
        "MRC Kids", "Cicada Waldorf School", "SRI Al-Amin Tuition", "Genius Aulad Preschool",
        "Taska Permata Harmoni", "KinderKaizen Playschool", "PIBG Sekolah Kebangsaan",
    ],
    "Education || Upskilling & Certification": [
        "Coursera Plus", "Udemy Malaysia", "42 Kuala Lumpur Bootcamp",
        "HRD Corp Approved Training", "LinkedIn Learning", "DataCamp", "edX Certification",
        "Pluralsight", "ACCA Exam Fee", "AWS Certification Testing",
    ],
    "Family & gifts || Charity & Donations": [
        "MERCY Malaysia", "Yayasan Chow Kit", "Makna Cancer Council", "Teach For Malaysia",
        "WWF Malaysia Donation", "Global Peace Mission", "Kechara Soup Kitchen",
        "Hospis Malaysia", "NASOM Autism Charity", "PERTIWI Soup Kitchen",
    ],
    "Family & gifts || Childcare & Family Support": [
        "Mothercare Malaysia", "Babysitter / Pengasuh Transfer", "Childcare Nanny Allowance",
        "Anakku Baby Store", "Chicco Malaysia", "Poney Babywear", "The Baby Store MY",
        "First Few Years Baby Shop", "Spectra Breast Pump MY", "Philips Avent Baby Care",
    ],
    "Family & gifts || Festive (Angpao / Duit Raya) & Gifts": [
        "TNG e-Duit Raya Transfer", "MAE DuitNow e-Angpao", "BloomThis Florist",
        "50Gram Gift Box", "Happy Bunch Malaysia", "Flower Chimp", "Royce Gift Hamper",
        "Wedding Angpao Transfer", "Gift Box Hamper Raya",
    ],
    "Family & gifts || Parents Allowance": [
        "Parents Monthly Allowance Transfer", "DuitNow Bulanan Mak Ayah",
        "Family Home Support Transfer", "Ibu Bapa Monthly Transfer",
        "Elaun Bulanan Emak", "Duit Belanja Abah & Mak",
    ],
    "Family & gifts || Zakat & Fitrah": [
        "PPZ-MAIWP Zakat Pendapatan", "Lembaga Zakat Selangor (LZS)",
        "Zakat Pulau Pinang", "Zakat Negeri Sembilan", "MAIWP Zakat Fitrah",
        "Zakat Johor MAINJ", "Zakat Kedah LZNK", "Zakat Perak MAIPk",
    ],
    "Entertainment & travel || Flights & Transport": [
        "AirAsia MOVE", "Malaysia Airlines Enrich", "Batik Air Malaysia",
        "Firefly Airlines", "Scoot Airlines", "Klook Travel Activity",
        "Trip.com Flights", "RedBus Malaysia", "Easybook Express", "MASwings",
    ],
    "Entertainment & travel || Games & Hobbies": [
        "Pop Mart Malaysia", "Miniso Malaysia", "Steam Wallet MY", "Valorant Riot Points",
        "HoYoverse Top-Up", "PlayStation Store MY", "Nintendo eShop",
        "Codashop Malaysia", "Toy World Hobby", "Razer Gold PIN", "Toys'R'Us Malaysia",
    ],
    "Entertainment & travel || Hotel & Holiday Stay": [
        "Agoda.com", "Booking.com", "Airbnb Stay", "Traveloka Hotel",
        "Genting SkyWorlds Resort", "Sunway Resort Hotel", "Shangri-La KL",
        "Dash Resort Langkawi", "Hard Rock Hotel Penang",
    ],
    "Entertainment & travel || Movies, Concerts & Events": [
        "GSC Cinemas", "TGV Cinemas", "Dadi Cinema Pavilion", "Ticket2U Events",
        "CloudJoi Tickets", "GoLive Asia Concert", "Sunway Lagoon Theme Park",
        "Genting SkyWorlds Tickets", "Loud Speaker Family Karaoke", "AQUARIA KLCC",
    ],
    "Investments & savings || ASB / Tabung Haji": [
        "myASNB Amanah Saham", "THiJARI Tabung Haji", "Maybank2u ASNB Transfer",
        "TNG GO+ ASNB", "ASNB Amanah Saham Bumiputera", "Tabung Haji Direct Deposit",
    ],
    "Investments & savings || EPF Voluntary (i-Saraan)": [
        "KWSP i-Akaun Top-Up", "EPF Self-Contribution", "EPF i-Saraan Voluntary",
        "KWSP Caruman Sukarela", "i-Sayang EPF Transfer",
    ],
    "Investments & savings || PRS (Private Retirement Scheme)": [
        "PPA Private Retirement Scheme", "Principal PRS Plus", "Kenanga OnePRS",
        "Public Mutual PRS", "AHAM PRS Growth Fund", "Manulife Shariah PRS",
    ],
    "Investments & savings || SSPN Savings": [
        "PTPTN Simpan SSPN Prime", "Simpan SSPN Plus Savings", "Maybank SSPN Deposit",
        "myPTPTN SSPN Simpanan Pendidikan", "JomPAY Simpan SSPN",
    ],
    "Investments & savings || Stocks, ETF & Crypto": [
        "Moomoo MY", "Webull Malaysia", "StashAway Malaysia", "Versa Invest",
        "KDI Invest Kenanga", "Wahed Invest", "Luno Malaysia", "Rakuten Trade",
        "FSMOne Malaysia", "Touch 'n Go GOinvest", "Hata Crypto MY", "BursaAnywhere",
    ],
    "Taxes & government || Government Service & Stamp Duty": [
        "LHDN Stamp Duty STAMPS", "e-Stamping Malaysia", "MyGovernment Fee",
        "SSM e-Info Renewal", "Imigresen MyIMM Passport", "JPN MyKad Renewal Fee",
        "POS Malaysia Stamp Duty",
    ],
    "Taxes & government || JPJ / Renewals / Summons": [
        "MyJPJ License Renewal", "MyBayar Saman PDRM", "PUSPAKOM Inspection",
        "JPJ Lesen Memandu CDL", "mySIKAP JPJ Renewal", "DBKL Traffic Compound",
    ],
    "Taxes & government || LHDN Income Tax / PCB": [
        "LHDN ByrHASiL Tax", "MyTax e-Filing", "LHDN CP500 Instalment",
        "Hasil e-Billing Income Tax", "PCB e-PCB LHDN",
    ],
    "Fees & charges || Bank & Transfer Fee": [
        "MEPS ATM Interbank Fee", "RENTAS Transfer Charge", "DuitNow Overseas Fee",
        "Maybank Debit Card Fee", "Bank Statement Certified Fee", "IBG Transfer Charge",
        "Fall-Below Minimum Balance Fee", "Card Replacement Fee",
    ],
    "Fees & charges || Card Annual Fee": [
        "Maybank Credit Card Annual Fee", "CIMB Credit Card Annual Charge",
        "Public Bank Annual Membership Fee", "RHB Card Annual Fee", "UOB Credit Card Annual Fee",
        "Hong Leong Card Annual Fee", "HSBC Amanah Card Fee", "AEON Credit Annual Fee",
    ],
    "Fees & charges || Foreign Currency Fee": [
        "Maybank Cross-Border FX Fee", "CIMB Overseas Markup Fee",
        "GXBank Foreign Conversion Fee", "TNG eWallet Visa FX Margin Fee",
        "BigPay Currency Conversion Markup", "HSBC Dynamic Currency Conversion Fee",
    ],
    "Fees & charges || Late Payment Fee": [
        "Credit Card Late Payment Charge", "Unifi Late Reconnection Fee",
        "SPayLater Late Charge Fee", "PTPTN Late Penalty Charge",
        "GrabPayLater Late Fee", "Home Loan Late Interest Charge",
    ],
}

ALL_PAIRS = [f"{cat} || {sub}" for cat, subs in CANONICAL_TAXONOMY.items() for sub in subs]
PAIR_TO_CAT = {f"{cat} || {sub}": cat for cat, subs in CANONICAL_TAXONOMY.items() for sub in subs}

LHDN_PAIRS_BUCKETS = {
    "Health & medical || Clinic & GP": "Medical (Self/Spouse/Child / Parents)",
    "Health & medical || Dental & Optical": "Medical & Dental Treatment",
    "Health & medical || Full Medical Checkup (LHDN)": "Full Medical Checkup (LHDN up to RM1,000)",
    "Health & medical || Gym, Sports & Fitness": "Sports Equipment & Facility (LHDN up to RM1,000)",
    "Health & medical || Hospital & Specialist": "Medical Serious Diseases / Treatment",
    "Health & medical || Pharmacy & Medicine": "Medical / Complete Checkup / Vaccine",
    "Insurance & Takaful || Education Insurance": "Education & Medical Insurance (up to RM3,000)",
    "Insurance & Takaful || Life Insurance / Takaful": "Life Insurance / Takaful (up to RM3,000)",
    "Insurance & Takaful || Medical & Health Card": "Education & Medical Insurance (up to RM3,000)",
    "Education || Books & Stationery": "Lifestyle Books, Journals & Publications",
    "Education || Higher Education & Tuition": "Self Education Fees (up to RM7,000)",
    "Education || School & Childcare Fees": "Childcare / Taska / Tadika Fees (up to RM3,000)",
    "Education || Upskilling & Certification": "Self Upskilling Course Fees (up to RM2,000)",
    "Shopping || Electronics & Gadgets": "Lifestyle Personal Computer / Smartphone / Tablet",
    "Bills & utilities || Home Internet & WiFi (Unifi / Time)": "Lifestyle Internet Subscription",
    "Investments & savings || SSPN Savings": "SSPN Net Deposit Relief (up to RM8,000)",
    "Investments & savings || PRS (Private Retirement Scheme)": "PRS & Deferred Annuity (up to RM3,000)",
    "Investments & savings || EPF Voluntary (i-Saraan)": "EPF / Voluntary Contribution Relief",
    "Family & gifts || Zakat & Fitrah": "Zakat / Fitrah Tax Rebate (Sec 6A)",
    "Family & gifts || Charity & Donations": "Approved Charity Donation (Sec 44(6))",
    "Family & gifts || Childcare & Family Support": "Breastfeeding Equipment / Child Relief",
    "Transport || Petrol & EV Charging": "EV Charging Facility Relief (up to RM2,500)",
}

assert set(LHDN_PAIRS_BUCKETS.keys()).issubset(set(ALL_PAIRS)), "Invalid LHDN_PAIRS_BUCKETS key"
assert set(MERCHANT_SEEDS_2026.keys()) == set(ALL_PAIRS), "MERCHANT_SEEDS_2026 must cover all 74 pairs"

CITIES = [
    "Petaling Jaya, Selangor", "TRX Kuala Lumpur", "Subang Jaya, Selangor",
    "Bangsar South, Kuala Lumpur", "Mont Kiara, KL", "Puchong, Selangor",
    "George Town, Penang", "Mount Austin, Johor Bahru", "Ipoh, Perak",
    "Cyberjaya, Selangor", "Kota Kinabalu, Sabah", "Kuching, Sarawak",
    "Melaka Raya, Melaka", "Kuantan, Pahang", "Seremban, Negeri Sembilan",
    "Shah Alam, Selangor", "Klang, Selangor", "Alor Setar, Kedah",
    "Kota Bharu, Kelantan", "Kuala Terengganu, Terengganu", "Miri, Sarawak",
    "Bayan Lepas, Penang", "Skudai, Johor", "Putrajaya, WP Putrajaya",
]

P2P_NAMES = [
    "Ahmad Farhan", "Tan Wei Ming", "Priya Devi", "Nurul Aina", "Jason Lim",
    "Siti Aishah", "Kevin Wong", "Syafiqah Hanim", "Hafizuddin", "Mei Ling Tan",
    "Danial Hakim", "Rachel Ong", "Arvind Kumar", "Zulhilmi", "Chloe Chong", "Faizal Azman",
]


GEN_SCRIPT = r'''
import json
import os
import random
import re
import time
from collections import Counter
from pathlib import Path
from typing import Literal, Optional, List
from decimal import Decimal
import torch
from pydantic import BaseModel, Field

from run_foundry_v4 import (
    ALL_PAIRS,
    CANONICAL_TAXONOMY,
    CHECKPOINT_PATH,
    CITIES,
    LHDN_PAIRS_BUCKETS,
    MERCHANT_SEEDS_2026,
    OUT_DIR,
    P2P_NAMES,
    PROGRESS_PATH,
    SEED_V3_PATH,
    STATUS_PATH,
)

GEN_OUT = Path("/content/outputs_v4/.gen_v4.jsonl")
META_OUT = Path("/content/outputs_v4/.gen_meta.json")


class NotifSample(BaseModel):
    taxonomy_pair: str
    source_type: Literal["payment_notification"]
    edge_case: str
    expected_merchant: str
    expected_total_myr: str
    expected_date_iso: str
    lhdn_bucket: Optional[str] = None
    raw_text: str = Field(..., min_length=28, max_length=550)


class NotifBatchOutput(BaseModel):
    samples: List[NotifSample] = Field(..., min_length=8, max_length=8)


class ReceiptSample(BaseModel):
    taxonomy_pair: str
    source_type: Literal["thermal_receipt"]
    edge_case: str
    expected_merchant: str
    expected_total_myr: str
    expected_date_iso: str
    lhdn_bucket: Optional[str] = None
    raw_text: str = Field(..., min_length=95, max_length=1650)


class ReceiptBatchOutput(BaseModel):
    samples: List[ReceiptSample] = Field(..., min_length=4, max_length=4)


# Carefully exclude placeholder leaks WITHOUT dropping legitimate "Merchant: <Name>" notifications!
BAD_LEAK_RE = re.compile(
    r"(\|\||taxonomy_pair|expected_merchant|expected_total|source_type|Indomaret|Alfamart|"
    r"\bRp\.?\s?\d|1234567890|Lot 1 Jalan 1|<MERCHANT>|\[MERCHANT\]|"
    r"\bYOUR MERCHANT\b|\bMERCHANT NAME HERE\b|\bMERCHANT_NAME\b)",
    re.IGNORECASE,
)
IDR_ONLY_RE = re.compile(r"\bIDR\s?\d", re.IGNORECASE)
NEG_NOTIF_RE = re.compile(
    r"\b(declined|failed|unsuccessful|gagal|rejected|awaiting)\b|"
    r"(?:transaction|payment|status)\s*(?:is|:)?\s*pending\b",
    re.IGNORECASE,
)
FX_CUE_RE = re.compile(
    r"(?:\b(?:SGD|USD|JPY|THB|EUR|GBP|AUD|CNY|RMB|HKD|TWD|KRW|IDR|VND|"
    r"fx|foreign|overseas|luar negara|exchange rate|kadar tukaran|cross[- ]border)\b|[¥€£$])",
    re.IGNORECASE,
)
PLACEHOLDER_MERCH = {"merchant", "unknown", "n/a", "na", "none", "test merchant", "your merchant"}


def clean_amount_v4(val: str, raw_text: str):
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


def load_seed():
    rows = []
    seen = set()
    if SEED_V3_PATH.exists():
        with SEED_V3_PATH.open("r", encoding="utf-8", newline="\n") as f:
            for line in f:
                s = line.strip()
                if not s:
                    continue
                r = json.loads(s)
                cat, sub = r.get("category", ""), r.get("subcategory", "")
                if sub not in CANONICAL_TAXONOMY.get(cat, []):
                    continue
                key = dedup_key(r["raw_text"])
                if key in seen:
                    continue
                seen.add(key)
                if "edge_case" not in r:
                    r["edge_case"] = "seed_v3"
                rows.append(r)
    return rows, seen


def load_checkpoint(seen):
    rows = []
    done_chunks = set()
    prev_drops = Counter()
    if PROGRESS_PATH.exists():
        try:
            prog = json.loads(PROGRESS_PATH.read_text(encoding="utf-8"))
            done_chunks = set(prog.get("done_chunks", []))
            prev_drops = Counter(prog.get("drop_reasons", {}))
        except Exception:
            done_chunks = set()
            prev_drops = Counter()
    if CHECKPOINT_PATH.exists():
        with CHECKPOINT_PATH.open("r", encoding="utf-8", newline="\n") as f:
            for line in f:
                s = line.strip()
                if not s:
                    continue
                try:
                    r = json.loads(s)
                except Exception:
                    continue
                k = dedup_key(r["raw_text"])
                seen.add(k)
                rows.append(r)
    return rows, done_chunks, prev_drops


def build_prompt(kind: str, pair: str, edge_case: str, idx: int, rng: random.Random):
    city = CITIES[(idx * 7 + rng.randint(0, 23)) % len(CITIES)]
    mon = ((idx + rng.randint(0, 11)) % 12) + 1
    day = ((idx * 3 + rng.randint(0, 27)) % 28) + 1
    seeds = MERCHANT_SEEDS_2026[pair]
    n_pick = 8 if kind == "notif" else 4
    picked = [seeds[(idx * 3 + j) % len(seeds)] for j in range(min(n_pick, len(seeds)))]
    lhdn_hint = LHDN_PAIRS_BUCKETS.get(pair)

    if edge_case == "standard_bank_push":
        style = (
            "Generate 8 diverse Malaysian bank push/SMS/email alerts (MAE/Maybank2u, CIMB OCTO, MyPB, RHB Now, "
            "HLB Connect, AmOnline, BIMB, UOB TMRW, OCBC, HSBC, SC Mobile, myBSN, FPX, JomPAY, DuitNow QR, Auto-Debit). "
            "Mix English and authentic Bahasa Melayu ('Pembayaran berjaya sebanyak RM... kepada...', 'Transaksi kad debit disahkan RM... di...', 'selesai', 'dibayar'; never Indonesian 'sukses'). "
            "Include realistic Balance/Baki or cashback distractors in ~30% of alerts while keeping expected_total_myr exact."
        )
    elif edge_case == "ewallet_bnpl_digital":
        style = (
            "Generate 8 diverse Malaysian e-wallet, digital bank, and BNPL completed payment notifications "
            "(TNG eWallet, GXBank, AEON Bank, Ryt Bank, Boost Bank, Boost PayFlex, ShopeePay, SPayLater, "
            "GrabPay, GrabPayLater, Atome, Setel, BigPay, FavePay, LazPayLater). Vary compact push banners and structured 'Merchant: / Amount:' alerts."
        )
    elif edge_case == "truncated_merchant":
        style = (
            "Generate 8 realistic noisy Malaysian card-terminal / SMS / bank-statement notifications where the merchant name in raw_text is TRUNCATED (14-24 chars), "
            "ALL-CAPS, stripped of vowels/spaces, or prefixed by payment gateways such as 'PAY*', 'SHOPEEPAY*', 'DNQR*', 'IPAY88*', 'RAZERMS*', 'TNGD*', 'MYDEBIT*', 'FPX*', "
            "and/or ends in 'SDN BH', 'S/B', 'KLCC', 'PJ', 'TRX', 'MYS' (e.g. 'RESTORAN NASI LEMAK WA', '99 SPEEDMART SDN BH', 'DNQR*KLINIK MEDIVIR', 'PAY*ZUS COFFEE-TRX'). "
            "IMPORTANT: Put the FULL clean merchant name in expected_merchant while raw_text contains the truncated noisy string!"
        )
    elif edge_case == "foreign_currency_fx":
        style = (
            "Generate 8 realistic Malaysian cross-border card/ewallet payment alerts featuring FOREIGN CURRENCY CONVERSION "
            "(vary across SGD, USD, JPY, THB, EUR, GBP, AUD, CNY, HKD, TWD, KRW, IDR) alongside the EXACT settled MYR/RM amount in raw_text. "
            "Vary amounts across realistic transaction totals (do NOT repeat the same numbers like 82.51 or 42.90 across samples). "
            "CRITICAL: expected_total_myr MUST be the exact settled MYR/RM figure appearing in raw_text, NOT the foreign currency figure."
        )
    elif edge_case == "split_bill_p2p":
        names = [P2P_NAMES[(idx + j) % len(P2P_NAMES)] for j in range(8)]
        style = (
            f"Generate 8 realistic Malaysian DuitNow / MAE / TNG / CIMB outgoing P2P or split-bill notifications sent to individuals "
            f"({', '.join(names[:4])}, etc.) where the expense category is revealed in the Ref / Recipient Ref / Payment Note / Memo! "
            f"Examples: 'DuitNow transfer of RM28.50 to Ahmad Farhan. Ref: Share HaiDiLao TRX' or 'MAE transfer RM110.00 to Siti Aishah - Note: Unifi + TNB split'. "
            f"Always set expected_merchant accurately to the recipient or clear venue/service context in the memo."
        )
    elif edge_case == "lhdn_notification":
        lhdn_rule = (
            f"ALL 8 MUST have lhdn_bucket='{lhdn_hint}' and clearly show a genuine Malaysian LHDN-claimable expense/relief context (e-Invoice/LHDN relief note/medical/lifestyle/SSPN/PRS/Zakat)."
            if lhdn_hint else
            "ALL 8 MUST have lhdn_bucket=null because this category is NOT an LHDN personal tax relief."
        )
        style = (
            f"Generate 8 Malaysian bank/FPX/JomPAY/MyInvois e-Receipt notifications for '{pair}'. {lhdn_rule}"
        )
    elif edge_case == "lhdn_receipt":
        style = (
            f"Generate 4 realistic Malaysian LHDN MyInvois / 80mm clinic, specialist, lifestyle, education, insurance, SSPN, zakat, or EV-charging receipts. "
            f"Include merchant header, 12-digit SSM reg (e.g. 202401031245), TIN/e-Invoice ref, itemized lines, SST (6%/8%) if applicable, 5-sen rounding, and EXACT final TOTAL matching expected_total_myr. "
            f"Set lhdn_bucket='{lhdn_hint}' on all 4 samples."
        )
    else:  # thermal_receipt_noisy_ocr
        style = (
            "Generate 4 realistic NOISY 80mm Malaysian thermal receipt OCR scans (StoreHub, Qashier, Slurp!, FeedMe, kopitiam, Speedmart, workshop, mall retail). "
            "Include realistic OCR quirks (slightly uneven spacing, mixed uppercase/BM/EN item labels, split-line subtotal/SST/5-sen rounding, cash tendered/change OR DuitNow QR), "
            "keeping the final TOTAL / JUMLAH BERSIH in RM exact."
        )

    lhdn_instr = (
        f"Set lhdn_bucket to '{lhdn_hint}' when applicable for Malaysian tax relief, or null."
        if lhdn_hint else
        "Set lhdn_bucket to null."
    )
    msg = (
        f"{style}\n"
        f"Target taxonomy_pair for ALL {n_pick} samples MUST be exact string: \"{pair}\"\n"
        f"Target edge_case for ALL {n_pick} samples MUST be exact string: \"{edge_case}\"\n"
        f"Anchor city/area: {city} (vary outlets across Malaysia). Date anchor: 2026-{mon:02d}-{day:02d} (vary formats).\n"
        f"Use distinct realistic Malaysian merchants/billers inspired by (plus fresh local Malaysian SMEs/outlets): {', '.join(picked)}.\n"
        f"{lhdn_instr}\n"
        f"STRICT rules (violations are discarded):\n"
        f"1. expected_total_myr MUST be plain 2-decimal MYR string e.g. '42.90' (NO 'RM', NO comma), and that EXACT number MUST appear in raw_text as RM/MYR.\n"
        f"2. NEVER output '||', schema field names, placeholders ('<MERCHANT>', 'Lot 1 Jalan 1', '1234567890'), Indonesian stores/Rp.\n"
        f"3. Do NOT include OTP, TAC, failed, declined, or pending words in completed alerts.\n"
        f"4. Output all {n_pick} unique samples in the required JSON schema."
    )
    prompt_str = (
        "<|im_start|>system\n"
        "You synthesize high-realism, ultra-diverse 2025/2026 Malaysian payment notifications & OCR thermal receipts for on-device PFM distillation.<|im_end|>\n"
        f"<|im_start|>user\n{msg}<|im_end|>\n<|im_start|>assistant\n"
    )
    return prompt_str


def build_prompt_jobs():
    rng = random.Random(20261120)
    notif_jobs = []
    receipt_jobs = []
    non_lhdn = [p for p in ALL_PAIRS if p not in LHDN_PAIRS_BUCKETS]
    lhdn_list = [p for p in ALL_PAIRS if p in LHDN_PAIRS_BUCKETS]

    # Notifications (8x per prompt): ~2,904 prompts -> 23,232 raw slots
    specs = [
        ("standard_bank_push", ALL_PAIRS, 11),
        ("ewallet_bnpl_digital", ALL_PAIRS, 7),
        ("truncated_merchant", ALL_PAIRS, 8),
        ("foreign_currency_fx", ALL_PAIRS, 5),
        ("split_bill_p2p", ALL_PAIRS, 4),
        ("lhdn_notification", lhdn_list, 11),
        ("lhdn_notification", non_lhdn, 1),
    ]
    g_idx = 0
    for edge_case, plist, reps in specs:
        for pair in plist:
            for r in range(reps):
                g_idx += 1
                notif_jobs.append({
                    "kind": "notif",
                    "pair": pair,
                    "edge_case": edge_case,
                    "prompt": build_prompt("notif", pair, edge_case, g_idx, rng),
                })

    # Receipts (4x per prompt): ~1,314 prompts -> 5,256 raw slots
    r_specs = [
        ("lhdn_receipt", lhdn_list, 27),
        ("thermal_receipt_noisy_ocr", ALL_PAIRS, 9),
    ]
    for edge_case, plist, reps in r_specs:
        for pair in plist:
            for r in range(reps):
                g_idx += 1
                receipt_jobs.append({
                    "kind": "receipt",
                    "pair": pair,
                    "edge_case": edge_case,
                    "prompt": build_prompt("receipt", pair, edge_case, g_idx, rng),
                })

    rng.shuffle(notif_jobs)
    rng.shuffle(receipt_jobs)

    chunks = []
    for i in range(0, len(notif_jobs), 160):
        chunks.append(("notif", notif_jobs[i:i + 160]))
    for i in range(0, len(receipt_jobs), 100):
        chunks.append(("receipt", receipt_jobs[i:i + 100]))
    return chunks


def build_topup_chunk(round_idx: int, needed: int):
    rng = random.Random(900000 + round_idx)
    n_prompts = min(180, max(32, (needed // 6) + 12))
    jobs = []
    edges = ["standard_bank_push", "ewallet_bnpl_digital", "truncated_merchant", "foreign_currency_fx"]
    for i in range(n_prompts):
        pair = ALL_PAIRS[(round_idx * 37 + i) % len(ALL_PAIRS)]
        ec = edges[(round_idx + i) % len(edges)]
        jobs.append({
            "kind": "notif",
            "pair": pair,
            "edge_case": ec,
            "prompt": build_prompt("notif", pair, ec, 50000 + round_idx * 1000 + i, rng),
        })
    return ("notif", jobs)


def update_gen_status(**extra):
    payload = {"stage": "GENERATING_VLLM", "ts": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()), **extra}
    STATUS_PATH.write_text(json.dumps(payload, indent=2), encoding="utf-8")


def run_gen():
    from vllm import LLM, SamplingParams
    try:
        from vllm.sampling_params import StructuredOutputsParams
        def make_sp(schema, max_toks, temp):
            return SamplingParams(
                temperature=temp, top_p=0.95, max_tokens=max_toks,
                structured_outputs=StructuredOutputsParams(json=schema),
            )
    except ImportError:
        from vllm.sampling_params import GuidedDecodingParams
        def make_sp(schema, max_toks, temp):
            return SamplingParams(
                temperature=temp, top_p=0.95, max_tokens=max_toks,
                guided_decoding=GuidedDecodingParams(json=schema),
            )

    seed_rows, seen = load_seed()
    new_samples, done_chunks, drop_reasons = load_checkpoint(seen)
    print(f"Seed v3: {len(seed_rows)} rows | Resumed v4 checkpoint: {len(new_samples)} valid rows across {len(done_chunks)} chunks.", flush=True)

    props = torch.cuda.get_device_properties(0)
    vram = props.total_memory / (1024 ** 3)
    print(f"vLLM GPU: {props.name} (sm_{props.major}{props.minor}, {vram:.1f} GB)", flush=True)

    candidates = []
    if vram >= 38.0:
        candidates.append(("Qwen/Qwen2.5-32B-Instruct-AWQ", "awq", 4096))
    if props.major >= 8 and vram >= 20.0:
        candidates.append(("Qwen/Qwen2.5-14B-Instruct-AWQ", "awq", 3584))
    candidates.append(("Qwen/Qwen2.5-7B-Instruct-AWQ", "awq", 3584))
    candidates.append(("Qwen/Qwen2.5-7B-Instruct", None, 3584))

    llm = None
    model_id = None
    for mid, quant, mlen in candidates:
        for use_pc in (True, False):
            try:
                kwargs = {
                    "model": mid,
                    "dtype": "half",
                    "max_model_len": mlen,
                    "gpu_memory_utilization": 0.90,
                }
                if quant:
                    kwargs["quantization"] = quant
                if use_pc:
                    kwargs["enable_prefix_caching"] = True
                print(f"Loading vLLM teacher: {mid} (prefix_caching={use_pc})...", flush=True)
                llm = LLM(**kwargs)
                model_id = mid
                break
            except Exception as e:
                print(f"Init attempt failed for {mid} (pc={use_pc}): {e}", flush=True)
        if llm is not None:
            break
    if llm is None:
        raise RuntimeError("Could not initialize any vLLM teacher model")

    chunks = build_prompt_jobs()
    sp_notif = make_sp(NotifBatchOutput.model_json_schema(), 2048, 0.88)
    sp_receipt = make_sp(ReceiptBatchOutput.model_json_schema(), 2800, 0.85)
    edge_counts = Counter(r.get("edge_case", "unknown") for r in new_samples)
    total_chunks = len(chunks)
    t_gen0 = time.perf_counter()
    new_this_run = 0

    def process_chunk(c_idx: int, c_kind: str, jobs: list):
        nonlocal new_this_run
        sp = sp_notif if c_kind == "notif" else sp_receipt
        model_cls = NotifBatchOutput if c_kind == "notif" else ReceiptBatchOutput
        outputs = llm.generate([j["prompt"] for j in jobs], sp)
        added_rows = []
        for job, out in zip(jobs, outputs):
            txt = out.outputs[0].text.strip()
            try:
                parsed = model_cls.model_validate_json(txt)
            except Exception:
                drop_reasons["json_error"] += 1
                continue
            for s in parsed.samples:
                pair = str(s.taxonomy_pair).strip()
                if " || " not in pair:
                    drop_reasons["pair_format"] += 1
                    continue
                cat, sub = pair.split(" || ", 1)
                if sub not in CANONICAL_TAXONOMY.get(cat, []):
                    drop_reasons["pair_mismatch"] += 1
                    continue
                if BAD_LEAK_RE.search(s.raw_text):
                    drop_reasons["leak_or_placeholder"] += 1
                    continue
                ec = job["edge_case"]
                if ec != "foreign_currency_fx" and IDR_ONLY_RE.search(s.raw_text):
                    drop_reasons["foreign_idr_leak"] += 1
                    continue
                if ec == "foreign_currency_fx" and not FX_CUE_RE.search(s.raw_text):
                    drop_reasons["missing_fx_cue"] += 1
                    continue
                if s.source_type == "payment_notification" and NEG_NOTIF_RE.search(s.raw_text):
                    drop_reasons["neg_notif_trigger"] += 1
                    continue
                merch = s.expected_merchant.strip()
                if len(merch) < 2 or len(merch) > 90 or merch.lower() in PLACEHOLDER_MERCH:
                    drop_reasons["bad_merchant"] += 1
                    continue
                norm_amt = clean_amount_v4(s.expected_total_myr, s.raw_text)
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
                if lhdn is None and ec in ("lhdn_notification", "lhdn_receipt") and pair in LHDN_PAIRS_BUCKETS:
                    lhdn = LHDN_PAIRS_BUCKETS[pair]
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
                added_rows.append(row)
                new_samples.append(row)
                edge_counts[ec] += 1
                new_this_run += 1

        if added_rows:
            with CHECKPOINT_PATH.open("a", encoding="utf-8") as cf:
                for row in added_rows:
                    cf.write(json.dumps(row, ensure_ascii=False) + "\n")
                cf.flush()
                os.fsync(cf.fileno())
        done_chunks.add(c_idx)
        PROGRESS_PATH.write_text(json.dumps({
            "model": model_id,
            "done_chunks": sorted(done_chunks),
            "total_chunks": total_chunks,
            "new_valid": len(new_samples),
            "dropped": sum(drop_reasons.values()),
            "drop_reasons": dict(drop_reasons),
            "edge_case_counts": dict(edge_counts),
        }, indent=2), encoding="utf-8")

        elapsed = max(1.0, time.perf_counter() - t_gen0)
        rate = new_this_run / elapsed
        remain_chunks = max(0, total_chunks - len(done_chunks))
        avg_per_chunk = max(1.0, len(new_samples) / max(1, len(done_chunks)))
        eta_sec = int((remain_chunks * avg_per_chunk) / max(rate, 0.1)) if rate > 0 else None
        update_gen_status(
            model=model_id,
            chunks_done=len(done_chunks),
            chunks_total=total_chunks,
            new_valid=len(new_samples),
            total_combined=len(seed_rows) + len(new_samples),
            samples_per_sec=round(rate, 2),
            eta_seconds=eta_sec,
            edge_case_counts=dict(edge_counts),
        )
        print(
            f"[Chunk {len(done_chunks)}/{total_chunks}] +{len(added_rows)} valid | total_new={len(new_samples)} | "
            f"rate={rate:.2f}/s | drops={dict(drop_reasons)}",
            flush=True,
        )

    for c_idx, (c_kind, jobs) in enumerate(chunks):
        if c_idx in done_chunks:
            continue
        process_chunk(c_idx, c_kind, jobs)

    # Top-up guarantee if drop rate unexpectedly leaves < 25,000 new unique samples
    topup_round = 0
    while len(new_samples) < 25000 and topup_round < 6:
        topup_round += 1
        needed = 25250 - len(new_samples)
        c_kind, t_jobs = build_topup_chunk(topup_round, needed)
        print(f"Top-up round {topup_round}: generating {len(t_jobs)} prompts for remaining {needed} slots...", flush=True)
        process_chunk(total_chunks + topup_round, c_kind, t_jobs)

    combined = seed_rows + new_samples
    with GEN_OUT.open("w", encoding="utf-8") as f:
        for row in combined:
            f.write(json.dumps(row, ensure_ascii=False) + "\n")

    final_pairs = Counter(f"{r['category']} || {r['subcategory']}" for r in combined)
    meta = {
        "model": model_id,
        "gpu": f"{props.name} ({vram:.1f} GB)",
        "seed_v3_kept": len(seed_rows),
        "new_valid": len(new_samples),
        "total_combined": len(combined),
        "classes_covered": len(final_pairs),
        "min_class_count": min(final_pairs.values()) if final_pairs else 0,
        "dropped": sum(drop_reasons.values()),
        "drop_reasons": dict(drop_reasons),
        "edge_case_counts": dict(edge_counts),
    }
    META_OUT.write_text(json.dumps(meta, indent=2), encoding="utf-8")
    print(f"Completed v4 generation: {len(combined)} total ({len(new_samples)} new, {len(final_pairs)}/74 classes)", flush=True)


if __name__ == "__main__":
    run_gen()
'''


def update_status(stage, **extra):
    payload = {"stage": stage, "ts": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()), **extra}
    STATUS_PATH.write_text(json.dumps(payload, indent=2), encoding="utf-8")
    print(f"[STATUS] {stage} {extra}", flush=True)


def read_jsonl(path: Path):
    rows = []
    with path.open("r", encoding="utf-8", newline="\n") as f:
        for line in f:
            s = line.strip()
            if s:
                rows.append(json.loads(s))
    return rows


def ensure_deps(train_only: bool = False):
    pkgs = ["scikit-learn", "scipy", "transformers"] if train_only else ["vllm", "hf_transfer", "scikit-learn", "scipy", "transformers"]
    update_status("INSTALLING", detail=f"Installing {', '.join(pkgs)}")
    subprocess.run([sys.executable, "-m", "pip", "install", "-q", *pkgs], check=True)


def run_vllm_generation():
    update_status("GENERATING_VLLM", detail="Starting resumable vLLM Qwen2.5 v4 synthesis (target >=25,000 new)")
    gen_path = Path("/content/gen_vllm_v4.py")
    gen_path.write_text(GEN_SCRIPT, encoding="utf-8")
    t0 = time.perf_counter()
    env = {
        **os.environ,
        "HF_HUB_ENABLE_HF_TRANSFER": "1",
        "PYTHONPATH": f"{Path(__file__).resolve().parent}:{os.environ.get('PYTHONPATH', '')}",
    }
    subprocess.run([sys.executable, "-u", str(gen_path)], check=True, env=env)
    tmp_ds = OUT_DIR / ".gen_v4.jsonl"
    DATASET_PATH.write_bytes(tmp_ds.read_bytes())
    meta = json.loads((OUT_DIR / ".gen_meta.json").read_text(encoding="utf-8"))
    meta["gen_seconds"] = round(time.perf_counter() - t0, 1)
    return meta


def normalize_merchant_group(name: str) -> str:
    """Normalizes merchant string to a leakage-free root group for strict train/cal/test splitting."""
    s = re.sub(r"[^a-z0-9\s]", " ", str(name).lower())
    drop = {
        "sdn", "bhd", "berhad", "plt", "llp", "malaysia", "my", "mys", "kl", "pj", "selangor",
        "penang", "pulau", "pinang", "johor", "bahru", "ipoh", "melaka", "subang", "jaya",
        "petaling", "cheras", "bangsar", "mont", "kiara", "puchong", "trx", "klcc", "damansara",
        "shah", "alam", "cyberjaya", "putrajaya", "branch", "outlet", "store", "centre", "center",
    }
    toks = [t for t in s.split() if t not in drop and not t.isdigit()]
    return " ".join(toks[:3]) or re.sub(r"\s+", " ", s).strip() or "unknown"


def build_zero_leakage_splits(samples):
    """Partitions dataset into train, cal (~30% heldout groups), v4_test (~70% heldout groups), and frozen v3_holdout."""
    holdout_v3_exact = set()
    holdout_v3_groups = set()
    if HOLDOUT_V3_PATH.exists():
        for h in json.loads(HOLDOUT_V3_PATH.read_text(encoding="utf-8")):
            holdout_v3_exact.add((h["expected_merchant"].strip(), h["raw_text"]))
            holdout_v3_groups.add(normalize_merchant_group(h["expected_merchant"]))

    v3_holdout_idx = []
    v3_shadow_idx = []
    elig_by_sub_grp = defaultdict(lambda: defaultdict(list))
    for idx, r in enumerate(samples):
        key = (r["expected_merchant"].strip(), r["raw_text"])
        grp = normalize_merchant_group(r["expected_merchant"])
        if key in holdout_v3_exact:
            v3_holdout_idx.append(idx)
        elif grp in holdout_v3_groups:
            v3_shadow_idx.append(idx)
        else:
            pair = f"{r['category']}||{r['subcategory']}"
            elig_by_sub_grp[pair][grp].append(idx)

    # First assign global holdout decision per merchant group (never split one group across train and eval!)
    holdout_groups = set()
    rng = random.Random(42)
    for pair in sorted(elig_by_sub_grp.keys()):
        grps = sorted(elig_by_sub_grp[pair].keys())
        rng.shuffle(grps)
        if len(grps) >= 4:
            k = max(2, round(len(grps) * 0.14))
            for g in grps[:k]:
                holdout_groups.add(g)

    cal_groups = set()
    test_groups = set()
    hg_sorted = sorted(holdout_groups)
    rng.shuffle(hg_sorted)
    n_cal = max(1, round(len(hg_sorted) * 0.30))
    for g in hg_sorted[:n_cal]:
        cal_groups.add(g)
    for g in hg_sorted[n_cal:]:
        test_groups.add(g)

    train_idx, cal_idx, v4_test_idx = [], [], []
    v3_shadow_test = 0
    for idx, r in enumerate(samples):
        key = (r["expected_merchant"].strip(), r["raw_text"])
        if key in holdout_v3_exact:
            continue
        grp = normalize_merchant_group(r["expected_merchant"])
        if grp in holdout_v3_groups:
            v4_test_idx.append(idx)
            v3_shadow_test += 1
        elif grp in cal_groups:
            cal_idx.append(idx)
        elif grp in test_groups:
            v4_test_idx.append(idx)
        else:
            train_idx.append(idx)

    return {
        "train_idx": train_idx,
        "cal_idx": cal_idx,
        "v4_test_idx": v4_test_idx,
        "v3_holdout_idx": v3_holdout_idx,
        "v3_shadow_in_test": v3_shadow_test,
    }


def java_parity_words(text: str):
    """Exact bit-for-bit parity with Java MlClassifier.tokenizeWords()."""
    buf = []
    out = []
    for ch in text.lower():
        if ch.isalnum():
            buf.append(ch)
        else:
            if len(buf) >= 2:
                out.append("".join(buf))
            buf.clear()
    if len(buf) >= 2:
        out.append("".join(buf))
    return out


def featurize_counts(text: str):
    """Extracts block-0 word (1-2) and block-1 char_wb (3-5) term frequency dicts identically to Java."""
    words = java_parity_words(text)
    w_counts = Counter()
    for i, w in enumerate(words):
        w_counts["w:" + w] += 1
        if i + 1 < len(words):
            w_counts["w:" + w + " " + words[i + 1]] += 1
    c_counts = Counter()
    for w in words:
        padded = " " + w + " "
        L = len(padded)
        for n in (3, 4, 5):
            if L >= n:
                for start in range(L - n + 1):
                    c_counts["c:" + padded[start:start + n]] += 1
    return w_counts, c_counts


def make_truncated_merchant(merchant: str) -> str:
    s = merchant.strip().upper()
    if len(s) >= 18:
        cut = max(14, min(22, int(len(s) * 0.72)))
        return s[:cut].strip()
    return s


def compute_calibration_metrics(logits, y_true, classes, n_bins=15):
    import numpy as np
    from sklearn.metrics import accuracy_score, f1_score

    logits = np.asarray(logits, dtype=np.float64)
    shifted = logits - logits.max(axis=1, keepdims=True)
    exps = np.exp(shifted)
    probs = exps / exps.sum(axis=1, keepdims=True)

    N, C = probs.shape
    y_arr = np.asarray(y_true, dtype=np.int64)
    true_probs = np.clip(probs[np.arange(N), y_arr], 1e-12, 1.0)
    nll = float(-np.mean(np.log(true_probs)))

    one_hot = np.zeros_like(probs)
    one_hot[np.arange(N), y_arr] = 1.0
    brier = float(np.mean(np.sum((probs - one_hot) ** 2, axis=1)))

    confs = probs.max(axis=1)
    pred_idx = probs.argmax(axis=1)
    accs = (pred_idx == y_arr).astype(np.float64)

    ece = 0.0
    bins = np.linspace(0.0, 1.0, n_bins + 1)
    for b in range(n_bins):
        lo, hi = bins[b], bins[b + 1]
        mask = (confs >= lo) & (confs <= hi if b == n_bins - 1 else confs < hi)
        if np.any(mask):
            ece += (np.sum(mask) / N) * abs(float(accs[mask].mean()) - float(confs[mask].mean()))

    preds = [classes[i] for i in pred_idx]
    trues = [classes[i] for i in y_arr]
    p_cats = [p.split("||")[0] for p in preds]
    t_cats = [t.split("||")[0] for t in trues]
    return {
        "cat_acc": round(float(accuracy_score(t_cats, p_cats)), 4),
        "subcat_acc": round(float(accuracy_score(trues, preds)), 4),
        "subcat_macro_f1": round(float(f1_score(trues, preds, labels=sorted(set(trues)), average="macro", zero_division=0)), 4),
        "nll": round(nll, 4),
        "ece": round(float(ece), 4),
        "brier": round(brier, 4),
        "preds": preds,
    }


def fit_temperature(logits, y_true):
    import numpy as np
    from scipy.optimize import minimize_scalar

    logits = np.asarray(logits, dtype=np.float64)
    y_arr = np.asarray(y_true, dtype=np.int64)
    arange_n = np.arange(len(y_arr))

    def loss_fn(T: float):
        scaled = logits / T
        scaled -= scaled.max(axis=1, keepdims=True)
        exps = np.exp(scaled)
        probs = exps / exps.sum(axis=1, keepdims=True)
        p_true = np.clip(probs[arange_n, y_arr], 1e-12, 1.0)
        return float(-np.mean(np.log(p_true)))

    res = minimize_scalar(loss_fn, bounds=(0.02, 4.0), method="bounded")
    return float(res.x)


def train_neural_teacher(samples, splits, epochs=4, max_len=224):
    import numpy as np
    import torch
    import torch.nn as nn
    from torch.utils.data import DataLoader, TensorDataset
    from transformers import AutoModel, AutoTokenizer, get_cosine_schedule_with_warmup

    backbone = "xlm-roberta-base"
    train_idx = splits["train_idx"]
    cal_idx = splits["cal_idx"]
    v4_test_idx = splits["v4_test_idx"]
    v3_hold_idx = splits["v3_holdout_idx"]

    vram_gb = torch.cuda.get_device_properties(0).total_memory / (1024 ** 3) if torch.cuda.is_available() else 0.0
    batch_size = 64 if vram_gb >= 38.0 else 32
    update_status("TRAINING_NEURAL_TEACHER", backbone=backbone, train_n=len(train_idx), cal_n=len(cal_idx), batch_size=batch_size)

    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    amp_dtype = torch.bfloat16 if (torch.cuda.is_available() and torch.cuda.is_bf16_supported()) else torch.float16

    labels = sorted({f"{r['category']}||{r['subcategory']}" for r in samples})
    lbl2id = {l: i for i, l in enumerate(labels)}

    tok = AutoTokenizer.from_pretrained(backbone)
    enc = AutoModel.from_pretrained(backbone)

    class StudentNet(nn.Module):
        def __init__(self, encoder, n_cls):
            super().__init__()
            self.encoder = encoder
            h = encoder.config.hidden_size
            self.drop = nn.Dropout(0.15)
            self.head = nn.Linear(h, n_cls)

        def forward(self, input_ids, attention_mask):
            out = self.encoder(input_ids=input_ids, attention_mask=attention_mask).last_hidden_state
            mask = attention_mask.unsqueeze(-1).float()
            pooled = (out * mask).sum(dim=1) / mask.sum(dim=1).clamp(min=1e-6)
            return self.head(self.drop(pooled))

    model = StudentNet(enc, len(labels)).to(device)
    tr_rows = [samples[i] for i in train_idx]
    tr_texts = [f"{r['expected_merchant']}\n{r['raw_text']}" for r in tr_rows] + [r["raw_text"] for r in tr_rows]
    tr_y = [lbl2id[f"{r['category']}||{r['subcategory']}"] for r in tr_rows] * 2

    tr_enc = tok(tr_texts, padding=True, truncation=True, max_length=max_len, return_tensors="pt")
    tr_dl = DataLoader(TensorDataset(tr_enc["input_ids"], tr_enc["attention_mask"], torch.tensor(tr_y, dtype=torch.long)), batch_size=batch_size, shuffle=True)

    counts = Counter(tr_y)
    weights = torch.tensor([min(4.0, len(tr_y) / (len(labels) * max(1, counts.get(i, 1)))) for i in range(len(labels))], dtype=torch.float, device=device)
    crit = nn.CrossEntropyLoss(weight=weights, label_smoothing=0.04)
    opt = torch.optim.AdamW([
        {"params": model.encoder.parameters(), "lr": 3e-5, "weight_decay": 0.01},
        {"params": model.head.parameters(), "lr": 3e-4, "weight_decay": 0.01},
    ])
    total_steps = len(tr_dl) * epochs
    sched = get_cosine_schedule_with_warmup(opt, num_warmup_steps=max(10, int(0.08 * total_steps)), num_training_steps=total_steps)

    def predict_logits(texts):
        model.eval()
        enc_d = tok(texts, padding=True, truncation=True, max_length=max_len, return_tensors="pt")
        dl = DataLoader(TensorDataset(enc_d["input_ids"], enc_d["attention_mask"]), batch_size=batch_size * 2)
        outs = []
        with torch.no_grad():
            for ids, mask in dl:
                ids, mask = ids.to(device), mask.to(device)
                with torch.amp.autocast("cuda", dtype=amp_dtype, enabled=torch.cuda.is_available()):
                    outs.append(model(ids, mask).float().cpu().numpy())
        return np.vstack(outs)

    cal_rows = [samples[i] for i in cal_idx]
    cal_y = [lbl2id[f"{r['category']}||{r['subcategory']}"] for r in cal_rows]
    best_state = None
    best_f1 = -1.0
    best_ep = 0
    t0 = time.perf_counter()
    scaler = torch.amp.GradScaler("cuda", enabled=(amp_dtype == torch.float16 and torch.cuda.is_available()))

    for ep in range(1, epochs + 1):
        model.train()
        for ids, mask, y in tr_dl:
            ids, mask, y = ids.to(device), mask.to(device), y.to(device)
            opt.zero_grad(set_to_none=True)
            with torch.amp.autocast("cuda", dtype=amp_dtype, enabled=torch.cuda.is_available()):
                loss = crit(model(ids, mask), y)
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
        cal_logits = predict_logits([r["raw_text"] for r in cal_rows])
        cm = compute_calibration_metrics(cal_logits, cal_y, labels)
        print(f"Teacher epoch {ep}/{epochs}: cal raw subcat_acc={cm['subcat_acc']} macro_f1={cm['subcat_macro_f1']} nll={cm['nll']}", flush=True)
        if cm["subcat_macro_f1"] > best_f1:
            best_f1 = cm["subcat_macro_f1"]
            best_ep = ep
            best_state = {k: v.cpu() for k, v in model.state_dict().items()}

    if best_state is not None:
        model.load_state_dict({k: v.to(device) for k, v in best_state.items()})

    def eval_split(indices):
        rows = [samples[i] for i in indices]
        ys = [lbl2id[f"{r['category']}||{r['subcategory']}"] for r in rows]
        m_r = compute_calibration_metrics(predict_logits([r["raw_text"] for r in rows]), ys, labels)
        m_b = compute_calibration_metrics(predict_logits([f"{r['expected_merchant']}\n{r['raw_text']}" for r in rows]), ys, labels)
        return {
            "raw_only": {k: v for k, v in m_r.items() if k != "preds"},
            "with_merchant": {k: v for k, v in m_b.items() if k != "preds"},
            "raw_preds": m_r["preds"],
        }

    v3_eval = eval_split(v3_hold_idx) if v3_hold_idx else {}
    v4_eval = eval_split(v4_test_idx) if v4_test_idx else {}

    # Cache soft logits across ALL samples (both + raw views) in compact float16 (~8 MB total RAM)
    update_status("EXTRACTING_TEACHER_LOGITS", n_samples=len(samples))
    z_both = predict_logits([f"{r['expected_merchant']}\n{r['raw_text']}" for r in samples]).astype(np.float16)
    z_raw = predict_logits([r["raw_text"] for r in samples]).astype(np.float16)

    del model, enc, best_state, tr_enc, tr_dl
    if torch.cuda.is_available():
        torch.cuda.empty_cache()

    return {
        "labels": labels,
        "lbl2id": lbl2id,
        "z_both": z_both,
        "z_raw": z_raw,
        "metrics": {
            "backbone": backbone,
            "amp_dtype": str(amp_dtype).replace("torch.", ""),
            "epochs": epochs,
            "best_epoch": best_ep,
            "train_seconds": round(time.perf_counter() - t0, 1),
            "v3_holdout": {k: v for k, v in v3_eval.items() if k != "raw_preds"},
            "v4_test": {k: v for k, v in v4_eval.items() if k != "raw_preds"},
        },
        "v4_test_raw_preds": v4_eval.get("raw_preds", []),
    }


def build_tfidf_vocab_and_matrix(rows, classes, lbl2id, teacher_z_both=None, teacher_z_raw=None,
                                 w_vocab=None, c_vocab=None, w_idf=None, c_idf=None,
                                 max_w_feats=16000, max_c_feats=14000):
    import numpy as np
    from scipy.sparse import csr_matrix, hstack

    texts = (
        [f"{r['expected_merchant']}\n{r['raw_text']}" for r in rows]
        + [r["raw_text"] for r in rows]
        + [r["expected_merchant"] for r in rows]
        + [make_truncated_merchant(r["expected_merchant"]) for r in rows]
    )
    y = np.array([lbl2id[f"{r['category']}||{r['subcategory']}"] for r in rows] * 4, dtype=np.int64)
    has_teacher = None
    teacher_logits = None
    if teacher_z_both is not None and teacher_z_raw is not None:
        n = len(rows)
        has_teacher = np.zeros(len(texts), dtype=bool)
        has_teacher[: 2 * n] = True
        teacher_logits = np.vstack([teacher_z_both.astype(np.float32), teacher_z_raw.astype(np.float32)])

    parsed = [featurize_counts(t) for t in texts]
    if w_vocab is None:
        df_w, df_c = Counter(), Counter()
        for wc, cc in parsed:
            for k in wc:
                df_w[k] += 1
            for k in cc:
                df_c[k] += 1
        N = len(texts)
        w_vocab = [k for k, c in df_w.most_common() if c >= 3][:max_w_feats]
        c_vocab = [k for k, c in df_c.most_common() if c >= 5][:max_c_feats]
        w_idf = np.array([math.log((1.0 + N) / (1.0 + df_w[k])) + 1.0 for k in w_vocab], dtype=np.float64)
        c_idf = np.array([math.log((1.0 + N) / (1.0 + df_c[k])) + 1.0 for k in c_vocab], dtype=np.float64)

    w_idx = {k: i for i, k in enumerate(w_vocab)}
    c_idx = {k: i for i, k in enumerate(c_vocab)}

    def build_block(counts_list, vmap, idf_arr):
        indptr = [0]
        indices, data = [], []
        for cnt in counts_list:
            ri, rv = [], []
            for tok, tf in cnt.items():
                fi = vmap.get(tok)
                if fi is not None:
                    ri.append(fi)
                    rv.append((1.0 + math.log(tf)) * idf_arr[fi])
            if rv:
                norm = math.sqrt(sum(v * v for v in rv))
                if norm > 0:
                    rv = [v / norm for v in rv]
            indices.extend(ri)
            data.extend(rv)
            indptr.append(len(indices))
        return csr_matrix((data, indices, indptr), shape=(len(counts_list), len(vmap)), dtype=np.float32)

    X = hstack([build_block([p[0] for p in parsed], w_idx, w_idf),
                build_block([p[1] for p in parsed], c_idx, c_idf)], format="csr")
    return {
        "X": X,
        "y": y,
        "has_teacher": has_teacher,
        "teacher_logits": teacher_logits,
        "w_vocab": w_vocab,
        "c_vocab": c_vocab,
        "w_idf": w_idf,
        "c_idf": c_idf,
        "w_idx": w_idx,
        "c_idx": c_idx,
    }


def transform_with_vocab(texts, vocab_bundle):
    import numpy as np
    from scipy.sparse import csr_matrix, hstack

    w_idx, c_idx = vocab_bundle["w_idx"], vocab_bundle["c_idx"]
    w_idf, c_idf = vocab_bundle["w_idf"], vocab_bundle["c_idf"]

    def build_block(counts_list, vmap, idf_arr):
        indptr = [0]
        indices, data = [], []
        for cnt in counts_list:
            ri, rv = [], []
            for tok, tf in cnt.items():
                fi = vmap.get(tok)
                if fi is not None:
                    ri.append(fi)
                    rv.append((1.0 + math.log(tf)) * idf_arr[fi])
            if rv:
                norm = math.sqrt(sum(v * v for v in rv))
                if norm > 0:
                    rv = [v / norm for v in rv]
            indices.extend(ri)
            data.extend(rv)
            indptr.append(len(indices))
        return csr_matrix((data, indices, indptr), shape=(len(counts_list), len(vmap)), dtype=np.float32)

    pts = [featurize_counts(t) for t in texts]
    return hstack([build_block([p[0] for p in pts], w_idx, w_idf),
                   build_block([p[1] for p in pts], c_idx, c_idf)], format="csr")


def train_distilled_linear_gpu(X_tr, y_tr, teacher_logits, n_rows_base, classes, alpha=0.40, wd=1.5e-4, tau=2.0, epochs=16, batch_size=512, lr=1e-2):
    import numpy as np
    import torch
    import torch.nn as nn
    import torch.nn.functional as F

    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    N, D = X_tr.shape
    C = len(classes)
    layer = nn.Linear(D, C).to(device)
    nn.init.zeros_(layer.weight)
    nn.init.zeros_(layer.bias)

    opt = torch.optim.AdamW(layer.parameters(), lr=lr, weight_decay=wd)
    total_steps = max(1, epochs * math.ceil(N / batch_size))
    sched = torch.optim.lr_scheduler.CosineAnnealingLR(opt, T_max=total_steps)

    # Slight inverse-sqrt class weight capped at 3.5 for tail balance without destabilizing calibration
    counts = Counter(y_tr.tolist())
    mean_c = N / max(1, C)
    cw = torch.tensor([min(3.5, math.sqrt(mean_c / max(1, counts.get(i, 1)))) for i in range(C)], dtype=torch.float32, device=device)

    rng = np.random.default_rng(42)
    z_all = teacher_logits  # shape (2*n_rows_base, C), aligned with first 2*n_rows_base rows of X_tr
    cutoff = 2 * n_rows_base

    for ep in range(epochs):
        perm = rng.permutation(N)
        for start in range(0, N, batch_size):
            b_idx = perm[start:start + batch_size]
            xb = torch.from_numpy(X_tr[b_idx].toarray()).to(device)
            yb = torch.from_numpy(y_tr[b_idx]).to(device)
            logits = layer(xb)
            ce = F.cross_entropy(logits, yb, weight=cw)

            soft_mask = b_idx < cutoff
            if np.any(soft_mask):
                t_sub = torch.from_numpy(z_all[b_idx[soft_mask]]).to(device)
                s_sub = logits[torch.from_numpy(soft_mask).to(device)]
                kl = F.kl_div(
                    F.log_softmax(s_sub / tau, dim=-1),
                    F.softmax(t_sub / tau, dim=-1),
                    reduction="batchmean",
                ) * (tau * tau)
                loss = (1.0 - alpha) * ce + alpha * kl
            else:
                loss = ce

            opt.zero_grad(set_to_none=True)
            loss.backward()
            opt.step()
            sched.step()

    W = layer.weight.detach().cpu().numpy().astype(np.float64)
    b = layer.bias.detach().cpu().numpy().astype(np.float64)
    del layer
    if torch.cuda.is_available():
        torch.cuda.empty_cache()
    return W, b


def serialize_sparse_model(W, b, classes, vocab_bundle, extra_meta=None, w_decimals=3):
    import numpy as np

    w_vocab, c_vocab = vocab_bundle["w_vocab"], vocab_bundle["c_vocab"]
    all_vocab = w_vocab + c_vocab
    all_idf = list(vocab_bundle["w_idf"]) + list(vocab_bundle["c_idf"])
    n_w = len(w_vocab)

    features_out = {}
    nnz = 0
    for fi, feat in enumerate(all_vocab):
        col = W[:, fi]
        nz = np.flatnonzero(col)
        if len(nz) == 0:
            continue
        pairs = []
        for ci in nz:
            rw = round(float(col[ci]), w_decimals)
            if rw != 0.0:
                pairs.append([int(ci), rw])
        if not pairs:
            continue
        nnz += len(pairs)
        block = 0 if fi < n_w else 1
        features_out[feat] = [round(float(all_idf[fi]), 4), block, pairs]

    blob = {
        "version": 2,
        "classes": classes,
        "intercepts": [round(float(x), 4) for x in b],
        "features": features_out,
    }
    if extra_meta:
        blob["meta"] = extra_meta
    raw_json = json.dumps(blob, separators=(",", ":"), ensure_ascii=False)
    gz_bytes = gzip.compress(raw_json.encode("utf-8"), compresslevel=9)
    return raw_json, gz_bytes, len(features_out), nnz


def adaptive_prune_and_calibrate(W, b, classes, vocab_bundle, X_cal_raw, X_cal_both, y_cal, max_gz_bytes=MAX_GZ_BYTES, fixed_temperature=None):
    import numpy as np

    # Binary search smallest prune_min_weight yielding gz_bytes <= max_gz_bytes
    nz_abs = np.abs(W[W != 0])
    q92 = float(np.quantile(nz_abs, 0.92)) if nz_abs.size > 0 else 0.35
    lo, hi = 0.010, max(0.35, round(q92, 4))
    best_thresh = hi
    for _ in range(10):
        mid = round((lo + hi) / 2.0, 4)
        Wp = W.copy()
        Wp[np.abs(Wp) < mid] = 0.0
        _, gz, _, _ = serialize_sparse_model(Wp, b, classes, vocab_bundle, w_decimals=3)
        if len(gz) <= max_gz_bytes:
            best_thresh = mid
            hi = mid
        else:
            lo = mid

    Wp = W.copy()
    Wp[np.abs(Wp) < best_thresh] = 0.0

    # Compute calibration stats before & after post-prune temperature scaling
    l_raw = X_cal_raw @ Wp.T + b
    l_both = X_cal_both @ Wp.T + b
    l_comb = np.vstack([l_raw, l_both])
    y_comb = np.concatenate([y_cal, y_cal])

    pre_cal = compute_calibration_metrics(l_comb, y_comb, classes)
    T_post = float(fixed_temperature) if fixed_temperature is not None else fit_temperature(l_comb, y_comb)
    post_cal = compute_calibration_metrics(l_comb / T_post, y_comb, classes)

    # Fold 1 / T_post into weights & intercepts (preserves 100% Java schema compatibility)
    W_final = Wp / T_post
    b_final = b / T_post

    thresh_used = best_thresh
    for _ in range(15):
        meta = {
            "prune_min_weight": thresh_used,
            "temperature": round(T_post, 4),
            "cal_nll_before": pre_cal["nll"],
            "cal_nll_after": post_cal["nll"],
            "cal_ece_before": pre_cal["ece"],
            "cal_ece_after": post_cal["ece"],
        }
        raw_json, gz_bytes, n_feats, nnz = serialize_sparse_model(W_final, b_final, classes, vocab_bundle, extra_meta=meta, w_decimals=3)
        if len(gz_bytes) <= max_gz_bytes:
            break
        thresh_used = round(max(thresh_used + 0.01, thresh_used * 1.15), 4)
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


def extract_lexicon(samples):
    import numpy as np
    from sklearn.feature_extraction.text import TfidfVectorizer
    from sklearn.linear_model import LogisticRegression

    txts = [f"{r['expected_merchant']}\n{r['raw_text']}" for r in samples]
    lbls = [f"{r['category']}||{r['subcategory']}" for r in samples]
    vec = TfidfVectorizer(ngram_range=(1, 2), min_df=3, max_features=20000, stop_words="english")
    X = vec.fit_transform(txts)
    clf = LogisticRegression(max_iter=800, tol=1e-3, C=3.0).fit(X, lbls)
    vocab = np.array(vec.get_feature_names_out())
    stop_tokens = {
        "receipt", "payment", "paid", "myr", "total", "subtotal", "cash", "change", "tax",
        "invoice", "simplified", "jalan", "selangor", "kuala", "lumpur", "malaysia", "sdn",
        "bhd", "berhad", "ref", "date", "time", "qty", "item", "duitnow", "fpx", "successful",
        "penang", "johor", "bahru", "ipoh", "cheras", "subang", "jaya", "petaling", "perak",
        "sarawak", "sabah", "kuching", "kinabalu", "melaka", "seremban", "puchong", "bangsar",
        "shah", "alam", "cyberjaya", "damansara", "georgetown", "george", "town", "kuantan",
        "pahang", "kedah", "kelantan", "acct", "balance", "net", "payable", "ssm", "reg",
    }
    lexicon = defaultdict(list)
    for ci, lbl in enumerate(clf.classes_):
        weights = clf.coef_[ci]
        for ti in np.argsort(weights)[-25:][::-1]:
            tok = str(vocab[ti])
            w = float(weights[ti])
            if w <= 0.15 or any(ch.isdigit() for ch in tok):
                continue
            parts = tok.split()
            if all(p in stop_tokens or len(p) <= 2 for p in parts):
                continue
            lexicon[lbl].append({"term": tok, "weight": round(w, 3)})
    LEXICON_PATH.write_text(json.dumps(lexicon, indent=2, ensure_ascii=False), encoding="utf-8")
    return len(lexicon)


def main():
    import numpy as np
    from sklearn.linear_model import LogisticRegression

    parser = argparse.ArgumentParser()
    parser.add_argument("--train-only", action="store_true", help="Skip vLLM generation and train directly from dataset_malaysian_v4.jsonl")
    args = parser.parse_args()

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    t_all = time.perf_counter()
    try:
        ensure_deps(train_only=args.train_only)
        if args.train_only and DATASET_PATH.exists():
            samples = read_jsonl(DATASET_PATH)
            prog = json.loads(PROGRESS_PATH.read_text(encoding="utf-8")) if PROGRESS_PATH.exists() else {}
            final_pairs = Counter(f"{r['category']} || {r['subcategory']}" for r in samples)
            seed_cnt = sum(1 for r in samples if r.get("edge_case") == "seed_v3")
            gen_meta = {
                "model": prog.get("model", "Qwen/Qwen2.5-32B-Instruct-AWQ"),
                "gpu": "Pre-generated v4 corpus (A100 32B-AWQ + L4 14B-AWQ resume)",
                "seed_v3_kept": seed_cnt,
                "new_valid": len(samples) - seed_cnt,
                "total_combined": len(samples),
                "classes_covered": len(final_pairs),
                "min_class_count": min(final_pairs.values()) if final_pairs else 0,
                "dropped": prog.get("dropped", 0),
                "drop_reasons": prog.get("drop_reasons", {}),
                "edge_case_counts": prog.get("edge_case_counts", {}),
                "train_only": True,
            }
            print(f"[--train-only] Loaded pre-generated {len(samples)} samples ({seed_cnt} seed + {len(samples) - seed_cnt} new)", flush=True)
        else:
            gen_meta = run_vllm_generation()
            samples = read_jsonl(DATASET_PATH)
        splits = build_zero_leakage_splits(samples)
        train_idx = splits["train_idx"]
        cal_idx = splits["cal_idx"]
        v4_test_idx = splits["v4_test_idx"]
        v3_hold_idx = splits["v3_holdout_idx"]

        print(
            f"Zero-leakage splits: total={len(samples)} train={len(train_idx)} cal={len(cal_idx)} "
            f"v4_test={len(v4_test_idx)} v3_holdout={len(v3_hold_idx)} (v3_group_shadow={splits['v3_shadow_in_test']})",
            flush=True,
        )

        # Stage 1: Fine-tune Neural Teacher & extract soft logits
        teacher_out = train_neural_teacher(samples, splits, epochs=4, max_len=224)
        classes = teacher_out["labels"]
        lbl2id = teacher_out["lbl2id"]
        z_both = teacher_out["z_both"]
        z_raw = teacher_out["z_raw"]

        # Stage 2: Build TF-IDF matrices on train slice
        update_status("TRAINING_SPARSE_STUDENTS", train_n=len(train_idx), cal_n=len(cal_idx))
        train_rows = [samples[i] for i in train_idx]
        cal_rows = [samples[i] for i in cal_idx]

        vb = build_tfidf_vocab_and_matrix(
            train_rows, classes, lbl2id,
            teacher_z_both=z_both[train_idx],
            teacher_z_raw=z_raw[train_idx],
            max_w_feats=16000, max_c_feats=14000,
        )
        X_tr, y_tr = vb["X"], vb["y"]
        X_cal_raw = transform_with_vocab([r["raw_text"] for r in cal_rows], vb)
        X_cal_both = transform_with_vocab([f"{r['expected_merchant']}\n{r['raw_text']}" for r in cal_rows], vb)
        y_cal = np.array([lbl2id[f"{r['category']}||{r['subcategory']}"] for r in cal_rows], dtype=np.int64)

        candidates_log = []

        def eval_candidate(name, cfg, W, b):
            l_r = X_cal_raw @ W.T + b
            l_b = X_cal_both @ W.T + b
            T = fit_temperature(np.vstack([l_r, l_b]), np.concatenate([y_cal, y_cal]))
            m_r = compute_calibration_metrics(l_r / T, y_cal, classes)
            m_b = compute_calibration_metrics(l_b / T, y_cal, classes)
            score = 0.5 * (m_r["subcat_macro_f1"] + m_b["subcat_macro_f1"])
            rec = {
                "name": name,
                "config": cfg,
                "temperature": round(T, 4),
                "cal_mean_macro_f1": round(score, 4),
                "cal_mean_nll": round(0.5 * (m_r["nll"] + m_b["nll"]), 4),
                "cal_raw": {k: v for k, v in m_r.items() if k != "preds"},
                "cal_both": {k: v for k, v in m_b.items() if k != "preds"},
            }
            candidates_log.append(rec)
            print(f"[Candidate {name}] score={score:.4f} raw_sub={m_r['subcat_acc']} both_sub={m_b['subcat_acc']} T={T:.3f}", flush=True)
            return score, -rec["cal_mean_nll"], W, b

        best_tuple = None
        # Candidate A sweep: Hard-label Multinomial Logistic Regression
        for idx_c, (C_val, cw_val) in enumerate([(3.0, "balanced"), (5.0, "balanced"), (4.0, None)]):
            clf = LogisticRegression(solver="lbfgs", max_iter=600, tol=1e-3, C=C_val, class_weight=cw_val)
            clf.fit(X_tr, y_tr)
            # Align rows to global classes
            W_full = np.zeros((len(classes), X_tr.shape[1]), dtype=np.float64)
            b_full = np.full(len(classes), -20.0, dtype=np.float64)
            for li, cidx in enumerate(clf.classes_):
                W_full[int(cidx)] = clf.coef_[li]
                b_full[int(cidx)] = clf.intercept_[li]
            res = eval_candidate(f"hard_lr_{idx_c}", {"kind": "lr", "C": C_val, "class_weight": cw_val}, W_full, b_full)
            if best_tuple is None or res[:2] > best_tuple[:2]:
                best_tuple = (*res, {"kind": "lr", "C": C_val, "class_weight": cw_val}, f"hard_lr_{idx_c}")

        # Candidate B sweep: Knowledge-Distilled Soft+Hard Linear Student on GPU
        for idx_d, (alpha_val, wd_val) in enumerate([(0.35, 2e-4), (0.50, 1e-4)]):
            W_d, b_d = train_distilled_linear_gpu(
                X_tr, y_tr, vb["teacher_logits"], len(train_rows), classes,
                alpha=alpha_val, wd=wd_val, tau=2.0, epochs=16, batch_size=512, lr=1e-2,
            )
            res = eval_candidate(f"distill_kl_{idx_d}", {"kind": "distill", "alpha": alpha_val, "wd": wd_val}, W_d, b_d)
            if res[:2] > best_tuple[:2]:
                best_tuple = (*res, {"kind": "distill", "alpha": alpha_val, "wd": wd_val}, f"distill_kl_{idx_d}")

        _, _, W_best, b_best, best_cfg, best_name = best_tuple
        print(f"Selected winning sparse student architecture: {best_name} {best_cfg}", flush=True)

        # Stage 3: Adaptive Prune (< 1.88 MB) & Fold Temperature Scaling for Holdout Eval Model
        hold_export = adaptive_prune_and_calibrate(W_best, b_best, classes, vb, X_cal_raw, X_cal_both, y_cal)
        MODEL_HOLDOUT_JSON.write_text(hold_export["raw_json"], encoding="utf-8")
        MODEL_HOLDOUT_GZ.write_bytes(hold_export["gz_bytes"])
        assert len(hold_export["gz_bytes"]) <= 2_097_152, f"Holdout model exceeds 2MB: {len(hold_export['gz_bytes'])}"

        def eval_exported(indices):
            rows = [samples[i] for i in indices]
            ys = [lbl2id[f"{r['category']}||{r['subcategory']}"] for r in rows]
            Xr = transform_with_vocab([r["raw_text"] for r in rows], vb)
            Xb = transform_with_vocab([f"{r['expected_merchant']}\n{r['raw_text']}" for r in rows], vb)
            Xm = transform_with_vocab([r["expected_merchant"] for r in rows], vb)
            mr = compute_calibration_metrics(Xr @ hold_export["W_final"].T + hold_export["b_final"], ys, classes)
            mb = compute_calibration_metrics(Xb @ hold_export["W_final"].T + hold_export["b_final"], ys, classes)
            mm = compute_calibration_metrics(Xm @ hold_export["W_final"].T + hold_export["b_final"], ys, classes)

            by_edge = {}
            by_edge_idx = defaultdict(list)
            for k, r in enumerate(rows):
                by_edge_idx[r.get("edge_case", "unknown")].append(k)
            for ec, ks in sorted(by_edge_idx.items()):
                sub_preds = [mr["preds"][k] for k in ks]
                sub_true = [f"{rows[k]['category']}||{rows[k]['subcategory']}" for k in ks]
                cat_ok = sum(1 for p, t in zip(sub_preds, sub_true) if p.split("||")[0] == t.split("||")[0])
                sub_ok = sum(1 for p, t in zip(sub_preds, sub_true) if p == t)
                by_edge[ec] = {
                    "n": len(ks),
                    "raw_cat_acc": round(cat_ok / len(ks), 4),
                    "raw_subcat_acc": round(sub_ok / len(ks), 4),
                }
            return {
                "n": len(rows),
                "raw_only": {k: v for k, v in mr.items() if k != "preds"},
                "with_merchant": {k: v for k, v in mb.items() if k != "preds"},
                "merchant_only": {k: v for k, v in mm.items() if k != "preds"},
                "by_edge_case": by_edge,
                "raw_preds": mr["preds"],
            }

        sparse_v3_eval = eval_exported(v3_hold_idx) if v3_hold_idx else {}
        sparse_v4_eval = eval_exported(v4_test_idx) if v4_test_idx else {}

        # Stage 4: Refit winning architecture on 100% of combined dataset for Production APK Asset
        update_status("TRAINING_FULL_PROD_MODEL", n_total=len(samples), winner=best_name)
        vb_full = build_tfidf_vocab_and_matrix(
            samples, classes, lbl2id,
            teacher_z_both=z_both, teacher_z_raw=z_raw,
            max_w_feats=16000, max_c_feats=14000,
        )
        if best_cfg["kind"] == "lr":
            clf_full = LogisticRegression(solver="lbfgs", max_iter=600, tol=1e-3, C=best_cfg["C"], class_weight=best_cfg["class_weight"])
            clf_full.fit(vb_full["X"], vb_full["y"])
            W_prod_raw = np.zeros((len(classes), vb_full["X"].shape[1]), dtype=np.float64)
            b_prod_raw = np.full(len(classes), -20.0, dtype=np.float64)
            for li, cidx in enumerate(clf_full.classes_):
                W_prod_raw[int(cidx)] = clf_full.coef_[li]
                b_prod_raw[int(cidx)] = clf_full.intercept_[li]
        else:
            W_prod_raw, b_prod_raw = train_distilled_linear_gpu(
                vb_full["X"], vb_full["y"], vb_full["teacher_logits"], len(samples), classes,
                alpha=best_cfg["alpha"], wd=best_cfg["wd"], tau=2.0, epochs=16, batch_size=512, lr=1e-2,
            )

        X_full_cal_r = transform_with_vocab([r["raw_text"] for r in cal_rows], vb_full)
        X_full_cal_b = transform_with_vocab([f"{r['expected_merchant']}\n{r['raw_text']}" for r in cal_rows], vb_full)
        prod_export = adaptive_prune_and_calibrate(
            W_prod_raw, b_prod_raw, classes, vb_full, X_full_cal_r, X_full_cal_b, y_cal,
            max_gz_bytes=MAX_GZ_BYTES, fixed_temperature=hold_export["temperature"],
        )
        raw_prod_json = prod_export["raw_json"]
        gz_prod_bytes = prod_export["gz_bytes"]
        prod_feats = prod_export["active_features"]
        prod_nnz = prod_export["nonzero_weights"]
        MODEL_FULL_JSON.write_text(raw_prod_json, encoding="utf-8")
        MODEL_FULL_GZ.write_bytes(gz_prod_bytes)
        assert len(gz_prod_bytes) < 2_097_152, f"Production model exceeds 2MB: {len(gz_prod_bytes)}"

        lex_count = extract_lexicon(samples)

        holdout_rows = []
        v4_t_preds = teacher_out.get("v4_test_raw_preds", [])
        v4_s_preds = sparse_v4_eval.get("raw_preds", [])
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
                "sparse_v4_raw_pred": v4_s_preds[k] if k < len(v4_s_preds) else "",
                "xlmr_v4_raw_pred": v4_t_preds[k] if k < len(v4_t_preds) else "",
                "raw_text": s["raw_text"],
            })
        HOLDOUT_PATH.write_text(json.dumps(holdout_rows, indent=2, ensure_ascii=False), encoding="utf-8")

        report = {
            "total_seconds": round(time.perf_counter() - t_all, 1),
            "generation": gen_meta,
            "splits": {
                "total": len(samples),
                "train": len(train_idx),
                "cal": len(cal_idx),
                "v4_test": len(v4_test_idx),
                "v3_holdout": len(v3_hold_idx),
                "v3_group_shadow_in_test": splits["v3_shadow_in_test"],
            },
            "dataset_counts": {
                "by_source": dict(Counter(r["source_type"] for r in samples)),
                "by_edge_case": dict(Counter(r.get("edge_case", "unknown") for r in samples)),
                "by_category": dict(Counter(r["category"] for r in samples)),
                "lhdn_non_null": sum(1 for r in samples if r.get("lhdn_bucket")),
                "classes_covered": len({f"{r['category']}||{r['subcategory']}" for r in samples}),
            },
            "neural_teacher": teacher_out["metrics"],
            "sparse_candidates_on_cal": candidates_log,
            "winning_sparse_student": {
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
            },
            "production_asset": {
                "vocab_word": len(vb_full["w_vocab"]),
                "vocab_char": len(vb_full["c_vocab"]),
                "active_features": prod_feats,
                "nonzero_weights": prod_nnz,
                "export_bytes": len(raw_prod_json.encode("utf-8")),
                "gz_bytes": len(gz_prod_bytes),
                "under_2mb": len(gz_prod_bytes) < 2_097_152,
                "lexicon_subcategories_covered": lex_count,
            },
        }
        REPORT_PATH.write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")
        update_status(
            "DONE",
            total_samples=len(samples),
            new_valid=gen_meta["new_valid"],
            v4_test=len(v4_test_idx),
            gz_bytes=len(gz_prod_bytes),
            winner=best_name,
        )
    except Exception as exc:
        import traceback
        tb = traceback.format_exc()
        print(tb, flush=True)
        update_status("ERROR", error=str(exc), traceback=tb)
        sys.exit(1)


if __name__ == "__main__":
    main()
