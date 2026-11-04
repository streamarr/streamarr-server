package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.media.MediaFileTitle;
import com.streamarr.server.domain.streaming.PlaybackSnapshot;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

/**
 * One playback session that plays or prepares a stream now, as a ServerAdmin sees it (#751). {@code
 * deviceName} is the name that the viewer's sign-in gave its device, empty when it gave none.
 * {@code title} is empty for a media file without a matched movie or episode. {@code timeline} and
 * {@code decision} are empty while the current format attempt prepares. {@code workers} names the
 * worker connection that runs each running job.
 */
@Builder
public record ActiveStream(
    @NonNull UUID playbackSessionId,
    @NonNull UUID profileId,
    @NonNull Optional<String> profileName,
    @NonNull Optional<String> deviceName,
    @NonNull UUID mediaFileId,
    @NonNull Optional<MediaFileTitle> title,
    @NonNull Optional<PlaybackSnapshot> timeline,
    @NonNull Optional<PlaybackDecision> decision,
    @NonNull List<String> workers) {

  public ActiveStream {
    workers = List.copyOf(workers);
  }
}
