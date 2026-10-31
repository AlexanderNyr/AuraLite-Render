# patches/ — one `.patch` per closed phase

Each closed phase of the ROADMAP lands here as a replayable mailbox patch
(`git format-patch`). This is the plan's closing ceremony: a phase is done
when its gate is green on the tree the patch reconstructs, not when its code
was written.

| Patch | Phase | Gate (measured on the replayed tree) |
|---|---|---|
| `phase-0-core-null.patch` | Phase 0 — core + NULL | `make test` → **627 passed, 0 failed, 6/6 suites green** (rhi, graph, uniform, biome, nulldev, pack) |
| `phase-1-gl-vulkan-compile-verified.patch` | Phase 1 — backends compile-verified | `make test` → **967 passed, 0 failed, 7/7 suites green** (adds tables: 340 literal checks) |
| `phase-2-docs-and-tools.patch` | Phase 2 — docs + tools | `make test` → **967/0, 7/7**, `make drift-check` → **PASS**, `make packlint PACK=… NULL_DEVICE=1` → **OK** |
| `phase-3-native-reference-pack.patch` | Phase 3 — AuraLite-Native pack | `make test` → **985/0, 7/7** (reference pack: 0 errors, 0 warnings, NULL frame 38 commands / 13 barriers / 40560 work groups / 0 violations), `make drift-check` → **PASS**, `make packlint PACK=packs/AuraLite-Native STRICT=1 NULL_DEVICE=1` → **OK** |

## Replay from nothing

```
git init new-tree && cd new-tree
git am patches/phase-0-core-null.patch \
       patches/phase-1-gl-vulkan-compile-verified.patch \
       patches/phase-2-docs-and-tools.patch \
       patches/phase-3-native-reference-pack.patch
make deps   # fetch LWJGL 3.4.3, the only network access
make test   # must end RESULT: PASS
make drift-check
```

Replayed trees are byte-identical to this repository's tracked files at the
phase they close (verified 2026-10-31: replay of Phases 0+1 clean against
the working tree; replay of 0+1+2 re-measures the full green gate plus the
tool gates). This directory's own bookkeeping (README, the closing lines in
STATUS/ROADMAP that point back here) is repo-side ceremony and is not part
of the patches.

Standing rule 1 applies to these files too: the numbers above are receipts
from `make test` and the tool targets, not estimates.
