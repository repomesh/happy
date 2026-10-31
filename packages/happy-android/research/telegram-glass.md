# Telegram's glass vs ours

Source: Telegram Android 13.1.1, commit `3de37c8cfb039452be7398909461da6288babf15`
(the same pin as `ui/NavMotion.kt`). The files are under `TMessagesProj/src/main/java/org/telegram/`:

- `ui/Components/blur3/DownscaleScrollableNoiseSuppressor.java`: the capture, downscale and blur
- `ui/Components/blur3/source/BlurredBackgroundSourceRenderNode.java` and
  `drawable/BlurredBackgroundDrawableRenderNode.java`: drawing the blur under a surface
- `ui/Components/blur3/drawable/color/impl/BlurredBackgroundProviderImpl.java` and
  `BlurredBackgroundColorProviderThemed.java`: tint, stroke and shadow for each surface
- `ui/Components/blur3/BlurredBackgroundWithFadeDrawable.java`,
  `chat/layouts/ChatActivityFadeView.java` and `DialogsActivityTopBubblesFadeView.java`: the fades
- `ui/DialogsActivity.java` (`drawBlurRect`, `blur3_InvalidateBlur`) and `ui/ChatActivity.java`
  (constructor, `ACTION_BAR_BLUR_ALPHA`, `actionBar.setupGlass`)
- `ui/Components/SizeNotifierFrameLayout.java`: the old blur path (`drawBlurRect`, `DOWN_SCALE`),
  still used below API 31
- `messenger/LiteMode.java` and `SharedConfig.chatBlurEnabled()`: the switches

## How Telegram's works (API 31+, "blur3")

**What is captured.** Telegram never blurs the whole window. Each screen collects the rects
of its visible glass surfaces, merges them, and rounds them out to 16 px
(`SourcePart.setPosition`). Only the content inside those rects is re-recorded into
RenderNodes (`IBlur3Capture.capture`). `DialogsActivity` adds 48 dp of list above and
below the bars. In a chat, the source is the wallpaper (`navbarContentSourceWallpaper`)
with the message list drawn into the noise suppressor's rects.

**Downscale and blur.** This is the frosted mode, the default whenever liquid glass is off
(`simpleMode`, `allowNoiseSuppress = false`):

