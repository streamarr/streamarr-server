package com.streamarr.server.domain.streaming;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import lombok.Builder;
import lombok.Getter;
import lombok.NonNull;

@Getter
@Builder
public class StreamSession {

  private static final String DEFAULT_VARIANT = "default";

  @NonNull private final UUID sessionId;
  @NonNull private final UUID mediaFileId;
  @NonNull private final PlaybackAuthority authority;
  @NonNull private final Path sourcePath;
  private final MediaProbe mediaProbe;
  // Null for a direct-play stream session, which starts no job attempt.
  private final TranscodeDecision transcodeDecision;
  private final StreamingOptions options;
  private final Instant createdAt;
  @Builder.Default private final StreamDelivery delivery = StreamDelivery.HLS;

  /** Where playback of the stream session starts on the media timeline. */
  @Getter(lombok.AccessLevel.NONE)
  private final Duration startPosition;

  /** The source container that a direct-play stream session serves. */
  @Getter(lombok.AccessLevel.NONE)
  private final SourceContainer sourceContainer;

  /**
   * The position, among the file's audio tracks in stream order, of the audio track that a
   * direct-play stream session's player selects. Empty for an HLS stream session and for a file
   * without audio.
   */
  @NonNull @Builder.Default
  private final OptionalInt directPlayAudioTrackPosition = OptionalInt.empty();

  @Getter(lombok.AccessLevel.NONE)
  @Builder.Default
  private final Map<String, TranscodeHandle> variantHandles = new ConcurrentHashMap<>();

  @Getter(lombok.AccessLevel.NONE)
  @Builder.Default
  private final Map<String, WorkerVerifiedEncoder> pinnedEncoders = new ConcurrentHashMap<>();

  @Getter(lombok.AccessLevel.NONE)
  @Builder.Default
  private final Map<String, EncoderPreset> variantEncoderPresets = new ConcurrentHashMap<>();

  @Builder.Default private final List<QualityVariant> variants = Collections.emptyList();

  @Getter(lombok.AccessLevel.NONE)
  @Builder.Default
  private final AtomicInteger currentSegment = new AtomicInteger();

  @Getter(lombok.AccessLevel.NONE)
  @Builder.Default
  private final AtomicReference<PlaybackSnapshot> playbackSnapshot =
      new AtomicReference<>(new PlaybackSnapshot(0, PlaybackState.STOPPED, Instant.now()));

  // Unlike the playback snapshot, which every request touches, it changes only with a report.
  @Getter(lombok.AccessLevel.NONE)
  @Builder.Default
  private final AtomicReference<TimelineReport> latestTimelineReport = new AtomicReference<>();

  @Getter(lombok.AccessLevel.NONE)
  @Builder.Default
  private final AtomicReference<ShortCopy> shortCopy = new AtomicReference<>();

  @Getter(lombok.AccessLevel.NONE)
  @Builder.Default
  private final AtomicBoolean playbackMovedFromTheStartPosition = new AtomicBoolean();

  // Integer.MAX_VALUE until a completed job attempt reports where the source ended.
  @Getter(lombok.AccessLevel.NONE)
  @Builder.Default
  private final AtomicInteger lastSegmentWithSourceMedia = new AtomicInteger(Integer.MAX_VALUE);

  public Optional<SourceContainer> getSourceContainer() {
    return Optional.ofNullable(sourceContainer);
  }

  /** Empty when playback starts at the beginning of the media. */
  public Optional<Duration> getStartPosition() {
    return Optional.ofNullable(startPosition);
  }

  /**
   * The dynamic range of the video that the stream session sends. A direct-play stream session
   * sends the source's video, which keeps its compatible base as a copy does.
   */
  public VideoRange getVideoRange() {
    if (delivery == StreamDelivery.HLS) {
      return transcodeDecision.videoRange();
    }

    return mediaProbe
        .videoPlaybackFacts()
        .map(facts -> VideoRange.ofCompatibleBase(facts.dynamicRange().compatibleBase()))
        .orElse(VideoRange.SDR);
  }

  public boolean isOwnedBy(UUID candidateProfileId) {
    return authority.profileId().equals(candidateProfileId);
  }

  public void updatePlaybackState(int positionSeconds, PlaybackState state) {
    var reportedAt = Instant.now();
    playbackSnapshot.updateAndGet(
        current -> new PlaybackSnapshot(positionSeconds, state, reportedAt));
    latestTimelineReport.set(new TimelineReport(positionSeconds, state, reportedAt));
    var startSecond = getStartPosition().map(Duration::toSeconds).orElse(0L);
    if (positionSeconds != startSecond) {
      playbackMovedFromTheStartPosition.set(true);
    }
  }

  /**
   * Whether playback moved from the start position: the player once reported a position in a whole
   * second other than the start position's. A later report back at that second does not undo the
   * move. A stream session without a start position starts at zero.
   */
  public boolean hasPlaybackMovedFromTheStartPosition() {
    return playbackMovedFromTheStartPosition.get();
  }

