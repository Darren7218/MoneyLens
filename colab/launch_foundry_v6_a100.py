#!/usr/bin/env python3
"""Provision Colab Pro A100 (NEVER H100) for Foundry v6 and download binary MLCL + QAT MLRR artifacts."""
import argparse
import json
import subprocess
import sys
import tempfile
import time
from pathlib import Path
from types import SimpleNamespace

SESSION = "ml-v6-a100"
REPO_ROOT = Path(r"C:\Users\user\MoneyLens")
COLAB_DIR = REPO_ROOT / "colab"
LOCAL_OUT = COLAB_DIR / "outputs_v6"
LOCAL_OUT.mkdir(parents=True, exist_ok=True)

HEARTBEAT_CODE = r"""
import json
from pathlib import Path
st_p = Path('/content/outputs_v6/foundry_v6_status.json')
lg_p = Path('/content/outputs_v6/foundry_v6.log')
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
    # Priority A100 80GB/High-RAM -> A100 -> L4 -> T4 High-RAM, NEVER H100
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
    parser.add_argument("--distill-only", action="store_true")
    args = parser.parse_args()
    global_start = time.time()
    try:
        if not args.attach:
            allocate_gpu()
            print("Uploading Foundry v6 scripts & existing datasets/holdouts...", flush=True)
            for src, dst in (
                (COLAB_DIR / "run_foundry_v4.py", "/content/run_foundry_v4.py"),
                (COLAB_DIR / "train_foundry_v4_local.py", "/content/train_foundry_v4_local.py"),
                (COLAB_DIR / "run_foundry_v5.py", "/content/run_foundry_v5.py"),
                (COLAB_DIR / "run_foundry_v6_a100.py", "/content/run_foundry_v6_a100.py"),
                (COLAB_DIR / "outputs_v5" / "dataset_malaysian_v5.jsonl.gz", "/content/dataset_malaysian_v5.jsonl.gz"),
                (COLAB_DIR / "outputs_v3" / "holdout_v3_details.json", "/content/holdout_v3.json"),
                (COLAB_DIR / "outputs_v4" / "holdout_v4_details.json", "/content/holdout_v4.json"),
                (COLAB_DIR / "outputs_v5" / "v5_hard_test.json", "/content/v5_hard_test.json"),
                (COLAB_DIR / "outputs_v5" / "ood_misc_test.json", "/content/ood_misc_test.json"),
            ):
                run_cli("upload", "-s", SESSION, str(src), dst, sub_timeout=240)
            exec_py("from pathlib import Path; Path('/content/outputs_v6').mkdir(parents=True, exist_ok=True)", timeout=60, sub_timeout=90)
            for ckpt_name in ("v6_synth.jsonl.gz", "teacher_logits_v6.npz"):
                ckpt_p = LOCAL_OUT / ckpt_name
                if ckpt_p.exists():
                    print(f"Uploading cached {ckpt_name}...", flush=True)
                    run_cli("upload", "-s", SESSION, str(ckpt_p), f"/content/outputs_v6/{ckpt_name}", sub_timeout=300)

            cli_flags = json.dumps(["/content/run_foundry_v6_a100.py"] + (["--distill-only"] if args.distill_only else []))
            launch_code = f"""
import os, subprocess
from pathlib import Path
Path('/content/outputs_v6').mkdir(parents=True, exist_ok=True)
pg = subprocess.run(['pgrep', '-f', 'run_foundry_v6_a100.py'], capture_output=True, text=True)
existing = [ln.strip() for ln in pg.stdout.splitlines() if ln.strip().isdigit()]
if existing:
    print('STARTED_PID', existing[0])
else:
    env = {{**os.environ, 'PYTHONUNBUFFERED': '1', 'HF_HUB_ENABLE_HF_TRANSFER': '1', 'PYTORCH_CUDA_ALLOC_CONF': 'expandable_segments:True'}}
    with open('/content/outputs_v6/foundry_v6.log', 'w', encoding='utf-8') as lf:
        p = subprocess.Popen(
            ['python3', '-u'] + {cli_flags},
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
                raise RuntimeError(f"Failed to launch Foundry v6: {r.stderr if r else ''}")

        status_local = LOCAL_OUT / "foundry_v6_status.json"
        last_line, last_tail = "", ""
        failures = 0
        while True:
            time.sleep(30)
            elapsed = int(time.time() - global_start)
            if elapsed > 7200:
                raise TimeoutError("Foundry v6 exceeded 2h timeout")
            hb = exec_py(HEARTBEAT_CODE, timeout=75, sub_timeout=110)
            if hb.returncode != 0:
                failures += 1
                print(f"[{elapsed}s] Keepalive warning ({failures}/8)", flush=True)
                if failures >= 8:
                    raise RuntimeError("Lost Colab session during Foundry v6")
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
                    run_cli("download", "-s", SESSION, "/content/outputs_v6/foundry_v6.log", str(LOCAL_OUT / "foundry_v6.log"), check=False)
                    for ckpt_name in ("v6_synth.jsonl.gz", "teacher_logits_v6.npz"):
                        run_cli("download", "-s", SESSION, f"/content/outputs_v6/{ckpt_name}", str(LOCAL_OUT / ckpt_name), check=False, sub_timeout=240)
                    raise RuntimeError(f"Foundry v6 failed: {st.get('error')}")
            elif tail and tail != last_tail:
                print(f"[{elapsed}s] {tail}", flush=True)
                last_tail = tail

        for name in (
            "foundry_v6_status.json",
            "foundry_v6_report.json",
            "foundry_v6.log",
            "foundry_v6_parity_vectors.json",
            "moneylens_classifier_holdout.bin.gz",
            "moneylens_classifier.bin.gz",
            "moneylens_reranker_holdout.bin.gz",
            "moneylens_reranker.bin.gz",
            "teacher_logits_v6.npz",
            "v6_synth.jsonl.gz",
        ):
            print(f"Downloading {name}...", flush=True)
            run_cli("download", "-s", SESSION, f"/content/outputs_v6/{name}", str(LOCAL_OUT / name), check=(name not in ("teacher_logits_v6.npz", "v6_synth.jsonl.gz")), sub_timeout=300)
        print("All Foundry v6 artifacts downloaded cleanly.", flush=True)
    finally:
        print(f"Stopping Colab session {SESSION}...", flush=True)
        run_cli("stop", "-s", SESSION, check=False, sub_timeout=45)
        usage = run_cli("usage", check=False, sub_timeout=45)
        print(usage.stdout.strip(), flush=True)


if __name__ == "__main__":
    main()
