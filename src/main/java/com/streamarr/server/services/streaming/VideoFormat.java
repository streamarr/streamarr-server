package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.streaming.VideoRange;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import lombok.Builder;
import lombok.NonNull;

/**
 * The format of a video track: the source's as the probe found it, or the output's as the format
 * attempt delivers it. {@code bitrate} is in bits per second, and empty when it is not known.
 * {@code codec} is {@value #UNKNOWN_CODEC} for a source whose probe named no codec.
 */
@Builder(toBuilder = true)
public record VideoFormat(
    @NonNull String codec,
    int width,
    int height,
    @NonNull OptionalDouble frameRate,
    @NonNull OptionalLong bitrate,
    @NonNull VideoRange videoRange,
    boolean dolbyVision) {

  public static final String UNKNOWN_CODEC = "unknown";
}
