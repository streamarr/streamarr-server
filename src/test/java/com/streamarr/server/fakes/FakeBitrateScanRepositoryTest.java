package com.streamarr.server.fakes;

import com.streamarr.server.domain.media.SourceFileSnapshot;
import com.streamarr.server.domain.streaming.StreamMeasurements;
import com.streamarr.server.domain.task.BitrateScanInputs;
import com.streamarr.server.domain.task.BitrateScanKey;
import com.streamarr.server.fixtures.StreamMeasurementFixture;
import com.streamarr.server.repositories.BitrateScanRepositoryContract;
import com.streamarr.server.repositories.media.BitrateScanRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;

@Tag("UnitTest")
@DisplayName("Fake Bitrate Scan Repository Tests")
class FakeBitrateScanRepositoryTest implements BitrateScanRepositoryContract {

  private static final SourceFileSnapshot SNAPSHOT =
      new SourceFileSnapshot(1_234, Instant.parse("2026-10-30T10:00:00Z"));

  private final FakeBitrateScanRepository repository = new FakeBitrateScanRepository();

  @Override
  public BitrateScanRepository repository() {
    return repository;
  }

  @Override
  public StoredScan createFileNeedingScan() {
    var mediaFileId = UUID.randomUUID();
    return new StoredScan(mediaFileId, storeInputs(mediaFileId, "aa"));
  }

  @Override
  public BitrateScanKey storeOtherKeyframePositions(UUID mediaFileId) {
    return storeInputs(mediaFileId, "bb");
  }

  private BitrateScanKey storeInputs(UUID mediaFileId, String keyframePositionsDigest) {
    var key =
        new BitrateScanKey(SNAPSHOT, keyframePositionsDigest, StreamMeasurements.CURRENT_VERSION);
    repository.storeOutcomeNeedingScan(
        mediaFileId,
        BitrateScanInputs.builder()
            .key(key)
            .positions(StreamMeasurementFixture.positions(90_000))
            .containerDuration(Optional.empty())
            .audioStreamIndexes(List.of())
            .chosenVideoHasProbedBitrate(false)
            .build());
    return key;
  }
}
