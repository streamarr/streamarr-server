package com.streamarr.server.repositories;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.media.LearnedStreamEnd;
import com.streamarr.server.domain.media.SourceFileSnapshot;
import com.streamarr.server.domain.streaming.SourceStreamEnd;
import com.streamarr.server.repositories.media.LearnedStreamEndRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The rules that every {@link LearnedStreamEndRepository} keeps, so the fake that unit tests use
 * behaves as the PostgreSQL repository does.
 */
public interface LearnedStreamEndRepositoryContract {

  SourceFileSnapshot SNAPSHOT =
      new SourceFileSnapshot(4_096, Instant.parse("2026-11-06T09:00:00.123456789Z"));
  SourceFileSnapshot CHANGED_SNAPSHOT =
      new SourceFileSnapshot(8_192, Instant.parse("2026-11-07T09:00:00Z"));

  LearnedStreamEndRepository repository();

  /** Creates a media file the repository can hold learned end times for. */
  UUID createMediaFile();

  /** Stores a probe outcome of the file for the snapshot, in place of any stored before. */
  void storeProbeOutcome(UUID mediaFileId, SourceFileSnapshot snapshot);

  /**
   * Stores a probe outcome of the file for the snapshot that needs a keyframe packet scan, in place
   * of any stored before.
   */
  void storeProbeOutcomeNeedingPacketScan(UUID mediaFileId, SourceFileSnapshot snapshot);

  /**
   * Publishes a keyframe packet scan of the file's stored outcome for the snapshot, with the stream
   * ends that the scan read, and returns whether the scan was stored.
   */
  boolean publishKeyframePacketScan(
      UUID mediaFileId, SourceFileSnapshot snapshot, List<SourceStreamEnd> streamEnds);

  /** Creates a media file whose stored probe outcome holds {@link #SNAPSHOT}. */
  default UUID createProbedMediaFile() {
    var mediaFileId = createMediaFile();
    storeProbeOutcome(mediaFileId, SNAPSHOT);
    return mediaFileId;
  }

  @Test
  @DisplayName(
      "Should read a learned end time when it was learned for the stored probe outcome's snapshot")
  default void shouldReadALearnedEndTimeWhenItWasLearnedForTheSnapshot() {
    var mediaFileId = createProbedMediaFile();
    var learned = learned(mediaFileId, SNAPSHOT, Duration.ofSeconds(84, 21_333_333));

    var stored = repository().store(learned);

    assertThat(stored).isTrue();
    assertThat(repository().findFor(mediaFileId, SNAPSHOT)).containsExactly(learned);
  }

  @Test
  @DisplayName("Should keep the first learned end time when a later attempt learns another one")
  default void shouldKeepTheFirstLearnedEndTimeWhenALaterAttemptLearnsAnotherOne() {
    var mediaFileId = createProbedMediaFile();
    var first = learned(mediaFileId, SNAPSHOT, Duration.ofSeconds(84));
    repository().store(first);

    var stored = repository().store(learned(mediaFileId, SNAPSHOT, Duration.ofSeconds(90)));

    assertThat(stored).isFalse();
    assertThat(repository().findFor(mediaFileId, SNAPSHOT)).containsExactly(first);
  }

  @Test
  @DisplayName("Should replace a learned end time when the file changed since it was learned")
  default void shouldReplaceALearnedEndTimeWhenTheFileChangedSinceItWasLearned() {
    var mediaFileId = createProbedMediaFile();
    repository().store(learned(mediaFileId, SNAPSHOT, Duration.ofSeconds(84)));
    storeProbeOutcome(mediaFileId, CHANGED_SNAPSHOT);
    var relearned = learned(mediaFileId, CHANGED_SNAPSHOT, Duration.ofSeconds(90));

    var stored = repository().store(relearned);

    assertThat(stored).isTrue();
    assertThat(repository().findFor(mediaFileId, CHANGED_SNAPSHOT)).containsExactly(relearned);
    assertThat(repository().findFor(mediaFileId, SNAPSHOT)).isEmpty();
  }

  @Test
  @DisplayName(
      "Should store nothing when the file's stored probe outcome holds another snapshot than the"
          + " learned end time's")
  default void shouldStoreNothingWhenTheStoredProbeOutcomeHoldsAnotherSnapshot() {
    var mediaFileId = createMediaFile();
    storeProbeOutcome(mediaFileId, CHANGED_SNAPSHOT);

    var stored = repository().store(learned(mediaFileId, SNAPSHOT, Duration.ofSeconds(84)));

    assertThat(stored).isFalse();
    assertThat(repository().findFor(mediaFileId, SNAPSHOT)).isEmpty();
  }

  @Test
  @DisplayName(
      "Should keep the changed file's learned end time when an attempt that read the earlier file"
          + " completes late")
  default void shouldKeepTheChangedFilesLearnedEndTimeWhenAnAttemptOfTheEarlierFileCompletesLate() {
    var mediaFileId = createProbedMediaFile();
    storeProbeOutcome(mediaFileId, CHANGED_SNAPSHOT);
    var changedFileEnd = learned(mediaFileId, CHANGED_SNAPSHOT, Duration.ofSeconds(90));
    repository().store(changedFileEnd);

    var stored = repository().store(learned(mediaFileId, SNAPSHOT, Duration.ofSeconds(84)));

    assertThat(stored).isFalse();
    assertThat(repository().findFor(mediaFileId, CHANGED_SNAPSHOT)).containsExactly(changedFileEnd);
  }

