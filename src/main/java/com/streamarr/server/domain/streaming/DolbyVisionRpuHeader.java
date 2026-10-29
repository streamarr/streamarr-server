package com.streamarr.server.domain.streaming;

import lombok.Builder;

/**
 * The fields of the first Dolby Vision RPU header that tell the Dolby Vision profile of a stream
 * whose configuration record names none. {@code vdrBitDepth} is vdr_bit_depth_minus8 + 8.
 */
@Builder
public record DolbyVisionRpuHeader(
    int vdrRpuProfile,
    boolean baseLayerFullRange,
    boolean enhancementLayerSpatialResamplingFilter,
    boolean disableResidual,
    int vdrBitDepth) {}
