# MoneyLens 0.2.0

The Android implementation now covers the feature groups in the product plan, including its later installment, foreign-purchase and rule-editor workflows. It remains a personal build requiring phone validation, not a production-certified release.

## What you can use

| Area | Added or expanded |
|---|---|
| Faster entry | Remembered defaults, favorites, repeat transactions, custom categories, tags |
| Receipt capture | Camera/photo picker, bundled Latin and Chinese OCR, encrypted receipt images, text/image sharing |
| Notification review | Amount candidates, merchant rules, batch confirmation/ignore, links to existing records, quiet hours and daily digest |
| Statements | CSV column mapping, row preview, duplicate warnings, exact-file replay protection, reversible import batches |
| Accounts | Statement reconciliation, explicit balance adjustments, shared expenses and repayments |
| Planning | Rollover budgets, salary cycles through day 31, budget alerts, weekly/monthly/yearly bills, subscription suggestions and cancellation reminders |
| Installments | Full purchase and fees recorded once; scheduled repayments reduce a liability |
| Foreign purchases | Original currency/amount, actual MYR charged, explicit conversion fees and stored decimal rate |
| Reports | Six-cycle spending and cash flow, matched elapsed-day comparison, daily allowance, explained available-to-spend estimate |
| Recovery | Receipt-inclusive encrypted backup, legacy restore, pre-restore recovery screen, scheduled folder backups and status |
| Everyday access | Home-screen widget, launcher shortcuts, source/date/amount/tag filters, rule undo and correction history |

## Install

Back up first, then install **MoneyLens-0.2.0.apk over 0.1.0**. Keep the existing installation: uninstalling removes its encrypted data and key. The package name and signing certificate are unchanged. Schema migration retains the earlier ledger. No real financial data is bundled in the app.

Configure notification access, selected payment apps, review prompts, and any backup folder in Settings. All suggested payments require your confirmation. The app itself has no internet permission.

## Verification and limits

- 51 JVM checks passed: 22 original checks and 29 extended checks.
- 22 DOM integration checks passed, submitting real UI forms through the Java ledger.
- Signed release compiled with bundled OCR models; signing and merged permissions inspected.
- Chromium execution was blocked by the environment, so rendered layout and accessibility were not verified here. DOM checks are not a substitute for device testing.
- No phone or emulator was connected. S24 FE notification templates, permission changes, OCR camera behavior, reboot/background scheduling, first-use airplane-mode operation and physical-device backup restore remain untested.
- No bank-specific adapter is claimed validated. Generic parsing may miss or misinterpret notifications; review and reconciliation remain necessary.
- Scheduled work is approximate. The OS can delay it. Backup files stay in your selected folder until you remove them.

## Architecture differences from the proposal

This compatible update uses a native Java shell, bundled WebView UI, pure Java ledger, AES-GCM/Keystore encrypted atomic snapshots and JobScheduler. It does not claim the proposed Compose/Room/SQLCipher/WorkManager stack or its performance targets. Large-history indexing/pagination and a strict authentication-bound vault remain engineering work; the optional capture pause is a behavior setting, not a cryptographic strict-lock mode.

There is no automatic bank login, payment execution, multi-device sync, cloud AI or SMS/accessibility scraping. These were outside the planned personal release.

The source includes the architecture notes, build instructions, passing test output and a device acceptance checklist. Preserve the separately supplied original signing-key archive for future compatible updates.
