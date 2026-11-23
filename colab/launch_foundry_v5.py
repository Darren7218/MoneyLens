#!/usr/bin/env python3
"""Provision or attach to Colab Pro GPU for MoneyLens Foundry v5 with checkpoint & teacher-logit backup."""
import argparse
import json
import subprocess
import sys
import tempfile
import time
from pathlib import Path
from types import SimpleNamespace

SESSION = "ml-v5"
REPO_ROOT = Path(r"C:\Users\user\MoneyLens")
COLAB_DIR = REPO_ROOT / "colab"
LOCAL_OUT = COLAB_DIR / "outputs_v5"
LOCAL_OUT.mkdir(parents=True, exist_ok=True)

LOCAL_V4_GZ = COLAB_DIR / "outputs_v4" / "dataset_malaysian_v4.jsonl.gz"
LOCAL_HOLDOUT_V3 = COLAB_DIR / "outputs_v3" / "holdout_v3_details.json"
LOCAL_HOLDOUT_V4 = COLAB_DIR / "outputs_v4" / "holdout_v4_details.json"

HEARTBEAT_CODE = r"""
import json
from pathlib import Path
st_p = Path('/content/outputs_v5/foundry_v5_status.json')
lg_p = Path('/content/outputs_v5/foundry_v5.log')
tz_p = Path('/content/outputs_v5/teacher_logits_v5.npz')
ds_p = Path('/content/outputs_v5/dataset_malaysian_v5.jsonl.gz')
st = st_p.read_text(encoding='utf-8') if st_p.exists() else ''
tail = ''
if lg_p.exists():
    lines = lg_p.read_text(encoding='utf-8', errors='replace').splitlines()
    if lines:
        tail = lines[-1][-180:]
print('STATUS_JSON:' + json.dumps({
    'status': st,
    'tail': tail,
    'has_teacher_npz': tz_p.exists(),
    'has_v5_gz': ds_p.exists(),
}))
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
        res = run_cli("new", "-s", SESSION, *flags, check=False, sub_timeout=180)
        if res.returncode == 0:
            print(f"Allocated Colab runtime: {label}", flush=True)
            info = exec_py("import subprocess; print(subprocess.check_output(['nvidia-smi', '-L']).decode().strip())")
            print(info.stdout.strip(), flush=True)
            return label
        print(f"  unavailable ({res.stderr.strip()[:220]})", flush=True)
    raise RuntimeError("No Colab A100/L4/T4 GPU runtime could be allocated")


def backup_vllm_checkpoints():
    run_cli("download", "-s", SESSION, "/content/outputs_v5/gen_v5_progress.json", str(LOCAL_OUT / "gen_v5_progress.json"), check=False, sub_timeout=90)
    run_cli("download", "-s", SESSION, "/content/outputs_v5/gen_v5_checkpoint.jsonl", str(LOCAL_OUT / "gen_v5_checkpoint.jsonl"), check=False, sub_timeout=240)


def parse_heartbeat(stdout: str):
    for line in stdout.splitlines():
        if line.startswith("STATUS_JSON:"):
            return json.loads(line[len("STATUS_JSON:"):])
    return None


def session_is_lost(res) -> bool:
    txt = f"{res.stdout}\n{res.stderr}".lower()
    return ("appears to be lost" in txt) or ("404" in txt) or ("401" in txt) or ("not found" in txt)


def run_single_session(attempt: int, global_start: float, attach: bool = False, train_only: bool = False, distill_only: bool = False):
    if attach:
        print(f"[Session {attempt}] Attaching to live '{SESSION}' session...", flush=True)
    else:
        allocate_gpu()
        exec_py("from pathlib import Path; Path('/content/outputs_v5').mkdir(parents=True, exist_ok=True)")
        print(f"[Session {attempt}] Uploading scripts & base corpus/holdouts...", flush=True)
        run_cli("upload", "-s", SESSION, str(COLAB_DIR / "run_foundry_v4.py"), "/content/run_foundry_v4.py", sub_timeout=120)
        run_cli("upload", "-s", SESSION, str(COLAB_DIR / "train_foundry_v4_local.py"), "/content/train_foundry_v4_local.py", sub_timeout=120)
        run_cli("upload", "-s", SESSION, str(COLAB_DIR / "run_foundry_v5.py"), "/content/run_foundry_v5.py", sub_timeout=120)
        run_cli("upload", "-s", SESSION, str(LOCAL_V4_GZ), "/content/dataset_malaysian_v4.jsonl.gz", sub_timeout=240)
        run_cli("upload", "-s", SESSION, str(LOCAL_HOLDOUT_V3), "/content/holdout_v3.json", sub_timeout=120)
        run_cli("upload", "-s", SESSION, str(LOCAL_HOLDOUT_V4), "/content/holdout_v4.json", sub_timeout=240)

        ds_gz_local = LOCAL_OUT / "dataset_malaysian_v5.jsonl.gz"
        tz_local = LOCAL_OUT / "teacher_logits_v5.npz"
        if distill_only and ds_gz_local.exists() and tz_local.exists():
            print(f"[Session {attempt}] Uploading cached v5 dataset + teacher_logits_v5.npz for --distill-only...", flush=True)
            run_cli("upload", "-s", SESSION, str(ds_gz_local), "/content/outputs_v5/dataset_malaysian_v5.jsonl.gz", sub_timeout=300)
            run_cli("upload", "-s", SESSION, str(tz_local), "/content/outputs_v5/teacher_logits_v5.npz", sub_timeout=300)
        elif train_only and ds_gz_local.exists():
            print(f"[Session {attempt}] Uploading cached dataset_malaysian_v5.jsonl.gz for --train-only...", flush=True)
            run_cli("upload", "-s", SESSION, str(ds_gz_local), "/content/outputs_v5/dataset_malaysian_v5.jsonl.gz", sub_timeout=300)
        else:
            ckpt_local = LOCAL_OUT / "gen_v5_checkpoint.jsonl"
            prog_local = LOCAL_OUT / "gen_v5_progress.json"
            if ckpt_local.exists() and prog_local.exists():
                print(f"[Session {attempt}] Uploading v5 synthesis checkpoint...", flush=True)
                run_cli("upload", "-s", SESSION, str(ckpt_local), "/content/outputs_v5/gen_v5_checkpoint.jsonl", sub_timeout=240)
                run_cli("upload", "-s", SESSION, str(prog_local), "/content/outputs_v5/gen_v5_progress.json", sub_timeout=90)

        cli_flags = []
        if distill_only and (LOCAL_OUT / "dataset_malaysian_v5.jsonl.gz").exists() and (LOCAL_OUT / "teacher_logits_v5.npz").exists():
            cli_flags = ["--distill-only"]
        elif train_only and (LOCAL_OUT / "dataset_malaysian_v5.jsonl.gz").exists():
            cli_flags = ["--train-only"]

        cmd_args = json.dumps(["/content/run_foundry_v5.py"] + cli_flags)
        launch_code = f"""
