package com.streamarr.server.domain.streaming;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import java.util.OptionalInt;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("UnitTest")
@DisplayName("Media File Probe Property Issue Tests")
class MediaFileProbePropertyIssueTest {

  @Test
  @DisplayName("Should find no issue when the stream carries no Dolby Vision")
  void shouldFindNoIssueWhenTheStreamCarriesNoDolbyVision() {
    var range =
        new VideoDynamicRange(CompatibleBase.PQ, Presence.PRESENT, new DolbyVision.Absent());

    assertThat(MediaFileProbePropertyIssue.foundIn(range)).isEmpty();
  }

  // bl_signal_compatibility_id names a claimed base: 0 none, 1 HDR10, 2 SDR, 4 HLG, and 6 the PQ
  // base of profile 7.
  @ParameterizedTest(name = "identifier {0} beside {1}: {2}")
  @MethodSource("compatibilityClaims")
  @DisplayName(
      "Should find a disagreeing compatibility only when the identifier claims another base")
  void shouldFindADisagreeingCompatibilityOnlyWhenTheIdentifierClaimsAnotherBase(
      int compatibilityId, CompatibleBase signaled, boolean disagrees) {
    var range =
        new VideoDynamicRange(
            signaled,
            Presence.UNSPECIFIED,
            new DolbyVision.Present(
                Optional.of(
                    DolbyVisionConfiguration.builder()
                        .baseLayerCompatibilityId(OptionalInt.of(compatibilityId))
                        .baseLayer(true)
                        .rpu(true)
                        .build()),
                Presence.UNSPECIFIED));

    assertThat(
            MediaFileProbePropertyIssue.foundIn(range)
                .contains(
                    MediaFileProbePropertyIssue.DOLBY_VISION_COMPATIBILITY_DISAGREES_WITH_TRANSFER))
        .isEqualTo(disagrees);
  }

  static Stream<Arguments> compatibilityClaims() {
    return Stream.of(
        Arguments.of(0, CompatibleBase.NONE, false),
        Arguments.of(1, CompatibleBase.PQ, false),
        Arguments.of(2, CompatibleBase.SDR, false),
        Arguments.of(4, CompatibleBase.HLG, false),
        Arguments.of(6, CompatibleBase.PQ, false),
        Arguments.of(6, CompatibleBase.SDR, true),
        Arguments.of(2, CompatibleBase.PQ, true),
        Arguments.of(0, CompatibleBase.SDR, true),
        Arguments.of(3, CompatibleBase.SDR, true));
  }

  @Test
  @DisplayName("Should find no disagreement when the record states no compatibility identifier")
  void shouldFindNoDisagreementWhenTheRecordStatesNoCompatibilityIdentifier() {
    var range =
        new VideoDynamicRange(
            CompatibleBase.SDR,
            Presence.UNSPECIFIED,
            new DolbyVision.Present(
                Optional.of(DolbyVisionConfiguration.builder().baseLayer(true).build()),
                Presence.UNSPECIFIED));

    assertThat(MediaFileProbePropertyIssue.foundIn(range)).isEmpty();
  }

  @ParameterizedTest
  @EnumSource(MediaFileProbePropertyIssue.class)
  @DisplayName("Should rule out a copy when the probe finds any property issue")
  void shouldRuleOutACopyWhenTheProbeFindsAnyPropertyIssue(MediaFileProbePropertyIssue issue) {
    assertThat(issue.rulesOutCopy()).isTrue();
  }
}
