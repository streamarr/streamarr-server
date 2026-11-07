package com.streamarr.server.services.streaming.events;

import com.streamarr.server.domain.streaming.SourceEndedEarly;
import java.util.Optional;
import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

/**
 * A worker reports that a job attempt read its source to the end. The worker reports the completion
 * only after the server accepted the attempt's last segment. {@code sourceEndedEarly} is the
 * worker's report that the source ran out before the last advertised media segment.
 */
@Builder
public record JobAttemptCompletedEvent(
    @NonNull UUID streamSessionId,
    @NonNull UUID jobAttemptId,
    @NonNull String variantLabel,
    @NonNull Optional<SourceEndedEarly> sourceEndedEarly) {

  // Lombok skips its own builder() because this one exists.
  public static JobAttemptCompletedEventBuilder builder() {
    return new JobAttemptCompletedEventBuilder().sourceEndedEarly(Optional.empty());
  }
}