import os, subprocess
from pathlib import Path
Path('/content/outputs_v5').mkdir(parents=True, exist_ok=True)
env = {{**os.environ, 'PYTHONUNBUFFERED': '1', 'HF_HUB_ENABLE_HF_TRANSFER': '1'}}
with open('/content/outputs_v5/foundry_v5.log', 'w') as lf:
    p = subprocess.Popen(
        ['python3', '-u'] + {cmd_args},
        stdout=lf, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL,
        start_new_session=True, env=env
    )
print('STARTED_PID', p.pid)
"""
        r = exec_py(launch_code)
        print(r.stdout.strip(), r.stderr.strip(), flush=True)

    status_local = LOCAL_OUT / "foundry_v5_status.json"
    prog_local = LOCAL_OUT / "gen_v5_progress.json"
    last_line, last_tail = "", ""
    last_ckpt_chunk = -1
    if not attach and prog_local.exists():
        try:
            last_ckpt_chunk = len(json.loads(prog_local.read_text(encoding="utf-8")).get("done_chunks", [])) - 1
        except Exception:
            last_ckpt_chunk = -1
    backed_up_ds = False
    backed_up_teachers = False
    exec_failures = 0
    first_poll = attach

    while True:
        if not first_poll:
            time.sleep(60)
        first_poll = False
        elapsed = int(time.time() - global_start)
        if elapsed > 28800:
            backup_vllm_checkpoints()
            run_cli("download", "-s", SESSION, "/content/outputs_v5/foundry_v5.log", str(LOCAL_OUT / "foundry_v5.log"), check=False, sub_timeout=180)
            raise TimeoutError("Foundry v5 exceeded 8-hour global timeout")

        hb = exec_py(HEARTBEAT_CODE, timeout=75, sub_timeout=110)
        if hb.returncode != 0 or session_is_lost(hb):
            exec_failures += 1
            msg = f"{hb.stdout} {hb.stderr}".strip().replace("\n", " ")[:200]
            print(f"[{elapsed}s] Keepalive warning ({exec_failures}/5): {msg}", flush=True)
            if session_is_lost(hb) or exec_failures >= 5:
                return "LOST"
            continue

        parsed = parse_heartbeat(hb.stdout)
        if not parsed:
            exec_failures += 1
            print(f"[{elapsed}s] Heartbeat unparseable ({exec_failures}/5)", flush=True)
            if exec_failures >= 5:
                return "LOST"
            continue
        exec_failures = 0

        if parsed.get("has_v5_gz") and not backed_up_ds:
            run_cli("download", "-s", SESSION, "/content/outputs_v5/dataset_malaysian_v5.jsonl.gz", str(LOCAL_OUT / "dataset_malaysian_v5.jsonl.gz"), check=False, sub_timeout=240)
            backed_up_ds = True
        if parsed.get("has_teacher_npz") and not backed_up_teachers:
            run_cli("download", "-s", SESSION, "/content/outputs_v5/teacher_logits_v5.npz", str(LOCAL_OUT / "teacher_logits_v5.npz"), check=False, sub_timeout=240)
            backed_up_teachers = True

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
            if stage == "GENERATING_V5_VLLM":
                cdone = int(st.get("chunks_done", 0) or 0)
                if cdone > last_ckpt_chunk:
                    backup_vllm_checkpoints()
                    last_ckpt_chunk = cdone
            elif stage == "DONE":
                return "DONE"
            elif stage == "ERROR":
                backup_vllm_checkpoints()
                run_cli("download", "-s", SESSION, "/content/outputs_v5/foundry_v5.log", str(LOCAL_OUT / "foundry_v5.log"), check=False, sub_timeout=180)
                raise RuntimeError(f"Foundry v5 failed: {st.get('error')}")
        elif tail and tail != last_tail:
            print(f"[{elapsed}s] {tail}", flush=True)
            last_tail = tail


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--attach", action="store_true", help="Attach to existing live ml-v5 session")
    parser.add_argument("--train-only", action="store_true", help="Skip vLLM synthesis and train teachers + students from dataset_malaysian_v5.jsonl.gz")
    parser.add_argument("--distill-only", action="store_true", help="Skip HF teachers and distill/export using teacher_logits_v5.npz")
    args = parser.parse_args()

    global_start = time.time()
    try:
        for attempt in range(1, 7):
            has_ds = (LOCAL_OUT / "dataset_malaysian_v5.jsonl.gz").exists()
            has_tz = (LOCAL_OUT / "teacher_logits_v5.npz").exists()
            use_train_only = args.train_only or (attempt > 1 and has_ds)
            use_distill_only = args.distill_only or (attempt > 1 and has_ds and has_tz)
            outcome = run_single_session(
                attempt, global_start,
                attach=(args.attach and attempt == 1),
                train_only=use_train_only,
                distill_only=use_distill_only,
            )
            if outcome == "DONE":
                break
            print(f"Session {attempt} lost ({outcome}); stopping dead session and auto-resuming from local backup...", flush=True)
            run_cli("stop", "-s", SESSION, check=False, sub_timeout=45)
            time.sleep(5)
        else:
            raise RuntimeError("Exhausted 6 Colab auto-resume attempts before reaching DONE")

        artifacts = [
            "foundry_v5_status.json",
            "foundry_v5_report.json",
            "foundry_v5.log",
            "dataset_malaysian_v5.jsonl.gz",
            "v5_hard_test.json",
            "ood_misc_test.json",
            "moneylens_classifier_holdout.json.gz",
            "moneylens_classifier.json.gz",
            "moneylens_reranker_holdout.bin.gz",
            "moneylens_reranker.bin.gz",
        ]
        for name in artifacts:
            print(f"Downloading {name}...", flush=True)
            run_cli("download", "-s", SESSION, f"/content/outputs_v5/{name}", str(LOCAL_OUT / name), sub_timeout=300)
        print("All Foundry v5 artifacts downloaded cleanly.", flush=True)
    finally:
        print(f"Stopping Colab session {SESSION}...", flush=True)
        run_cli("stop", "-s", SESSION, check=False, sub_timeout=45)
        usage = run_cli("usage", check=False, sub_timeout=45)
        print(usage.stdout.strip(), flush=True)


if __name__ == "__main__":
    main()
