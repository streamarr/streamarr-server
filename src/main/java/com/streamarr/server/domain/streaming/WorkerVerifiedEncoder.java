package com.streamarr.server.domain.streaming;

import lombok.Builder;
import lombok.NonNull;

/**
 * An encoder backend that a worker advertises after its test encode passed. {@code
 * keyframeVerified} is true only when ADR 0037 lists the encoder as keyframe-verified.
 */
@Builder
public record WorkerVerifiedEncoder(@NonNull EncoderBackend backend, boolean keyframeVerified) {}
