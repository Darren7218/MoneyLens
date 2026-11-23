#!/usr/bin/env python3
"""Provision Colab Pro GPU for Track 3 Contrastive Merchant Canonicalizer v1, monitor, and download artifacts."""
import json
import subprocess
import sys
import tempfile
import time
from pathlib import Path
from types import SimpleNamespace

SESSION = "ml-canon-v1"
REPO_ROOT = Path(r"C:\Users\user\MoneyLens")
COLAB_DIR = REPO_ROOT / "colab"
LOCAL_OUT = COLAB_DIR / "outputs_merchant_v1"
LOCAL_OUT.mkdir(parents=True, exist_ok=True)

HEARTBEAT_CODE = r"""
import json
from pathlib import Path
st_p = Path('/content/outputs_merchant_v1/merchant_canon_v1_status.json')
lg_p = Path('/content/outputs_merchant_v1/merchant_canon_v1.log')
st = st_p.read_text(encoding='utf-8') if st_p.exists() else ''
tail = ''
if lg_p.exists():
    lines = lg_p.read_text(encoding='utf-8', errors='replace').splitlines()
    if lines:
        tail = lines[-1][-180:]
print('STATUS_JSON:' + json.dumps({'status': st, 'tail': tail}))
"""


def run_cli(*args, check=True, sub_timeout=120):
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
    shapes = [
        (["--gpu", "L4"], "L4"),
        (["--gpu", "A100", "--high-mem"], "A100 High-RAM"),
        (["--gpu", "T4", "--high-mem"], "T4 High-RAM"),
    ]
    run_cli("stop", "-s", SESSION, check=False, sub_timeout=45)
    for flags, label in shapes:
        print(f"Trying Colab Pro runtime: {label}...", flush=True)
        res = run_cli("new", "-s", SESSION, *flags, check=False, sub_timeout=180)
        if res.returncode == 0:
            print(f"Allocated Colab runtime: {label}", flush=True)
            info = exec_py("import subprocess; print(subprocess.check_output(['nvidia-smi', '-L']).decode().strip())")
            print(info.stdout.strip(), flush=True)
            return label
        print(f"  unavailable ({res.stderr.strip()[:220]})", flush=True)
    raise RuntimeError("No Colab L4/A100/T4 GPU runtime could be allocated")


def parse_heartbeat(stdout: str):
    for line in stdout.splitlines():
        if line.startswith("STATUS_JSON:"):
            return json.loads(line[len("STATUS_JSON:"):])
    return None


