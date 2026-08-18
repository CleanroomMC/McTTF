# Mojang's Vanilla Fonts: Old vs New

What Java Edition actually ships as the default, enchantment, uniform, and illager fonts, and how that changed across the 1.13 resource-pack rewrite and the 1.20 Unifont swap.
Numbers below were measured from the 1.12.2 client jar and the 26.2 client jar, then converted with McTTF.

"Old" here means **1.12.2 and earlier**. "New" means **current vanilla** (26.2 at the time of writing).
1.13–1.19 sit in between and are called out where they differ from both ends.

## Timeline

| When                                  | What Mojang changed                                                                                                                                                                                                                                                                                                                                         |
|---------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Classic–1.12.2                        | Two `FontRenderer`s. Glyph map is a hardcoded 256-character string. Unicode is `unicode_page_XX.png` + `glyph_sizes.bin`. No `font/*.json`.                                                                                                                                                                                                                 |
| 1.13 (17w43a / resource-pack rewrite) | Fonts become JSON provider lists under `assets/minecraft/font/`. Extra bitmap sheets (`accented.png`, `nonlatin_european.png`) take the European letters that used to live in `ascii.png`. `legacy_unicode` wraps the old page files. `ttf` providers exist for resource packs. 1.13-pre6 briefly replaced the default with a TTF and was reverted in pre7. |
| 1.16 (20w17a)                         | JSON text gets a `font` key (`minecraft:default` by default).                                                                                                                                                                                                                                                                                               |
| 1.18 (21w37a)                         | `illageralt` + `asciillager.png` (Dungeons runes). Unused in gameplay but reachable via commands.                                                                                                                                                                                                                                                           |
| 1.20 (23w17a)                         | `legacy_unicode` removed. Unicode pages leave the jar. Fallback is GNU Unifont via the `unihex` provider. `reference` providers split `default.json` into `include/space`, `include/default`, `include/unifont`.                                                                                                                                            |

The named fonts across that span:

| Font id      | Role                                           | 1.12.2                                   | 1.13–1.19                                     | 1.20+                                                     |
|--------------|------------------------------------------------|------------------------------------------|-----------------------------------------------|-----------------------------------------------------------|
| `default`    | Chat, GUI, signs (Mojangles / Minecraft Seven) | `ascii.png` + unicode pages              | ascii + accented + nonlatin + unicode pages   | ascii + accented + nonlatin with Unifont filling the rest |
| `alt`        | Enchanting-table SGA                           | second `FontRenderer` on `ascii_sga.png` | `alt.json` → `ascii_sga.png` (A–Z / a–z only) | same                                                      |
| `uniform`    | "Force Unicode Font" / missing-glyph fallback  | the unicode pages themselves             | `legacy_unicode`                              | `unihex` (Unifont `.hex` zip)                             |
| `illageralt` | Unused Dungeons runes                          | absent                                   | from 1.18                                     | `asciillager.png`                                         |

None of these JSON files live in Mojang's asset index. They are inside the
client jar (except modern Unifont zips, which *are* hashed asset-index objects).

## How a font is described

### 1.12.2: hardcoded `FontRenderer`

`Minecraft` constructs two renderers:

- `textures/font/ascii.png` — default
- `textures/font/ascii_sga.png` — Standard Galactic Alphabet

The character-to-cell map is a 256-character string in `FontRenderer.renderChar`.
It is a CP437-style 16×16 grid: Latin-1 and a handful of extras in rows 0–1 and
8–10, ASCII in rows 2–7, box-drawing in rows 11–13, Greek / math in rows 14–15.
Space and NBSP are forced to 4 px before the sheet is consulted.

Anything not in that string (or everything, if "Force Unicode Font" is on) is
drawn from `textures/font/unicode_page_XX.png` using one byte per codepoint in
`font/glyph_sizes.bin`. High nibble = start column, low nibble = end column, in
a 16×16 cell that is drawn 8 px tall (half scale). Advance is
`(end − start + 1) / 2 + 1`. There is no supplementary-plane coverage.

The 1.12.2 jar ships 222 of 256 pages (missing `08` and `d8`–`f8`, i.e. unused
and surrogate blocks). `glyph_sizes.bin` is 65 536 bytes with 53 429 nonzero
entries.

### 1.13+: `font/<id>.json`

A font is a list of **providers**. The first provider that defines a codepoint
wins. Vanilla uses:

