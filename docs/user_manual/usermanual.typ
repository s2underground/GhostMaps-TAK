
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
 GhostMaps downloads S2Underground's map data (KMZ overlays from the public
 GhostMaps repository on GitHub) straight into ATAK, and keeps it up to date.
 No TAK Server account is needed; any internet connection works.
]
= Opening GhostMaps
Tap the GhostMaps button on the toolbar. The panel has three tabs:

- *Latest*: the newest version of every map product, newest first. Daily
  exports (for example the Common Intelligence Picture) appear once, with
  the number of versions available. Archived products are left out.
- *Browse*: the repository's folders, including the archive. Tap a folder to
  open it and the yellow bar at the top to go back up.
- *Installed*: everything GhostMaps put on this device, updates first.

Type in the filter box to narrow any tab (for example `iran`, `wildfire`,
`border`). Every word has to match the file's name or folder.
]

#tak-slide[
= Installing and updating
Each file shows its date, version and download size. Tap *Install* to
download it; ATAK imports it like any file from the Import Manager, so it
appears in Overlay Manager → File Overlays and QuickSearch can find its
placemarks.

- *Update* (amber) means a newer version exists, or the same file changed on
  GitHub. Updating installs the new file, then removes the older version.
- *Update all* installs every pending update at once.
- Tap an installed file (or its row) to fly the map to it. Tap the green
  *Installed* button, or *Remove* on the Installed tab, to remove it.
- Files of 10 MB or more, or 2 MB or more on cellular/hotspot, ask before
  downloading.

Downloads run one at a time and keep going when the panel is closed; a
message appears when each finishes. A download that's stopped or loses its
connection continues where it left off when you tap Install again. Every file
is checked against GitHub's fingerprint before it is imported.
]

#tak-slide[
= Updates and working offline
GhostMaps checks GitHub when you open the panel (at most every 10 minutes)
and when you tap the refresh button. With no connection, the last file list
is shown, with a note saying when it was saved. Installed files stay on the
map either way.

GitHub allows about 60 list checks per hour from one network; unchanged lists
don't count. If many devices share one connection and hit the limit, the
status line says when it resets.

Removing a file in the Overlay Manager is fine: GhostMaps notices and lists it
as not installed.

= DataSync and other sources
GhostMaps keeps its files in its own folder (`atak/GhostMaps`) and never
changes or removes files it didn't install. If a map is already on the device
from DataSync or another import, its row says "also loaded from another
source", and installing asks first, since you'd see it twice on the map.

When S2Underground reorganizes the data on GitHub (new folders, archived
products), installed files are recognized in their new place without
downloading them again.
]
