package dev.auralite.render.rhi;

import dev.auralite.render.rhi.GfxEnums.ImageType;

public interface GfxImage extends GfxResource {
    GfxDesc.Image desc();
    GfxFormat format();
    int width();
    int height();
    int depth();
    int mipLevels();
    int arrayLayers();
    int samples();
    ImageType type();
}
