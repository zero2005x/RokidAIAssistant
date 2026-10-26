# ADR 0005: Layout belongs to the device: pagination is measured on the glasses

- **Status:** Accepted
- **Date:** 2026-10-08

## Context

Wearable waveguide displays differ in pixel dimensions, density, font metrics, and optical alignment relative to the wearer's eye box (for example, `RG_glasses` reports a `480×640` application viewport, and wearers often need to resize or shift the active area so edges are not clipped). Users can also configure font size (`12–36 sp`) and typeface (`SYSTEM` or `MONOSPACE`) from the phone.

Splitting AI responses into fixed character pages on the phone cannot account for actual glyph widths (CJK ideographs vs. Latin text vs. emoji), explicit line breaks, font scaling, or the vertical space taken by the glasses' status header and bottom hint row. Naive character slicing either truncates lines at the bottom of the screen or wastes most of the viewport.

## Decision

We keep text layout and pagination ownership on the glasses (`:glasses-app`), while the phone sends the cleaned full text and declarative viewport settings:

1. **Declarative safe-viewport configuration (`GlassesDisplayConfig`):**
   - The phone stores and sends percentage-based viewport settings (`fontSizeSp`, `font`, `widthPercent`, `heightPercent`, `leftPercent`, `topPercent`), normalized to `30–94%` width/height and at least a `3%` margin from each edge, via `SYSTEM_CONFIG` (`0xF1`) when the user taps Apply and upon Bluetooth reconnection.
   - The glasses persist the applied configuration in `SharedPreferences` (`glasses_display`) and apply the font family and scaled `Density` across all glasses UI labels, including the Bluetooth device selector.
2. **Bounded header and hint chrome (`GlassesDisplayLayout`):**
   - Inside the safe viewport (`MainActivity`), the top status/page-indicator row is capped at `25%` (`HEADER_FRACTION`) and the bottom hint row at `20%` (`HINT_FRACTION`) of the viewport height, each scrollable if its content overflows, guaranteeing that the main answer area is never crowded out by large font sizes or small viewports.
3. **Lossless, device-measured pagination (`MeasuredTextPaginator`):**
   - The main answer `BoxWithConstraints` measures the remaining pixel width and height and passes a `TextMeasurer` callback to `GlassesViewModel.updateTextLayout`.
   - `MeasuredTextPaginator.paginate` lays out the text with the exact `TextStyle`, font family, density, and pixel constraints, slicing pages at measured line boundaries (`layout.getLineBottom(line) <= heightPx`).
   - Every character, whitespace run, blank line, and UTF-16 surrogate pair is preserved losslessly. If a single line is taller than `heightPx`, it is kept intact on its own page and the renderer allows vertical scrolling.
   - If an answer arrives before the first Compose layout pass completes, `GlassesViewModel.paginateText` applies a conservative character-budget fallback (`MAX_CHARS_PER_PAGE = 120` scaled by viewport area and font ratio) and re-paginates while preserving the user's current reading offset once `TextMeasurer` reports the real bounds.
4. **Device-reported preview metrics (`GlassesDisplayMetrics`):**
   - On connect, the glasses report `widthPx`, `heightPx`, `density`, and `fontScale` via `DISPLAY_METRICS` (`0x33`). The phone Settings preview uses these reported dimensions (or an explicitly labeled `480×640` illustrative canvas before a device has connected).

## Consequences

- Answers paginate accurately on the glasses across any combination of font size, typeface, safe-area geometry, and language without re-requesting text from the phone.
- Because the phone does not execute the glasses' `TextMeasurer`, phone-side page counts (such as `glasses_page_count` in `RoutingMetricEntity`) are estimates (`120` chars/page); any behavior that depends on exact page boundaries (such as holding page 1 during streaming or handing off long answers at page 3) must use `MeasuredTextPaginator` on the glasses.
- The phone preview accurately mirrors the Android application viewport (`widthPx × heightPx`), though physical optical alignment still requires on-head confirmation by the wearer.
