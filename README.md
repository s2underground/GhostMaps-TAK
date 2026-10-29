GhostMaps
_________________________________________________________________
PURPOSE AND CAPABILITIES

A data manager for S2Underground's GhostMaps map products. Browse, download,
install, update and remove the KMZ/KML overlays and icon sets published in
the public GhostMaps repository (github.com/s2underground/GhostMaps) from
inside ATAK, without a TAK Server account.

- **Latest** lists the newest version of every product (the daily Common
  Intelligence Picture exports show once, with how many versions exist);
  **Browse** walks the repository's folders, archives included; **Installed**
  shows what's on the device, split into map layers and icon sets, with
  anything that needs attention first.
- **Map layers** (KMZ/KML) are kept in GhostMaps' own folder
  (`atak/GhostMaps/`) and loaded in place by ATAK's KML importer, so they
  appear under Overlay Manager → File Overlays like any other file.
- **Icon sets** (ZIPs of icons, such as "S2U Map Icons.zip") are added
  through ATAK's icon-set manager, so they show up in the point dropper's
  icon list, not as map layers.
- **Coexists with DataSync.** GhostMaps never writes into `atak/overlays/`
  and never changes or removes a file it didn't install. If the same map is
  already loaded from DataSync (or the Import Manager, a data package…), the
  row says so and GhostMaps asks before adding a second copy. An icon set
  already in ATAK from another source is left alone.
- **Survives reorganizing the repository.** Installed files are followed
  through moved files, renamed folders and new archive folders without a
  download. Updates are only offered from the same product in the same
  folder, never from an archive. A file deleted from GitHub stays on the
  device and is marked; a similar file, if any, is offered as a suggestion
  that the user confirms.
- **Updates**: a newer dated or numbered file of the same product
  (`…_October_27_2026_Export_1.kmz` after `…_October_23_2026_Export_1.kmz`),
  or the same file changed on GitHub (e.g. `Kinetic_Activities_Last_30_Days.kmz`).
  The old version is removed only after ATAK has loaded the new one.
- **Safe downloads**: every file is downloaded from the exact commit the list
  came from, resumes after an interruption, and is checked against its git
  blob SHA-1 before ATAK sees it. Sizes are shown, and large downloads ask
  first (10 MB, or 2 MB on a metered connection).
- Works offline with the last saved file list.

_________________________________________________________________
STATUS

Version 1.0, for ATAK-CIV 5.6.0. Built with the 5.6.0 plugin template
(Gradle 8.14.3, Android Gradle Plugin 8.13.0) against the ATAK-CIV 5.5.1.8
SDK. 138 unit tests pass, including the repository's real Oct 28 and Oct 29
reorganizations, a fake GitHub server for every network failure mode, and
the install engine end to end against a simulated ATAK. Lint reports 0
errors. Device testing in progress (ATAK 5.5.1 developer build).

_________________________________________________________________
POINT OF CONTACTS

S2Underground: s2underground@protonmail.com

_________________________________________________________________
PORTS REQUIRED

Outbound HTTPS (443) to:

- `api.github.com`: the file list. A check costs one conditional request;
  when nothing changed GitHub answers "304 Not Modified", which doesn't count
  against its limit of 60 anonymous requests per hour per IP address.
- `raw.githubusercontent.com`: the files themselves.

No inbound ports. No data is sent anywhere except these requests. The
plugin refuses to connect anywhere else, including when a server redirects.

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

### Design

The plugin is split into layers so that everything except the thin ATAK
adapter is plain Java and unit-tested on a desktop JVM:

```
ui/       GhostMapsPane (views, dialogs) ← RowBuilder (pure: snapshot → rows, statuses, actions)
engine/   GhostMapsEngine: install records, job queue, reconciliation; publishes immutable Snapshots
          Platform: the only interface to ATAK (probe, import/remove layer, add/remove icon set)
atak/     AtakPlatform, AtakEvents: the real Platform and ATAK event wiring
net/      RepoClient: GitHub listing and verified downloads (RepoSource interface)
core/     identity, parsing, planning and storage; no Android code
```

- **Threading.** All engine state lives on one single-threaded executor;
  public methods only post work to it. Downloads and listing checks run on a
  network executor and post results back. The UI receives immutable
  `Snapshot`s on the main thread and never touches ATAK, the disk or the
  network itself.