| type             | Role                                                                            |
|------------------|---------------------------------------------------------------------------------|
| `space`          | Explicit advances. Current vanilla: space = 4, ZWNJ = 0.                        |
| `bitmap`         | A PNG plus a `chars` grid. `height` defaults to 8, `ascent` is required.        |
| `legacy_unicode` | 1.13–1.19 only. Same pages + `glyph_sizes.bin` as 1.12.2.                       |
| `reference`      | 1.20+. Inlines another font file once.                                          |
| `unihex`         | 1.20+. GNU Unifont `.hex` inside a zip.                                         |
| `ttf`            | Resource-pack TrueType. Vanilla `default` / `alt` / `illageralt` do not use it. |

Current `default.json` is only references:

```json
{
  "providers": [
    { "type": "reference", "id": "minecraft:include/space" },
    { "type": "reference", "id": "minecraft:include/default", "filter": { "uniform": false } },
    { "type": "reference", "id": "minecraft:include/unifont" }
  ]
}
```

`include/default.json` is the Mojangles bitmaps (`nonlatin_european`, `accented`,
`ascii`), in that order.

## `default` — the face you actually read

### Sheets

| File | Cell | 1.12.2 | 1.13+ |
| --- | --- | --- | --- |
| `textures/font/ascii.png` | 8×8 in a 128×128 sheet (16×16 grid) | Every mapped "default" letter, including À, box drawing, Greek | Printable ASCII plus a few leftovers (`£`, `ƒ`, `ª`, `º`, some box drawing). Accented / Greek / most Latin-1 cells are blanked. |
| `textures/font/accented.png` | 12 px tall (`height` 12, `ascent` 10) | absent | Redesigned ÀÁÂ… with room for the accent above the cap |
| `textures/font/nonlatin_european.png` | 8×8 | absent | Greek, Cyrillic, extra symbols, later emoji / Gothic / currency |
| `textures/font/unicode_page_XX.png` | 16×16 in a 256×256 sheet, drawn at height 8 | the rest of the BMP | 1.13–1.19 only, as `legacy_unicode` |
| Unifont (`unihex` zip) | 16 px Unifont glyphs | absent | 1.20+ fallback; not a Mojangles bitmap |

`ascii.png` is still 128×128 in both eras. The *grid positions* of A–Z / 0–9 did
not move. Mojang blanked the old Latin-1 / Greek cells and drew those characters
on the new sheets instead.

### Coverage (McTTF output of each jar)

McTTF traces every bitmap / `legacy_unicode` glyph it can see. It skips `unihex`
and `ttf`, so a current-jar `default` TTF is "Mojangles only", not Unifont.

| | 1.12.2 | 26.2 (McTTF, no Unifont) |
| --- | --- | --- |
| Mapped codepoints | 53 429 | 2 414 |
| File size | 20.7 MB | 358 KB |
| Planes | BMP only | BMP + 40 supplementary (from `nonlatin_european.png`) |
| ASCII `U+0020`–`U+007E` | 95 / 95 | 95 / 95 |
| Latin-1 `U+0080`–`U+00FF` | 128 / 128 | 94 / 128 |
| CJK `U+4E00`–`U+4E20` | 33 / 33 | 0 |
| Box drawing `U+2500`–`U+257F` | 128 / 128 | 40 / 128 |
| Bopomofo `U+3105`–`U+3129` | 37 / 37 | 1 / 37 |
| Gothic `U+10330` | no | yes |
| U+1FAA3 (bucket emoji) | no | yes |

Shared codepoints: **2 291**. Only in 1.12.2: **51 138** (the unicode pages).
Only in 26.2: **123** (later `nonlatin_european` additions).

A 1.13–1.19 jar is the union: Mojangles sheets **and** the unicode pages, so
CJK is still there, but `À` already comes from `accented.png`.

### Shared glyphs: what actually matches

Of those 2 291 shared codepoints, 141 have identical outlines and advances.
That set is essentially "the classic 8 px Mojangles face": `A`–`Z`, `a`–`z`,
digits, space, `£`, `│`, and similar.

**ASCII letters and digits are the same face.** Comparing `ascii.png` by alpha
only, 142 / 256 cells match. The letters did not change; many fully-transparent
pixels did (RGB under alpha 0), which does not affect a traced outline.

**Fourteen punctuation marks were redrawn** in later `ascii.png`. They are
thinner, and several lost a pixel of advance:

```
" ' ( ) * , . : ; @ ` { | }
```

`"` went from advance 5 to 4 (500 → 400 font units at 100 units/px).

