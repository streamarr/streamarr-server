package com.streamarr.server.services.streaming;

import java.util.OptionalInt;
import java.util.OptionalLong;
import lombok.Builder;
import lombok.NonNull;

/**
 * The format of an audio track: the source's as the probe found it, or the output's as the format
 * attempt delivers it. {@code bitrate} is in bits per second. {@code channels} and {@code bitrate}
 * are empty when they are not known.
 */
@Builder
public record AudioFormat(
    @NonNull String codec, @NonNull OptionalInt channels, @NonNull OptionalLong bitrate) {}
