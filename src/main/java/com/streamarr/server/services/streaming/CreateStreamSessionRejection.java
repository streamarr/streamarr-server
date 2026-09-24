package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.streaming.ProbeError;
import java.util.UUID;
import lombok.NonNull;

public sealed interface CreateStreamSessionRejection {

  record TranscodeCapacityUnavailable(int maximumConcurrent)
      implements CreateStreamSessionRejection {}

  record MediaFileNotFound(@NonNull UUID mediaFileId) implements CreateStreamSessionRejection {}

  record ProbeNotReady() implements CreateStreamSessionRejection {}

  record ProbeFailed(@NonNull ProbeError reason) implements CreateStreamSessionRejection {}

  /** The video must be encoded, and the probe recorded no usable frame rate to encode it at. */
  record FrameRateUnknown() implements CreateStreamSessionRejection {}

  /** The probed duration, missing or under a millisecond, gives the playlist no media segment. */
  record NoMediaSegments() implements CreateStreamSessionRejection {}
}
