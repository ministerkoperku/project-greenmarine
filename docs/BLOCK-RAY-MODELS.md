# Data-driven block ray models

`BlockRayModels.register(id, supports, provider)` lets a client integration describe
blocks whose baked parent model differs from their displayed geometry. The provider
runs on the render thread during budgeted section extraction and returns model
parts with a material BlockState, local translation, uniform scale and a visible
face mask using Direction.ordinal(). Materials retain their own tint, emission and
sunlight transmission. Registration returns a closeable handle.

An unmatched block or a provider returning null uses normal baked geometry. A matching provider returning an
empty list declares an empty shape; it must not be replaced with a fallback cube.
Providers receive the current client level, immutable block position, state and
optional local CompoundTag data. Do not retain the level or mutate world data.

The bundled optional Mechanics Dream adapter reads chunk microgrid attachments
and describes the displayed quarter/half block pieces. It is packaged separately
from the base source set and does not make Mechanics Dream, Fabric API or KoperLib
required dependencies. Section geometry notifications invalidate cached ray meshes
even if the container BlockState remains unchanged.

This API supplies ray geometry, not a replacement raster renderer or a moving-body
adapter. An external renderer must supply the same piece transforms/materials when
it builds its own ray mesh; static world extraction cannot correct a separate
moving-body mesh automatically.
