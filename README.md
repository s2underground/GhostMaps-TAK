GhostMaps
_________________________________________________________________
PURPOSE AND CAPABILITIES

A data manager for S2Underground's GhostMaps map products. Browse, download,
install and update the KMZ overlays published in the public GhostMaps
repository (github.com/s2underground/GhostMaps) from inside ATAK, without a
TAK Server account.

- **Latest** lists the newest version of every product (the daily Common
  Intelligence Picture exports show once, with how many versions exist);
  **Browse** walks the repository's folders, archive included; **Installed**
  shows what's on the device, updates first.
- Files are imported through ATAK's own importer (the Import Manager's path),
  so they appear under Overlay Manager → File Overlays and their placemarks
  are searchable in QuickSearch.
- Detects updates two ways: a newer dated/numbered file of the same product
  (`…_October_27_2026_Export_1.kmz` replaces `…_October_23_2026_Export_1.kmz`),
  or the same file changed on GitHub (e.g. `Kinetic_Activities_Last_30_Days.kmz`).
  **Update** installs the new file, then removes the old one; **Update all**
  does every product at once.
- Shows each file's size and asks before large downloads (10 MB, or 2 MB on
  a metered connection). Downloads resume after an interruption and are
  verified against the file's git blob SHA-1 before import.
- Works offline with the last saved file list.

_________________________________________________________________
STATUS

Version 1.0, for ATAK-CIV 5.6.0. Built with the 5.6.0 plugin template
(Gradle 8.14.3, Android Gradle Plugin 8.13.0) against the ATAK-CIV 5.5.1.8
SDK. Unit tests pass (34); lint reports 0 errors. Not yet tested on a device.

_________________________________________________________________
POINT OF CONTACTS

S2Underground: s2underground@protonmail.com

_________________________________________________________________
PORTS REQUIRED

Outbound HTTPS (443) to:

- `api.github.com`: the file list (one request per check; ETag-revalidated,
  so an unchanged list doesn't count against GitHub's limit of 60 anonymous
  requests per hour per IP address).
- `raw.githubusercontent.com`: the files themselves.

No inbound ports. No data is sent anywhere except these requests.

_________________________________________________________________
EQUIPMENT REQUIRED

An internet connection when checking for and downloading files.

_________________________________________________________________
EQUIPMENT SUPPORTED

Any Android phone or tablet running ATAK-CIV 5.6.0.

_________________________________________________________________
COMPILATION

Standard ATAK plugin build from the plugin template, no third-party
libraries:

    ./gradlew assembleCivRelease

Unit tests (plain JVM, no device): `./gradlew testCivDebugUnitTest`.
Lint: `./gradlew lintCivRelease` (0 errors; the remaining warnings are unused
resources inherited from the plugin template).

_________________________________________________________________
DEVELOPER NOTES

- Permissions declared: `INTERNET` and `ACCESS_NETWORK_STATE` (both normal
  permissions ATAK already holds; the second checks for a metered
  connection before a large download). No native code.
- Listing: GitHub's git tree API,
  `GET /repos/s2underground/GhostMaps/git/trees/main?recursive=1`: one
  request returns every path, size and blob SHA. The response and its ETag are
  saved in `atak/tools/ghostmaps/`; checks are conditional
  (`If-None-Match`), and the saved list is used when offline or rate-limited.
- Downloads: `raw.githubusercontent.com/s2underground/GhostMaps/main/<path>`,
  one at a time on a background thread, with `Range` resume from
  `atak/tools/ghostmaps/downloads/<sha>.part`. The finished file's git blob
  SHA-1 must match the listing, or it is discarded.
- Import: `com.atakmap.android.importfiles.task.ImportFileTask` with the
  Import Manager's flags (validate extension, KMZ strict mode, prompt only on
  ambiguous matches). KMZ/KML land in `atak/overlays/`. Removal sends ATAK's
  `ImportExportMapComponent.ACTION_DELETE_DATA` for the file. Tapping an
  installed file sends `ZOOM_TO_FILE_ACTION`.
- What's installed is kept in `atak/tools/ghostmaps/installed.json` (repo
  path, blob SHA, local path, content/MIME type). Entries whose file was
  removed in the Overlay Manager are dropped automatically.
- Product grouping comes from file names: the date ("October_27_2026",
  "August 11_2025", "Sep_30_2025", "August 24, 2026" or yyyy-mm-dd) and a
  trailing version ("v2", "V2", "Export_1", "Export_v2") are stripped; what
  remains plus the folder identifies the product. Newer = later date, then
  higher version; dated beats undated. Folders named like `0_Archive` are
  left out of Latest.
- The Android version code is pinned in `gradle.properties`
  (`takStaticVersion`, 1.0 = 10000); bump it with every release.

---

# User guide

## Installing

Download the APK for your ATAK version from this project's Releases page and
open it on the device (or `adb install` it). Then open ATAK. It asks
"Load plugin: GhostMaps?"; tap OK. If it doesn't ask, open the Plugins
(Package Manager) screen from ATAK's menu, tap GhostMaps, then **Load**.

The GhostMaps button (a ghost) sits with the other tools in ATAK's menu; add
it to the main toolbar through ATAK's toolbar customization for one-tap
access. Tapping the button again closes the panel.

Release APKs are signed through TAK.gov's third-party pipeline and load in the
regular ATAK-CIV from the Play Store or TAK.gov. A build you make yourself is
signed with the SDK's development key and only loads in the SDK's developer
build of ATAK (see "Building it yourself").

