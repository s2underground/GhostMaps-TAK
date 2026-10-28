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
- Files are kept in GhostMaps' own folder (`atak/GhostMaps/`) and loaded
  in place by ATAK's own importer, so they appear under Overlay Manager →
  File Overlays and their placemarks are searchable in QuickSearch.
- **Coexists with DataSync.** GhostMaps never copies into `atak/overlays/`
  and never changes or removes a file it didn't install. If the same map is
  already loaded from DataSync (or the Import Manager, a data package…),
  GhostMaps says so on the row and asks before adding a second copy.
- **Survives reorganizing the repository.** Installed files are found again
  after folders are renamed, files are moved or archived, or a file is
  renamed: same contents → silently re-pointed (no download); same file name
  or same map name → offered as an update; gone → kept and marked "no longer
  on GitHub". A renamed default branch is followed automatically.
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
SDK. Unit tests pass (48, including the repo's real Oct 28 reorganization);
lint reports 0 errors. Device testing in progress (ATAK 5.5.1 developer
build).

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
  If `main` stops existing (404/409/422), the repo's `default_branch` is
  looked up once (`GET /repos/s2underground/GhostMaps`) and remembered.
- Downloads: `raw.githubusercontent.com/s2underground/GhostMaps/<branch>/<path>`,
  one at a time on a background thread, with `Range` resume from
  `atak/tools/ghostmaps/downloads/<sha>.part`. The finished file's git blob
  SHA-1 must match the listing, or it is discarded.
- Install: the verified file is moved to `atak/GhostMaps/<file name>` (or
  `<name> (2).kmz` if that name is still in use by the version being
  replaced) and loaded in place: KMZ/KML by broadcasting
  `ImportExportMapComponent.ACTION_IMPORT_DATA` (content type `KML`, file
  URI), exactly as ATAK's in-place resolver does; other types through
  `ImportFileTask` with `FlagImportInPlace`. Success is confirmed by polling
  `URIContentManager.getHandler(file)`. `ImportFileTask` is not used for
  KMZ/KML: (1) when a task finishes it calls
  `ImportExportMapComponent.cleanupResolvers()`, which detaches ATAK's
  "file sorted → load it" listener from the shared resolvers, so in a plugin
  only the first file of a session actually loads (later ones are moved and
  announced as "Finished Import" but never appear on the map); (2) even in
  place, it can delete or replace a same-named file at its default
  destination (`atak/overlays/`), which may belong to DataSync.
- A recorded file ATAK doesn't list as loaded (e.g. one that build left
  sorted but unloaded in `atak/overlays/`) is handed to ATAK's KML loader
  where it is when the panel opens, at most once per file per session. Files
  are never moved once ATAK may reference them.
- Troubleshooting: long-press the refresh button to write
  `atak/GhostMaps/ghostmaps-diagnostics.txt` (installed files and whether
  each is loaded, every file ATAK has loaded, each Overlay Manager entry and
  whether building it fails, and recent warnings/errors from ATAK's log for
  its own process). Nothing is sent anywhere; skim it before sharing, since
  ATAK's log can include callsigns or server names.
- Remove: the file's `URIContentHandler.deleteContent()` (fallback
  `ImportReceiver.remove(uri, contentType, mime)`), then the file is deleted if
  it's inside `atak/GhostMaps/`. Replaced versions are removed only after the
  new one has loaded. Tapping an installed file sends `ZOOM_TO_FILE_ACTION`.
- Ownership: `atak/tools/ghostmaps/installed.json` lists every file GhostMaps
  installed (repo path, blob SHA, local path, content/MIME type). Only those
  are ever replaced or removed. Other loaded files come from
  `URIContentManager.getRegisteredHandlers()` and are only read, to flag
  duplicates by map name.
- Device sync: a `URIContentListener` drops the record when the user removes
  a GhostMaps file in the Overlay Manager (and deletes the file); records
  whose file is gone are dropped; a recorded file that ATAK no longer lists is
  re-requested once the panel opens (self-heal).
- Identity across reorganizations (`core/UpdateCheck`): an installed record
  is matched to the listing by path, then identical blob SHA anywhere
  (re-pointed in `installed.json`, no download), then same file name anywhere
  (offered as an update), then same product anywhere (newest version
  offered). Products come from file names, independent of folder: the date
  ("October_27_2026", "August 11_2025", "Sep_30_2025", "August 24, 2026" or
  yyyy-mm-dd) and a trailing version ("v2", "V2", "Export_1", "Export_v2")
  are stripped, then case and punctuation are ignored. Newer = later date,
  then higher version; dated beats undated; for a tie, the copy outside an
  archive folder wins. Folders named `Archive`, `0_Archive` etc. (any level)
  are left out of Latest.
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
- "Also loaded from another source" means the same map is already on the
  device outside GhostMaps (typically DataSync). Installing asks first, since
  it would put a second copy on the map. GhostMaps never touches that copy.

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

