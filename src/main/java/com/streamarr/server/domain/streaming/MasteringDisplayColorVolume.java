package com.streamarr.server.domain.streaming;

import lombok.Builder;
import lombok.NonNull;

/** Container-declared mastering display values; luminances use units of 1/10000 cd/m². */
@Builder
public record MasteringDisplayColorVolume(
    @NonNull ChromaticityCoordinates red,
    @NonNull ChromaticityCoordinates green,
    @NonNull ChromaticityCoordinates blue,
    @NonNull ChromaticityCoordinates whitePoint,
    long maxLuminance,
    long minLuminance) {}
