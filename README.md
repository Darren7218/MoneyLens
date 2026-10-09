# MoneyLens 0.2.0 — personal Android build

Offline expense tracking for Android 9+, with MYR accounting, a light/dark olive interface, notification review, bundled receipt OCR, imports and planning. This is an Android APK for sideloading, not a hosted website or Google Play release.

## Install the update

1. Make an encrypted backup in your current MoneyLens installation.
2. Open `MoneyLens-0.2.0.apk` on your phone and install over 0.1.0. Both releases use the same signing key and application ID. Do not uninstall first: uninstalling removes local data and its encryption key.
3. Open the app. Schema 1 data migrates to schema 2 without resetting transactions.
4. In Settings, choose payment source apps, enable Android notification access, and allow MoneyLens review prompts. Access is optional; manual tracking works without it.
5. Use Home → Scan receipt or Paste payment. Verify every suggestion before recording.
6. Use Settings → Import CSV for bank statements. Map columns, check possible duplicates, and select the rows to save. Transfer rows need manual review.
7. Configure quiet hours, digest, budget thresholds and bill reminders under Reminders & privacy.
8. Set up a password-encrypted backup folder if you want approximate daily backups. Verify a restore before relying on recovery.
9. Add the MoneyLens home-screen widget. Long-press its launcher icon for expense, receipt, Inbox and favorite shortcuts.

The APK starts with an empty ledger and Cash account. The separate HTML preview contains fictional demonstration data only. The app has no INTERNET permission; source banking apps and user-selected document providers can have their own network access.

## Implemented workflows

- Manual expenses, income, transfers, refunds; remembered defaults, favorites, repeat entry, custom categories and tags.
- Exact integer-sen balanced postings; split categories; linked refunds; edit history, void and undo.
- Bank, cash, wallet and card accounts; opening balances; comparison with statement balances and explicit equity adjustments.
- Shared expenses, outstanding receivables and repayments that do not count as income.
- Installment liability, exact payment distribution and transfer repayment schedules; fees recorded with purchase.
- Original foreign currency/amount, actual MYR settlement, included conversion fee and decimal rate history. Unsplit foreign purchases allocate fees to a separate expense category.
- Search and filters for dates, merchant/category text, account, amount range, tags and source.
- Selected-app notification capture; conservative MYR parsing; exact callback deduplication; review/ignore, linking to existing transactions and batch review.
- User-approved merchant rules, priorities, source constraints, preview and undo of the last 30 rule changes.
- Salary-cycle budgets (days 1–31 clamped in short months), optional unused-budget rollover, historical limits and threshold reminders.
- Weekly/monthly/yearly/one-time bills, variable estimates, matching existing payments, cancellation reminders and subscription suggestions.
- Goals as virtual earmarks, six-cycle spending and cash-flow reports, comparable elapsed-day spending, daily allowance and explained available-to-spend estimate.
- Receipt photo/picker, bundled ML Kit Latin and Chinese recognition, encrypted JPEG attachments, review before posting. Share image/text into MoneyLens.
- CSV mapping/preview, exact-file row deduplication, possible-duplicate review and import-batch undo that keeps edited replacements.
- Home-screen widget and launcher shortcuts. Amounts are hidden unless enabled and app lock is off.
- Device-credential app lock; optional pause of capture while the app is closed/locked.
- AES-GCM encrypted vault and attachments; encrypted portable archive; legacy backup restore; local pre-restore recovery UI.
- Approximate daily encrypted backups to a user-chosen folder, last-success/error status; manual CSV and redacted diagnostic export.

## Architecture actually shipped

```
Local HTML/CSS/JS → asynchronous Android bridge → Ledger / Extras / Finance → Vault
NotificationListener → allowlist → PaymentParser → durable review draft → Ledger
Photo/share → bounded image decode → bundled ML Kit → ReceiptParser → review draft
CSV picker → CsvImport mapping/preview → selected rows → Ledger
JobScheduler → durable prompt replay / reminders / encrypted backup
```

Pure Java domain classes have no Android dependency. All mutations are validated on a private snapshot. The synchronized Vault serializes commits and writes encrypted state through AtomicFile. Posting amounts are signed 64-bit minor units; currency conversion rates use decimal arithmetic. Drafts, bills and goals do not post money until explicitly confirmed.

