package com.streamarr.server.domain.streaming;

import com.streamarr.transcode.engine.model.QualityVariant;
import com.streamarr.transcode.engine.model.TranscodeDecision;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class StreamSession {

  private final UUID sessionId;
  private final UUID mediaFileId;
  // The owning profile, stamped at creation — playback tokens bind to it.
  private final UUID profileId;
  private final MediaProbe mediaProbe;
  private final TranscodeDecision transcodeDecision;
  private final StreamingOptions options;
  private final Instant createdAt;

  @Builder.Default private final List<QualityVariant> variants = Collections.emptyList();

  @Getter(lombok.AccessLevel.NONE)
  @Builder.Default
  private final AtomicReference<PlaybackSnapshot> playbackSnapshot =
      new AtomicReference<>(new PlaybackSnapshot(0, PlaybackState.STOPPED, Instant.now()));

  public boolean isOwnedBy(UUID candidateProfileId) {
    return profileId != null && profileId.equals(candidateProfileId);
  }

  public void mirrorCommittedPlaybackState(
      int positionSeconds, PlaybackState state, Instant accessedAt) {
    playbackSnapshot.updateAndGet(
        current ->
            new PlaybackSnapshot(
                positionSeconds,
                state,
                accessedAt.isAfter(current.accessedAt()) ? accessedAt : current.accessedAt()));
  }

  public PlaybackSnapshot getPlaybackSnapshot() {
    return playbackSnapshot.get();
  }

  public Instant getLastAccessedAt() {
    return playbackSnapshot.get().accessedAt();
  }

  public void setLastAccessedAt(Instant accessedAt) {
    playbackSnapshot.updateAndGet(
        current -> new PlaybackSnapshot(current.positionSeconds(), current.state(), accessedAt));
  }
}