- **Write-ahead install lifecycle.** Each install record moves through
  `PLACED` (file placed in `atak/GhostMaps/`) → `IMPORT_REQUESTED` (ATAK
  asked to load it) → `ACTIVE` (ATAK confirms it) → `REMOVING`. Each state is
  saved before the step it describes, so a crash at any point is finished or
  undone by the next reconciliation.
- **Reconciliation** compares records with what ATAK actually has, on start,
  on panel open and after ATAK's file-list and icon-set events. It never
  acts when ATAK's state can't be read or storage is unmounted, and only
  drops a record after the file has been missing for a 10-second grace
  period. A layer ATAK has lost is re-requested at most once per session,
  and only after start-up has settled and the panel has been used.
- **Ownership.** Only files inside `atak/GhostMaps/` that GhostMaps placed
  there are ever moved, replaced or deleted. Records from early test builds
  that point elsewhere are marked "earlier test build" and can only be
  forgotten (the file is left as is).
- **Storage.** `atak/tools/ghostmaps/installed.json` (version 2; version 1 is
  migrated) is written atomically, keeping the previous version as
  `installed.json.bak`. If it's ever unreadable it is set aside as
  `installed.json.corrupt-<time>`, never overwritten, the backup is used,
  and files in GhostMaps' folder that match a published file are re-adopted
  by content hash. (Files it can't identify are left alone, never adopted.)
- **Before ATAK sees a file** it must match the listing's blob SHA-1, and a
  map layer must really be a KMZ (ZIP) or KML (XML); an icon set must be a
  ZIP of images only. An icon set whose ID ATAK already has from another
  source, or from a different GhostMaps product, is refused.

### ATAK interfaces used

- **Map layers:** loaded in place by broadcasting
  `ImportExportMapComponent.ACTION_IMPORT_DATA` (content type `KML`, file
  URI), as ATAK's own in-place resolver does. Loaded = a `FileContentHandler`
  for the file is registered in `URIContentManager` (canonical paths).
  Removed with the handler's `deleteContent()` (fallback
  `ImportReceiver.remove`); note ATAK deletes the file itself when it
  unloads it. `ZOOM_TO_FILE_ACTION` flies the map to a layer.
- **`ImportFileTask` is never used.** When a task finishes it calls
  `ImportExportMapComponent.cleanupResolvers()`, which detaches ATAK's
  shared "file sorted → load it" listener, so in a plugin only the first
  file of a session actually loads. It can also replace or delete a
  same-named file in `atak/overlays/`, which may belong to DataSync.
- **Icon sets:** added with the `IconsMapAdapter.ADD_ICONSET` intent
  (`filepath`), removed with `REMOVE_ICONSET` (`iconsetUid`); presence is
  read from `UserIconDatabase`. ATAK accepts a ZIP of images without
  `iconset.xml` and names the set after the file, with a UID of the file's
  SHA-256; the Import Manager's resolver requires `iconset.xml`, which is why
  "S2U Map Icons.zip" must not go through it. Before installing, GhostMaps
  checks the ZIP holds only images (no data packages) and that ATAK doesn't
  already have the set from another source.
- **Overlay Manager:** never read or modified. (An earlier diagnostics
  feature walked its list models, which rebuilds ATAK's cached lists and
  made entries disappear; it was removed.)

### GitHub

- **Listing:** `GET /repos/s2underground/GhostMaps/commits/main` with
  `Accept: application/vnd.github.sha` and `If-None-Match` gives the current
  commit (a 304 when unchanged). Only when the commit changed:
  `GET /repos/…/git/trees/<commit>?recursive=1`, which returns every path,
  size and blob SHA in one request. Both are saved in `atak/tools/ghostmaps/`
  and used offline or when rate-limited. If `main` disappears, the repo's
  `default_branch` is looked up and used only if it works.
- **Downloads:** `raw.githubusercontent.com/s2underground/GhostMaps/<commit>/<path>`,
  pinned to the listing's commit so a file changed mid-download can't be
  spliced. Resume with `Range` from `downloads/<sha>.part` (the server's
  `Content-Range` must match), three attempts with backoff for transient
  errors, a free-space check, and git blob SHA-1 verification before the
  file is used.

### Identity and updates (`core/RepoEntry`, `core/UpdatePlanner`)

