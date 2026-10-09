# Verification — 0.2.0

- Gradle release build passed using JDK 17, platform 35 and build-tools 35.0.0.
- 51 JVM tests passed (22 original + 29 extended).
- 22 JSDOM + Java domain integration checks passed.
- APK signature verified; certificate exactly matches the delivered 0.1.0 APK.
- Merged APK metadata confirms package app.moneylens, version code 2 and no INTERNET permission.
- Bundled OCR model entries inspected in the APK.
- Every bundled UI asset byte-compared with final source.
- Android lint was unavailable in the offline cache and release lint is disabled.
- Browser launch was blocked by environment socket policy. Rendered layout, TalkBack and large-font behavior are not verified.
- No device or emulator was connected. OCR engine execution, Android Keystore/AtomicFile fault injection, notification templates, permissions, camera/share lifecycle, widget, reboot and scheduled backup behavior require a phone trial.

APK SHA-256: `aeca2e0b2d28220fb9bc910053cc08b38d802e7764487d2236c3d675e9838439`

Signing certificate SHA-256: `558cca0f0b3362e810cf5a4efae6849d983e4fc981d9c2ce7e261aaae0cf2799`

## S24 FE acceptance checklist

1. Back up 0.1.0; update in place and verify balances/history.
2. Test expense, split, refund, edit, void/undo, transfer/card repayment, shared expense and reimbursement.
3. Test notification permission denied/revoked, selected-source filtering, duplicate callbacks, lock and quiet hours; compare real redacted bank samples.
4. Test photo and screenshot OCR in airplane mode from first launch; inspect rotation and manually correct uncertain totals.
5. Import a small statement, review duplicates, repeat the same file, undo the batch and verify edited records remain.
6. Test day-31 cycles, bill matching/cancellation, installment repayment and alert thresholds.
7. Save/restore an encrypted receipt backup with the right and wrong passwords; confirm local recovery. Test unavailable backup folder.
8. Inspect widget and shortcuts with app lock on; check that no private amounts are exposed.
9. Reboot, force-stop/reopen, screen lock and Samsung battery restrictions; observe approximate reminders and backup status.
10. Check large fonts, TalkBack, keyboard sheets, 24-hour battery impact and large-ledger performance before regular use.
