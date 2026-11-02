package com.streamarr.server.services.streaming;

import java.util.Optional;
import lombok.NonNull;

/**
 * Why a playback session's current format attempt failed, and the codec family of its video encode.
 * The codec family is empty when the attempt copied the video.
 */
record FailedFormatAttempt(
    @NonNull PlaybackSessionFailure reason, @NonNull Optional<String> encodeCodecFamily) {

  boolean copiedVideo() {
    return encodeCodecFamily.isEmpty();
  }
}
