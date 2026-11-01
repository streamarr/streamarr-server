package com.streamarr.server.repositories;

import static com.streamarr.server.fixtures.StreamMeasurementFixture.measured;
import static com.streamarr.server.fixtures.StreamMeasurementFixture.measurement;
import static com.streamarr.server.fixtures.StreamMeasurementFixture.positions;
import static com.streamarr.server.fixtures.StreamMeasurementFixture.success;
import static com.streamarr.server.fixtures.StreamMeasurementFixture.successBuilder;
import static com.streamarr.server.fixtures.StreamMeasurementFixture.unmeasured;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.media.SourceFileSnapshot;
import com.streamarr.server.domain.streaming.KeyframeKnowledge;
import com.streamarr.server.domain.streaming.KeyframePacketScanReason;
import com.streamarr.server.domain.streaming.KeyframePositionSource;
import com.streamarr.server.domain.streaming.ProbeOutcome;
import com.streamarr.server.domain.streaming.StreamMeasurementSource;
import com.streamarr.server.domain.streaming.StreamMeasurements;
import com.streamarr.server.domain.task.ProbeInputs;
import com.streamarr.server.domain.task.ProbePublication;
import com.streamarr.server.fixtures.StreamMeasurementFixture;
import com.streamarr.server.repositories.media.MediaFileContainerInfoRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The stream measurement rules that every {@link MediaFileContainerInfoRepository} keeps (ADR
 * 0054), so the fake that unit tests use behaves as the PostgreSQL repository does.
 */
public interface StreamMeasurementRepositoryContract {

  SourceFileSnapshot MEASURED_SNAPSHOT =
      new SourceFileSnapshot(1234, Instant.parse("2026-10-30T10:00:00Z"));
  SourceFileSnapshot CHANGED_SNAPSHOT =
      new SourceFileSnapshot(5678, Instant.parse("2026-10-31T10:00:00Z"));

  MediaFileContainerInfoRepository repository();

  /** Creates a media file the repository can hold probe results for. */
  UUID createMediaFile();

  @Test
  @DisplayName("Should read each stream's measurement when a probe publishes a measurement")
  default void shouldReadEachStreamsMeasurementWhenProbePublishesMeasurement() {
    var mediaFileId = createMediaFile();

    repository().publish(publication(mediaFileId, 1, measured(positions(90_000))));

    assertThat(repository().findStreamMeasurements(mediaFileId)).contains(measurement());
  }

  @Test
  @DisplayName(
      "Should keep the measurement when a new probe version reports the same keyframe positions"
          + " without one")
  default void shouldKeepMeasurementWhenNewProbeVersionReportsSameKeyframePositionsWithoutOne() {
    var mediaFileId = createMediaFile();
    repository().publish(publication(mediaFileId, 1, measured(positions(90_000))));

    repository().publish(publication(mediaFileId, 2, unmeasured(positions(90_000))));

    assertThat(repository().findStreamMeasurements(mediaFileId)).contains(measurement());
  }

  @Test
  @DisplayName(
      "Should read no measurement when a new probe outcome stores other keyframe positions")
  default void shouldReadNoMeasurementWhenNewProbeOutcomeStoresOtherKeyframePositions() {
    var mediaFileId = createMediaFile();
    repository().publish(publication(mediaFileId, 1, measured(positions(90_000))));

    repository().publish(publication(mediaFileId, 2, unmeasured(positions(45_000))));

    assertThat(repository().findStreamMeasurements(mediaFileId)).isEmpty();
  }

  @Test
  @DisplayName(
      "Should read no measurement when the source changes, even once the old snapshot returns")
  default void shouldReadNoMeasurementWhenSourceChangesEvenOnceOldSnapshotReturns() {
    var mediaFileId = createMediaFile();
    repository().publish(publication(mediaFileId, 1, measured(positions(90_000))));

    repository().trySaveProbeRequest(mediaFileId, new ProbeInputs(CHANGED_SNAPSHOT, 1));
    repository().trySaveProbeRequest(mediaFileId, new ProbeInputs(MEASURED_SNAPSHOT, 1));
    repository().publish(publication(mediaFileId, 1, unmeasured(positions(90_000))));

    assertThat(repository().findStreamMeasurements(mediaFileId)).isEmpty();
  }

