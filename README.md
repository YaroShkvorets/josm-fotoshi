# Fotoshi photos in JOSM

A [JOSM](https://josm.openstreetmap.de) plugin. Its Fotoshi panel shows the photos in the selected object's `image:fotoshi` tag, one at a time. The tag holds [Fotoshi](https://fotoshi.com) photo ids, separated by `;` when there are several.

- ‹ › step through the photos.
- Scroll over the photo to zoom in and out around the pointer, and drag to move a zoomed-in photo.
- A click on the photo, or **Open**, shows its page on fotoshi.com, with the photographer and licence.
- Photos come straight from Fotoshi's CDN, so no key or account is needed.

## Install

1. Download `fotoshi.jar` from the [latest release](https://github.com/YaroShkvorets/josm-fotoshi/releases/latest) into JOSM's plugins folder:
   - macOS: `~/Library/JOSM/plugins`
   - Linux: `~/.local/share/JOSM/plugins`
   - Windows: `%APPDATA%\JOSM\plugins`
2. Tick `fotoshi` in Preferences > Plugins. It loads without a restart.
3. Open the panel from the Windows menu, or its camera button on the left.

It needs JOSM 18822 or later.

## Build

`./build.sh` builds `build/fotoshi.jar` with JDK 11 or later. It builds against the installed JOSM on macOS; elsewhere, set `JOSM_JAR` to JOSM's jar. `./build.sh install` also copies the jar into JOSM's plugins folder.

## Release

Publish a GitHub release with a tag like `v2`. The workflow then builds the jar against JOSM 18822, the oldest version the plugin supports (`Plugin-Mainversion` in `MANIFEST.MF`), and attaches it to the release. The plugin version is the tag without the `v`.

JOSM's plugin list can link to the latest release's jar: `https://github.com/YaroShkvorets/josm-fotoshi/releases/latest/download/fotoshi.jar`.

## Licence

GPL-2.0-or-later, like JOSM. See [LICENSE](LICENSE).
