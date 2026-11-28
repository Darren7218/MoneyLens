#!/usr/bin/env python3
"""Provision Colab Pro GPU (A100 High-RAM -> A100 -> L4 -> T4 High-RAM, NEVER H100) for Phase D Run."""
import argparse
import json
import subprocess
import sys
import tempfile
import time
from pathlib import Path
from types import SimpleNamespace

SESSION = "ml-phased"
REPO_ROOT = Path(r"C:\Users\user\MoneyLens")
COLAB_DIR = REPO_ROOT / "colab"
LOCAL_OUT = COLAB_DIR / "outputs_phased"
LOCAL_OUT.mkdir(parents=True, exist_ok=True)

HEARTBEAT_CODE = r"""
import json
from pathlib import Path
st_p = Path('/content/outputs_phased/phased_run_status.json')
lg_p = Path('/content/outputs_phased/phased_run.log')
st = st_p.read_text(encoding='utf-8') if st_p.exists() else ''
tail = ''
if lg_p.exists():
    lines = lg_p.read_text(encoding='utf-8', errors='replace').splitlines()
    if lines:
        tail = lines[-1][-180:]
print('STATUS_JSON:' + json.dumps({'status': st, 'tail': tail}))
"""


def run_cli(*args, check=True, sub_timeout=180):
    cmd = [sys.executable, "-m", "colab_cli.cli", "--auth=oauth2", *args]
    try:
        res = subprocess.run(
            cmd, capture_output=True, text=True, encoding="utf-8", errors="replace",
            stdin=subprocess.DEVNULL, timeout=sub_timeout,
        )
    except subprocess.TimeoutExpired:
        res = SimpleNamespace(returncode=124, stdout="", stderr=f"local subprocess timeout after {sub_timeout}s")
    if check and res.returncode != 0:
        raise RuntimeError(f"colab_cli {' '.join(args)} failed ({res.returncode}):\n{res.stdout}\n{res.stderr}")
    return res


def run_cli_retry(*args, attempts=4, check=True, sub_timeout=300):
    last_exc = None
    for attempt in range(1, attempts + 1):
        try:
            return run_cli(*args, check=check, sub_timeout=sub_timeout)
        except Exception as exc:
            last_exc = exc
            if attempt < attempts:
                wait_s = 5 * attempt
                print(f"[retry {attempt}/{attempts - 1}] {' '.join(args[:2])} failed ({exc}); waiting {wait_s}s...", flush=True)
                time.sleep(wait_s)
    raise last_exc


def exec_py(code: str, timeout: int = 75, sub_timeout: int | None = None):
    with tempfile.NamedTemporaryFile("w", suffix=".py", delete=False, encoding="utf-8") as tf:
        tf.write(code)
        tmp = tf.name
    try:
        return run_cli(
            "exec", "-s", SESSION, "-f", tmp, "--timeout", str(timeout),
            check=False, sub_timeout=sub_timeout or (timeout + 30),
        )
    finally:
        Path(tmp).unlink(missing_ok=True)


def allocate_gpu():
    # Priority A100 High-RAM -> A100 -> L4 -> T4 High-RAM, NEVER H100
    shapes = [
        (["--gpu", "A100", "--high-mem"], "A100 High-RAM"),
        (["--gpu", "A100"], "A100"),
        (["--gpu", "L4"], "L4"),
        (["--gpu", "T4", "--high-mem"], "T4 High-RAM"),
    ]
    run_cli("stop", "-s", SESSION, check=False, sub_timeout=45)
    for round_i in range(1, 4):
        for flags, label in shapes:
            print(f"Trying Colab Pro runtime (round {round_i}/3): {label}...", flush=True)
            res = run_cli("new", "-s", SESSION, *flags, check=False, sub_timeout=240)
            if res.returncode == 0:
                print(f"Allocated Colab runtime: {label}", flush=True)
                info = exec_py("import subprocess; print(subprocess.check_output(['nvidia-smi', '-L']).decode().strip())")
                print(info.stdout.strip(), flush=True)
                return label
            print(f"  unavailable ({res.stderr.strip()[:220]})", flush=True)
        if round_i < 3:
            print("Colab 503/unavailable across shapes; waiting 120s before retry...", flush=True)
            time.sleep(120)
    raise RuntimeError("No Colab A100/L4/T4 GPU runtime could be allocated after 3 rounds")


