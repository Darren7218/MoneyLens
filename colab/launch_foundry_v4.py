#!/usr/bin/env python3
"""Provision or attach to Colab Pro GPU via colab_cli with bounded subprocess timeouts, keepalive & auto-resume."""
import argparse
import json
import subprocess
import sys
import tempfile
import time
from pathlib import Path
from types import SimpleNamespace

SESSION = "ml-v4"
REPO_ROOT = Path(r"C:\Users\user\MoneyLens")
LOCAL_SEED = REPO_ROOT / "colab" / "outputs_v3" / "dataset_malaysian_v3.jsonl"
LOCAL_HOLDOUT_V3 = REPO_ROOT / "colab" / "outputs_v3" / "holdout_v3_details.json"
LOCAL_SCRIPT = REPO_ROOT / "colab" / "run_foundry_v4.py"
LOCAL_OUT = REPO_ROOT / "colab" / "outputs_v4"
LOCAL_OUT.mkdir(parents=True, exist_ok=True)

HEARTBEAT_CODE = r"""
import json
from pathlib import Path
st_p = Path('/content/outputs_v4/foundry_v4_status.json')
lg_p = Path('/content/outputs_v4/foundry_v4.log')
st = st_p.read_text(encoding='utf-8') if st_p.exists() else ''
tail = ''
if lg_p.exists():
    lines = lg_p.read_text(encoding='utf-8', errors='replace').splitlines()
    if lines:
        tail = lines[-1][-180:]
print('STATUS_JSON:' + json.dumps({'status': st, 'tail': tail}))
"""


def run_cli(*args, check=True, sub_timeout=90):
    cmd = [sys.executable, "-m", "colab_cli.cli", "--auth=oauth2", *args]
    try:
        res = subprocess.run(cmd, capture_output=True, text=True, timeout=sub_timeout)
    except subprocess.TimeoutExpired:
        res = SimpleNamespace(returncode=124, stdout="", stderr=f"local subprocess timeout after {sub_timeout}s")
    if check and res.returncode != 0:
        raise RuntimeError(f"colab_cli {' '.join(args)} failed ({res.returncode}):\n{res.stdout}\n{res.stderr}")
    return res


def exec_py(code: str, timeout: int = 60, sub_timeout: int | None = None):
    with tempfile.NamedTemporaryFile("w", suffix=".py", delete=False, encoding="utf-8") as tf:
        tf.write(code)
        tmp = tf.name
    try:
        return run_cli(
            "exec", "-s", SESSION, "-f", tmp, "--timeout", str(timeout),
            check=False, sub_timeout=sub_timeout or (timeout + 20),
        )
    finally:
        Path(tmp).unlink(missing_ok=True)


def allocate_gpu():
    shapes = [
        (["--gpu", "A100", "--high-mem"], "A100 High-RAM"),
        (["--gpu", "L4"], "L4"),
        (["--gpu", "T4", "--high-mem"], "T4 High-RAM"),
    ]
    run_cli("stop", "-s", SESSION, check=False, sub_timeout=45)
    for flags, label in shapes:
        print(f"Trying Colab Pro runtime: {label}...", flush=True)
        res = run_cli("new", "-s", SESSION, *flags, check=False, sub_timeout=90)
        if res.returncode == 0:
            print(f"Allocated Colab runtime: {label}", flush=True)
            info = exec_py("import subprocess; print(subprocess.check_output(['nvidia-smi', '-L']).decode().strip())")
            print(info.stdout.strip(), flush=True)
            return label
        print(f"  unavailable ({res.stderr.strip()[:220]})", flush=True)
    raise RuntimeError("No Colab A100/L4/T4 GPU runtime could be allocated")


def backup_checkpoints():
    run_cli("download", "-s", SESSION, "/content/outputs_v4/gen_v4_progress.json", str(LOCAL_OUT / "gen_v4_progress.json"), check=False, sub_timeout=90)
    run_cli("download", "-s", SESSION, "/content/outputs_v4/gen_v4_checkpoint.jsonl", str(LOCAL_OUT / "gen_v4_checkpoint.jsonl"), check=False, sub_timeout=300)


