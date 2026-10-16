package com.koper.greenmarine.api.render;

import com.mojang.renderpearl.api.textures.*;

public final class TextureHandle extends OwnedResource {
    private final GpuTexture texture;
    private final GpuTextureView view;
    TextureHandle(ResourceScope owner, GpuTexture texture, GpuTextureView view) { super(owner); this.texture = texture; this.view = view; }
    public GpuTextureView view() { check(); return view; }
    public GpuTexture texture() { check(); return texture; }
    @Override protected void destroy() { try { view.close(); } finally { texture.close(); } }
}
