#!/usr/bin/env python3
"""Offline Private Evaluator for Track 6 Opt-In On-Device Correction Logs.

Decrypts an encrypted MoneyLens `.mlb` backup strictly in memory via local Crypto + BackupArchive
(zero plaintext backup files written to disk) or reads a local state JSON file, evaluates
logged corrections against current PaymentParser/MlClassifier/MlNeuralReranker/NotificationGate,
and reports accuracy & top misclassified merchants/sources.
"""
import argparse
import json
import subprocess
import sys
import tempfile
from pathlib import Path

REPO = Path(r"C:\Users\user\MoneyLens")
ASSETS = REPO / "app" / "src" / "main" / "assets"

JAVA_RUNNER = r"""
package app.moneylens;

import org.json.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public final class PrivateCorrectionEval {
  public static void main(String[] args) throws Exception {
    String mode = args[0]; // "--mlb", "--json", or "--self-test"
    Path assets = Paths.get(args[1]);
    MlClassifier.loadFromPath(assets.resolve("moneylens_classifier.json.gz"));
    MlNeuralReranker.loadFromPath(assets.resolve("moneylens_reranker.bin.gz"));
    MerchantCanonicalizer.loadFromPath(assets.resolve("moneylens_merchant_canon.bin.gz"));
    NotificationGate.loadFromPath(assets.resolve("moneylens_notif_gate.bin.gz"));

    JSONObject state;
    if ("--mlb".equals(mode)) {
      byte[] enc = Files.readAllBytes(Paths.get(args[2]));
      char[] pw = args[3].toCharArray();
      BackupArchive.Contents c = BackupArchive.unpack(Crypto.restore(enc, pw));
      state = c.state;
    } else if ("--json".equals(mode)) {
      state = new JSONObject(Files.readString(Paths.get(args[2]), StandardCharsets.UTF_8));
      Ledger.migrate(state);
      Ledger.validate(state);
    } else {
      throw new IllegalArgumentException("Unknown mode: " + mode);
    }

    JSONArray logs = state.optJSONArray("correctionLog");
    int total = logs == null ? 0 : logs.length();
    int catTested = 0, catHit = 0, kindTested = 0, kindHit = 0, ignoreCount = 0;
    Map<String, int[]> byMerchant = new LinkedHashMap<>();
    Map<String, int[]> bySource = new LinkedHashMap<>();

    for (int i = 0; logs != null && i < logs.length(); i++) {
      JSONObject entry = logs.getJSONObject(i);
      String action = entry.optString("action", "");
      String pkg = entry.optString("sourcePackage", "");
      String src = entry.optString("source", "");
      JSONObject bef = entry.optJSONObject("before");
      JSONObject aft = entry.optJSONObject("after");
      if (bef == null || aft == null) continue;
      String merch = aft.optString("merchant", bef.optString("merchant", "")).trim();
      String srcKey = !pkg.isEmpty() ? pkg : (!src.isEmpty() ? src : "manual");

      if ("draftIgnore".equals(action) || aft.optBoolean("ignored", false)) {
        ignoreCount++;
        bySource.computeIfAbsent(srcKey, _k -> new int[2])[1]++;
        continue;
      }

      String wantCat = aft.optString("category", "");
      String wantKind = aft.optString("kind", "");
      if (!wantCat.isEmpty() && !merch.isEmpty()) {
        catTested++;
        String[] pred = PaymentParser.classify(merch, srcKey, merch);
        boolean ok = wantCat.equalsIgnoreCase(pred[0]);
        if (ok) catHit++;
        int[] mb = byMerchant.computeIfAbsent(merch, _k -> new int[2]);
        mb[0] += ok ? 1 : 0;
        mb[1]++;
        int[] sb = bySource.computeIfAbsent(srcKey, _k -> new int[2]);
        sb[0] += ok ? 1 : 0;
        sb[1]++;
      }
      if (!wantKind.isEmpty() && "draftConfirm".equals(action)) {
        kindTested++;
        if (wantKind.equalsIgnoreCase(bef.optString("kind", ""))) kindHit++;
      }
    }

    JSONArray topMisMerch = new JSONArray();
    for (Map.Entry<String, int[]> e : byMerchant.entrySet()) {
      int[] v = e.getValue();
      if (v[0] < v[1]) topMisMerch.put(new JSONObject().put("merchant", e.getKey()).put("correct", v[0]).put("total", v[1]));
    }
    JSONArray srcStats = new JSONArray();
    for (Map.Entry<String, int[]> e : bySource.entrySet()) {
      int[] v = e.getValue();
      srcStats.put(new JSONObject().put("source", e.getKey()).put("correct", v[0]).put("total", v[1]));
    }

    JSONObject report = new JSONObject()
        .put("totalEntries", total)
        .put("optIn", state.getJSONObject("settings").optBoolean("correctionLogOptIn", false))
        .put("catTested", catTested)
        .put("catAccuracy", catTested > 0 ? Math.round(10000.0 * catHit / catTested) / 10000.0 : 1.0)
        .put("kindTested", kindTested)
        .put("kindAccuracy", kindTested > 0 ? Math.round(10000.0 * kindHit / kindTested) / 10000.0 : 1.0)
        .put("draftIgnoreCount", ignoreCount)
        .put("misclassifiedMerchants", topMisMerch)
        .put("bySource", srcStats);
    System.out.println("PRIVATE_EVAL_JSON:" + report.toString());
  }
}
"""

