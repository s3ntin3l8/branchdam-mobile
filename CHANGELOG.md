# Changelog

## [0.7.2](https://github.com/s3ntin3l8/branchdam-mobile/compare/branchdam-mobile-v0.7.1...branchdam-mobile-v0.7.2) (2026-09-05)


### Bug Fixes

* **android:** resolve foreground service crash and MediaStore query error on Android 15 ([#130](https://github.com/s3ntin3l8/branchdam-mobile/issues/130)) ([e7d6b4e](https://github.com/s3ntin3l8/branchdam-mobile/commit/e7d6b4e5db2db813d348509cbca3280394784716))

## [0.7.1](https://github.com/s3ntin3l8/branchdam-mobile/compare/branchdam-mobile-v0.7.0...branchdam-mobile-v0.7.1) (2026-09-05)


### Features

* **android:** add QR pairing scan in Settings ([#128](https://github.com/s3ntin3l8/branchdam-mobile/issues/128)) ([4b523a2](https://github.com/s3ntin3l8/branchdam-mobile/commit/4b523a21af0c877af7b564942cc284834767c1e6))


### Bug Fixes

* **android:** sequence runtime permission requests and fix layout-blocking bugs ([#126](https://github.com/s3ntin3l8/branchdam-mobile/issues/126)) ([dd320ac](https://github.com/s3ntin3l8/branchdam-mobile/commit/dd320ac3f278a2c8aa8b9d2449c27ba3f6e27580))

## [0.7.0](https://github.com/s3ntin3l8/branchdam-mobile/compare/branchdam-mobile-v0.6.0...branchdam-mobile-v0.7.0) (2026-09-05)


### Features

* **android:** version-stamp apk/aab releases and surface in Settings ([#123](https://github.com/s3ntin3l8/branchdam-mobile/issues/123)) ([16a2eb8](https://github.com/s3ntin3l8/branchdam-mobile/commit/16a2eb84d97e6e742c681cac5b2dd4029409f523))

## [0.6.0](https://github.com/s3ntin3l8/branchdam-mobile/compare/branchdam-mobile-v0.5.1...branchdam-mobile-v0.6.0) (2026-09-05)


### Features

* **android:** add Gallery thumbnail grid and Sync Status dashboard ([#112](https://github.com/s3ntin3l8/branchdam-mobile/issues/112)) ([49b41b7](https://github.com/s3ntin3l8/branchdam-mobile/commit/49b41b70037040454c0d9403a8089db6ccce70b8))
* **android:** add Settings and Safe Space screens, wire into navigation ([#111](https://github.com/s3ntin3l8/branchdam-mobile/issues/111)) ([72f620d](https://github.com/s3ntin3l8/branchdam-mobile/commit/72f620de55e340260b79d8dd4ee022cec7a79c9a))
* **android:** wire navigation framework and LineageScreen to real backend data ([#110](https://github.com/s3ntin3l8/branchdam-mobile/issues/110)) ([adec81a](https://github.com/s3ntin3l8/branchdam-mobile/commit/adec81aa148ad74e9a3e24091c69f600189d088b))


### Bug Fixes

* **android:** align native libs to 16KB pages and drop 32-bit ABIs ([#113](https://github.com/s3ntin3l8/branchdam-mobile/issues/113)) ([0f47151](https://github.com/s3ntin3l8/branchdam-mobile/commit/0f47151651c69c8ff9394c0cedbcceae4812de03))

## [0.5.1](https://github.com/s3ntin3l8/branchdam-mobile/compare/branchdam-mobile-v0.5.0...branchdam-mobile-v0.5.1) (2026-09-04)


### Bug Fixes

* **ci:** build branchdam.aar before compiling the release APK/AAB ([#107](https://github.com/s3ntin3l8/branchdam-mobile/issues/107)) ([8f54ca5](https://github.com/s3ntin3l8/branchdam-mobile/commit/8f54ca594472c41d45f21085cb86ebe82d31a3ec))
* **ci:** stop joining gradlew CLI args into one string ([#105](https://github.com/s3ntin3l8/branchdam-mobile/issues/105)) ([e9c519e](https://github.com/s3ntin3l8/branchdam-mobile/commit/e9c519e8246f4b95e3cbbe8c406d91f1e13edfa8))

## [0.5.0](https://github.com/s3ntin3l8/branchdam-mobile/compare/branchdam-mobile-v0.4.0...branchdam-mobile-v0.5.0) (2026-09-04)

> This release was cut manually via a `Release-As` trailer
> ([#97](https://github.com/s3ntin3l8/branchdam-mobile/pull/97)): the commits
> below carry bracket task-ID prefixes (`[T2-*]`, `[A]`–`[E]`) that
> release-please's conventional-commit parser rejects, so the generated
> changelog for this version would otherwise have been empty. This section
> was written by hand from the actual diffs.

### Features

* **build:** gomobile bind pipeline + CI workflow ([#63](https://github.com/s3ntin3l8/branchdam-mobile/issues/63)) ([6531fa7](https://github.com/s3ntin3l8/branchdam-mobile/commit/6531fa7879fddd7cd1cb624db4431aeecbbb9b78))
* **core:** FFI surface redesign into the branchdam package ([#75](https://github.com/s3ntin3l8/branchdam-mobile/issues/75)) ([7e7a021](https://github.com/s3ntin3l8/branchdam-mobile/commit/7e7a021d5bf42733f73552e48e0eed02a8a85dfd))
* **android:** shell wiring for lineage pipeline, HandlerThread, and trash sync ([#76](https://github.com/s3ntin3l8/branchdam-mobile/issues/76)) ([33da84c](https://github.com/s3ntin3l8/branchdam-mobile/commit/33da84c72706e7e241378f1bcca5f86f23515efc))
* **ios:** shell wiring for WelcomeView, cancel flag, lineage, and PrivacyInfo ([#77](https://github.com/s3ntin3l8/branchdam-mobile/issues/77)) ([2e2182b](https://github.com/s3ntin3l8/branchdam-mobile/commit/2e2182b56d464cc12b798ff92b2b5930aa6fa48c))
* **android:** foldable-aware UI via Jetpack WindowManager ([#81](https://github.com/s3ntin3l8/branchdam-mobile/issues/81)) ([3fd8a25](https://github.com/s3ntin3l8/branchdam-mobile/commit/3fd8a2592e721e0d57e3ef4f26c455322e8231c9))
* **android:** SyncWorker foreground service on Android 14+ ([#83](https://github.com/s3ntin3l8/branchdam-mobile/issues/83)) ([a9bcd74](https://github.com/s3ntin3l8/branchdam-mobile/commit/a9bcd74dc1d1989170f81e8f11b41b4f2cd2c4e3))
* **android:** debounce MediaStore observer callbacks onto a background Handler ([#84](https://github.com/s3ntin3l8/branchdam-mobile/issues/84)) ([39fcab2](https://github.com/s3ntin3l8/branchdam-mobile/commit/39fcab2cd0e9117337a7f9dd19248aca3a0d7bf6))
* **security:** EncryptedSharedPreferences for non-apiKey secrets, iOS Keychain parity ([#86](https://github.com/s3ntin3l8/branchdam-mobile/issues/86)) ([8a7d9c0](https://github.com/s3ntin3l8/branchdam-mobile/commit/8a7d9c05aacadec12d414f336a58d27f125251d7))
* **android:** OTG staging size cap with post-copy BLAKE3 verify ([#91](https://github.com/s3ntin3l8/branchdam-mobile/issues/91)) ([77308eb](https://github.com/s3ntin3l8/branchdam-mobile/commit/77308ebe7f1a87aa05b0bbde230d936b9e8012c0))

### Bug Fixes

* **android:** enforce HTTPS-only baseURL with a network security config ([#82](https://github.com/s3ntin3l8/branchdam-mobile/issues/82)) ([a478040](https://github.com/s3ntin3l8/branchdam-mobile/commit/a47804079c7532361ef1b0a279ca75464ffaaa91))
* **core:** cap payloadJSON size in the events queue ([#87](https://github.com/s3ntin3l8/branchdam-mobile/issues/87)) ([ddfe3d0](https://github.com/s3ntin3l8/branchdam-mobile/commit/ddfe3d0377c8aa16f8fec1d155d53f01e1207aef))
* **cross-platform:** unify pref-key strings between Android and iOS to prevent drift ([#89](https://github.com/s3ntin3l8/branchdam-mobile/issues/89)) ([4a1ece5](https://github.com/s3ntin3l8/branchdam-mobile/commit/4a1ece5fa7b9d7886c0a34204650dbd261b132c4)), ([#95](https://github.com/s3ntin3l8/branchdam-mobile/issues/95)) ([ab1874a](https://github.com/s3ntin3l8/branchdam-mobile/commit/ab1874ac2db2ce18a1a0ae1fa40adde3cbc164f0))
* **ios:** synchronize isCancelled flag under Swift 6 strict concurrency ([#90](https://github.com/s3ntin3l8/branchdam-mobile/issues/90)) ([27ecc91](https://github.com/s3ntin3l8/branchdam-mobile/commit/27ecc91fd649d8b2d1549496937aa6bda88fd17e))
* **android:** fail OTG staging on a post-copy BLAKE3 mismatch instead of ingesting ([#96](https://github.com/s3ntin3l8/branchdam-mobile/issues/96)) ([3e52efd](https://github.com/s3ntin3l8/branchdam-mobile/commit/3e52efdae6c128f2d1beb52d52232e263fca0078))

### Tests

* **f-plan:** fill Go core, Android, and iOS test gaps from the F test plan ([#78](https://github.com/s3ntin3l8/branchdam-mobile/issues/78)) ([327bd36](https://github.com/s3ntin3l8/branchdam-mobile/commit/327bd36747bdc0b5afdb29a2f986a9e1d46d6769)), ([#79](https://github.com/s3ntin3l8/branchdam-mobile/issues/79)) ([714736f](https://github.com/s3ntin3l8/branchdam-mobile/commit/714736fa1b8b3e7d0f2ce14ad5f5f1613837d06d)), ([#80](https://github.com/s3ntin3l8/branchdam-mobile/issues/80)) ([12ec7a7](https://github.com/s3ntin3l8/branchdam-mobile/commit/12ec7a71ccb6eb46ad62bf3d0f39cfde267b7dbc))

### Documentation

* **core:** clarify single-header policy for the API key ([#85](https://github.com/s3ntin3l8/branchdam-mobile/issues/85)) ([6f21b3e](https://github.com/s3ntin3l8/branchdam-mobile/commit/6f21b3e8498720ab35ca0b8cf0c017f1e4df4e53))

### Miscellaneous Chores

* force release-please to cut 0.5.0 ([#97](https://github.com/s3ntin3l8/branchdam-mobile/issues/97)) ([b510a1f](https://github.com/s3ntin3l8/branchdam-mobile/commit/b510a1f8313b45b446fc90eeb1f95c2ffd72bfbe))

## [0.4.0](https://github.com/s3ntin3l8/branchdam-mobile/compare/branchdam-mobile-v0.3.0...branchdam-mobile-v0.4.0) (2026-08-30)


### Features

* **core/engine:** hash-based dedup against local queue and server library ([#53](https://github.com/s3ntin3l8/branchdam-mobile/issues/53)) ([23f26a8](https://github.com/s3ntin3l8/branchdam-mobile/commit/23f26a839d3a7429df7d6e1ebc50a4eee08774dc))
* **ios:** address Android/iOS feature parity gaps across lineage, notifications, safe space, and OTG feedback ([#54](https://github.com/s3ntin3l8/branchdam-mobile/issues/54)) ([f5e700c](https://github.com/s3ntin3l8/branchdam-mobile/commit/f5e700cd2410977f5416a580a138de8c788c40a1))
* **ios:** configure BranchDAMTests target, xcframework build automation, and CI test pipeline ([#48](https://github.com/s3ntin3l8/branchdam-mobile/issues/48)) ([a555d07](https://github.com/s3ntin3l8/branchdam-mobile/commit/a555d0752b2799bba484d990cdda59018d57c930))
* **mobile:** camera-roll import confirmation notification with actions and auto-import settings ([#52](https://github.com/s3ntin3l8/branchdam-mobile/issues/52)) ([3cab79a](https://github.com/s3ntin3l8/branchdam-mobile/commit/3cab79a47dd50e21cb931e6bb8d156148ba1163a))
* **mobile:** USB-C OTG SD card discovery & ingest with human confirmation ([#50](https://github.com/s3ntin3l8/branchdam-mobile/issues/50)) ([4f82e35](https://github.com/s3ntin3l8/branchdam-mobile/commit/4f82e353064a733fd92f8d28240f97e2d3517acf))


### Bug Fixes

* **mobile:** respect metered network constraint in Android & iOS sync schedulers ([#51](https://github.com/s3ntin3l8/branchdam-mobile/issues/51)) ([d505922](https://github.com/s3ntin3l8/branchdam-mobile/commit/d505922649e77873560fe1f839622b3bfc170210))

## [0.3.0](https://github.com/s3ntin3l8/branchdam-mobile/compare/branchdam-mobile-v0.2.0...branchdam-mobile-v0.3.0) (2026-08-29)


### Features

* **branding:** add Android adaptive icons, iOS AppIcon catalog, and in-app brand assets ([#38](https://github.com/s3ntin3l8/branchdam-mobile/issues/38)) ([da2c96b](https://github.com/s3ntin3l8/branchdam-mobile/commit/da2c96bcdfef7541140ae2586f73f0ee7c07f246)), closes [#31](https://github.com/s3ntin3l8/branchdam-mobile/issues/31)
* **client:** update Go core client, mobile bindings, and Settings UI for POST /api/v1/agent/upload ([#35](https://github.com/s3ntin3l8/branchdam-mobile/issues/35)) ([4627a0e](https://github.com/s3ntin3l8/branchdam-mobile/commit/4627a0e5fef2413129f1c6ff490d9be9c3b25835)), closes [#30](https://github.com/s3ntin3l8/branchdam-mobile/issues/30)


### Bug Fixes

* **branding:** reference brand_teal color, use ic_brand_monogram drawable, and clean up imports ([#39](https://github.com/s3ntin3l8/branchdam-mobile/issues/39)) ([5d4c0f4](https://github.com/s3ntin3l8/branchdam-mobile/commit/5d4c0f4e80efd6f58011b57da69aff8c23e58e11))
* **mobile:** wire camera model in upload pipeline and dynamic naming template fetch ([#37](https://github.com/s3ntin3l8/branchdam-mobile/issues/37)) ([1f2a9fd](https://github.com/s3ntin3l8/branchdam-mobile/commit/1f2a9fdd58ebd70928b022d9286a8511e2db6b67))

## [0.2.0](https://github.com/s3ntin3l8/branchdam-mobile/compare/branchdam-mobile-v0.1.0...branchdam-mobile-v0.2.0) (2026-08-29)


### Features

* **android:** add DNG+JPEG pair detection, Motion Photos, and Confidence-1.00 lineage ([#22](https://github.com/s3ntin3l8/branchdam-mobile/issues/22)) ([5b2b450](https://github.com/s3ntin3l8/branchdam-mobile/commit/5b2b45025a17cf30adcfac27dfdca49152b86acb)), closes [#4](https://github.com/s3ntin3l8/branchdam-mobile/issues/4)
* **android:** implement MediaStore observer and WorkManager background ingest daemon ([#21](https://github.com/s3ntin3l8/branchdam-mobile/issues/21)) ([f9cb7e8](https://github.com/s3ntin3l8/branchdam-mobile/commit/f9cb7e890f9b64fd230e95c62f6c139edf2ed85f)), closes [#2](https://github.com/s3ntin3l8/branchdam-mobile/issues/2)
* **android:** implement MediaStore trash sync and Safe Space Reclaim ([#23](https://github.com/s3ntin3l8/branchdam-mobile/issues/23)) ([8456f0a](https://github.com/s3ntin3l8/branchdam-mobile/commit/8456f0a0f08c4bb1cbe3982ac7f45d9c100cc464)), closes [#7](https://github.com/s3ntin3l8/branchdam-mobile/issues/7)
* **android:** implement Pixel Fold dual-pane UI, audit triage queue, and QR pairing ([#24](https://github.com/s3ntin3l8/branchdam-mobile/issues/24)) ([a4af3dd](https://github.com/s3ntin3l8/branchdam-mobile/commit/a4af3dd7c49377a5cc89009a4a9b1c341602dd79)), closes [#8](https://github.com/s3ntin3l8/branchdam-mobile/issues/8)
* **core:** implement Go core engine with BLAKE3, SQLite queue.db, and chunked uploader ([#20](https://github.com/s3ntin3l8/branchdam-mobile/issues/20)) ([3de5ed3](https://github.com/s3ntin3l8/branchdam-mobile/commit/3de5ed3cd7f4e9e6d4579baa0429befd95ea24b4))
* **ios:** implement Apple ProRAW + HEIC pairing and Live Photo micro-video extraction ([#27](https://github.com/s3ntin3l8/branchdam-mobile/issues/27)) ([074f466](https://github.com/s3ntin3l8/branchdam-mobile/commit/074f4662884467c8e4054d2198ad7e7aef06fb79)), closes [#13](https://github.com/s3ntin3l8/branchdam-mobile/issues/13)
* **ios:** implement PhotoKit observer and NSURLSession background ingest ([#26](https://github.com/s3ntin3l8/branchdam-mobile/issues/26)) ([07d6613](https://github.com/s3ntin3l8/branchdam-mobile/commit/07d6613e74bcbd1c26cdea279eb9e0f0889e09dd)), closes [#12](https://github.com/s3ntin3l8/branchdam-mobile/issues/12)
* **ios:** implement PhotoKit trash sync and Safe Space Reclaim ([#28](https://github.com/s3ntin3l8/branchdam-mobile/issues/28)) ([44adee8](https://github.com/s3ntin3l8/branchdam-mobile/commit/44adee843a26f45bab3988fbbb8a99f9373cfd76)), closes [#14](https://github.com/s3ntin3l8/branchdam-mobile/issues/14)
* **ios:** implement SwiftUI adaptive UI, lineage viewer, audit triage, and QR pairing ([#29](https://github.com/s3ntin3l8/branchdam-mobile/issues/29)) ([02e841e](https://github.com/s3ntin3l8/branchdam-mobile/commit/02e841eab5f0b251ad4996aa2ba508dad23f2337)), closes [#15](https://github.com/s3ntin3l8/branchdam-mobile/issues/15)
* **ios:** scaffold Xcode project and Swift C-bridge / UniFFI to core engine ([#25](https://github.com/s3ntin3l8/branchdam-mobile/issues/25)) ([45c21af](https://github.com/s3ntin3l8/branchdam-mobile/commit/45c21aff32fb6a3dcb0c1f0d06704d2046eb83bd))
* **scaffold:** initialize branchdam-mobile repository from mobile-app-template ([972429c](https://github.com/s3ntin3l8/branchdam-mobile/commit/972429c2bfb9b3158d65f3dafbbd8cc5048708a7))

## Changelog

All notable changes to this project will be documented in this file.