def parse_heartbeat(stdout: str):
    for line in stdout.splitlines():
        if line.startswith("STATUS_JSON:"):
            return json.loads(line[len("STATUS_JSON:"):])
    return None


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--attach", action="store_true")
    args = parser.parse_args()
    global_start = time.time()
    try:
        if not args.attach:
            allocate_gpu()
            exec_py(
                "from pathlib import Path; "
                "Path('/content/outputs_phased').mkdir(parents=True, exist_ok=True); "
                "Path('/content/frozen_track4_v1').mkdir(parents=True, exist_ok=True)",
                timeout=60, sub_timeout=90,
            )
            print("Uploading Phase D scripts, datasets & frozen holdouts...", flush=True)
            uploads = [
                (COLAB_DIR / "run_receipt_foundry_v1.py", "/content/run_receipt_foundry_v1.py"),
                (COLAB_DIR / "run_foundry_v4.py", "/content/run_foundry_v4.py"),
                (COLAB_DIR / "run_track4_foundry_v1.py", "/content/run_track4_foundry_v1.py"),
                (COLAB_DIR / "run_track34_notif_gate_v1.py", "/content/run_track34_notif_gate_v1.py"),
                (COLAB_DIR / "run_notif_gate_v2_a100.py", "/content/run_notif_gate_v2_a100.py"),
                (COLAB_DIR / "run_phased_a100.py", "/content/run_phased_a100.py"),
                (COLAB_DIR / "outputs_receipt_v1" / "receipt_vision_v1_dataset.jsonl.gz", "/content/receipt_vision_v1_dataset.jsonl.gz"),
                (COLAB_DIR / "outputs_receipt_v1" / "receipt_holdout_real_ocr.json", "/content/receipt_holdout_real_ocr.json"),
                (COLAB_DIR / "outputs_track4_v1" / "track4_llm_synth_cache.json", "/content/track4_llm_synth_cache.json"),
                (COLAB_DIR / "outputs_track4_v1" / "semantic_search_blind_test.json", "/content/frozen_track4_v1/semantic_search_blind_test.json"),
                (COLAB_DIR / "outputs_track4_v1" / "quick_entry_blind_test.json", "/content/frozen_track4_v1/quick_entry_blind_test.json"),
                (COLAB_DIR / "outputs_track34_v1" / "track3_dev.json", "/content/track3_dev.json"),
                (COLAB_DIR / "outputs_track34_v1" / "track3_hard_blind.json", "/content/track3_hard_blind.json"),
                (COLAB_DIR / "outputs_track5_v1" / "track5_adversarial_dev.json", "/content/track5_adversarial_dev.json"),
                (COLAB_DIR / "outputs_track5_v1" / "track5_adversarial_blind.json", "/content/track5_adversarial_blind.json"),
            ]
            for src, dst in uploads:
                if not src.exists():
                    raise FileNotFoundError(f"Missing required upload file: {src}")
                run_cli_retry("upload", "-s", SESSION, str(src), dst, attempts=4, sub_timeout=300)

            launch_code = """
import os, subprocess
from pathlib import Path
Path('/content/outputs_phased').mkdir(parents=True, exist_ok=True)
pg = subprocess.run(['pgrep', '-f', 'run_phased_a100.py'], capture_output=True, text=True)
existing = [ln.strip() for ln in pg.stdout.splitlines() if ln.strip().isdigit()]
if existing:
    print('STARTED_PID', existing[0])
else:
    env = {
        **os.environ,
        'PYTHONUNBUFFERED': '1',
        'HF_HUB_ENABLE_HF_TRANSFER': '1',
        'PYTORCH_CUDA_ALLOC_CONF': 'expandable_segments:True',
    }
    with open('/content/outputs_phased/phased_run.log', 'w', encoding='utf-8') as lf:
        p = subprocess.Popen(
            ['python3', '-u', '/content/run_phased_a100.py'],
            stdout=lf, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL,
            close_fds=True, start_new_session=True, env=env
        )
    print('STARTED_PID', p.pid)
"""
            r = None
            for attempt in range(1, 4):
                r = exec_py(launch_code, timeout=90, sub_timeout=135)
                print(r.stdout.strip(), r.stderr.strip(), flush=True)
                if r.returncode == 0 and "STARTED_PID" in r.stdout:
                    break
                time.sleep(6)
            if r is None or r.returncode != 0 or "STARTED_PID" not in r.stdout:
                raise RuntimeError(f"Failed to launch Phase D Run: {r.stderr if r else ''}")

        status_local = LOCAL_OUT / "phased_run_status.json"
        last_line, last_tail = "", ""
        failures = 0
        while True:
            time.sleep(30)
            elapsed = int(time.time() - global_start)
            if elapsed > 7200:
                raise TimeoutError("Phase D Run exceeded 7200s timeout")
            hb = exec_py(HEARTBEAT_CODE, timeout=75, sub_timeout=110)
            if hb.returncode != 0:
                failures += 1
                print(f"[{elapsed}s] Keepalive warning ({failures}/8)", flush=True)
                if failures >= 8:
                    raise RuntimeError("Lost Colab session during Phase D Run")
                continue
            parsed = parse_heartbeat(hb.stdout)
            if not parsed:
                failures += 1
                if failures >= 6:
                    raise RuntimeError("Heartbeat unparseable")
                continue
            failures = 0
            raw_st, tail = parsed.get("status", ""), parsed.get("tail", "")
            if raw_st:
                status_local.write_text(raw_st, encoding="utf-8")
                st = json.loads(raw_st)
                line = json.dumps(st, ensure_ascii=False)
                if line != last_line:
                    print(f"[{elapsed}s] {line}", flush=True)
                    last_line = line
                elif tail and tail != last_tail:
                    print(f"[{elapsed}s] log: {tail}", flush=True)
                    last_tail = tail
                if st.get("stage") == "DONE":
                    break
                if st.get("stage") == "ERROR":
                    for partial in (
                        "phased_run.log",
                        "receipt_tagger_v2_report.json",
                        "moneylens_receipt_tagger_v2.json.gz",
                        "search_v5_report.json",
                        "search_v5_parity_vectors.json",
                        "moneylens_search_v5.bin.gz",
                        "notif_gate_v4_report.json",
                        "notif_gate_v4_parity_vectors.json",
                        "moneylens_notif_gate_v4.bin.gz",
                    ):
                        run_cli("download", "-s", SESSION, f"/content/outputs_phased/{partial}", str(LOCAL_OUT / partial), check=False, sub_timeout=240)
                    raise RuntimeError(f"Phase D Run failed: {st.get('error')}")
            elif tail and tail != last_tail:
                print(f"[{elapsed}s] {tail}", flush=True)
                last_tail = tail

        downloads = [
            ("phased_run_status.json", True),
            ("phased_run_report.json", True),
            ("phased_run.log", True),
            ("receipt_tagger_v2_report.json", True),
            ("moneylens_receipt_tagger_v2.json.gz", True),
            ("search_v5_report.json", True),
            ("search_v5_parity_vectors.json", True),
            ("moneylens_search_v5.bin.gz", True),
            ("notif_gate_v4_report.json", True),
            ("notif_gate_v4_parity_vectors.json", True),
            ("moneylens_notif_gate_v4.bin.gz", True),
        ]
        for name, req in downloads:
            print(f"Downloading {name}...", flush=True)
            if req:
                run_cli_retry(
                    "download", "-s", SESSION, f"/content/outputs_phased/{name}", str(LOCAL_OUT / name),
                    attempts=3, check=True, sub_timeout=300,
                )
            else:
                run_cli("download", "-s", SESSION, f"/content/outputs_phased/{name}", str(LOCAL_OUT / name), check=False, sub_timeout=300)
        print("All Phase D artifacts downloaded cleanly.", flush=True)
    finally:
        print(f"Stopping Colab session {SESSION}...", flush=True)
        run_cli("stop", "-s", SESSION, check=False, sub_timeout=45)
        usage = run_cli("usage", check=False, sub_timeout=45)
        print(usage.stdout.strip(), flush=True)


if __name__ == "__main__":
    main()