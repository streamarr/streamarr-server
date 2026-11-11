package com.streamarr.server.domain.streaming;

import lombok.NonNull;

/**
 * The encode that every lower variant beside a copy runs in Auto (ADR 0050): the codec profile that
 * it forces, and the range and bit depth that it writes.
 */
public record LowerVariantEncode(
    @NonNull EncodeCodecProfile codecProfile, @NonNull EncodeFormat format) {}
