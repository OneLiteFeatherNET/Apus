# Test fixtures

## mini-world

A minimal Vanilla-layout Minecraft world used by the render integration tests.

- **Origin:** extracted from an internal demo world backup (Minecraft 1.21.10).
- **Contents:** `level.dat` plus `region/*.mca` files. Nothing else.
- **Deliberately excluded:** `playerdata/`, `stats/`, `advancements/` — these contain
  personal data and must never be committed.
- **Layout:** Vanilla (`region/` directly below the world root), so BlueMap resolves
  `minecraft:overworld` without any dimension sub-folder.

Regenerate the original two-region set with the snippet in
`docs/superpowers/plans/2026-08-08-phase-1-render-kern.md`, Task 7, Step 1.

### Region layout

```
            region x=-1        region x=0
region z=0   r.-1.0.mca          r.0.0.mca
region z=1   r.-1.1.mca          r.0.1.mca
```

A 2x2 block of adjacent regions (block coordinates x in [-512, 511], z in [0, 1023]),
forming a single contiguous world. `r.0.0.mca` and `r.0.1.mca` are the original fixture
(one shared edge, `runner/src/test/java/.../RenderEndToEndTest.java` and
`TelemetryContractTest.java` depend on exactly these two, e.g. the hardcoded
`RENDERED_TILE_KEY` tile). `r.-1.0.mca` and `r.-1.1.mca` were added for
`docs/superpowers/spikes/2026-08-09-lowres-sharding-spike.md` — a 512-block-wide column
was needed so a render-mask split at `x=0` gives shards a full-height (1024-block) shared
boundary instead of the single 512-block edge the original two regions gave, without
which the lowres-tile aggregation race under test would have very few tiles in which to
occur. All four files are region files only, taken directly from the backup world's
`region/` directory — no `playerdata/`, `stats/`, or `advancements/`.