## Using it

| Tab | Shows |
|---|---|
| Latest | Newest version of each product, newest first; archive left out |
| Browse | The repository's folders (tap the yellow bar to go up) |
| Installed | Files GhostMaps installed; ones with updates first |

The filter box narrows any tab; every word must appear in the file's name or
folder (`iran kinetic`, `spokane`, `border`).

| Button | Meaning |
|---|---|
| Install | Download and import this file |
| Update (amber) | A newer version exists, or this file changed on GitHub. Installs it, then removes the older one |
| Installed (green) | Up to date. Tap to remove it |
| 42% | Downloading. Tap to stop; tapping Install later resumes |
| Retry | The last attempt failed; the reason is shown under the name |

- Tap an installed file's row to fly the map to it (same as the Overlay
  Manager).
- Installing an older version from Browse keeps whatever else is installed;
  only Update removes older versions.
- Removing a file in the Overlay Manager is fine; GhostMaps notices and shows
  it as not installed.

## Updates and data use

- The list is checked when you open the panel (at most every 10 minutes) and
  when you tap refresh. Offline, the last saved list is shown with a note.
- Sizes are shown on every row. Files of 10 MB or more, or 2 MB or more on
  cellular/hotspot, ask first. The largest file today is the 2020 census
  demographics layer at about 59 MB; most products are under 1 MB, and a
  daily Common Intelligence Picture is about 3.4 MB.
- GitHub allows 60 list checks per hour per network address (unchanged lists
  don't count). If many devices share one connection and hit the limit, the
  status line says when it resets and the saved list is used meanwhile.

---

# Building it yourself

You need JDK 17, Android Studio (or just the Android SDK) and the ATAK-CIV SDK
for your target version (5.6.0 SDK from tak.gov; 5.5.x SDKs are also on the
Releases page of github.com/TAK-Product-Center/atak-civ).

1. **Put this project inside the SDK**, two folders down, e.g.
   `ATAK-CIV-<version>-SDK/plugins/GhostMaps/`. The build finds `main.jar`,
   `android_keystore` and `atak-gradle-takdev.jar` by looking two folders up.
   (Elsewhere, set `sdk.path` and `takdev.plugin` in `local.properties`.)
2. **Open it in Android Studio.** It creates `local.properties` with your
   Android SDK path. Under Build → Select Build Variant, choose **civDebug** or
   **civRelease**.
3. **Check `ext.ATAK_VERSION`** in `app/build.gradle` (`'5.6.0'`). For another
   SDK, use the value from that SDK's `samples/plugintemplate/app/build.gradle`.
4. **Build** (Build → Build APK, or `./gradlew assembleCivRelease`). Before a
   new release, raise `takStaticVersion` in `gradle.properties` (1.0 = 10000,
   1.1 = 10100), or Android won't install it as an update.

APKs land in `app/build/outputs/apk/civ/`. Your own builds are signed with the
SDK's development key, so to test one, install the SDK's `atak.apk` (the
developer build, which shows "DEVELOPER BUILD" in red) on a spare device. It
replaces regular ATAK and its data, since both use the same package name.

## Project layout

```
app/src/main/java/com/atakmap/android/ghostmaps/plugin/
├── GhostMapsPlugin.java     entry point: toolbar button, pane, finish toasts
├── GhostMapsPane.java       tabs, filter, rows, Install/Update/Remove, dialogs
├── GhostMapsManager.java    listing state, download queue, install/replace logic
├── GitHubSource.java        GitHub tree API (cached, ETag) and resumable, verified downloads
├── Installer.java           ATAK import (ImportFileTask) and removal (DELETE_DATA)
└── core/                    no Android code, unit-tested on a plain JVM
    ├── RepoFile.java        one file in the repo (path, blob SHA, size)
    ├── FileName.java        product / date / version from a file name
    ├── TreeParser.java      GitHub tree JSON → importable files
    ├── Catalog.java         products, newest versions, folders, filtering
    ├── InstalledFile.java   one installed file
    ├── InstalledStore.java  installed.json
    ├── UpdateCheck.java     installed vs. repo: up to date / changed / newer / removed
    ├── GitBlobHasher.java   git blob SHA-1 while streaming
    └── Formats.java         sizes, dates, folder names
app/src/main/res/layout/     ghostmaps_pane.xml, ghostmaps_row.xml
app/src/main/assets/plugin.xml registers GhostMapsPlugin with ATAK
app/src/test/...             JUnit tests (34), run against a snapshot of the real repo listing
docs/user_manual/            user manual source (Typst), built by TAK.gov's pipeline
```

## Changelog

**1.0**
- Targets ATAK-CIV 5.6.0 (5.6.0 plugin template).
- Latest / Browse / Installed views of the GhostMaps repository with filter.
- Install, update (replacing older versions), update all, remove, fly-to.
- Resumable, SHA-verified downloads; offline use of the saved list.

## Ideas for a next version

- A mirror setting (e.g. a Raspberry Pi on a mesh network serving a copy of
  the repository) so devices without internet can update.
- Optional "check for updates when ATAK starts" with a toolbar badge.
- Auto-update selected products (e.g. the daily CIP) on Wi-Fi.

## License

Derived from the ATAK-CIV plugin template, which is distributed under the
GNU GPL v3 with the rest of ATAK-CIV. This project is offered under the same
license. The map data it downloads is licensed separately by S2Underground
(CC BY-NC-SA 4.0; see the GhostMaps repository).
