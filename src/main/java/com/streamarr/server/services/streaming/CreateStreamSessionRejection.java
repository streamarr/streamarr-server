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
}
