#!/usr/bin/env python3
"""Generates app/src/main/java/com/vidora/app/ui/icons/IosGlyphs.kt + IosIconMap.kt from Framework7 Icons (MIT).

Usage: python3 tools/icons/gen_ios_icons.py <framework7-icons/package/svg>
"""
import re, sys, os

SVG_DIR = sys.argv[1]
OUT = os.path.join(os.path.dirname(__file__), '../../app/src/main/java/com/vidora/app/ui/icons')

# material icon (family.Name) -> framework7 glyph
MAP = {
 'automirrored.rounded.ArrowBack': 'chevron_left',
 'automirrored.rounded.KeyboardArrowRight': 'chevron_right',
 'automirrored.rounded.OpenInNew': 'arrow_up_right_square',
 'automirrored.rounded.PlaylistAdd': 'text_badge_plus',
 'automirrored.rounded.PlaylistPlay': 'list_bullet',
 'automirrored.rounded.QueueMusic': 'music_note_list',
 'automirrored.rounded.Send': 'paperplane_fill',
 'automirrored.rounded.VolumeUp': 'speaker_2_fill',
 'outlined.AccountCircle': 'person_crop_circle',
 'outlined.Download': 'arrow_down_circle',
 'outlined.FileDownload': 'arrow_down_to_line',
 'outlined.Home': 'house',
 'outlined.SlowMotionVideo': 'play_circle',
 'outlined.Subscriptions': 'rectangle_stack',
 'outlined.VideoLibrary': 'play_rectangle',
 'rounded.AccountCircle': 'person_crop_circle_fill',
 'rounded.Add': 'plus',
 'rounded.Album': 'music_albums',
 'rounded.ArrowDropDown': 'chevron_down',
 'rounded.Audiotrack': 'music_note',
 'rounded.AutoAwesome': 'sparkles',
 'rounded.Bedtime': 'moon_fill',
 'rounded.BrightnessMedium': 'sun_max_fill',
 'rounded.Check': 'checkmark',
 'rounded.CheckCircle': 'checkmark_circle_fill',
 'rounded.Close': 'xmark',
 'rounded.ClosedCaption': 'captions_bubble_fill',
 'rounded.ClosedCaptionDisabled': 'captions_bubble',
 'rounded.CloudOff': 'wifi_slash',
 'rounded.Delete': 'trash',
 'rounded.DeleteSweep': 'trash_fill',
 'rounded.Download': 'arrow_down_circle_fill',
 'rounded.Edit': 'pencil',
 'rounded.FastForward': 'forward_fill',
 'rounded.FastRewind': 'backward_fill',
 'rounded.Favorite': 'heart_fill',
 'rounded.FavoriteBorder': 'heart',
 'rounded.Forward10': 'goforward_10',
 'rounded.Fullscreen': 'arrow_up_left_arrow_down_right',
 'rounded.FullscreenExit': 'arrow_down_right_arrow_up_left',
 'rounded.GraphicEq': 'waveform',
 'rounded.HighQuality': 'slider_horizontal_3',
 'rounded.History': 'clock',
 'rounded.Home': 'house_fill',
 'rounded.Info': 'info_circle',
 'rounded.KeyboardArrowDown': 'chevron_down',
 'rounded.KeyboardArrowUp': 'chevron_up',
 'rounded.KeyboardDoubleArrowRight': 'chevron_right_2',
 'rounded.LibraryMusic': 'music_albums_fill',
 'rounded.Lock': 'lock_fill',
 'rounded.LockOpen': 'lock_open_fill',
 'rounded.Mic': 'mic_fill',
 'rounded.MoreVert': 'ellipsis_vertical',
 'rounded.Movie': 'film',
 'rounded.NorthWest': 'arrow_up_left',
 'rounded.NotificationsActive': 'bell_fill',
 'rounded.Pause': 'pause_fill',
 'rounded.Person': 'person_fill',
 'rounded.PictureInPictureAlt': 'rectangle_on_rectangle',
 'rounded.PlayArrow': 'play_fill',
 'rounded.PushPin': 'pin_fill',
 'rounded.Refresh': 'arrow_clockwise',
 'rounded.RemoveCircleOutline': 'minus_circle',
 'rounded.Repeat': 'repeat',
 'rounded.RepeatOne': 'repeat_1',
 'rounded.Replay': 'arrow_counterclockwise',
 'rounded.Replay10': 'gobackward_10',
 'rounded.Search': 'search',
 'rounded.Settings': 'gear_alt',
 'rounded.Share': 'square_arrow_up',
 'rounded.Shield': 'shield_fill',
 'rounded.Shuffle': 'shuffle',
 'rounded.SkipNext': 'forward_end_fill',
 'rounded.SkipPrevious': 'backward_end_fill',
 'rounded.SlowMotionVideo': 'play_circle_fill',
 'rounded.Speed': 'speedometer',
 'rounded.Cast': 'tv',
 'rounded.CastConnected': 'tv_fill',
 'rounded.Subscriptions': 'rectangle_stack_fill',
 'rounded.ThumbDown': 'hand_thumbsdown',
 'rounded.ThumbUp': 'hand_thumbsup',
 'rounded.Toc': 'list_bullet_indent',
 'rounded.Translate': 'globe',
 'rounded.Verified': 'checkmark_seal_fill',
 'rounded.VerifiedUser': 'checkmark_shield_fill',
 'rounded.Visibility': 'eye_fill',
 'rounded.VisibilityOff': 'eye_slash_fill',
 'rounded.WatchLater': 'clock_fill',
 'rounded.ZoomInMap': 'arrow_down_right_arrow_up_left',
 'rounded.ZoomOutMap': 'arrow_up_left_arrow_down_right',
 'rounded.VideoLibrary': 'play_rectangle_fill',
}
# extra glyphs used directly by new UI (About, update, cast, data saver …)
EXTRA = ['info_circle_fill', 'arrow_2_circlepath', 'tv', 'antenna_radiowaves_left_right', 'speedometer',
         'leaf_arrow_circlepath', 'chevron_right', 'square_arrow_down', 'checkmark_seal', 'doc_text', 'link',
         'app_badge', 'star_fill', 'heart_circle_fill', 'gauge', 'wifi', 'bolt_fill', 'film_fill',
         'logo_github', 'device_phone_portrait', 'hand_raised_fill', 'paintbrush_fill', 'sparkles',
         # iOS Settings tiles
         'person_fill', 'square_grid_2x2_fill', 'play_rectangle_fill', 'shield_lefthalf_fill', 'hand_thumbsup_fill',
         'arrow_down_to_line', 'gear_alt_fill', 'sun_max_fill', 'captions_bubble_fill', 'bell_fill']