def parse_heartbeat(stdout: str):
    for line in stdout.splitlines():
        if line.startswith("STATUS_JSON:"):
            return json.loads(line[len("STATUS_JSON:"):])
    return None


def session_is_lost(res) -> bool:
    txt = f"{res.stdout}\n{res.stderr}".lower()
    return ("appears to be lost" in txt) or ("404" in txt) or ("401" in txt) or ("not found" in txt)


def run_single_session(attempt: int, global_start: float, attach: bool = False, train_only: bool = False):
    if attach:
        print(f"[Session {attempt}] Attaching directly to live '{SESSION}' session...", flush=True)
    else:
        allocate_gpu()
        exec_py("from pathlib import Path; Path('/content/outputs_v4').mkdir(parents=True, exist_ok=True)")
        print(f"[Session {attempt}] Uploading holdout_v3.json and run_foundry_v4.py...", flush=True)
        run_cli("upload", "-s", SESSION, str(LOCAL_HOLDOUT_V3), "/content/holdout_v3.json", sub_timeout=180)
        run_cli("upload", "-s", SESSION, str(LOCAL_SCRIPT), "/content/run_foundry_v4.py", sub_timeout=120)

        prog_local = LOCAL_OUT / "gen_v4_progress.json"
        if train_only:
            ds_local = LOCAL_OUT / "dataset_malaysian_v4.jsonl"
            if not ds_local.exists():
                raise FileNotFoundError(f"--train-only requires {ds_local}")
            print(f"[Session {attempt}] Uploading pre-generated dataset_malaysian_v4.jsonl ({ds_local.stat().st_size} bytes)...", flush=True)
            run_cli("upload", "-s", SESSION, str(ds_local), "/content/outputs_v4/dataset_malaysian_v4.jsonl", sub_timeout=360)
            if prog_local.exists():
                run_cli("upload", "-s", SESSION, str(prog_local), "/content/outputs_v4/gen_v4_progress.json", sub_timeout=90)
        else:
            run_cli("upload", "-s", SESSION, str(LOCAL_SEED), "/content/seed_v3.jsonl", sub_timeout=180)
            ckpt_local = LOCAL_OUT / "gen_v4_checkpoint.jsonl"
            if ckpt_local.exists() and prog_local.exists():
                print(f"[Session {attempt}] Uploading saved checkpoint...", flush=True)
                run_cli("upload", "-s", SESSION, str(ckpt_local), "/content/outputs_v4/gen_v4_checkpoint.jsonl", sub_timeout=300)
                run_cli("upload", "-s", SESSION, str(prog_local), "/content/outputs_v4/gen_v4_progress.json", sub_timeout=90)

        cmd_args = "['/content/run_foundry_v4.py', '--train-only']" if train_only else "['/content/run_foundry_v4.py']"
        launch_code = f"""
import os, subprocess
from pathlib import Path
Path('/content/outputs_v4').mkdir(parents=True, exist_ok=True)
env = {{**os.environ, 'PYTHONUNBUFFERED': '1', 'HF_HUB_ENABLE_HF_TRANSFER': '1'}}
with open('/content/outputs_v4/foundry_v4.log', 'w') as lf:
    p = subprocess.Popen(
        ['python3', '-u'] + {cmd_args},
        stdout=lf, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL,
        start_new_session=True, env=env
    )
print('STARTED_PID', p.pid)
"""
        r = exec_py(launch_code)
        print(r.stdout.strip(), r.stderr.strip(), flush=True)

    status_local = LOCAL_OUT / "foundry_v4_status.json"
    prog_local = LOCAL_OUT / "gen_v4_progress.json"
    last_line = ""
    last_tail = ""
    last_ckpt_chunk = -1
    if not attach and prog_local.exists():
        try:
            last_ckpt_chunk = len(json.loads(prog_local.read_text(encoding="utf-8")).get("done_chunks", [])) - 1
        except Exception:
            last_ckpt_chunk = -1
    exec_failures = 0
    first_poll = attach

    while True:
        if not first_poll:
            time.sleep(60)
        first_poll = False
        elapsed = int(time.time() - global_start)
        if elapsed > 36000:
            backup_checkpoints()
            run_cli("download", "-s", SESSION, "/content/outputs_v4/foundry_v4.log", str(LOCAL_OUT / "foundry_v4.log"), check=False, sub_timeout=180)
            raise TimeoutError("Foundry v4 exceeded 10-hour global timeout")

        hb = exec_py(HEARTBEAT_CODE, timeout=55, sub_timeout=75)
        if hb.returncode != 0 or session_is_lost(hb):
            exec_failures += 1
            msg = f"{hb.stdout} {hb.stderr}".strip().replace("\n", " ")[:200]
            print(f"[{elapsed}s] Keepalive warning ({exec_failures}/3): {msg}", flush=True)
            if session_is_lost(hb) or exec_failures >= 3:
                return "LOST"
            continue

        parsed = parse_heartbeat(hb.stdout)
        if not parsed:
            exec_failures += 1
            print(f"[{elapsed}s] Heartbeat unparseable ({exec_failures}/3)", flush=True)
            if exec_failures >= 3:
                return "LOST"
            continue
        exec_failures = 0

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

            stage = st.get("stage")
            if stage == "GENERATING_VLLM":
                cdone = int(st.get("chunks_done", 0) or 0)
                if cdone > last_ckpt_chunk:
                    backup_checkpoints()
                    last_ckpt_chunk = cdone
            elif stage == "DONE":
                return "DONE"
            elif stage == "ERROR":
                backup_checkpoints()
                run_cli("download", "-s", SESSION, "/content/outputs_v4/foundry_v4.log", str(LOCAL_OUT / "foundry_v4.log"), check=False, sub_timeout=180)
                raise RuntimeError(f"Foundry v4 failed: {st.get('error')}")
        elif tail and tail != last_tail:
            print(f"[{elapsed}s] {tail}", flush=True)
            last_tail = tail


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--attach", action="store_true", help="Attach to existing running ml-v4 session without restarting it")
    parser.add_argument("--train-only", action="store_true", help="Upload pre-generated dataset_malaysian_v4.jsonl and skip vLLM synthesis")
    args = parser.parse_args()

    global_start = time.time()
    try:
        for attempt in range(1, 7):
            outcome = run_single_session(attempt, global_start, attach=(args.attach and attempt == 1), train_only=args.train_only)
            if outcome == "DONE":
                break
            print(f"Session {attempt} lost ({outcome}); stopping dead session and auto-resuming from local checkpoint...", flush=True)
            run_cli("stop", "-s", SESSION, check=False, sub_timeout=45)
            time.sleep(5)
        else:
            raise RuntimeError("Exhausted 6 Colab auto-resume attempts before reaching DONE")

        artifacts = [
            "foundry_v4_status.json",
            "foundry_v4_report.json",
            "foundry_v4.log",
            "holdout_v4_details.json",
            "merchant_lexicon_v4.json",
            "moneylens_classifier_holdout.json.gz",
            "moneylens_classifier.json.gz",
            "gen_v4_progress.json",
        ]
        if not args.train_only:
            artifacts.insert(0, "dataset_malaysian_v4.jsonl")
        for name in artifacts:
            print(f"Downloading {name}...", flush=True)
            run_cli("download", "-s", SESSION, f"/content/outputs_v4/{name}", str(LOCAL_OUT / name), sub_timeout=300)
        print("All Foundry v4 artifacts downloaded cleanly.", flush=True)
    finally:
        print(f"Stopping Colab session {SESSION}...", flush=True)
        run_cli("stop", "-s", SESSION, check=False, sub_timeout=45)
        usage = run_cli("usage", check=False, sub_timeout=45)
        print(usage.stdout.strip(), flush=True)


if __name__ == "__main__":
    main()
