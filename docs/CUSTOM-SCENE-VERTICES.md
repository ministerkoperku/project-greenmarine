# Procedural scene vertex programs

Schema 1 scene passes may declare a `vertex` shader resource and an optional
`vertices` count. The vertex source is loaded from
`assets/<namespace>/shaders/<path>.vsh` and executed with the pass fragment shader.

```json
{"name":"custom_quad","vertex":"example:screen_quad",
 "fragment":"example:screen_effect","vertices":6}
```

The source generates its positions from `gl_VertexIndex`; no vertex buffer is
bound by this scene API. Counts must describe complete triangles and be between
3 and 65536. The default is three. A count other than three requires an explicit
vertex program. Compute and lightmap passes cannot select this option.

Vertex and fragment stages share the declared `inputs`, storage resources and
custom `uniforms`. Their compiled uniform layouts are validated before drawing.
Declared vertex sources participate in transactional resource snapshots and
pipeline cache identity. Packs without `vertex` retain the ordinary fullscreen
triangle and `texCoord` varying. The 288-byte MarineFrame remains unchanged.

The automatic Iris converter uses this facility to preserve original fullscreen
vertex code, varying calculations and the original unit-quad vertices. Supported
native execution candidates are generated automatically; requirements that the
runtime cannot supply remain explicit conversion diagnostics.
