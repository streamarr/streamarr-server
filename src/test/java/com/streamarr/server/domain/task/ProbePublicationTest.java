package com.streamarr.server.domain.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.streamarr.server.domain.media.SourceFileSnapshot;
import com.streamarr.server.domain.streaming.KeyframeKnowledge;
import com.streamarr.server.domain.streaming.KeyframePacketScanReason;
import com.streamarr.server.domain.streaming.KeyframePosition;
import com.streamarr.server.domain.streaming.KeyframePositionSource;
import com.streamarr.server.domain.streaming.KeyframePositions;
import com.streamarr.server.domain.streaming.KeyframesUnavailableReason;
import com.streamarr.server.domain.streaming.ProbeContainer;
import com.streamarr.server.domain.streaming.ProbeError;
import com.streamarr.server.domain.streaming.ProbeOutcome;
import com.streamarr.server.domain.streaming.StreamInfo;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("UnitTest")
@DisplayName("Probe publication")
class ProbePublicationTest {

  @ParameterizedTest
  @MethodSource("outcomesThatAProbeReports")
  @DisplayName("Should accept an outcome when a probe reports it")
  void shouldAcceptAnOutcomeWhenAProbeReportsIt(ProbeOutcome outcome) {
    var publication = builder().outcome(outcome).build();

    assertThat(publication.outcome()).isEqualTo(outcome);
  }

  @Test
  @DisplayName("Should reject an outcome when its keyframes name positions kept from a packet scan")
  void shouldRejectAnOutcomeWhenItsKeyframesNamePositionsKeptFromAPacketScan() {
    var publication =
        builder()
            .outcome(
                success(
                    new KeyframeKnowledge.PacketScanRequiredWithKeptPositions(
                        KeyframePacketScanReason.NO_CONTAINER_INDEX, scannedPositions())));

    assertThatIllegalArgumentException()
        .isThrownBy(publication::build)
        .withMessage("A probe reports no positions kept from an earlier packet scan");
  }

  private static Stream<ProbeOutcome> outcomesThatAProbeReports() {
    return Stream.of(
        success(
            new KeyframeKnowledge.PacketScanRequired(KeyframePacketScanReason.NO_CONTAINER_INDEX)),
        success(new KeyframeKnowledge.Known(scannedPositions())),
        success(new KeyframeKnowledge.Unavailable(KeyframesUnavailableReason.NO_KEYFRAMES)),
        success(new KeyframeKnowledge.NotReported()),
        new ProbeOutcome.Failure(ProbeError.INVALID_MEDIA));
  }

  private static ProbeOutcome.Success success(KeyframeKnowledge keyframes) {
    return ProbeOutcome.Success.builder()
        .container(ProbeContainer.builder().build())
        .streams(List.of(StreamInfo.builder().index(0).codecType("video").build()))
        .keyframes(keyframes)
        .build();
  }

  private static KeyframePositions scannedPositions() {
    return KeyframePositions.builder()
        .streamIndex(0)
        .timeBaseNumerator(1)
        .timeBaseDenominator(90_000)
        .source(KeyframePositionSource.PACKET_SCAN)
        .keyframes(List.of(new KeyframePosition(0, 0)))
        .build();
  }

  private static ProbePublication.ProbePublicationBuilder builder() {
    return ProbePublication.builder()
        .mediaFileId(UUID.randomUUID())
        .snapshot(new SourceFileSnapshot(1, Instant.EPOCH))
        .probeVersion(9);
  }
}
