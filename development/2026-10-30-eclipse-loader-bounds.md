# Eclipse conversion loader bounds

Measured the editable conversion workspace at
`build/verification/eclipse-conversion-2026-10-29/converted` on 2026-10-30.
The measurements describe this generated workspace, not runtime rendering evidence.

| Tree | Files | Bytes |
| --- | ---: | ---: |
| Complete workspace | 1815 | 70,546,489 |
| Active `assets/` | 1014 | 16,911,228 |
| Preserved `iris-source/` | 757 | 49,489,684 |
| `conversion/` reports | 43 | 4,044,810 |

The workspace has 1844 descendant filesystem entries. The largest active resource
is a 152,000-byte SPIR-V binary. Linked fragment sources are at most 109,701 bytes;
the largest GLSL include in the complete workspace is 146,284 bytes. The original
moon PNG is 19,811,157 bytes, but it is outside `assets/` and must not be loaded
into an active shader resource snapshot merely to preserve an editable original.

The previous 8 MiB external asset snapshot limit rejects the active resource tree.
The previous include loader also checks the size of the combined root/include map
against 80, rejecting supported manifests with many root stages even without many
includes. Resource-manager text reads and individual shader reads remain bounded
at 256 KiB because the measured native shader resources fit that limit. This change
does not raise texture limits or add runtime support for the preserved images.

The revised loader permits 4096 entries per container, 32 MiB of active assets per
container and 128 MiB per scan. This leaves room for the measured sources and their
SPIR-V sidecars while keeping container, aggregate and individual resource bounds.
Directory traversal counts the container root as an entry; ZIP traversal counts
explicit ZIP entries. Preserved files still consume entry slots, but never snapshot
bytes. The include map separately permits 512 distinct include resources with the
existing depth limit of 16 and cycle rejection. Root stage counts remain governed
by the shaderpack schema.

Validation used `./gradlew test --tests '*PackCatalogTest' --tests '*ExternalPacksTest'`.
Six new regressions failed against the old production code for the expected file,
snapshot and include limits; after implementation all 17 focused tests passed.
Fixtures exercise directory and inflated ZIP loading, ignored preserved originals,
4096/4097 ZIP entries, exact 32 MiB and 128 MiB asset budgets plus overflow, 512
declared root stages sharing an include, 512/513 distinct includes, include cycles,
include depth, and preservation of the last catalog after an excessive reload.
The root integration run owns full-suite/build verification. No translated pack
manifest was presented as playable, and no GPU runtime acceptance is claimed here.
