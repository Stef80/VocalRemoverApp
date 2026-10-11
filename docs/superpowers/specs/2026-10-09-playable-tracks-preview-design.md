# Playable Tracks Preview

## Goal

Add a standalone Compose component in `:ui` that previews a vertical list of processed
tracks. This is presentation-only: do not load real files, inspect metadata, or connect
playback yet.

## Design

- Render a vertical list of Material 3 cards with rounded corners, following the visual
  direction of the album-card mockup while adapting it for tracks with no known artwork.
- Each card shows a generic title (`Brano 1`, `Brano 2`, etc.), a sample duration, and a
  play affordance that is visual only.
- Do not show album art, artist, album, or file metadata.
- Keep the composable independent from `MainActivity` and expose a `@Preview` with a few
  sample tracks.

## Scope and validation

This change adds only the reusable UI component and preview sample data. It does not alter
navigation or the launcher screen. Validate by compiling the `:ui` CPU debug variant.
