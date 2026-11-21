#!/usr/bin/env python3
"""Generates launcher icon styles + activity-alias entries for Vidora.

Each (icon style x app name) combination becomes one <activity-alias>, which lets the
user change the launcher icon AND the launcher name from inside the app.
Run:  python3 tools/gen_icons.py   (rewrites res files + AndroidManifest.xml)
"""
import os

ROOT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main")
RES = os.path.join(ROOT, "res")

# id, label, gradient start, gradient end, glyph color
ICONS = [
    ("classic",  "Classic",  "#FF1744", "#B0003A", "#FFFFFF"),
    ("midnight", "Midnight", "#311B92", "#0D0221", "#E1BEE7"),
    ("ocean",    "Ocean",    "#00B0FF", "#0039CB", "#FFFFFF"),
    ("emerald",  "Emerald",  "#00E676", "#00695C", "#FFFFFF"),
    ("sunset",   "Sunset",   "#FFAB00", "#FF3D00", "#FFFFFF"),
    ("mono",     "Mono",     "#212121", "#000000", "#FFFFFF"),
]

# id, string resource value
NAMES = [
    ("vidora", "Vidora"),
    ("vidorapro", "Vidora Pro"),
    ("player", "Player"),
    ("mediahub", "Media Hub"),
]

DEFAULT = ("classic", "vidora")

BG = """<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">
    <path android:pathData="M0,0h108v108h-108z">
        <aapt:attr name="android:fillColor">
            <gradient android:type="linear"
                android:startX="0" android:startY="0"
                android:endX="108" android:endY="108"
                android:startColor="{c1}" android:endColor="{c2}" />
        </aapt:attr>
    </path>
    <path android:fillColor="#22FFFFFF"
        android:pathData="M108,0 L108,46 C80,52 56,30 62,0 Z" />
</vector>
"""

# Rounded "play" glyph inside a soft ring, kept within the 66dp safe zone.
FG = """<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">
    <path android:fillColor="{g}" android:fillAlpha="0.18"
        android:pathData="M54,30a24,24 0,1 1,0 48a24,24 0,1 1,0 -48z" />
    <path android:fillColor="{g}"
        android:pathData="M47.5,40.6 C46,39.7 44,40.8 44,42.6 L44,65.4 C44,67.2 46,68.3 47.5,67.4 L66.3,56 C67.8,55.1 67.8,52.9 66.3,52 Z" />
</vector>
"""

MONO = """<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">
    <path android:fillColor="#FFFFFFFF"
        android:pathData="M47.5,40.6 C46,39.7 44,40.8 44,42.6 L44,65.4 C44,67.2 46,68.3 47.5,67.4 L66.3,56 C67.8,55.1 67.8,52.9 66.3,52 Z" />
</vector>
"""

ADAPTIVE = """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_bg_{id}" />
    <foreground android:drawable="@drawable/ic_launcher_fg_{id}" />
    <monochrome android:drawable="@drawable/ic_launcher_mono" />
</adaptive-icon>
"""


def write(path, content):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        f.write(content)


def main():
    for icon_id, _, c1, c2, g in ICONS:
        write(f"{RES}/drawable/ic_launcher_bg_{icon_id}.xml", BG.format(c1=c1, c2=c2))
        write(f"{RES}/drawable/ic_launcher_fg_{icon_id}.xml", FG.format(g=g))
        write(f"{RES}/mipmap-anydpi-v26/ic_launcher_{icon_id}.xml", ADAPTIVE.format(id=icon_id))
    write(f"{RES}/drawable/ic_launcher_mono.xml", MONO)

    names = "\n".join(f'    <string name="launcher_name_{n}" translatable="false">{v}</string>' for n, v in NAMES)
    write(f"{RES}/values/launcher_names.xml",
          f'<?xml version="1.0" encoding="utf-8"?>\n<resources>\n{names}\n</resources>\n')

    aliases = []
    for icon_id, *_ in ICONS:
        for name_id, _ in NAMES:
            enabled = "true" if (icon_id, name_id) == DEFAULT else "false"
            aliases.append(f"""        <activity-alias
            android:name=".alias.L_{icon_id}_{name_id}"
            android:enabled="{enabled}"
            android:exported="true"
            android:icon="@mipmap/ic_launcher_{icon_id}"
            android:roundIcon="@mipmap/ic_launcher_{icon_id}"
            android:label="@string/launcher_name_{name_id}"
            android:targetActivity=".MainActivity">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity-alias>""")

    manifest_path = f"{ROOT}/AndroidManifest.xml"
    with open(manifest_path) as f:
        manifest = f.read()
    start = manifest.index("<!-- ALIASES:BEGIN -->") + len("<!-- ALIASES:BEGIN -->")
    end = manifest.index("        <!-- ALIASES:END -->")
    manifest = manifest[:start] + "\n" + "\n".join(aliases) + "\n" + manifest[end:]
    write(manifest_path, manifest)

    # Kotlin catalog so the settings screen always matches the manifest.
    kt_icons = ",\n".join(f'        IconStyle("{i}", "{l}", 0xFF{c1[1:]}, 0xFF{c2[1:]})' for i, l, c1, c2, _ in ICONS)
    kt_names = ",\n".join(f'        LauncherName("{n}", "{v}")' for n, v in NAMES)
    write(os.path.join(ROOT, "java/com/vidora/app/util/LauncherCatalog.kt"), f"""// GENERATED by tools/gen_icons.py — do not edit by hand.
package com.vidora.app.util

data class IconStyle(val id: String, val label: String, val start: Long, val end: Long)
data class LauncherName(val id: String, val label: String)

object LauncherCatalog {{
    val icons = listOf(
{kt_icons}
    )
    val names = listOf(
{kt_names}
    )
    const val DEFAULT_ICON = "{DEFAULT[0]}"
    const val DEFAULT_NAME = "{DEFAULT[1]}"
}}
""")


if __name__ == "__main__":
    main()