- An installed file is matched to the listing by **exact contents** (blob
  SHA) or **exact path**. Same contents at a new path → the record is
  re-pointed with no download.
- A **product** is the file name without its date and version, within its
  **scope folder** (the folder with any archive folders removed), and of the
  same kind. Dates in many forms are recognized ("October_27_2026",
  "August 11_2025", "27OCT2026", "2026-10-27", "10/27/2026", "July 2025"…),
  and versions like "v2", "Export_1" or "Export_v2".
- **Newer** = later date, then higher version. Updates are only offered
  within a product group and never point into an archive folder.
- **Deleted** files are kept and marked. A same-named or same-product file
  elsewhere is only a *suggestion*; replacing needs the user's confirmation.
- **Update** installs exactly the file the user confirmed and, once it is
  loaded, replaces every installed version it is the update for. **Update
  all** does that for every pending update. A file can't be removed while
  its own install is still running.

### Other

- Permissions: `INTERNET` and `ACCESS_NETWORK_STATE` (both normal
  permissions ATAK already holds; the second detects metered connections).
  No native code.
- Troubleshooting: long-press the refresh button to write
  `atak/tools/ghostmaps/diagnostics.txt`: the engine's records and their
  state, the listing, and recent GhostMaps warnings from ATAK's log for its
  own process. Nothing is sent anywhere.
- The Android version code is pinned in `gradle.properties`
  (`takStaticVersion`, 1.0 = 10000); bump it with every release.

---

# User guide

## Installing

Download the APK for your ATAK version from the Releases page
(github.com/s2underground/GhostMaps-ATAK/releases) and
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
| Latest | Newest version of each product, newest first; archives left out |
| Browse | The repository's folders (tap the yellow bar to go up) |
| Installed | Map layers and icon sets on this device; ones needing attention first |

The filter box narrows any tab; every word must appear in the file's name or
folder (`iran kinetic`, `spokane`, `border`).

Each row has a button for its main action (Install, Update, Show on map,
Retry…); tapping the row does the same. Installed files also have a
**Remove** button, which always asks first. Long-press a row for every
option in one menu.

| Status | Meaning |
|---|---|
| On the map | Loaded. Tap to fly to it |
| Installed in ATAK's icon sets | The icons are in the point dropper's icon list |
| Not in ATAK's icon sets | The icon set was removed in ATAK; tap Add to ATAK |
| Update available | A newer version exists, or the file changed on GitHub. Tap to update; the old version is removed once the new one is loaded |
| Downloading 42% | Tap to stop; installing again later resumes |
| ATAK is loading it… | Waiting for ATAK to confirm. Large files can take a minute |
| Not on the map; tap Reload | The file is here but ATAK isn't showing it |
| No longer on GitHub | Kept on your device. If a similar file exists, it's offered |
| Already loaded from another source | The same map is on the device outside GhostMaps (typically DataSync) |
| Failed: … | The reason, and a Retry button |
| Installed by an earlier test build | From a pre-release GhostMaps, outside its folder. Forget clears the entry; the file is left alone |

- Removing a layer in the Overlay Manager, or an icon set in ATAK's icon
  settings, is fine; GhostMaps notices within a few seconds.
- Installing an older version from Browse keeps whatever else is installed;
  only Update replaces.

## Updates and data use

- The list is checked when you open the panel (at most every 10 minutes) and
  when you tap refresh. Offline, the last saved list is shown with a note.
- Sizes are shown on every row. Files of 10 MB or more, or 2 MB or more on
  cellular/hotspot, ask first. Most products are under 1 MB; a daily Common
  Intelligence Picture is about 3.4 MB; the largest file is the 2020 census
  demographics layer at about 59 MB.
- If many devices share one connection and reach GitHub's hourly limit, the
  status line says so and the saved list is used meanwhile.

## For the publisher: organizing the GhostMaps repository

GhostMaps is built so the repository can be reorganized freely. What it
relies on:

- **A product is its file name minus the date and version, within its
  folder.** `Iran_Kinetic_Events_October_15_2026_v1.kmz` and
  `Iran_Kinetic_Events_November_2_2026.kmz` in the same folder are versions of
  one product. The same name in a different folder is a different product, so
  two regions can each have a "Kinetic Events" layer.