| | Telegram |
|---|---|
| capture scale | 1/8 (`k = 8`): the rect is recorded at an eighth of its size |
| blur | `RenderEffect.createBlurEffect` with a full-size radius of **40 dp**, converted for the 1/8 copy through sigma: `downscaleRadius(r, s) = max(1, toRadius(toSigma(r) / s))`, where `toSigma(r) = 0.57735 r + 0.5` (hwui's own conversion) |
| tile mode | `CLAMP` |
| colour | chained `ColorMatrix.setSaturation(3f)`, so saturation ×3 |
| upscale | the RenderNode drawn with scale 8 and pivot 0,0, filtered bilinearly by the GPU |

With liquid glass on (`LiteMode.FLAG_LIQUID_GLASS`, off by default and turned off for
everyone in the `lite_mode6` migration), there is a second 1/4 pass with a 6 dp blur and
×3 saturation under an AGSL refraction shader (`liquid_glass_shader.agsl`). This file does
not cover it.

**Tint over the blur.** This is the theme's own background colour, not a grey:

| surface | light | dark |
|---|---|---|
| chat-list action bar (`DialogsActivity.drawBlurRect`), `windowBackgroundWhite` | alpha 216/255 = 0.847 | alpha 178/255 = 0.698 (`ChatActivity.ACTION_BAR_BLUR_ALPHA`) |
| chat input and side buttons (`blurredBackgroundColorProvider`), `chat_messagePanelBackground` | 216/255 | 0.76 |
| chat header islands (`topPanelChatActivity`), `chat_topPanelBackground` | 0.76 | 0.76 |
| with blur off (`checkBlurEnabled` false) | 255 (opaque) | 255 |

**Edges.** A full-width bar has none: `actionBar.setCastShadows(false)`, with no stroke.
Floating glass (the rounded header islands and the input) gets a stroke drawn as two half
round-rects: a top half and a bottom half.

| | light | dark |
|---|---|---|
| top stroke | `#FFFFFFFF`, 0.55 dp | `#20FFFFFF`, 0.55 dp |
| bottom stroke | `#FFFFFFFF`, 0.55 dp | `#14FFFFFF`, 0.55 dp |
| shadow | `#20000000`, radius 1 dp, dy 1/3 dp | none |

**Fades.** Content does not stop at a hard line under a bar:

- In a chat, `ChatActivityFadeView` draws the blurred source under the top and bottom
  zones, masked `DST_IN` with a 48 dp gradient of alpha `0, 0x60, 0xB0, 0xE8` of 285
  (0, 0.34, 0.62, 0.81). The mask holds at 0.81 past the ramp. The bottom zone is the
  input zone itself (`setFadeZoneBottom(inputHeight)`, `checkUi_BlurHeight`), and the list's
  bottom padding ends at its top, so the ramp lies inside the dock and never over a resting
  message.
- On the chat list, `DialogsActivityTopBubblesFadeView` is a plain colour gradient
  (`E8, C0, 90, 00` of the background) at most 40 dp tall, under the filter tabs.

**Updates.** Every `dispatchDraw` calls `blur3_InvalidateBlur()`, but each rect is
re-recorded only when a hash of what it covers changes (`Blur3HashImpl`: positions,
colours, the list's draw hash), and the merged result node only when a part changed. While
scrolling, the work is per frame, limited to the rects under glass. When idle, it is free.
Scroll offsets modulo the scale (`onScrolled`) are for the noise-suppressing mode, which is
off by default.

**Noise and dither.** There is none. The 1/8 copy with a 40 dp blur is smooth enough.

**Fallbacks.**

- **API 31+ with blur allowed:** `chatBlurEnabled()` requires performance class AVERAGE or
  better and `LiteMode.FLAG_CHAT_BLUR` (on by default). Telegram uses blur3 as described above.
- **API 29–30, or a HIGH-class device on the old path:** `SizeNotifierFrameLayout.drawBlurRect`
  uses a RenderNode blur (`getBlurRadius()` = 60 on HIGH, ×2 saturation, `TileMode.DECAL`,
  `getRenderNodeScale()` = density), or a bitmap blur on a 1/12 copy (`DOWN_SCALE = 12`)
  in a background thread with alpha from `key_chat_BlurAlphaSlow`.
- **Blur off or LOW class:** the bar is the opaque theme colour.

**Cost.** On a 2.6× phone, a 411 dp wide bar of about 100 dp (status bar included) is
1069 × 260 px. Its 1/8 copy is about 134 × 33 px, and the 40 dp radius (104 px, a sigma of
about 60 px) becomes a sigma of about 7.6 px on it. Cheap. The cost is the re-recording on
scroll, which the hashes keep to dirty rects.

**The ground under the blur.** Every frosted source starts with an opaque colour: the chat
list's `renderNodeUpdateDisplayList` does `drawColor(windowBackgroundWhite)` before the
blurred parts, and the chat's source has the wallpaper as its `underSource`
(`BlurredBackgroundSourceRenderNode.draw` draws `underSource` first). What the tint lies on
is therefore opaque: the window's colour with the blurred content over it. The sharp content
under the bar never shows through.

## Why there was no blur on the device (fixed 2026-10-31)

The first port (below) took Telegram's numbers but left out that opaque ground. Our content
layer (`glassSource`) records the content without the screen's background, which is painted
by a modifier outside it (`Modifier.background(colors.surface)` on the screen). So the
blurred copy was mostly transparent: thin text blurred at a 61 px sigma fades to almost
nothing. The bar then drew a 70–85% tint over that, and below the bar the real, sharp
content still showed through the remaining 15–30%. On the Fold, in dark mode, the
transcript read clearly through the agent chat's title ("sharp, slightly dimmed"). It
looked like no blur at all because the only thing visible under the tint was the
unblurred original. Robolectric doesn't draw RenderEffect, so the tests never saw it.

The fix is Telegram's structure: `GlassSource` carries the screen's `ground`
(`rememberGlassSource(ground)`; `GlassBarLayout` passes its `ground`, the agent chat its
`colors.surface`). Each glass draws that ground, then the blurred content, then the tint
(`drawSource`, Telegram's `BlurredBackgroundSourceRenderNode.draw` with `underSource`).
Each glass also blurs 48 dp of content around itself, not just its own rect, kept to the
source's bounds (`GlassSpec.capture`, Telegram's `rectf.inset(0, -dp(45..48))`), so
content near the bar glows into it before it slides under, instead of the blur clamping
at the bar's edge.

Checked on the Galaxy Z Fold7 inner display (1968×2184, 420 dpi), light and dark, on agent
home, an agent chat, legacy home and its chat, and settings. Screenshots in
`.context/shots/device/` (not committed): `before-agentchat-dark.png` shows sharp text
under the title; `after-*` show it frosted.

## Why the blur shimmered while scrolling (fixed 2026-10-31)

Steve, on the Fold: "The blur works but it's shaky… white text on black coming into the
frame is very unstable."

**How it was measured.** `adb shell input motionevent` held a finger on the agent chat and
moved it 1 px at a time, 40 steps, with a `screencap` of the inner display after each one
(dark mode, an idle chat of white text). The content under the bar moved exactly 1 px per
step. For each band of the bar, D is the mean |frame − previous frame| in 8-bit luma.
Under a stable blur, D is small and about the same every step. An unstable one sits still
and then jumps, so its D alternates between 0 and a spike. Scripts are in `.context/blur/`
(`step.sh`, `jitter.py`, not committed).

**Cause: the copy was drawn small, not shrunk.** `recordBlurOf` drew the content layer
straight into the 1/8 layer under `scale(1/8)`, as Telegram's `beginRecordingRect` does
(`c.scale(1f / k, 1f / k)`). hwui rasterises the text again at that scale, so each glyph
lands on the small copy's 8 px grid. Scroll 1 px and nothing changes; scroll a few more and
a glyph's whole column of small pixels flips. After the 40 dp blur, that is a soft blob
that sits still for several frames and then jumps. Bright thin glyphs on black are the worst
case. The same run with the copy at 1/4, 1/2 and 1/1 put the steps at the small copy's
pixel size and not elsewhere. Decal versus clamp tiling and the 48 dp capture margin made no
difference (both measured). Saturation ×3 doesn't touch grey text.

**Why Telegram gets away with it.** Its capture is the same, and so are its offsets: the
scroll-modulo correction in `DownscaledRenderNode.onScrolled` runs only with
`allowNoiseSuppress`, which nothing turns on. Its chats are bubbles on a wallpaper,
though. Under the input and header the 1/8 copy sees large antialiased rounded rects whose
edges move smoothly at any scale, and the text inside is a small part of the picture. Our
transcript is bare text on the page colour, so the text is all the glass sees.

**Fix.** The glass now draws the content at full size into a layer of its own, softened by
a blur of about half a small pixel (`GlassSpec.prefilterRadius(8)`: sigma 4 px), and
shrinks that (`GlassLayers.record`). Text is rasterised at full size, the prefilter takes
out what the 1/8 grid can't hold, and the result moves with the content pixel by pixel. The
capture rect is rounded to whole pixels so the full-size layer lies on the screen's grid.
The 40 dp blur, ×3 saturation, tints and alphas are all unchanged, so the bar looks the same
at rest. The cost is one more layer, the size of the bar plus its 48 dp margin, with a small
blur, per glass.

| per 1 px step, dark | before: D mean / max / CV | after: D mean / max / CV |
|---|---|---|
| bar, status-bar band (y 0–90) | 0.21 / 2.68 / 2.27; 24% of pixels jump by > 4 at once | 0.12 / 0.51 / 0.80; none |
| bar, title band (90–180) | 0.23 / 1.21 / 1.47 | 0.07 / 0.14 / 0.41 |
| bar, bottom band (180–255) | 0.17 / 1.00 / 1.45 | 0.05 / 0.09 / 0.35 |

The worst single frame is 8–11× smaller in every band, and the coefficient of variation
fell from around 1.5 (still, then jump) to around 0.4 (an even drift).

## The dock's "shadow" (fixed 2026-10-31)

Steve: "the bottom part of the content is under a shadow and I can't scroll it up." The
agent chat's fade box was the dock plus 48 dp, so it ramped to 0.81 tint and blur over the
48 dp above the composer. The list keeps only the dock plus 8 dp clear, so the newest
message always rested under the ramp. Telegram's fade zone is the input zone itself (see
"Fades"). The fade box is now the dock's own height and starts at its top
(`ConversationScreen`). `ConversationComposerGlassTest` checks that the newest row ends
above the fade at rest, with the card open, and with a 20-line draft.

## Ours before this change (`ui/components/Glass.kt`)

| | ours (before) | Telegram |
|---|---|---|
| capture | the whole scrolling content into one `GraphicsLayer` (`glassSource`); each glass redraws only the part under it | only the rects under glass |
| downscale | ×4 | ×8 |
| blur radius | 24 dp, divided straight by the scale (6 dp on the copy) | 40 dp, converted through sigma (≈ 4.3 dp on the copy) |
| saturation | ×1.4 | ×3 |
| bar tint | page colour at 0.72 in both themes | page colour at 0.847 light, 0.698 dark |
| floating tint | grey `#F2F2F4` / `#2C2C2E` at 0.85 | panel colour at 0.847 light, 0.76 dark |
| fallback | tint at 0.94 | opaque |
| hairline | 0.75 dp, black 9% light / white 10% dark, all round | 0.55 dp, white top and bottom (light, with a 1 dp shadow) / white 12.5% and 8% (dark) |
| fade over a dock | 32 dp: 0, 0.38, 0.7, 1 | 48 dp: 0, 0.34, 0.62, 0.81 |
| updates | the blurred layer is re-recorded every frame a glass draws (scroll or not, when invalidated) | hash-gated per rect |
| noise | none | none |
| below API 31 | tint alone | old blur path, then opaque |

## What changed to match

- `GlassSpec.DOWNSCALE` is now 8. `blur` is 40 dp. The radius on the small copy is
  `GlassSpec.downscaledRadius`, Telegram's sigma conversion.
- `SATURATION` is now 3.
- Bars (`glassBar`) use the page colour at 216/255 on a light ground and 178/255 on a dark
  one. The ground is classed as light or dark by its luminance, as Telegram's
  `isDark()` does. There is no edge, as Telegram's bar has none.
- Floating glass (the composer) uses 216/255 in light. Dark stays at **0.85, not
  Telegram's 0.76**: at 0.76 the composer's placeholder falls under 4.5:1 over a white
  picture (`ConversationComposerGlassTest`).
- The hairline is 0.55 dp, and dark's is white at `0x20`.
- The dock fade is 48 dp, with Telegram's 0, 0x60, 0xB0, 0xE8 of 285 stops. **Below the
  ramp it goes opaque instead of holding 0.81.** Our dock is narrower than the window on
  a tablet, so a 19% see-through band would show sharp content beside it.
- The bar's blur is clipped to the bar (`clipRect`), so the clamped edge of the blur can't
  spill past it.

## What stayed different, and why

- **One recorded layer, not dirty rects.** Compose's `GraphicsLayer` is already a
  RenderNode, and drawing it again inside another layer stays live. Scrolling therefore
  costs one small blur per glass surface per frame, with no re-recording of the content.
  Telegram's hash gating exists because a View-based capture has to re-record. Ours
  doesn't, so there is nothing to gate.
- **The floating tint stays our grey.** Telegram tints with its panel colour, which in light
  is white, and separates the white pill with a white stroke and a 1 dp shadow. On our white
  page that would make the composer vanish at rest. The grey tint and the dark hairline do
  that job instead.
- **No liquid-glass refraction pass.** It is off by default in Telegram too.
- **No old-path blur below API 31.** Telegram's bitmap path runs a CPU blur on a thread.
  Ours shows the tint at 0.94 over the ground instead, which is opaque, as Telegram's is with blur off.

## Screenshots

Robolectric doesn't draw RenderEffect blur, so judge the glass on a device. The first
port was checked only in Robolectric and shipped without visible blur (see "Why there was
no blur on the device"). The device screenshots are in `.context/shots/device/` (not
committed).