def num(s): return float(s)

def shape_to_path(tag, attrs):
    a = dict(re.findall(r'([\w-]+)="([^"]*)"', attrs))
    if tag == 'path':
        return a.get('d')
    if tag == 'circle':
        cx, cy, r = num(a['cx']), num(a['cy']), num(a['r'])
        return f"M {cx-r} {cy} A {r} {r} 0 1 0 {cx+r} {cy} A {r} {r} 0 1 0 {cx-r} {cy} Z"
    if tag == 'ellipse':
        cx, cy, rx, ry = num(a['cx']), num(a['cy']), num(a['rx']), num(a['ry'])
        return f"M {cx-rx} {cy} A {rx} {ry} 0 1 0 {cx+rx} {cy} A {rx} {ry} 0 1 0 {cx-rx} {cy} Z"
    if tag == 'rect':
        x, y, w, h = num(a.get('x', '0')), num(a.get('y', '0')), num(a['width']), num(a['height'])
        rx = num(a.get('rx', a.get('ry', '0')))
        if rx <= 0:
            return f"M {x} {y} H {x+w} V {y+h} H {x} Z"
        return (f"M {x+rx} {y} H {x+w-rx} A {rx} {rx} 0 0 1 {x+w} {y+rx} V {y+h-rx} A {rx} {rx} 0 0 1 {x+w-rx} {y+h} "
                f"H {x+rx} A {rx} {rx} 0 0 1 {x} {y+h-rx} V {y+rx} A {rx} {rx} 0 0 1 {x+rx} {y} Z")
    if tag == 'polygon':
        pts = re.split(r'[\s,]+', a['points'].strip())
        coords = [f"{pts[i]} {pts[i+1]}" for i in range(0, len(pts) - 1, 2)]
        return "M " + " L ".join(coords) + " Z"
    return None