JAVA_SELFTEST = r"""
package app.moneylens;

import org.json.*;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;

public final class CreateSampleEncryptedBackup {
  public static void main(String[] args) throws Exception {
    Path outMlb = Paths.get(args[0]);
    String pw = args[1];
    JSONObject s = Ledger.initial();
    Ledger.apply(s, "settings", new JSONObject().put("correctionLogOptIn", true));
    Ledger.apply(s, "transaction", new JSONObject()
        .put("key", "k1").put("kind", "expense").put("amount", "12.50")
        .put("account", "cash").put("category", "Miscellaneous")
        .put("merchant", "ZUS Coffee").put("date", LocalDate.now().toString()));
    String tid = s.getJSONArray("transactions").getJSONObject(0).getString("id");
    Ledger.apply(s, "quickNote", new JSONObject().put("id", tid).put("note", "Latte").put("category", "Food & drinks"));

    Ledger.apply(s, "draft", new JSONObject().put("id", "d-1").put("merchant", "Random Trap").put("amount", 1000).put("source", "PromoApp"));
    Ledger.apply(s, "ignore", new JSONObject().put("id", "d-1"));

    byte[] archive = BackupArchive.pack(s, new LinkedHashMap<>());
    byte[] encrypted = Crypto.backup(archive, pw.toCharArray());
    Files.write(outMlb, encrypted);
  }
}
"""


def find_json_jar() -> str:
    local = list((REPO / "tests" / "lib").glob("json-*.jar"))
    if local:
        return str(local[0])
    gradle = Path.home() / ".gradle" / "caches" / "modules-2" / "files-2.1" / "org.json" / "json"
    for p in gradle.rglob("json-*.jar"):
        if "sources" not in p.name and "javadoc" not in p.name:
            return str(p)
    raise RuntimeError("org.json jar not found")


def compile_java(extra_srcs, cls_out: Path, jar: str):
    java_srcs = [
        str(jf) for jf in (REPO / "app" / "src" / "main" / "java" / "app" / "moneylens").glob("*.java")
        if "import android" not in jf.read_text(encoding="utf-8", errors="ignore")
    ] + [str(s) for s in extra_srcs]
    subprocess.run(["javac", "-encoding", "UTF-8", "-cp", jar, "-d", str(cls_out), *java_srcs], check=True)


def run_eval_cmd(cls_out: Path, jar: str, mode_args):
    res = subprocess.run(
        ["java", "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-cp", f"{cls_out};{jar}", "app.moneylens.PrivateCorrectionEval", *mode_args],
        text=True, encoding="utf-8", capture_output=True,
    )
    if res.returncode != 0:
        raise RuntimeError(f"PrivateCorrectionEval failed:\n{res.stdout}\n{res.stderr}")
    for line in res.stdout.splitlines():
        if line.startswith("PRIVATE_EVAL_JSON:"):
            return json.loads(line[len("PRIVATE_EVAL_JSON:"):])
    raise RuntimeError(f"Missing PRIVATE_EVAL_JSON output:\n{res.stdout}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--encrypted-backup", help="Path to password-encrypted .mlb backup")
    parser.add_argument("--password", help="Backup decryption password")
    parser.add_argument("--state-json", help="Path to local state.json file")
    parser.add_argument("--self-test", action="store_true", help="Create temp encrypted .mlb backup and verify private eval")
    args = parser.parse_args()

    jar = find_json_jar()
    with tempfile.TemporaryDirectory() as td:
        td_p = Path(td)
        cls_out = td_p / "classes"
        cls_out.mkdir()
        eval_src = td_p / "PrivateCorrectionEval.java"
        eval_src.write_text(JAVA_RUNNER, encoding="utf-8")
        extra = [eval_src]
        if args.self_test:
            st_src = td_p / "CreateSampleEncryptedBackup.java"
            st_src.write_text(JAVA_SELFTEST, encoding="utf-8")
            extra.append(st_src)
        compile_java(extra, cls_out, jar)

        if args.self_test:
            sample_mlb = td_p / "sample_backup.mlb"
            pw = "track6-test-pw-2026"
            subprocess.run(
                ["java", "-cp", f"{cls_out};{jar}", "app.moneylens.CreateSampleEncryptedBackup", str(sample_mlb), pw],
                check=True,
            )
            rep = run_eval_cmd(cls_out, jar, ["--mlb", str(ASSETS), str(sample_mlb), pw])
            print("SELF-TEST PRIVATE EVAL:", json.dumps(rep, ensure_ascii=False))
            assert rep["optIn"] is True
            assert rep["totalEntries"] == 2
            assert rep["catTested"] == 1 and rep["catAccuracy"] == 1.0
            assert rep["draftIgnoreCount"] == 1
            print("PASS Track 6 eval_private_corrections --self-test")
            return

        if args.encrypted_backup:
            if not args.password:
                raise SystemExit("--password is required with --encrypted-backup")
            rep = run_eval_cmd(cls_out, jar, ["--mlb", str(ASSETS), args.encrypted_backup, args.password])
        elif args.state_json:
            rep = run_eval_cmd(cls_out, jar, ["--json", str(ASSETS), args.state_json])
        else:
            raise SystemExit("Provide --self-test, --encrypted-backup + --password, or --state-json")
        print(json.dumps(rep, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
