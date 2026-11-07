package com.streamarr.server.services.streaming.events;

import com.streamarr.server.domain.streaming.SourceEndedEarly;
import com.streamarr.server.domain.streaming.SourceStreamEnd;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

/**
 * A worker reports that a job attempt read its source to the end. The worker reports the completion
 * only after the server accepted the attempt's last segment. {@code sourceEndedEarly} is the
 * worker's report that the source ran out before the last advertised media segment. {@code
 * streamEnds} lists where the attempt read each chosen stream's last sample.
 */
@Builder
public record JobAttemptCompletedEvent(
    @NonNull UUID streamSessionId,
    @NonNull UUID jobAttemptId,
    @NonNull String variantLabel,
    @NonNull Optional<SourceEndedEarly> sourceEndedEarly,
    @NonNull List<SourceStreamEnd> streamEnds) {

  public JobAttemptCompletedEvent {
    streamEnds = List.copyOf(streamEnds);
  }

  // Lombok skips its own builder() because this one exists.
  public static JobAttemptCompletedEventBuilder builder() {
    return new JobAttemptCompletedEventBuilder()
        .sourceEndedEarly(Optional.empty())
        .streamEnds(List.of());
  }
}
