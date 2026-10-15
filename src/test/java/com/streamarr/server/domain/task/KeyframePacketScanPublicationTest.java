package com.streamarr.server.domain.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.streamarr.server.domain.media.SourceFileSnapshot;
import com.streamarr.server.domain.streaming.KeyframeKnowledge;
import com.streamarr.server.domain.streaming.KeyframePacketScanReason;
import com.streamarr.server.domain.streaming.KeyframePosition;
import com.streamarr.server.domain.streaming.KeyframePositionSource;
import com.streamarr.server.domain.streaming.KeyframePositions;
import com.streamarr.server.domain.streaming.KeyframesUnavailableReason;
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
@DisplayName("Keyframe packet scan publication")
class KeyframePacketScanPublicationTest {

  private static final SourceFileSnapshot SNAPSHOT = new SourceFileSnapshot(1, Instant.EPOCH);
  private static final KeyframeKnowledge UNAVAILABLE =
      new KeyframeKnowledge.Unavailable(KeyframesUnavailableReason.NO_KEYFRAMES);

  @ParameterizedTest
  @MethodSource("keyframesThatAPacketScanReports")
  @DisplayName("Should accept keyframes when a packet scan reports them")
  void shouldAcceptKeyframesWhenAPacketScanReportsThem(KeyframeKnowledge keyframes) {
    var publication = builder().keyframes(keyframes).build();

    assertThat(publication.keyframes()).isEqualTo(keyframes);
  }

  @Test
  @DisplayName("Should reject a publication when its media file is missing")
  void shouldRejectAPublicationWhenItsMediaFileIsMissing() {
    assertThatNullPointerException()
        .isThrownBy(() -> new KeyframePacketScanPublication(null, SNAPSHOT, 3, UNAVAILABLE))
        .withMessageContaining("mediaFileId");
  }

  @Test
  @DisplayName("Should reject a publication when its snapshot is missing")
  void shouldRejectAPublicationWhenItsSnapshotIsMissing() {
    var mediaFileId = UUID.randomUUID();

    assertThatNullPointerException()
        .isThrownBy(() -> new KeyframePacketScanPublication(mediaFileId, null, 3, UNAVAILABLE))
        .withMessageContaining("snapshot");
  }

  @Test
  @DisplayName("Should reject a publication when its keyframes are missing")
  void shouldRejectAPublicationWhenItsKeyframesAreMissing() {
    var mediaFileId = UUID.randomUUID();

    assertThatNullPointerException()
        .isThrownBy(() -> new KeyframePacketScanPublication(mediaFileId, SNAPSHOT, 3, null))
        .withMessageContaining("keyframes");
  }

  @ParameterizedTest
  @MethodSource("keyframesThatNoPacketScanReports")
  @DisplayName("Should reject keyframes that a packet scan cannot report")
  void shouldRejectKeyframesThatAPacketScanCannotReport(KeyframeKnowledge keyframes) {
    var publication = builder().keyframes(keyframes);

    assertThatIllegalArgumentException()
        .isThrownBy(publication::build)
        .withMessage("A packet scan stores known or unavailable keyframes");
  }

  private static Stream<KeyframeKnowledge> keyframesThatAPacketScanReports() {
    return Stream.of(
        new KeyframeKnowledge.Known(
            KeyframePositions.builder()
                .streamIndex(0)
                .timeBaseNumerator(1)
                .timeBaseDenominator(90_000)
                .source(KeyframePositionSource.PACKET_SCAN)
                .keyframes(List.of(new KeyframePosition(0, 0)))
                .build()),
        UNAVAILABLE);
  }

  private static Stream<KeyframeKnowledge> keyframesThatNoPacketScanReports() {
    return Stream.of(
        new KeyframeKnowledge.PacketScanRequired(KeyframePacketScanReason.NO_CONTAINER_INDEX),
        new KeyframeKnowledge.NotReported());
  }

  private static KeyframePacketScanPublication.KeyframePacketScanPublicationBuilder builder() {
    return KeyframePacketScanPublication.builder()
        .mediaFileId(UUID.randomUUID())
        .snapshot(SNAPSHOT)
        .probeVersion(3);
  }
}