def load(name):
    src = open(os.path.join(SVG_DIR, name + '.svg')).read()
    tx = ty = 0.0
    tr = re.findall(r'transform="([^"]*)"', src)
    if tr:
        m = re.fullmatch(r'translate\(\s*([-\d.]+)(?:[\s,]+([-\d.]+))?\s*\)', tr[0].strip())
        if len(tr) != 1 or not m:
            raise SystemExit(f'{name}: unsupported transform {tr}')
        tx, ty = float(m.group(1)), float(m.group(2) or 0)
    vb = re.search(r'viewBox="([^"]+)"', src).group(1).split()
    w, h = float(vb[2]), float(vb[3])
    paths = []
    for tag, attrs in re.findall(r'<(path|circle|rect|ellipse|polygon)\b([^>]*)/?>', src):
        d = shape_to_path(tag, attrs)
        if d: paths.append(re.sub(r'\s+', ' ', d).strip())
    if not paths: raise SystemExit(f'{name}: no paths')
    return w, h, tx, ty, paths

def ok(e):
    return os.path.exists(os.path.join(SVG_DIR, e + '.svg'))
glyphs = sorted(set(MAP.values()) | {e for e in EXTRA if ok(e)})
missing = [e for e in EXTRA if not ok(e)]
if missing: print('skipping missing extras:', missing)

def kname(g):
    parts = g.split('_')
    return parts[0] + ''.join(p[:1].upper() + p[1:] for p in parts[1:])

os.makedirs(OUT, exist_ok=True)
with open(os.path.join(OUT, 'IosGlyphs.kt'), 'w') as f:
    f.write('''// GENERATED by tools/icons/gen_ios_icons.py — do not edit by hand.
// Glyphs from Framework7 Icons (https://github.com/framework7io/framework7-icons), MIT License,
// Copyright (c) Vladimir Kharlampidi. iOS-style line/fill icons drawn to Apple's HIG.
package com.vidora.app.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** iOS-style glyphs as Compose vectors (drawn with ~8 % padding like Material icons). */
@Suppress("ObjectPropertyName")
object IosGlyphs {
    private fun glyph(name: String, w: Float, h: Float, tx: Float, ty: Float, vararg paths: String): ImageVector {
        val pad = w * 0.08f
        return ImageVector.Builder(
            name = "ios.$name", defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = w + pad * 2, viewportHeight = h + pad * 2,
        ).apply {
            addGroup(translationX = pad + tx, translationY = pad + ty)
            paths.forEach { addPath(pathData = addPathNodes(it), fill = SolidColor(Color.Black)) }
            clearGroup()
        }.build()
    }

''')
    for g in glyphs:
        w, h, tx, ty, paths = load(g)
        args = ', '.join('"' + p + '"' for p in paths)
        f.write(f'    val {kname(g)}: ImageVector by lazy {{ glyph("{g}", {w}f, {h}f, {tx}f, {ty}f, {args}) }}\n')
    f.write('}\n')

# extension properties shadowing the Material ones
by_family = {}
for k, g in MAP.items():
    by_family.setdefault(k.rsplit('.', 1)[0], []).append((k.rsplit('.', 1)[1], g))
recv = {'rounded': 'Icons.Rounded', 'outlined': 'Icons.Outlined', 'automirrored.rounded': 'Icons.AutoMirrored.Rounded'}
alias = {'rounded': 'R', 'outlined': 'O', 'automirrored.rounded': 'AR'}
with open(os.path.join(OUT, 'IosIconMap.kt'), 'w') as f:
    f.write('''// GENERATED by tools/icons/gen_ios_icons.py — do not edit by hand.
// Each property shadows the Material icon of the same name: iOS glyph when IconStyle.ios, else Material.
package com.vidora.app.ui.icons

import androidx.compose.material.icons.Icons
import androidx.compose.ui.graphics.vector.ImageVector
''')
    for fam, items in sorted(by_family.items()):
        for name, g in sorted(items):
            f.write(f'import androidx.compose.material.icons.{fam}.{name} as M{alias[fam]}{name}\n')
    f.write('\n')
    for fam, items in sorted(by_family.items()):
        for name, g in sorted(items):
            f.write(f'val {recv[fam]}.{name}: ImageVector get() = if (IconStyle.ios) IosGlyphs.{kname(g)} else {recv[fam]}.M{alias[fam]}{name}\n')
print(len(glyphs), 'glyphs')
