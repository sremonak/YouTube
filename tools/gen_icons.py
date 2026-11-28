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
    ("classic",  "Orbit",    "#091E2B", "#142B3D", "#72F7C1"),
    ("midnight", "Midnight", "#311B92", "#0D0221", "#E1BEE7"),
    ("ocean",    "Ocean",    "#00B0FF", "#0039CB", "#FFFFFF"),
    ("emerald",  "Emerald",  "#00E676", "#00695C", "#FFFFFF"),
    ("sunset",   "Sunset",   "#FFAB00", "#FF3D00", "#FFFFFF"),
    ("mono",     "Mono",     "#212121", "#000000", "#FFFFFF"),
]

# id, string resource value
NAMES = [
    ("vidora", "Orvixa"),
    ("vidorapro", "Orvixa Plus"),
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

# Original orbit/play mark. Legacy icon/name ids are retained for in-place launcher updates.
FG = """<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">
    <path android:fillColor="#00000000" android:strokeColor="{g}"
        android:strokeWidth="7" android:strokeLineCap="round"
        android:pathData="M65,34 C54,27 41,31 34,42 C26,56 33,72 47,76 C59,80 71,74 76,64" />
    <path android:fillColor="{g}"
        android:pathData="M49,42 Q47,41 47,44 L47,66 Q47,69 50,67 L68,56 Q71,54 68,52 Z" />
    <path android:fillColor="#FFD497" android:pathData="M80,32 A4,4 0,1 1,72 32 A4,4 0,1 1,80 32" />
</vector>
"""

MONO = FG.replace("{g}", "#FFFFFFFF").replace("#FFD497", "#FFFFFFFF")

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
