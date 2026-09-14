## Summary

<!-- What changes for a listener or a developer, in two or three sentences. -->

## Spec / issue

<!-- The issue this implements, or the short statement: why, contract, invariants kept, verification. -->

## Verification

<!-- What you ran and what you saw. Report red as red. -->

- [ ] `./gradlew testDebugUnitTest lintDebug assembleDebug` passes locally
- [ ] New rules have JVM tests that fail without them
- [ ] Room schema change → migration + exported schema JSON + `RizxMigrationTest` case
- [ ] Device/emulator run when the claim is about a device (say which)

## Checklist

- [ ] One slice; no unrelated refactors
- [ ] `domain/` stays Android-free; UI never touches a provider or ExoPlayer
- [ ] Identity is `ProviderRef`; no stream URL is persisted
- [ ] Failures of providers, plugins and cloud calls are isolated
- [ ] Strings added in `values`, `values-es`, `values-pt`, `values-fr`
- [ ] New dependency (if any) justified here and listed in `docs/THIRD_PARTY_LICENSES.md` **and** `ui/screens/LicenseData.kt`
- [ ] Docs updated where a screen, setting or contract changed (`docs/USER_GUIDE.md`, `docs/TECHNICAL_GUIDE.md`, `docs/GOVERNANCE.md`)
- [ ] No secret, key or configuration value in code, tests, docs or this description
