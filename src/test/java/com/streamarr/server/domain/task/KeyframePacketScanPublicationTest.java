package com.streamarr.server.domain.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.streamarr.server.domain.media.SourceFileSnapshot;
import com.streamarr.server.domain.streaming.KeyframeKnowledge;
import com.streamarr.server.domain.streaming.KeyframePacketScanReason;
import com.streamarr.server.domain.streaming.KeyframesUnavailableReason;
import java.time.Instant;
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

  @Test
  @DisplayName("Should accept unavailable keyframes when a packet scan cannot find positions")
  void shouldAcceptUnavailableKeyframesWhenAPacketScanCannotFindPositions() {
    var keyframes = new KeyframeKnowledge.Unavailable(KeyframesUnavailableReason.NO_KEYFRAMES);

    var publication = builder().keyframes(keyframes).build();

    assertThat(publication.keyframes()).isEqualTo(keyframes);
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

  private static Stream<KeyframeKnowledge> keyframesThatNoPacketScanReports() {
    return Stream.of(
        new KeyframeKnowledge.PacketScanRequired(KeyframePacketScanReason.NO_CONTAINER_INDEX),
        new KeyframeKnowledge.NotReported());
  }

  private static KeyframePacketScanPublication.KeyframePacketScanPublicationBuilder builder() {
    return KeyframePacketScanPublication.builder()
        .mediaFileId(UUID.randomUUID())
        .snapshot(new SourceFileSnapshot(1, Instant.EPOCH))
        .probeVersion(3);
  }
}
