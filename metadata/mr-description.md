New app: **NarOffice** — a simple, fast office suite to view and edit Word (.docx), Excel (.xlsx/.csv)
and PowerPoint (.pptx) files.

* Written from scratch in Kotlin/Jetpack Compose; reads and writes Office Open XML directly
  (no bundled office suite, no native code). The APK is about 1.7 MB.
* No INTERNET permission, no trackers, no proprietary dependencies (AndroidX/Compose only).
* License: GPL-3.0-or-later. Fastlane metadata (en-US, az-AZ) with screenshots is in the repo.
* `dependenciesInfo` is disabled, so the APK carries no Google dependency-metadata block.

* [x] The app complies with the inclusion criteria
* [x] The original app author has been notified (I am the author)
* [x] All related fdroiddata and RFP issues have been referenced in this merge request (none exist)
* [ ] Builds with `fdroid build` and all pipelines pass (built locally with Gradle from a clean clone; waiting for CI)