  @Test
  @DisplayName(
      "Should read no measurement when a publication of another snapshot replaced it, even once"
          + " the old snapshot returns")
  default void
      shouldReadNoMeasurementWhenPublicationOfAnotherSnapshotReplacedItEvenOnceItReturns() {
    var mediaFileId = createMediaFile();
    repository().publish(publication(mediaFileId, 1, measured(positions(90_000))));

    repository()
        .publish(
            ProbePublication.builder()
                .mediaFileId(mediaFileId)
                .snapshot(CHANGED_SNAPSHOT)
                .probeVersion(1)
                .outcome(unmeasured(positions(90_000)))
                .build());
    repository().publish(publication(mediaFileId, 1, unmeasured(positions(90_000))));

    assertThat(repository().findStreamMeasurements(mediaFileId)).isEmpty();
  }

  @Test
  @DisplayName(
      "Should store the outcome without its measurement when the outcome has no keyframe"
          + " positions")
  default void shouldStoreOutcomeWithoutItsMeasurementWhenOutcomeHasNoKeyframePositions() {
    var mediaFileId = createMediaFile();
    var withoutPositions =
        successBuilder()
            .keyframes(
                new KeyframeKnowledge.PacketScanRequired(
                    KeyframePacketScanReason.NO_CONTAINER_INDEX))
            .streamMeasurements(Optional.of(measurement()))
            .build();

    var published = repository().publish(publication(mediaFileId, 1, withoutPositions));

    assertThat(published).isTrue();
    assertThat(repository().findStreamMeasurements(mediaFileId)).isEmpty();
  }

  @Test
  @DisplayName("Should replace the measurement when a later probe publishes a new measurement")
  default void shouldReplaceMeasurementWhenLaterProbePublishesNewMeasurement() {
    var mediaFileId = createMediaFile();
    repository().publish(publication(mediaFileId, 1, measured(positions(90_000))));
    var videoOnly =
        new StreamMeasurements(
            StreamMeasurementSource.KEYFRAME_PACKET_SCAN,
            List.of(StreamMeasurementFixture.video()));

    repository()
        .publish(publication(mediaFileId, 2, success(positions(90_000), Optional.of(videoOnly))));

    assertThat(repository().findStreamMeasurements(mediaFileId)).contains(videoOnly);
  }

  @Test
  @DisplayName(
      "Should keep the measurement when a keyframe packet scan source reports the same keyframe"
          + " positions")
  default void shouldKeepMeasurementWhenPacketScanSourceReportsSameKeyframePositions() {
    var mediaFileId = createMediaFile();
    repository().publish(publication(mediaFileId, 1, measured(positions(90_000))));
    var scanned = positions(90_000, KeyframePositionSource.PACKET_SCAN);

    repository().publish(publication(mediaFileId, 2, unmeasured(scanned)));

    assertThat(repository().findStreamMeasurements(mediaFileId)).contains(measurement());
  }

  @Test
  @DisplayName("Should list the measured streams by stream index whatever order they arrived in")
  default void shouldListMeasuredStreamsByStreamIndexWhateverOrderTheyArrivedIn() {
    var mediaFileId = createMediaFile();
    var reversed =
        new StreamMeasurements(
            StreamMeasurementSource.SAMPLE_TABLE,
            List.of(StreamMeasurementFixture.audio(), StreamMeasurementFixture.video()));

    repository()
        .publish(publication(mediaFileId, 1, success(positions(90_000), Optional.of(reversed))));

    assertThat(repository().findStreamMeasurements(mediaFileId)).contains(measurement());
  }

  @Test
  @DisplayName("Should read no measurement when no outcome is stored")
  default void shouldReadNoMeasurementWhenNoOutcomeIsStored() {
    var mediaFileId = createMediaFile();

    assertThat(repository().findStreamMeasurements(mediaFileId)).isEmpty();
  }

  private static ProbePublication publication(
      UUID mediaFileId, int probeVersion, ProbeOutcome.Success outcome) {
    return ProbePublication.builder()
        .mediaFileId(mediaFileId)
        .snapshot(MEASURED_SNAPSHOT)
        .probeVersion(probeVersion)
        .outcome(outcome)
        .build();
  }
}
