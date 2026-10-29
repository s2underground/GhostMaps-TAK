
#import "@preview/polylux:0.4.0": *
#import "formatting.typ": *

#show: userguide.with(
   plugin-name: "GhostMaps",
   plugin-version: "1.0",
   platform: "ATAK",
   platform-version: "5.6.0",
)


#tak-slide[
  = Overview
#toolbox.side-by-side(columns: (.75fr, 9fr))[
#image("plugin_icon.png", width: 70%)
][
 GhostMaps downloads S2Underground's map data (map layers and icon sets from
 the public GhostMaps repository on GitHub) straight into ATAK, and keeps it
 up to date. No TAK Server account is needed; any internet connection works.
]
= Opening GhostMaps
Tap the GhostMaps button on the toolbar. The panel has three tabs:

- *Latest*: the newest version of every product, newest first. Daily
  exports (for example the Common Intelligence Picture) appear once, with
  the number of versions available. Archived files are left out.
- *Browse*: the repository's folders, including archives. Tap a folder to
  open it and the yellow bar at the top to go back up.
- *Installed*: everything GhostMaps put on this device, map layers and icon
  sets separately, with anything that needs attention first.

Type in the filter box to narrow any tab (for example `iran`, `wildfire`,
`border`). Every word has to match the file's name or folder.
]

#tak-slide[
= Installing and updating
Each file shows its date, version and download size. *Tap* a row to do its
main action: Install, Update, show it on the map, Retry. *Long-press* a row
for everything else, including Remove (which always asks first).

- Map layers appear in Overlay Manager → File Overlays.
- Icon sets (such as "S2U Map Icons") are added to ATAK's icon sets, so
  their icons appear when you drop a point. They are not map layers.
- *Update* means a newer version exists, or the same file changed on GitHub.
  The old version stays on the map until the new one has loaded.
- *Update all* installs every pending update at once.
- Files of 10 MB or more, or 2 MB or more on cellular/hotspot, ask before
  downloading.

Downloads run one at a time and keep going when the panel is closed; a
message appears when each finishes. A download that's stopped or loses its
connection continues where it left off. Every file is checked against
GitHub's fingerprint before ATAK sees it.
]

#tak-slide[
= What the status line means
- *On the map* / *Installed in ATAK's icon sets*: all good.
- *Not in ATAK's icon sets*: the set was removed in ATAK; tap *Add to ATAK*.
- *ATAK is loading it…*: large files can take a minute.
- *Not on the map; tap Reload*: the file is here but ATAK isn't showing it.
- *No longer on GitHub*: kept on your device; a similar file is offered if
  there is one.
- *Already loaded from another source*: the same map is on the device from
  DataSync or another import. Installing asks first, since you'd see it twice.
- *Installed by an earlier test build*: from a pre-release GhostMaps. *Forget*
  clears the entry and leaves the file alone.
- *Failed*: the reason is shown, with a Retry button.
]

#tak-slide[
= Updates and working offline
GhostMaps checks GitHub when you open the panel (at most every 10 minutes)
and when you tap the refresh button. With no connection, the last file list
is shown, with a note. Installed files stay on the map either way.

If many devices share one connection and reach GitHub's hourly limit, the
status line says so and the saved list is used meanwhile.

Removing a layer in the Overlay Manager, or an icon set in ATAK's settings,
is fine: GhostMaps notices within a few seconds.

= DataSync and other sources
GhostMaps keeps its files in its own folder (`atak/GhostMaps`) and never
changes or removes files it didn't install.

When S2Underground reorganizes the data on GitHub (new folders, archived
products), installed files are recognized in their new place without
downloading them again.

= Troubleshooting
Long-press the refresh button to save a report to
`atak/tools/ghostmaps/diagnostics.txt`. Nothing is sent anywhere.
]
