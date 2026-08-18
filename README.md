# McTTF

Converts vanilla Minecraft's bitmap fonts into pixel-perfect TrueType (`.ttf`) fonts:

- `default`: the main game font (ascii + accented + nonlatin_european bitmap sheets on 1.13+. On 1.12.2 or below the hardcoded `FontRenderer` ascii grid plus `legacy_unicode` pages)
- `alt`: the enchantment table font (Standard Galactic Alphabet)
- `illageralt`: the unused illager rune font

Every glyph outline is traced directly from the source pixels (no bitmap-to-vector
approximation, no smoothing), so the resulting TTF renders each glyph as the exact
stair-stepped shape Minecraft draws on screen, just scalable.

## Requirements

- Java 25
- Network access on first run of a given version (to fetch the client jar), unless you pass `--input` yourself.
- 1.13+ jars are read via `font/default.json`/`font/alt.json`/`font/illageralt.json`.
- 1.12.2 (and earlier) has no font JSON. `default` and `alt` are synthesized from `FontRenderer`'s ascii grid plus`glyph_sizes.bin`/`unicode_page_XX.png`.
  - `illageralt` is skipped (added in 1.18). See [the documentation](docs/vanilla-fonts.md) for how Mojang's old and new font assets differ.  (Note the doc is pretty AI-slopped at the moment)

## Build

```
gradle build
```

## Run

With no arguments, it downloads the latest release client jar from Mojang (cached under `.mcttf/versions/<id>/client.jar`) and converts all three fonts, each in all four styles, into `./mcttf-output`:

```
gradle run
```

Options:

```
gradle run --args="[options]"
  -i, --input <jar-or-assets-dir>    Use this client jar or extracted assets dir instead of downloading
  -v, --version <id>                 Minecraft version to download (default: latest release)
  -o, --output <dir>                 Output directory (default: .mcttf/output)
  -f, --fonts <a,b,c>                Comma-separated font ids: default,alt,illageralt (default: all)
  -s, --styles <a,b,c>               Comma-separated styles: regular,bold,italic,bolditalic (default: all)
```

Output per font: `<Name>.ttf`, `<Name>-Bold.ttf`, `<Name>-Italic.ttf`, `<Name>-BoldItalic.ttf`(e.g. `MinecraftDefault.ttf`, `MinecraftDefault-Bold.ttf`, ...) each with the family/subfamily name, `head.macStyle` and `OS/2.fsSelection` bits set so apps group all four as one family.

Bold and italic are synthesized the same way Minecraft's own renderer does it, not just a generic vector transform.
Bold dilates the source raster 1px to the right before tracing (matching Minecraft's overlapping double-draw).
Italic shears the traced outline (matching Minecraft's real sheared-quad render, so edges stay crisp rather than stair-stepping).

## Using it as a library

Depend on this project and call `com.cleanroommc.mcttf.api.McTtf` directly with no CLI/process needed:

```java
import com.cleanroommc.mcttf.api.McTTF;
import com.cleanroommc.mcttf.extract.AssetSource;
import com.cleanroommc.mcttf.font.FontStyle;

// Convert all three standard fonts (all four styles each) from a client jar
McTtf.convertStandardFonts(Path.of("client.jar"), Path.of("out"));

// Or keep the asset source open and convert individual fonts/styles, or get raw bytes:
try (AssetSource assets = McTtf.openAssetSource(Path.of("client.jar"))) {
    byte[] ttfBytes = McTtf.convertToBytes(assets, "default", "Minecraft Default", FontStyle.BOLD);
    McTtf.convert(assets, "alt", "Minecraft Enchantment", FontStyle.ITALIC, Path.of("out/EnchantmentItalic.ttf"));
    McTtf.convertAllStyles(assets, "illageralt", "Minecraft Illager", "MinecraftIllager", Path.of("out"));
}
```

`McTtf.STANDARD_FONTS` lists the supported font ids (`default`, `alt`, `illageralt`) mapped to their display family names.
`McTtf.ALL_STYLES` lists the four `FontStyle`s (`REGULAR`, `BOLD`, `ITALIC`, `BOLD_ITALIC`).

## How it works

1. **Extract**: reads the font JSON descriptors (`bitmap` and `legacy_unicode` providers) and their backing PNGs straight out of the jar/asset tree.
   If the JSON is missing (1.12.2), a built-in profile reconstructs the same providers from `FontRenderer`'s hardcoded 16×16 ascii map.
2. **Slice**: cuts each glyph's cell out of its source texture and trims it to its opaque column range, mirroring how Minecraft computes advance width at render time.
3. **Trace**: separates each glyph's pixels into 8-connected filled regions and 4-connected background holes (a padded flood fill, so "outside" vs. an enclosed counter is never ambiguous).
   Then walks each region's boundary into a closed rectilinear polygon - filled regions forward, holes reversed, giving the winding the TrueType spec expects.
4. **Emit**: a hand-rolled `sfnt` writer builds the `glyf`/`loca`/`cmap`/`hmtx`/`head`/`hhea`/`maxp`/`name`/`post`/`OS/2` tables directly.
   No external font-authoring library is used.

The em models Minecraft's own chat line box: a 12-pixel-tall line with a 9-pixel ascent (`unitsPerEm = 1200`, 100 units/px).
That's also why these fonts can be reimported into Minecraft as a `ttf` font provider and come out pixel-perfect (set `"size": 12` not the default 11):

```json
{ "type": "ttf", "file": "custom_font:MinecraftDefault.ttf", "size": 12 }
```

Scaling is driven by each provider's declared `height`, so 8px ASCII cells and 16px supersampled unicode-page cells (displayed at height 8) come out the same physical size.

## Status

Regular, Bold, Italic and Bold Italic are all generated.
Minecraft's other text decorations (underline, strikethrough, obfuscated) are rendering-time effects, not glyph shapes, so they don't apply here.
`post.underlinePosition`/`underlineThickness` are still set for apps that draw their own underline.
