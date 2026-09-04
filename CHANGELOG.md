# Changelog

## [0.5.0](https://github.com/s3ntin3l8/branchdam-mobile/compare/branchdam-mobile-v0.4.0...branchdam-mobile-v0.5.0) (2026-09-04)


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
