package com.streamarr.server.domain.streaming;

import lombok.Builder;
import lombok.NonNull;

/**
 * One audio capability entry (ADR 0049): an audio codec and the most channels that the player
 * accepts for it. {@code hls} tells whether the entry applies to HLS delivery.
 */
@Builder
public record AudioCapabilityEntry(@NonNull AudioCodec codec, int maxChannels, boolean hls) {}