  @Test
  @DisplayName("Should read only the file's own learned end times")
  default void shouldReadOnlyTheFilesOwnLearnedEndTimes() {
    var mediaFileId = createProbedMediaFile();
    var otherFileId = createProbedMediaFile();
    var learned = learned(mediaFileId, SNAPSHOT, Duration.ofSeconds(84));
    var otherStream =
        learned(mediaFileId, SNAPSHOT, Duration.ofSeconds(64)).toBuilder().streamIndex(0).build();
    repository().store(learned);
    repository().store(otherStream);
    repository().store(learned(otherFileId, SNAPSHOT, Duration.ofSeconds(12)));

    assertThat(repository().findFor(mediaFileId, SNAPSHOT))
        .containsExactlyInAnyOrder(learned, otherStream);
  }

  @Test
  @DisplayName(
      "Should read each scanned end time when a keyframe packet scan of the stored probe outcome"
          + " publishes it")
  default void shouldReadEachScannedEndTimeWhenAKeyframePacketScanPublishesIt() {
    var mediaFileId = createMediaFile();
    storeProbeOutcomeNeedingPacketScan(mediaFileId, SNAPSHOT);

    var published =
        publishKeyframePacketScan(
            mediaFileId,
            SNAPSHOT,
            List.of(
                new SourceStreamEnd(0, Duration.ofSeconds(14, 166_666_666)),
                new SourceStreamEnd(1, Duration.ofMillis(14_250))));

    assertThat(published).isTrue();
    assertThat(repository().findFor(mediaFileId, SNAPSHOT))
        .containsExactlyInAnyOrder(
            learned(mediaFileId, SNAPSHOT, Duration.ofSeconds(14, 166_666_666)).toBuilder()
                .streamIndex(0)
                .build(),
            learned(mediaFileId, SNAPSHOT, Duration.ofMillis(14_250)));
  }

  // A job attempt learns an end from the last sample that it read. The scan read every packet of
  // the stream, so its end replaces the attempt's.
  @Test
  @DisplayName(
      "Should replace a learned end time when a keyframe packet scan reads the stream's end for the"
          + " same snapshot")
  default void shouldReplaceALearnedEndTimeWhenAKeyframePacketScanReadsTheStreamsEnd() {
    var mediaFileId = createMediaFile();
    storeProbeOutcomeNeedingPacketScan(mediaFileId, SNAPSHOT);
    repository().store(learned(mediaFileId, SNAPSHOT, Duration.ofSeconds(84)));

    publishKeyframePacketScan(
        mediaFileId, SNAPSHOT, List.of(new SourceStreamEnd(1, Duration.ofMillis(14_250))));

    assertThat(repository().findFor(mediaFileId, SNAPSHOT))
        .containsExactly(learned(mediaFileId, SNAPSHOT, Duration.ofMillis(14_250)));
  }

  @Test
  @DisplayName("Should keep a scanned end time when a later attempt learns another one")
  default void shouldKeepAScannedEndTimeWhenALaterAttemptLearnsAnotherOne() {
    var mediaFileId = createMediaFile();
    storeProbeOutcomeNeedingPacketScan(mediaFileId, SNAPSHOT);
    publishKeyframePacketScan(
        mediaFileId, SNAPSHOT, List.of(new SourceStreamEnd(1, Duration.ofMillis(14_250))));

    var stored = repository().store(learned(mediaFileId, SNAPSHOT, Duration.ofSeconds(84)));

    assertThat(stored).isFalse();
    assertThat(repository().findFor(mediaFileId, SNAPSHOT))
        .containsExactly(learned(mediaFileId, SNAPSHOT, Duration.ofMillis(14_250)));
  }

  @Test
  @DisplayName(
      "Should store no scanned end time when the stored probe outcome needs no keyframe packet"
          + " scan")
  default void shouldStoreNoScannedEndTimeWhenTheStoredProbeOutcomeNeedsNoKeyframePacketScan() {
    var mediaFileId = createProbedMediaFile();

    var published =
        publishKeyframePacketScan(
            mediaFileId, SNAPSHOT, List.of(new SourceStreamEnd(1, Duration.ofMillis(14_250))));

    assertThat(published).isFalse();
    assertThat(repository().findFor(mediaFileId, SNAPSHOT)).isEmpty();
  }

  @Test
  @DisplayName(
      "Should store no scanned end time when the scan read another version of the file than the"
          + " stored probe outcome describes")
  default void shouldStoreNoScannedEndTimeWhenTheScanReadAnotherVersionOfTheFile() {
    var mediaFileId = createMediaFile();
    storeProbeOutcomeNeedingPacketScan(mediaFileId, CHANGED_SNAPSHOT);

    var published =
        publishKeyframePacketScan(
            mediaFileId, SNAPSHOT, List.of(new SourceStreamEnd(1, Duration.ofMillis(14_250))));

    assertThat(published).isFalse();
    assertThat(repository().findFor(mediaFileId, SNAPSHOT)).isEmpty();
    assertThat(repository().findFor(mediaFileId, CHANGED_SNAPSHOT)).isEmpty();
  }

  private static LearnedStreamEnd learned(
      UUID mediaFileId, SourceFileSnapshot snapshot, Duration endTime) {
    return LearnedStreamEnd.builder()
        .mediaFileId(mediaFileId)
        .snapshot(snapshot)
        .streamIndex(1)
        .endTime(endTime)
        .build();
  }
}