## For the publisher: organizing the GhostMaps repository

GhostMaps is built so the repository can be reorganized freely. What it
relies on:

- **A map's identity is its file name minus the date and version**, in any
  folder. `Iran_Kinetic_Events_October_15_2026_v1.kmz` and
  `Iran Kinetic Events_November_2_2026.kmz` are versions of one map, wherever
  each lives. Keep that part of the name stable for a map, and don't reuse it
  for a different map.
- **New version** → add a file with the new date (or a higher `v2`/`Export_2`).
  Users get Update; the old version is removed from their devices once the
  new one is in.
- **Same file, new contents** (e.g. the 30-day pull) → commit over it with
  the same name. Users get Update.
- **Moving, renaming folders, archiving** → do it any way you like. Unchanged
  files are re-pointed on users' devices without a download. Anything under a
  folder named `Archive` or `0_Archive` (at any level) drops out of Latest
  but stays in Browse.
- **Deleting** → users who installed it keep it; it shows "no longer on
  GitHub" and they can remove it.
- **Renaming the default branch** is handled. Keep the repository public and
  its name (`s2underground/GhostMaps`) unchanged; both are built into the
  plugin.
- Supported file types: KMZ, KML, and other formats ATAK's Import Manager
  takes (GPX, GeoJSON, GeoPackage, icon-set ZIPs…). Other files (PDFs, images)
  aren't listed.

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

To test in an older developer ATAK, build for that version's plugin API
without editing anything, e.g. for the 5.5.1 developer build (whose SDK
template uses 5.5.0):

    ./gradlew assembleCivDebug -PatakVersion=5.5.0

A plugin built for a newer API than the ATAK it's loaded into is rejected, so
a 5.6.0 build won't load in 5.5.1. Release builds keep the 5.6.0 default.

APKs land in `app/build/outputs/apk/civ/`. Your own builds are signed with the
SDK's development key, so to test one, install the SDK's `atak.apk` (the
developer build, which shows "DEVELOPER BUILD" in red) on a spare device. It
replaces regular ATAK and its data, since both use the same package name.

## Project layout

```
app/src/main/java/com/atakmap/android/ghostmaps/plugin/
├── GhostMapsPlugin.java     entry point: toolbar button, pane, finish toasts
├── GhostMapsPane.java       tabs, filter, rows, Install/Update/Remove, dialogs
├── GhostMapsManager.java    listing state, download queue, install/replace, device sync
├── GitHubSource.java        GitHub tree API (cached, ETag, branch fallback); resumable, verified downloads
├── Installer.java           in-place import into ATAK, removal, files loaded by other sources
└── core/                    no Android code, unit-tested on a plain JVM
    ├── RepoFile.java        one file in the repo (path, blob SHA, size)
    ├── FileName.java        product / date / version from a file name
    ├── TreeParser.java      GitHub tree JSON → importable files
    ├── Catalog.java         products, newest versions, folders, filtering
    ├── InstalledFile.java   one installed file
    ├── InstalledStore.java  installed.json
    ├── UpdateCheck.java     finds installed files after reorganizing; up to date / moved / changed / newer / removed
    ├── LocalNames.java      on-device file names
    ├── OtherSources.java    maps already loaded from DataSync etc., by map name
    ├── GitBlobHasher.java   git blob SHA-1 while streaming
    └── Formats.java         sizes, dates, folder names
app/src/main/res/layout/     ghostmaps_pane.xml, ghostmaps_row.xml
app/src/main/assets/plugin.xml registers GhostMapsPlugin with ATAK
app/src/test/...             JUnit tests (48), incl. real repo listings before/after the Oct 28 reorganization
docs/user_manual/            user manual source (Typst), built by TAK.gov's pipeline
```

## Changelog

**1.0**
- Targets ATAK-CIV 5.6.0 (5.6.0 plugin template).
- Latest / Browse / Installed views of the GhostMaps repository with filter.
- Install, update (replacing older versions), update all, remove, fly-to.
- Resumable, SHA-verified downloads; offline use of the saved list.
- Own folder (`atak/GhostMaps/`), in-place import; never touches DataSync's
  or other sources' files, and flags maps already loaded from them.
- Installed files followed through folder renames, moves, archiving and file
  renames; default-branch rename handled.

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
