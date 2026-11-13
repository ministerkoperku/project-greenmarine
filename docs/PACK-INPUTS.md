# Pack constants and custom expressions

`PackInputs` is the standalone CPU model for a pack's `uniformInputs`
configuration. It contains constants, ordered custom-expression declarations and
the pack's `sunPathRotation` in degrees. It has no Minecraft or GPU dependency.
The client runtime owns host capture, evaluation state and uniform upload.

```json
{
  "constants": {
    "SEA_LEVEL": 62,
    "gain": 1.25,
    "enabled": true,
    "weights": [0.25, 0.5, 1.0]
  },
  "expressions": [
    {
      "kind": "variable",
      "type": "float",
      "name": "relativeAltitude",
      "expression": "eyeAltitude - SEA_LEVEL"
    },
    {
      "kind": "uniform",
      "type": "float",
      "name": "heightFactor",
      "expression": "clamp(relativeAltitude / 128, 0, 1) * gain"
    }
  ],
  "sunPathRotation": -35
}
```

`PackInputs.parse(JsonObject)` parses the object shown above. A missing optional
object (`null` Java reference) is `PackInputs.NONE`. All three fields are optional
and default to empty constants, empty expressions and zero rotation. Explicit
JSON `null` fields are errors. The parser rejects unknown fields, misspellings
and incorrect JSON types; it never converts strings into numbers or booleans.

Every expression requires exactly `kind`, `type`, `name` and `expression`.
`kind` is `variable` or `uniform`; `type` is `bool`, `int`, `float`, `vec2`,
`vec3` or `vec4`. These spellings are case sensitive. Declaration order is
preserved, and the compiled expression program determines dependency order.
Unknown functions, duplicate names, invalid syntax and dependency cycles fail
when constructing the model. Unknown **host inputs** remain explicit program
requirements and must be supplied by the runtime; the model supplies no fallback.

Constants use GLSL identifier keys. Dotted keys, the `gl_` prefix and the
expression literals `true`, `false` and `pi` are reserved. Constant names cannot
collide with expression declaration names. A vector or matrix can be stored
under its root name and accessed through expression components; matrices use
the expression engine's column-major convention.

Constant values are scalar booleans or finite numbers, or flat nonempty lists
of those scalar types. The Java constructor also accepts flat numeric/boolean
primitive arrays. Nested lists/arrays, objects, strings, `null`, character arrays
and object arrays are errors. Mixed scalar lists are retained as declared;
the consuming expression or GLSL layout validates the required component types.

Limits apply to both JSON parsing and the Java constructor:

| Item | Limit |
| --- | --- |
| Constant keys | 256 |
| Components in one list/array | 1–16384 |
| Total scalar components across constants | 65536 |
| Expression declarations | `UniformExpressions.Limits.DEFAULT` (1024) |
| Sun path rotation | Finite Java `float` |

JSON numbers remain `BigDecimal`, retaining fractional and unsigned boundaries
until the consuming layout checks them. For example, `4294967295` can pack into
a `uint`; `4294967295.0000000000000000001` fails integer validation even though
conversion to `double` would lose the fraction. Accepted Java numeric classes
are `Byte`, `Short`, `Integer`, `Long`, `Float`, `Double`, `BigInteger` and
`BigDecimal`. Other `Number` implementations, including mutable atomic numbers,
are rejected. Numbers must have a finite double representation; precise tiny
decimals are preserved for the layout's underflow checks.

The public constructor is
`PackInputs(Map<String,?> constants, List<Declaration> expressions, float sunPathRotation)`.
`constants()` returns an immutable map with fresh copies of primitive arrays on
every call. Lists and the declaration list are immutable snapshots. Input maps,
lists and arrays can be changed after construction without changing the model.
`program()` returns the same cached immutable `UniformExpressions.Program`.
Evaluation uses a renderer-owned `UniformExpressions.State`; it must not be
stored in or shared through this configuration object.

These declarations establish CPU configuration and validation. They do not
establish rendering support for missing shadow, distant-renderer, material or
per-draw inputs, and do not make a converted shaderpack runnable by themselves.
