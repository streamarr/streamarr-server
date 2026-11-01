package com.streamarr.server.repositories;

import static com.streamarr.server.fixtures.BitrateScanChunkFixture.chunk;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.streaming.KeyframeIntervalCounts;
import com.streamarr.server.domain.streaming.MeasuredStream;
import com.streamarr.server.domain.streaming.StreamMeasurementSource;
import com.streamarr.server.domain.streaming.StreamMeasurementUnavailableReason;
import com.streamarr.server.domain.streaming.StreamMeasurements;
import com.streamarr.server.domain.task.BitrateScanChunkPublication;
import com.streamarr.server.domain.task.BitrateScanKey;
import com.streamarr.server.domain.task.StoredBitrateScanChunk;
import com.streamarr.server.domain.task.StreamMeasurementPublication;
import com.streamarr.server.repositories.media.BitrateScanRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The bitrate scan rules that every {@link BitrateScanRepository} keeps (ADR 0054), so the fake
 * that unit tests use behaves as the PostgreSQL repository does.
 */
public interface BitrateScanRepositoryContract {

  BitrateScanRepository repository();

  /** Creates a media file whose stored outcome needs a bitrate scan, and returns the scan's key. */
  StoredScan createFileNeedingScan();

  /** Replaces the file's stored outcome with one whose keyframe positions differ. */
  BitrateScanKey storeOtherKeyframePositions(UUID mediaFileId);

  /** A media file and the key of its bitrate scan. */
  record StoredScan(UUID mediaFileId, BitrateScanKey key) {}

  @Test
  @DisplayName("Should read the stored chunks in interval order when the key's chunks are stored")
  default void shouldReadTheStoredChunksInIntervalOrderWhenTheKeysChunksAreStored() {
    var scan = createFileNeedingScan();

    assertThat(repository().storeChunk(publication(scan, chunk(2, 2)))).isTrue();
    assertThat(repository().storeChunk(publication(scan, chunk(0, 2)))).isTrue();

    assertThat(repository().findChunks(scan.mediaFileId(), scan.key()))
        .containsExactly(chunk(0, 2), chunk(2, 2));
  }

  @Test
  @DisplayName("Should keep the first chunk when a chunk from the same interval is stored again")
  default void shouldKeepTheFirstChunkWhenAChunkFromTheSameIntervalIsStoredAgain() {
    var scan = createFileNeedingScan();
    repository().storeChunk(publication(scan, chunk(0, 2)));

    assertThat(repository().storeChunk(publication(scan, chunk(0, 1)))).isFalse();

    assertThat(repository().findChunks(scan.mediaFileId(), scan.key()))
        .containsExactly(chunk(0, 2));
  }

  @Test
  @DisplayName("Should store no chunk when the stored outcome no longer holds the chunk's key")
  default void shouldStoreNoChunkWhenTheStoredOutcomeNoLongerHoldsTheChunksKey() {
    var scan = createFileNeedingScan();
    var current = storeOtherKeyframePositions(scan.mediaFileId());

    assertThat(repository().storeChunk(publication(scan, chunk(0, 2)))).isFalse();

    assertThat(repository().findChunks(scan.mediaFileId(), scan.key())).isEmpty();
    assertThat(repository().findChunks(scan.mediaFileId(), current)).isEmpty();
  }

  @Test
  @DisplayName(
      "Should delete only the chunks of another measurement version when the chunks of the key"
          + " remain")
  default void shouldDeleteOnlyTheChunksOfAnotherMeasurementVersionWhenTheKeysChunksRemain() {
    var scan = createFileNeedingScan();
    var otherVersion = otherMeasurementVersion(scan);
    repository().storeChunk(publication(scan, chunk(0, 2)));
    repository().storeChunk(publication(otherVersion, chunk(2, 2)));

    repository().deleteChunksNotFollowing(scan.mediaFileId(), scan.key());

    assertThat(repository().findChunks(scan.mediaFileId(), otherVersion.key())).isEmpty();
    assertThat(repository().findChunks(scan.mediaFileId(), scan.key()))
        .containsExactly(chunk(0, 2));
  }

  @Test
  @DisplayName(
      "Should delete the stored chunks when the stored outcome's keyframe positions change")
  default void shouldDeleteTheStoredChunksWhenTheStoredOutcomesKeyframePositionsChange() {
    var scan = createFileNeedingScan();
    repository().storeChunk(publication(scan, chunk(0, 2)));

    var current =
        new StoredScan(scan.mediaFileId(), storeOtherKeyframePositions(scan.mediaFileId()));

    assertThat(repository().findChunks(scan.mediaFileId(), scan.key())).isEmpty();
    assertThat(repository().storeChunk(publication(current, chunk(0, 1))))
        .as("The new positions' first chunk takes the interval")
        .isTrue();
  }