- **New version** → add a file with the new date (or a higher `v2`/`Export_2`)
  in the same folder. Users get Update; the old version is removed from their
  devices once the new one is in.
- **Same file, new contents** (e.g. the 30-day pull) → commit over it with
  the same name. Users get Update.
- **Archiving** → move old versions into a folder whose name starts or ends
  with "Archive" ("Archive", "0_Archive", "Archived Incidents",
  "2025 Archive"), at any level. Archived files drop out of Latest, stay in
  Browse, and are never offered as updates. An archive folder counts as part
  of its parent for products, so `Iran/Archive/x.kmz` and `Iran/y.kmz` are
  versions of one product.
- **Moving files and renaming folders** → do it any way you like. Unchanged
  files are re-pointed on users' devices without a download. If a product
  moves *and* gets a new version in the same commit, users get Update.
- **Deleting** → users who installed it keep it; it shows "no longer on
  GitHub" and they can remove it.
- **Renaming the default branch** is handled. Keep the repository public and
  its name (`s2underground/GhostMaps`) unchanged; both are built into the
  plugin.
- **File types**: `.kmz` and `.kml` map layers; `.zip` files of icon images
  (PNG/JPG/GIF/BMP, in folders or not, optionally with an `iconset.xml`).
  Other files (PDFs, images, data packages) aren't listed or are refused.

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
├── GhostMapsPlugin.java      entry point: wiring, toolbar button, toasts, diagnostics
├── ui/
│   ├── GhostMapsPane.java    views, tabs, filter, dialogs; renders RowBuilder rows
│   ├── RowBuilder.java       snapshot → rows with status and actions (pure, tested)
│   └── Diagnostics.java      writes diagnostics.txt
├── engine/
│   ├── GhostMapsEngine.java  records, job queue, install/update/remove, reconciliation
│   ├── Snapshot.java         immutable state for the UI
│   ├── Platform.java         the interface to ATAK
│   ├── Tuning.java           all timing rules
│   └── EngineLog.java        logging seam
├── atak/
│   ├── AtakPlatform.java     Platform on ATAK's APIs
│   └── AtakEvents.java       ATAK file-list and icon-set events → engine
├── net/
│   ├── RepoSource.java       listing + download interface
│   ├── RepoClient.java       GitHub: commit/ETag checks, tree, commit-pinned verified downloads
│   └── CancelToken.java      prompt download cancellation
└── core/                     no Android code
    ├── RepoEntry.java        one published file: kind, product, scope folder, archive flag
    ├── FileName.java         product / date / version from a file name
    ├── FileKind.java         map layer or icon set
    ├── TreeParser.java       GitHub tree JSON → entries
    ├── Catalog.java          indexes, newest versions, folders, search
    ├── UpdatePlanner.java    up to date / moved / changed / newer / superseded / gone
    ├── InstallRecord.java    one install and its lifecycle state
    ├── InstallStore.java     installed.json (atomic, backed up, migrates v1)
    ├── AtomicTextFile.java   crash-safe file writes
    ├── IconSetInspector.java checks icon-set ZIPs
    ├── DeviceState.java      what ATAK has loaded
    ├── OtherSources.java     maps loaded from DataSync etc.
    ├── GitBlobHasher.java    git blob SHA-1
    ├── LocalNames.java       on-device file names
    ├── Formats.java          sizes, dates, folder names
    └── Clock.java            injectable time
app/src/test/...              JUnit tests (core, net with a fake GitHub server, engine with a simulated ATAK, ui)
docs/user_manual/             user manual source (Typst), built by TAK.gov's pipeline
```

## Changelog

**1.0**
- Targets ATAK-CIV 5.6.0 (5.6.0 plugin template).
- Latest / Browse / Installed views of the GhostMaps repository with filter.
- Install, update (old version removed only after the new one loads), update
  all, remove, reload, fly-to.
- Icon sets installed through ATAK's icon-set manager.
- Commit-pinned, resumable, SHA-verified downloads; offline use of the saved
  list.
- Own folder (`atak/GhostMaps/`), in-place loading; never touches DataSync's
  or other sources' files, and flags maps already loaded from them.
- Installed files followed through moves, folder renames and archiving;
  updates confined to the same product and folder; default-branch rename
  handled.
- Crash-safe install records with automatic recovery.

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
