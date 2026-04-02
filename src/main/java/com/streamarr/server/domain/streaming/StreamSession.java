package com.streamarr.server.domain.streaming;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class StreamSession {

  private static final String DEFAULT_VARIANT = "default";

  private final UUID sessionId;
  private final UUID mediaFileId;
  private final Path sourcePath;
  private final MediaProbe mediaProbe;
  private final TranscodeDecision transcodeDecision;
  private final StreamingOptions options;
  private final Instant createdAt;

  @Builder.Default
  private final Map<String, TranscodeHandle> variantHandles = new ConcurrentHashMap<>();

  @Builder.Default private final List<QualityVariant> variants = Collections.emptyList();

  @Getter(lombok.AccessLevel.NONE)
  @Builder.Default
  private final AtomicReference<PlaybackSnapshot> playbackSnapshot =
      new AtomicReference<>(new PlaybackSnapshot(0, null, Instant.now(), 0));

  public void updatePlaybackState(int positionSeconds, PlaybackState state) {
    playbackSnapshot.updateAndGet(
        current ->
            new PlaybackSnapshot(positionSeconds, state, Instant.now(), current.seekOrigin()));
  }

  public PlaybackSnapshot getPlaybackSnapshot() {
    return playbackSnapshot.get();
  }

  public void seek(int positionSeconds) {
    playbackSnapshot.updateAndGet(
        current ->
            new PlaybackSnapshot(
                positionSeconds, current.state(), current.accessedAt(), positionSeconds));
  }

  public int getSeekOrigin() {
    return playbackSnapshot.get().seekOrigin();
  }

  public Instant getLastAccessedAt() {
    return playbackSnapshot.get().accessedAt();
  }

  public void setLastAccessedAt(Instant accessedAt) {
    playbackSnapshot.updateAndGet(
        current ->
            new PlaybackSnapshot(
                current.positionSeconds(), current.state(), accessedAt, current.seekOrigin()));
  }

  public TranscodeHandle getHandle() {
    return variantHandles.get(DEFAULT_VARIANT);
  }

  public void setHandle(TranscodeHandle handle) {
    variantHandles.put(DEFAULT_VARIANT, handle);
  }

  public void setVariantHandle(String variantLabel, TranscodeHandle handle) {
    variantHandles.put(variantLabel, handle);
  }

  public TranscodeHandle getVariantHandle(String variantLabel) {
    return variantHandles.get(variantLabel);
  }

  public boolean isSuspended() {
    return !variantHandles.isEmpty()
        && variantHandles.values().stream().allMatch(h -> h.status() == TranscodeStatus.SUSPENDED);
  }

  public boolean hasActiveTranscodes() {
    return variantHandles.values().stream().anyMatch(h -> h.status() == TranscodeStatus.ACTIVE);
  }

  public static String defaultVariant() {
    return DEFAULT_VARIANT;
  }
}
