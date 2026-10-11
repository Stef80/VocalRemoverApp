# Playable Tracks Preview Implementation Plan

> **For agentic workers:** Implement the single task below inline; do not create a commit.

**Goal:** Add a standalone Compose preview of processed tracks as a vertical list of rounded cards.

**Architecture:** Create a focused UI composable in `:ui` with a small local preview model/sample list. The component stays disconnected from the launcher and playback while presenting placeholder titles, sample durations, and a decorative play affordance.

**Tech Stack:** Kotlin, Jetpack Compose, Material 3, Android Gradle Plugin.

## Approved Visual Direction: Coral / Midnight Blue

Use the approved calligraphic microphone-and-note logo as the color reference for the
app. The selected palette is coral on midnight blue; the previous yellow direction is
superseded.

| Role | Color | Usage |
|---|---|---|
| Background | `#19263B` | Main app background |
| Surface | `#253A5B` | Cards and panels |
| Primary accent | `#ED615B` | Main buttons, selected states, and progress indicators |
| Text on primary | `#19263B` | Labels on filled coral controls |
| Main text | `#FFE9D6` | Warm ivory titles and readable content |
| Secondary text | `#FFC8B2` | Durations and supporting labels |

Keep coral selective so that important actions stand out; do not use it for every
surface or text element. Preserve readable contrast and distinguish errors from normal
coral accents with explicit labels/icons, not color alone.

For this task, apply the palette to the standalone tracks component and its preview via
Material 3 color roles. This establishes the direction for the future app-wide theme,
but does not change the launcher, legacy screen, or app icon in this task.

### Approved Logo Usage

Both versions use the same approved coral calligraphic microphone-and-note symbol.

| Asset | Intended use |
|---|---|
| `logo-corallo-blu-riquadro.png` | App launcher icon: coral symbol on the midnight-blue rounded tile |
| `logo-corallo-blu-trasparente.png` | In-app branding: symbol without a tile, on screen backgrounds |

The PNG exports are 1024 x 1024. Future launcher integration must adapt the framed
design into Android adaptive-icon foreground/background layers with appropriate safe
margins, rather than nesting the rounded tile inside another launcher mask.
Use the transparent logo selectively in screen headers or branding areas, preserving
its aspect ratio and ensuring contrast with the underlying surface. Do not use it as
a replacement for functional control icons.

This records the approved asset usage; preparing launcher resources and placing logos
on app screens remain outside the standalone tracks-preview task.

## Global Constraints

- This is presentation-only: do not load real files, inspect metadata, or connect playback.
- Do not show album art, artist, album, or file metadata.
- Keep the composable independent from `MainActivity` and expose a `@Preview` with a few sample tracks.
- Do not alter navigation or the launcher screen.
- Never run `git commit` in this repository.

## Follow-up: Approved Introductory Splash

The introductory splash is a separate addition to the tracks-preview task. Show
the transparent coral logo on midnight blue, with `Vocal Remover` in warm ivory and
`KARAOKE` in coral. Display it for 1.5 seconds before the existing Compose home,
without opening the legacy activity automatically or initializing the audio engine.
Save completion across activity recreation so returning from the legacy screen or
rotating after the introduction does not show it again.

- Add `VocalRemoverTheme.kt` to centralize the approved Material 3 color scheme.
- Add `SplashScreen.kt` with the centered logo/text layout and a Compose preview.
- Wire the timed introduction in `MainActivity.kt` using `rememberSaveable` and
  `LaunchedEffect`; keep the existing home button and legacy navigation.
- Apply a launcher-only no-action-bar XML theme with midnight-blue window/system
  backgrounds, plus matching Android 12+ system splash colors and logo. Leave the
  legacy activity's theme unchanged.
- Validate with `gradle :ui:assembleCpuDebug :ui:lintCpuDebug`. On a device, confirm
  the introduction transitions to home, does not reappear on returning from the
  legacy screen, and remains complete after rotation.

---

## File Structure

- Create `ui/src/main/java/com/stef/vocalremover/ui/PlayableTracks.kt` for the track preview model, composable card/list, private sample data, and Compose preview.

### Task 1: Add the standalone playable tracks preview

**Files:**
- Create: `ui/src/main/java/com/stef/vocalremover/ui/PlayableTracks.kt`

**Interfaces:**
- Produces `@Composable fun PlayableTracks(tracks: List<PlayableTrack>, modifier: Modifier = Modifier)`.
- Produces `data class PlayableTrack(val title: String, val duration: String)`.
- Provides `@Preview` populated with sample tracks; does not connect to app screens or player actions.

- [ ] **Step 1: Create the model and list composable**

Define the preview-facing item model and render the supplied tracks in a vertically scrolling `LazyColumn`, with consistent spacing and content padding. Use the existing Material 3 dependency and the approved color roles above; do not add dependencies or modify the Gradle files. Keep color values in the preview's color scheme rather than scattering literals through the cards.

- [ ] **Step 2: Implement each track card**

Render each item in a rounded Material 3 `Card` using the midnight-blue surface, ivory title, secondary duration color, and coral play accent from the approved palette. Show the generic title and duration as text, without artwork or artist/album labels. Include a Material icon play affordance only if an existing Material icon dependency already exposes it; otherwise use a text-based play glyph so no dependency is added. The affordance must have no click handler or playback behavior.

- [ ] **Step 3: Add preview data and Compose preview**

Add three locally defined sample tracks with titles `Brano 1`, `Brano 2`, and `Brano 3`, and plausible durations. Annotate a preview composable with `@Preview(showBackground = true)` and render `PlayableTracks` inside `MaterialTheme` with an explicit dark color scheme mapping the approved palette to `background`, `surface`, `primary`, `onPrimary`, `onBackground`, `onSurface`, and `onSurfaceVariant`. Draw the background with a themed `Surface` so the preview shows the intended midnight-blue canvas.

- [ ] **Step 4: Compile the UI module**

Run from repository root:

```bash
gradle :ui:assembleCpuDebug
```

Expected: the CPU debug UI variant compiles successfully with the new composable.
