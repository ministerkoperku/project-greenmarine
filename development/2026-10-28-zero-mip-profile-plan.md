# Fullpack profile and zero-mip GI follow-up

Owner reports foreign texture patterns on wood at night and approximately400 FPS
without the affected rendering versus150 with dips/poor1% lows. Latest owner game
is Prism with High2560x1370 capture,854x457 native surface rays. Preserve all earlier
uncommitted work and the owner's settings/worlds.

1. Decode the supplied Spark bTQOWFiNNH data and distinguish server CPU samples,
   global allocation/GC, client render submission and actual GPU timings. The
   supplied profile is SERVER/Server thread only; request a client sparkc sample
   while continuing independent work.
2. Confirm the real atlas configuration. Owner log reports4096x4096x0, unlike prior
   scratch fixtures with mip4. Thus explicit LOD4 still clamps to detailed base
   texels despite the previous sampler max-LOD fix. Add a native GPU zero-mip
   regression with camera-varying source UV and detailed reflections.
3. Add a bounded texture-average fallback for diffuse GI when the required coarse
   mip is absent. Avoid relying on owner mipmap settings or adding dependencies.
   Measure native GPU cost separately and inspect a zero-mip runtime fixture.
4. Use the client profile when supplied to locate frame stalls; do not attribute
   whole-process allocation or server tick samples to the renderer without evidence.
   Validate/build relevant changes and update the authorized Prism JAR with backup
   and byte/native verification only after checks.