This implementation extends the compatible 0.1.0 architecture: Java, local WebView, encrypted JSON snapshots and JobScheduler. It does **not** implement the proposed Kotlin/Compose, Room/SQLCipher, StateFlow or WorkManager stack. Those are engineering migration options, not claims about this APK. The snapshot store rewrites state per mutation and scans records for reports; it is not benchmarked for the plan's 50,000-transaction target. Limits: 100,000 transaction revisions, 100 accounts, 50 MB state, 64 MB expanded portable archive, 1,000 CSV data rows per import. Large histories need an indexed database migration.

The WebView serves only bundled assets and authenticated receipt bytes at a local HTTPS origin. External navigation, file/content access and mixed content are blocked. User text is escaped; no remote script is loaded. Temporary camera files are removed after processing. No telemetry, ads or internet permission is included.

## Security and recovery behavior

The Keystore key is accessible to this app for background operation; device authentication gates the UI. The capture-while-locked switch pauses intake, not cryptographic key access. A strict authentication-bound vault is not implemented. Notification text is parsed in memory and discarded; minimal draft fields and amount candidates remain encrypted. No provider-specific formats are claimed verified.

Portable backups use PBKDF2-HMAC-SHA256 (310,000 iterations) and AES-GCM. Version-2 archives include referenced receipts; legacy version-1 JSON backups still restore. Restore authenticates, checks bounded archive paths/digests, migrates and validates balanced postings before atomically replacing state. A previous encrypted ledger copy is retained locally. Restore clears transient drafts, sources and UI lock; configure permissions again. Automatic backup configuration is device-only and excluded from portable files. Scheduled files are retained until you remove them; no silent retention deletion is performed.

## Build

Requirements: JDK 17, Android SDK platform 35, build-tools 35.0.0. Gradle 8.9 and Android Gradle Plugin 8.7.3 are pinned. The first build needs dependency downloads. Models `text-recognition` and `text-recognition-chinese` 16.0.1 are bundled, increasing APK size to about 49 MB.

Set `ANDROID_HOME` or `sdk.dir` in local.properties. Restore the separately supplied private key to `build/moneylens-preview.jks` (alias `moneylens`, password `android`). It is a personal signing key, not an encryption password. Preserve it for compatible updates and never commit it to GitHub.

```sh
./gradlew :app:assembleRelease
```

Output: `app/build/outputs/apk/release/app-release.apk`. `build.sh` delegates to this build. Release lint is disabled because the lint artifact was unavailable in the offline dependency cache; no lint pass is claimed. Run Android Studio lint before wider distribution.

## Verification

See `docs/verification.md` and the test output files. Core and extended JVM tests exercise money, duplicate handling, migration, archives and schedules. JSDOM tests submit actual forms through a Java domain bridge. They verify event wiring and DOM output, not rendered layout or Android lifecycle.

Device checks remain essential: Samsung notification templates/redaction, first-use OCR in airplane mode, permissions/revocation, widget behavior, reboot/force-stop and battery restrictions, document-provider restore, camera orientation, large fonts/TalkBack, interruption recovery and battery/performance measurements. The build environment has no connected phone or emulator, and browser execution is blocked. Do not interpret successful compilation as these checks passing.

## Design references

The original olive/cream design draws general dashboard ideas from Ivy Wallet and Cashew; no source code or art was copied from them. Third-party runtime dependencies are listed by Gradle. ML Kit and AndroidX license notices shipped in dependency packages remain in the APK.

### Run automated checks

```sh
export JSON_JAR=/path/to/org-json-20240303.jar
bash tests/run-tests.sh
javac -cp "build/tests:$JSON_JAR" -d build/tests tests/PreviewFixture.java tests/BrowserBridge.java
java -cp "build/tests:$JSON_JAR" app.moneylens.PreviewFixture > build/preview-fixture.json
npm install --prefix tests
node tests/dom-tests.cjs
```

`tests/browser-tests.cjs` is an additional Playwright harness for an environment that permits Chromium. It was prepared but could not run here; it is not part of the passing-check count.
