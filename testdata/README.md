# Test fixtures

## mini-world

A minimal Vanilla-layout Minecraft world used by the render integration tests.

- **Origin:** extracted from an internal demo world backup (Minecraft 1.21.10).
- **Contents:** `level.dat` plus one or two `region/*.mca` files. Nothing else.
- **Deliberately excluded:** `playerdata/`, `stats/`, `advancements/` — these contain
  personal data and must never be committed.
- **Layout:** Vanilla (`region/` directly below the world root), so BlueMap resolves
  `minecraft:overworld` without any dimension sub-folder.

Regenerate with the snippet in
`docs/superpowers/plans/2026-08-08-phase-1-render-kern.md`, Task 7, Step 1.
