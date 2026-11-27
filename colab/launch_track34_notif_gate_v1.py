#!/usr/bin/env python3
"""Provision Colab Pro GPU (NEVER H100) for Track 3+4 Hard Blind Synthesizer & NotificationGate INT8 Training."""
import argparse
import json
import subprocess
import sys
import tempfile
import time
from pathlib import Path
from types import SimpleNamespace

SESSION = "ml-track34-v1"
REPO_ROOT = Path(r"C:\Users\user\MoneyLens")
COLAB_DIR = REPO_ROOT / "colab"
LOCAL_OUT = COLAB_DIR / "outputs_track34_v1"
LOCAL_OUT.mkdir(parents=True, exist_ok=True)

HEARTBEAT_CODE = r"""
import json
from pathlib import Path
st_p = Path('/content/outputs_track34_v1/track34_status.json')
lg_p = Path('/content/outputs_track34_v1/track34.log')
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
    # NEVER request H100
    shapes = [
        (["--gpu", "L4"], "L4"),
        (["--gpu", "T4"], "T4"),
        (["--gpu", "G4"], "G4"),
        (["--gpu", "A100"], "A100"),
        (["--gpu", "T4", "--high-mem"], "T4 High-RAM"),
        (["--gpu", "A100", "--high-mem"], "A100 High-RAM"),
    ]
    run_cli("stop", "-s", SESSION, check=False, sub_timeout=45)
    for flags, label in shapes:
        print(f"Trying Colab Pro runtime: {label}...", flush=True)
        res = run_cli("new", "-s", SESSION, *flags, check=False, sub_timeout=135)
        if res.returncode == 0:
            print(f"Allocated Colab runtime: {label}", flush=True)
            time.sleep(3)
            return label
        print(f"  unavailable ({res.stderr.strip()[:220]})", flush=True)
    raise RuntimeError("No Colab L4/T4/G4/A100 GPU runtime could be allocated")


def parse_heartbeat(stdout: str):
    for line in stdout.splitlines():
        if line.startswith("STATUS_JSON:"):
            return json.loads(line[len("STATUS_JSON:"):])
    return None


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--attach", action="store_true", help="Attach to existing live ml-track34-v1 session")
    args = parser.parse_args()
    global_start = time.time()
    try:
        if not args.attach:
            allocate_gpu()
            print("Uploading Track 3+4 pipeline script...", flush=True)
            run_cli("upload", "-s", SESSION, str(COLAB_DIR / "run_track34_notif_gate_v1.py"), "/content/run_track34_notif_gate_v1.py", sub_timeout=120)

            launch_code = """
import os, subprocess
from pathlib import Path
Path('/content/outputs_track34_v1').mkdir(parents=True, exist_ok=True)
pg = subprocess.run(['pgrep', '-f', 'run_track34_notif_gate_v1.py'], capture_output=True, text=True)
existing = [ln.strip() for ln in pg.stdout.splitlines() if ln.strip().isdigit()]
if existing:
    print('STARTED_PID', existing[0])
else:
    env = {**os.environ, 'PYTHONUNBUFFERED': '1'}
    with open('/content/outputs_track34_v1/track34.log', 'w', encoding='utf-8') as lf:
        p = subprocess.Popen(
            ['python3', '-u', '/content/run_track34_notif_gate_v1.py'],
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
                if attempt < 3:
                    print(f"Launch RPC attempt {attempt} failed ({r.returncode}), retrying in 6s...", flush=True)
                    time.sleep(6)
            if r is None or r.returncode != 0 or "STARTED_PID" not in r.stdout:
                raise RuntimeError(f"Failed to launch run_track34_notif_gate_v1.py ({r.returncode if r else -1}): {r.stderr if r else ''}")
        else:
            print(f"Attaching to live Colab session {SESSION}...", flush=True)

        status_local = LOCAL_OUT / "track34_status.json"
        last_line, last_tail = "", ""
        failures = 0
        while True:
            time.sleep(25)
            elapsed = int(time.time() - global_start)
            if elapsed > 3600:
                raise TimeoutError("Track 3+4 exceeded 1h timeout")
            hb = exec_py(HEARTBEAT_CODE, timeout=75, sub_timeout=110)
            if hb.returncode != 0:
                failures += 1
                print(f"[{elapsed}s] Keepalive warning ({failures}/8)", flush=True)
                if failures >= 8:
                    raise RuntimeError("Lost Colab session during Track 3+4")
                continue
            parsed = parse_heartbeat(hb.stdout)
            if not parsed:
                failures += 1
                if failures >= 6:
                    raise RuntimeError("Heartbeat unparseable")
                continue
            failures = 0
            raw_st = parsed.get("status", "")
            tail = parsed.get("tail", "")
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
                    run_cli("download", "-s", SESSION, "/content/outputs_track34_v1/track34.log", str(LOCAL_OUT / "track34.log"), check=False)
                    raise RuntimeError(f"Track 3+4 failed: {st.get('error')}")
            elif tail and tail != last_tail:
                print(f"[{elapsed}s] {tail}", flush=True)
                last_tail = tail

        for name in (
            "track34_status.json",
            "track34_report.json",
            "track34.log",
            "moneylens_notif_gate.bin.gz",
            "notif_gate_parity_vectors.json",
            "track3_dev.json",
            "track3_hard_blind.json",
        ):
            print(f"Downloading {name}...", flush=True)
            run_cli("download", "-s", SESSION, f"/content/outputs_track34_v1/{name}", str(LOCAL_OUT / name), check=True, sub_timeout=180)
        print("All Track 3+4 artifacts downloaded cleanly.", flush=True)
    finally:
        print(f"Stopping Colab session {SESSION}...", flush=True)
        run_cli("stop", "-s", SESSION, check=False, sub_timeout=45)
        usage = run_cli("usage", check=False, sub_timeout=45)
        print(usage.stdout.strip(), flush=True)


if __name__ == "__main__":
    main()
