package com.streamarr.server.domain.streaming;

import lombok.Builder;
import lombok.NonNull;

/**
 * An encoded variant before it is fitted to a capability entry: the codec profile it forces, the
 * displayed source picture whose aspect ratio it keeps, the largest picture it may take, the
 * source's output frame rate, and the bitrate that the worker caps it at.
 */
@Builder
public record EncodedVariantRequest(
    @NonNull EncodeCodecProfile codecProfile,
    @NonNull PictureSize source,
    @NonNull PictureSize bounds,
    @NonNull Fraction frameRate,
    long bitrate) {}