def main():
    import argparse
    parser = argparse.ArgumentParser()
    parser.add_argument("--attach", action="store_true", help="Attach to existing live ml-canon-v1 session")
    args = parser.parse_args()
    global_start = time.time()
    try:
        if not args.attach:
            allocate_gpu()
            exec_py("from pathlib import Path; Path('/content/outputs_merchant_v1').mkdir(parents=True, exist_ok=True)")
            print("Uploading scripts and datasets...", flush=True)
            run_cli("upload", "-s", SESSION, str(COLAB_DIR / "run_foundry_v4.py"), "/content/run_foundry_v4.py", sub_timeout=120)
            run_cli("upload", "-s", SESSION, str(COLAB_DIR / "run_merchant_canon_v1.py"), "/content/run_merchant_canon_v1.py", sub_timeout=120)
            run_cli("upload", "-s", SESSION, str(COLAB_DIR / "outputs_v5" / "dataset_malaysian_v5.jsonl.gz"), "/content/dataset_malaysian_v5.jsonl.gz", sub_timeout=240)
            run_cli("upload", "-s", SESSION, str(COLAB_DIR / "outputs_receipt_v1" / "receipt_vision_v1_dataset.jsonl.gz"), "/content/receipt_vision_v1_dataset.jsonl.gz", sub_timeout=240)
            run_cli("upload", "-s", SESSION, str(COLAB_DIR / "outputs_v4" / "holdout_v4_details.json"), "/content/holdout_v4_details.json", sub_timeout=120)
            run_cli("upload", "-s", SESSION, str(COLAB_DIR / "outputs_v5" / "v5_hard_test.json"), "/content/v5_hard_test.json", sub_timeout=120)

            launch_code = """
import os, subprocess
from pathlib import Path
Path('/content/outputs_merchant_v1').mkdir(parents=True, exist_ok=True)
env = {**os.environ, 'PYTHONUNBUFFERED': '1'}
with open('/content/outputs_merchant_v1/merchant_canon_v1.log', 'w') as lf:
    p = subprocess.Popen(
        ['python3', '-u', '/content/run_merchant_canon_v1.py'],
        stdout=lf, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL,
        start_new_session=True, env=env
    )
print('STARTED_PID', p.pid)
"""
            r = exec_py(launch_code)
            print(r.stdout.strip(), r.stderr.strip(), flush=True)
            if r.returncode != 0 or "STARTED_PID" not in r.stdout:
                raise RuntimeError(f"Failed to launch run_merchant_canon_v1.py ({r.returncode}): {r.stderr}")
        else:
            print(f"Attaching to live Colab session {SESSION}...", flush=True)

        status_local = LOCAL_OUT / "merchant_canon_v1_status.json"
        last_line, last_tail = "", ""
        failures = 0
        while True:
            time.sleep(25)
            elapsed = int(time.time() - global_start)
            if elapsed > 3600:
                raise TimeoutError("Merchant Canon v1 exceeded 1h timeout")
            hb = exec_py(HEARTBEAT_CODE, timeout=75, sub_timeout=110)
            if hb.returncode != 0:
                failures += 1
                print(f"[{elapsed}s] Keepalive warning ({failures}/5)", flush=True)
                if failures >= 5:
                    raise RuntimeError("Lost Colab session during Merchant Canon v1")
                continue
            parsed = parse_heartbeat(hb.stdout)
            if not parsed:
                failures += 1
                if failures >= 5:
                    raise RuntimeError("Heartbeat unparseable")
                continue
            failures = 0
            raw_st = parsed.get("status", "")
            tail = parsed.get("tail", "")
            if raw_st:
                status_local.write_text(raw_st, encoding="utf-8")
                st = json.loads(raw_st)
                line = json.dumps(st)
                if line != last_line:
                    print(f"[{elapsed}s] {line}", flush=True)
                    last_line = line
                elif tail and tail != last_tail:
                    print(f"[{elapsed}s] log: {tail}", flush=True)
                    last_tail = tail
                if st.get("stage") == "DONE":
                    break
                if st.get("stage") == "ERROR":
                    run_cli("download", "-s", SESSION, "/content/outputs_merchant_v1/merchant_canon_v1.log", str(LOCAL_OUT / "merchant_canon_v1.log"), check=False)
                    raise RuntimeError(f"Merchant Canon v1 failed: {st.get('error')}")
            elif tail and tail != last_tail:
                print(f"[{elapsed}s] {tail}", flush=True)
                last_tail = tail

        for name in (
            "merchant_canon_v1_status.json",
            "merchant_canon_v1_report.json",
            "merchant_canon_v1_test_pairs.json",
            "merchant_canon_v1.log",
            "moneylens_merchant_canon.bin.gz",
        ):
            print(f"Downloading {name}...", flush=True)
            run_cli("download", "-s", SESSION, f"/content/outputs_merchant_v1/{name}", str(LOCAL_OUT / name), sub_timeout=180)
        print("All Merchant Canon v1 artifacts downloaded cleanly.", flush=True)
    finally:
        print(f"Stopping Colab session {SESSION}...", flush=True)
        run_cli("stop", "-s", SESSION, check=False, sub_timeout=45)
        usage = run_cli("usage", check=False, sub_timeout=45)
        print(usage.stdout.strip(), flush=True)


if __name__ == "__main__":
    main()
