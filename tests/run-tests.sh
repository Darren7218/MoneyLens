#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${JSON_JAR:?Set JSON_JAR to org.json jar for JVM tests}"
mkdir -p build/tests
javac -encoding UTF-8 -cp "$JSON_JAR" -d build/tests app/src/main/java/app/moneylens/Ledger.java app/src/main/java/app/moneylens/MlClassifier.java app/src/main/java/app/moneylens/PaymentParser.java app/src/main/java/app/moneylens/Crypto.java app/src/main/java/app/moneylens/Finance.java app/src/main/java/app/moneylens/Extras.java app/src/main/java/app/moneylens/CsvImport.java app/src/main/java/app/moneylens/ReceiptParser.java app/src/main/java/app/moneylens/BackupArchive.java tests/CoreTests.java tests/ExtendedTests.java
java -cp "build/tests:$JSON_JAR" app.moneylens.CoreTests
java -cp "build/tests:$JSON_JAR" app.moneylens.ExtendedTests
for f in i18n.js format.js views/home.js views/plan.js views/transactions.js views/settings.js actions.js app.js; do
  node --check "app/src/main/assets/$f"
done
node tests/ui.test.js
