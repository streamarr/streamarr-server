package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.streaming.EncodeFormat;
import lombok.NonNull;

/**
 * The encode that a format attempt continues on the software encoder after its hardware encoder
 * failed (ADR 0044): the codec family and the format of the failed encode.
 */
public record SoftwareContinuation(@NonNull String codecFamily, @NonNull EncodeFormat format) {}
