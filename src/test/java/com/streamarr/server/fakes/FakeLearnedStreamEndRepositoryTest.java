package com.streamarr.server.fakes;

import com.streamarr.server.domain.media.MediaFileContainerInfo;
import com.streamarr.server.domain.media.ProbeVersion;
import com.streamarr.server.domain.media.SourceFileSnapshot;
import com.streamarr.server.domain.streaming.KeyframeKnowledge;
import com.streamarr.server.domain.streaming.KeyframePacketScanReason;
import com.streamarr.server.domain.streaming.KeyframePosition;
import com.streamarr.server.domain.streaming.KeyframePositionSource;
import com.streamarr.server.domain.streaming.KeyframePositions;
import com.streamarr.server.domain.streaming.ProbeContainer;
import com.streamarr.server.domain.streaming.ProbeOutcome;
import com.streamarr.server.domain.streaming.SourceStreamEnd;
import com.streamarr.server.domain.streaming.StreamInfo;
import com.streamarr.server.domain.task.KeyframePacketScanPublication;
import com.streamarr.server.domain.task.ProbePublication;
import com.streamarr.server.repositories.LearnedStreamEndRepositoryContract;
import com.streamarr.server.repositories.media.LearnedStreamEndRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;

@Tag("UnitTest")
@DisplayName("Fake Learned Stream End Repository Tests")
class FakeLearnedStreamEndRepositoryTest implements LearnedStreamEndRepositoryContract {

  private final FakeMediaFileContainerInfoRepository probeOutcomes =
      new FakeMediaFileContainerInfoRepository();
  private final FakeLearnedStreamEndRepository repository =
      new FakeLearnedStreamEndRepository(probeOutcomes);

  @Override
  public LearnedStreamEndRepository repository() {
    return repository;
  }

  @Override
  public UUID createMediaFile() {
    return UUID.randomUUID();
  }

  @Override
  public void storeProbeOutcome(UUID mediaFileId, SourceFileSnapshot snapshot) {
    probeOutcomes.store(
        MediaFileContainerInfo.builder()
            .mediaFileId(mediaFileId)
            .snapshot(snapshot)
            .probeVersion(ProbeVersion.CURRENT)
            .container(ProbeContainer.builder().build())
            .build());
  }

  @Override
  public void storeProbeOutcomeNeedingPacketScan(UUID mediaFileId, SourceFileSnapshot snapshot) {
    probeOutcomes.publish(
        ProbePublication.builder()
            .mediaFileId(mediaFileId)
            .snapshot(snapshot)
            .probeVersion(ProbeVersion.CURRENT)
            .outcome(
                new ProbeOutcome.Success(
                    ProbeContainer.builder().build(),
                    List.of(StreamInfo.builder().index(0).codecType("video").build()),
                    new KeyframeKnowledge.PacketScanRequired(
                        KeyframePacketScanReason.NO_CONTAINER_INDEX)))
            .build());
  }

  @Override
  public boolean publishKeyframePacketScan(
      UUID mediaFileId, SourceFileSnapshot snapshot, List<SourceStreamEnd> streamEnds) {
    return probeOutcomes.publishKeyframePacketScan(
        KeyframePacketScanPublication.builder()
            .mediaFileId(mediaFileId)
            .snapshot(snapshot)
            .probeVersion(ProbeVersion.CURRENT)
            .keyframes(
                new KeyframeKnowledge.Known(
                    KeyframePositions.builder()
                        .streamIndex(0)
                        .timeBaseNumerator(1)
                        .timeBaseDenominator(90_000)
                        .source(KeyframePositionSource.PACKET_SCAN)
                        .keyframes(List.of(new KeyframePosition(0, 0)))
                        .build()))
            .streamEnds(streamEnds)
            .build());
  }
}