  @Test
  @DisplayName("Should delete every chunk of the file when its chunks are deleted")
  default void shouldDeleteEveryChunkOfTheFileWhenItsChunksAreDeleted() {
    var scan = createFileNeedingScan();
    repository().storeChunk(publication(scan, chunk(0, 2)));

    repository().deleteChunks(scan.mediaFileId());

    assertThat(repository().findChunks(scan.mediaFileId(), scan.key())).isEmpty();
  }

  @Test
  @DisplayName("Should need no scan when the scan publishes the measurement of its key")
  default void shouldNeedNoScanWhenTheScanPublishesTheMeasurementOfItsKey() {
    var scan = createFileNeedingScan();
    assertThat(repository().findBitrateScanInputs(scan.mediaFileId()))
        .hasValueSatisfying(inputs -> assertThat(inputs.key()).isEqualTo(scan.key()));

    assertThat(repository().publishMeasurement(measurementOf(scan))).isTrue();

    assertThat(repository().findBitrateScanInputs(scan.mediaFileId())).isEmpty();
  }

  @Test
  @DisplayName("Should need a scan of the new key when the stored outcome changes after a scan")
  default void shouldNeedAScanOfTheNewKeyWhenTheStoredOutcomeChangesAfterAScan() {
    var scan = createFileNeedingScan();
    repository().publishMeasurement(measurementOf(scan));

    var current = storeOtherKeyframePositions(scan.mediaFileId());

    assertThat(repository().findBitrateScanInputs(scan.mediaFileId()))
        .hasValueSatisfying(inputs -> assertThat(inputs.key()).isEqualTo(current));
  }

  @Test
  @DisplayName("Should need no scan when the scan marks the measurement of its key unavailable")
  default void shouldNeedNoScanWhenTheScanMarksTheMeasurementOfItsKeyUnavailable() {
    var scan = createFileNeedingScan();

    assertThat(
            repository()
                .markMeasurementUnavailable(
                    scan.mediaFileId(),
                    scan.key(),
                    StreamMeasurementUnavailableReason.COPY_CHANGES_PACKETS))
        .isTrue();

    assertThat(repository().findBitrateScanInputs(scan.mediaFileId())).isEmpty();
  }

  @Test
  @DisplayName(
      "Should store neither a measurement nor a mark when the stored outcome no longer holds the"
          + " key")
  default void shouldStoreNeitherAMeasurementNorAMarkWhenTheStoredOutcomeNoLongerHoldsTheKey() {
    var scan = createFileNeedingScan();
    var current = storeOtherKeyframePositions(scan.mediaFileId());

    assertThat(repository().publishMeasurement(measurementOf(scan))).isFalse();
    assertThat(
            repository()
                .markMeasurementUnavailable(
                    scan.mediaFileId(),
                    scan.key(),
                    StreamMeasurementUnavailableReason.FAILED_ATTEMPTS))
        .isFalse();

    assertThat(repository().findBitrateScanInputs(scan.mediaFileId()))
        .hasValueSatisfying(inputs -> assertThat(inputs.key()).isEqualTo(current));
  }

  @Test
  @DisplayName("Should store no measurement when the key names another measurement version")
  default void shouldStoreNoMeasurementWhenTheKeyNamesAnotherMeasurementVersion() {
    var scan = createFileNeedingScan();
    var otherVersion = otherMeasurementVersion(scan);

    assertThat(repository().publishMeasurement(measurementOf(otherVersion))).isFalse();
    assertThat(
            repository()
                .markMeasurementUnavailable(
                    scan.mediaFileId(),
                    otherVersion.key(),
                    StreamMeasurementUnavailableReason.FAILED_ATTEMPTS))
        .isFalse();

    assertThat(repository().findBitrateScanInputs(scan.mediaFileId())).isPresent();
  }

  private static StoredScan otherMeasurementVersion(StoredScan scan) {
    var key = scan.key();
    return new StoredScan(
        scan.mediaFileId(),
        new BitrateScanKey(
            key.snapshot(), key.keyframePositionsDigest(), key.measurementVersion() + 1));
  }

  private static BitrateScanChunkPublication publication(
      StoredScan scan, StoredBitrateScanChunk chunk) {
    return BitrateScanChunkPublication.builder()
        .mediaFileId(scan.mediaFileId())
        .key(scan.key())
        .chunk(chunk)
        .build();
  }

  private static StreamMeasurementPublication measurementOf(StoredScan scan) {
    return StreamMeasurementPublication.builder()
        .mediaFileId(scan.mediaFileId())
        .key(scan.key())
        .measurements(
            new StreamMeasurements(
                StreamMeasurementSource.BITRATE_SCAN,
                List.of(
                    MeasuredStream.builder()
                        .streamIndex(0)
                        .measuredBytes(200)
                        .packetCount(2)
                        .timeBaseNumerator(1)
                        .timeBaseDenominator(90_000)
                        .measuredDuration(180_000)
                        .keyframeIntervalBytes(KeyframeIntervalCounts.of(100, 100))
                        .keyframeIntervalPacketCounts(KeyframeIntervalCounts.of(1, 1))
                        .build())))
        .build();
  }
}