  /** Empty until the player reports its timeline. */
  public Optional<TimelineReport> getLatestTimelineReport() {
    return Optional.ofNullable(latestTimelineReport.get());
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

  /** Live view — reflects concurrent handle mutations; not a snapshot. */
  public Map<String, TranscodeHandle> getVariantHandles() {
    return Collections.unmodifiableMap(variantHandles);
  }

  public Optional<TranscodeHandle> getHandle() {
    return getVariantHandle(DEFAULT_VARIANT);
  }

  public void setHandle(TranscodeHandle handle) {
    variantHandles.put(DEFAULT_VARIANT, handle);
  }

  public void setVariantHandle(String variantLabel, TranscodeHandle handle) {
    variantHandles.put(variantLabel, handle);
  }

  public Optional<TranscodeHandle> getVariantHandle(String variantLabel) {
    return Optional.ofNullable(variantHandles.get(variantLabel));
  }

  public void removeVariantHandle(String variantLabel) {
    variantHandles.remove(variantLabel);
  }

  /**
   * Whether the stream session offers this variant: a variant of its ladder, or the default variant
   * when the session has no ladder.
   */
  public boolean offersVariant(String variantLabel) {
    if (variants.isEmpty()) {
      return DEFAULT_VARIANT.equals(variantLabel);
    }

    return variants.stream().anyMatch(variant -> variant.label().equals(variantLabel));
  }

  /**
   * The first media segment of the most recently started job attempt, or the segment at which the
   * stream session starts before any job attempt starts. The current segment of ADR 0019 falls back
   * to it until the player requests a media segment.
   */
  public int getCurrentSegment() {
    return currentSegment.get();
  }

  public void setCurrentSegment(int mediaSegmentIndex) {
    currentSegment.set(mediaSegmentIndex);
  }

  /**
   * Pins the variant to the worker-verified encoder of its first job attempt: the encoder backend
   * and whether it is keyframe-verified. Every later job attempt of the variant must run on that
   * encoder, so a later pin never replaces the first.
   */
  public void pinEncoder(@NonNull String variantLabel, @NonNull WorkerVerifiedEncoder encoder) {
    pinnedEncoders.putIfAbsent(variantLabel, encoder);
  }

  public Optional<WorkerVerifiedEncoder> getPinnedEncoder(String variantLabel) {
    return Optional.ofNullable(pinnedEncoders.get(variantLabel));
  }

  /**
   * The variant's preset: the first call fixes it from {@code firstAttemptPreset}, and every later
   * job attempt of the variant reuses it (ADR 0044).
   */
  public EncoderPreset pinEncoderPreset(
      @NonNull String variantLabel, @NonNull Supplier<EncoderPreset> firstAttemptPreset) {
    return variantEncoderPresets.computeIfAbsent(variantLabel, _ -> firstAttemptPreset.get());
  }

  public boolean isSuspended() {
    return !variantHandles.isEmpty()
        && variantHandles.values().stream().allMatch(h -> h.status() == TranscodeStatus.SUSPENDED);
  }

  public boolean hasActiveTranscodes() {
    return variantHandles.values().stream().anyMatch(h -> h.status() == TranscodeStatus.ACTIVE);
  }

  /**
   * Where the stream session's copy ran short, once a job attempt that read the source shows it.
   */
  public Optional<ShortCopy> getShortCopy() {
    return Optional.ofNullable(shortCopy.get());
  }

  /**
   * Keeps the first short copy, because every later job attempt reads the same source. Answers
   * whether this call kept it.
   */
  public boolean tryMarkShortCopy(@NonNull ShortCopy copyThatRanShort) {
    return shortCopy.compareAndSet(null, copyThatRanShort);
  }

  /**
   * Where playback ends normally: the media end of a copy that ran short after every source stream
   * ended (ADR 0052).
   */
  public Optional<Duration> getNormalEnd() {
    return getShortCopy().filter(ShortCopy::everySourceStreamEnded).map(ShortCopy::mediaEnd);
  }

  /**
   * The last media segment that holds a source sample, once a completed job attempt reports that
   * the source ended before the last advertised segment (ADR 0037, early ends).
   */
  public OptionalInt getLastSegmentWithSourceMedia() {
    var last = lastSegmentWithSourceMedia.get();
    if (last == Integer.MAX_VALUE) {
      return OptionalInt.empty();
    }

    return OptionalInt.of(last);
  }

  /**
   * Keeps the earliest last segment with source media that any job attempt reports, because every
   * job attempt reads the same source.
   */
  public void markLastSegmentWithSourceMedia(int segmentIndex) {
    lastSegmentWithSourceMedia.accumulateAndGet(segmentIndex, Math::min);
  }

  public static String defaultVariant() {
    return DEFAULT_VARIANT;
  }
}