**Accented Latin is a different glyph.** 1.12.2 takes `À` from an 8 px
`ascii.png` cell (7 px of ink). 26.2 takes it from `accented.png` at height 12 /
ascent 10 (10 px of ink). Advance is still 6. About 1 200 shared characters are
at least 2 px taller in 26.2 for this reason.

**Everything else that both versions claim** (Cyrillic, most Greek, extra
symbols) is a redraw on `nonlatin_european.png`, not the old unicode-page cell.
Same codepoint, different pixels, often a different advance.

### Line metrics

McTTF models the chat line as 12 px / 9 px ascent (`unitsPerEm` 1200). Both
eras use that box for 8 px providers.

`accented.png` is height 12, so a current `default` TTF has `hhea.ascent` /
`usWinAscent` of 1000 instead of 900. Reimporting into a 1.13+ `ttf` provider
at `"size": 12` still lines up; the extra two pixels are the accents that used
to clip out of the 8 px cell.

Unicode-page glyphs in 1.12.2 are 16 px cells with provider height 8, so they
land at the same physical size as ascii. Their *advances* follow the bitmap
rule McTTF uses (`round((right+1)*scale)+1`), which is **not** 1.12.2
`FontRenderer`'s `(end−start)/2+1`. That only matters if you care about
matching 1.12.2 unicode spacing (CJK is 9 either way; bopomofo is not — see
Cleanroom `docs/font-rendering.md`).

## `alt` — Standard Galactic Alphabet

Same 128×128 `ascii_sga.png` in both eras. Ink for A–Z / a–z is alpha-identical
(255 / 256 cells match; the one stray pixel is an unused `;` cell).

| | 1.12.2 | 1.13+ |
| --- | --- | --- |
| Binding | Second `FontRenderer`, **same 256-char map as ascii** | `alt.json` maps **only A–Z / a–z** |
| Space extras | NBSP (U+00A0) = 4 (Forge `FontRenderer` special case) | ZWNJ (U+200C) = 0 from `space.json` |

Converted letter outlines and advances match. The TTFs are the same size
(5580 bytes) but not byte-identical, because of that one extra space codepoint.

1.12.2 *could* in principle draw `!` from the SGA sheet (empty cell → 1 px
advance). Nothing in vanilla asks it to. The 1.13 mapping is the useful font.

## `illageralt`

Added in 21w37a (1.18). Five rows on `asciillager.png`: digits, `?`, `!`,
comma / hyphen / period, then a–z and A–Z. Not a 1.12.2 asset. McTTF skips it
when the sheet and JSON are missing.

## `uniform` / Unifont

This is the fallback, not Mojangles.

- **1.12.2:** there is no separate font id. "Force Unicode Font" just points
  `FontRenderer` at the unicode pages for every character, including ASCII.
- **1.13–1.19:** `uniform.json` is a `legacy_unicode` provider. Same pages, an
  older GNU Unifont bitmap, BMP only.
- **1.20+:** those bitmaps are gone. `uniform` (and the tail of `default`) is
  live GNU Unifont via `unihex`. Shapes, combining marks, and the supported
  repertoire change whenever Mojang bumps Unifont (15 → 16 → 17 so far).

A current-jar McTTF `default` does **not** include Unifont. The 2 414 glyphs
are only the Mojangles bitmaps. 1.12.2 McTTF `default` *does* include the old
Unifont-derived pages, which is why it is 50× larger and looks like a different
product the moment you leave Western European.

## What this means in practice

- Type English in both: same face, except those 14 punctuation marks.
- Type `Àé日本語`: 1.12.2 uses 8 px ascii / unicode-page cells; current
  vanilla uses 12 px `accented.png` for the Latin and Unifont for the CJK.
- A resource pack that only replaces `ascii.png` is enough for English in
  every version. It is **not** enough for accented Latin after 1.13 — you
  also have to replace `accented.png` (and the JSON `chars` rows if you
  change the layout).
- You cannot drop a current `default.json` onto a 1.12.2 tree: it asks for
  `accented.png` and `nonlatin_european.png`, which are not there.
- You cannot use 1.12.2 as a `ttf` provider (that type does not exist yet).
  You can feed 1.12.2 textures through McTTF and use the TTF in a modern pack
  or in a replacement renderer.

McTTF's pre-1.13 adapter reconstructs the old `FontRenderer` map plus
`legacy_unicode`. It does not invent `accented.png`. The two generated
`MinecraftDefault.ttf` files are supposed to differ; they are traces of two
different Mojang fonts.
