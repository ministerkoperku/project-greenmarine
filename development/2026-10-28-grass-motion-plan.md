# Grass shadow stability during camera movement

Owner reports occasional square shadow fragments and apparent sideways sliding
while walking on High; stationary rendering looks correct. Ultra is not yet
accepted. Current Prism log confirms High, 2560x1370 capture, 854x457 native rays.

1. Reproduce in a static grass-and-block arena with deterministic camera translation
   and bob phases. Capture High and Ultra, raw visibility, native tap support, and
   isolated GI/shafts controls. Preserve owner instances/worlds.
2. Check that capture, native rays, reconstruction and history use matching bobbed
   camera matrices. Existing primary-ray math tests do not alone accept temporal
   reconstruction. Distinguish native undersampling from history sliding, sparse
   grass producers and workgroup-local GI smoothing.
3. Add a failing regression for the demonstrated cause before a focused fix. Keep
   sharp block boundaries, cutout occlusion, native resource ownership and API layout.
4. Verify actual Vulkan motion plus Java/Python/Rust checks, measure CPU/GPU cost
   separately, then build and export to the previously authorized Prism target with
   byte verification and backup. Do not claim every old cave/FPS issue is fixed.

Additional owner request: add a concise English AI notice to the root README.
