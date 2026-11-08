package com.streamarr.server.graphql.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.streaming.ChromaSubsampling;
import com.streamarr.server.domain.streaming.CompatibleBase;
import com.streamarr.server.domain.streaming.ContentLightLevel;
import com.streamarr.server.domain.streaming.DolbyVision;
import com.streamarr.server.domain.streaming.DolbyVisionConfiguration;
import com.streamarr.server.domain.streaming.Presence;
import com.streamarr.server.domain.streaming.VideoCodecProfile;
import com.streamarr.server.domain.streaming.VideoDynamicRange;
import com.streamarr.server.fixtures.VideoPlaybackFactsFixture;
import com.streamarr.server.services.streaming.VideoFormatDetails;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("UnitTest")
@DisplayName("Video Format Details DTO Tests")
class VideoFormatDetailsDtoTest {
  @Test
  @DisplayName("Should expose physical luminance and zero compatibility when metadata is present")
  void shouldExposePhysicalLuminanceAndZeroCompatibilityWhenMetadataIsPresent() {
    var dolby =
        DolbyVision.Present.builder()
            .declared(
                Optional.of(
                    DolbyVisionConfiguration.builder()
                        .profile(OptionalInt.of(5))
                        .level(OptionalInt.of(6))
                        .baseLayerCompatibilityId(OptionalInt.of(0))
                        .build()))
            .build();
    var details =
        unknownDetails()
            .dynamicRange(new VideoDynamicRange(CompatibleBase.NONE, Presence.ABSENT, dolby))
            .codecProfile(Optional.of(VideoCodecProfile.HEVC_MAIN_10))
            .codecLevel(OptionalInt.of(51))
            .bitDepth(OptionalInt.of(10))
            .chromaSubsampling(Optional.of(ChromaSubsampling.CHROMA_420))
            .masteringDisplay(Optional.of(VideoPlaybackFactsFixture.MASTERING_DISPLAY))
            .contentLightLevel(Optional.of(new ContentLightLevel(0, 355)))
            .build();

    var dto = VideoFormatDetailsDto.from(details);

    assertThat(dto.codecProfile()).isEqualTo("HEVC_MAIN_10");
    assertThat(dto.codecLevel()).isEqualTo(51);
    assertThat(dto.bitDepth()).isEqualTo(10);
    assertThat(dto.chromaSubsampling()).isEqualTo("CHROMA_420");
    assertThat(dto.dolbyVision().profile()).isEqualTo(5);
    assertThat(dto.dolbyVision().level()).isEqualTo(6);
    assertThat(dto.dolbyVision().baseLayerCompatibilityId()).isZero();
    assertThat(dto.masteringDisplay().minLuminance()).isEqualTo(0.005);
    assertThat(dto.masteringDisplay().maxLuminance()).isEqualTo(1000);
    assertThat(dto.contentLightLevel().maxContentLightLevel()).isZero();
    assertThat(dto.contentLightLevel().maxFrameAverageLightLevel()).isEqualTo(355);
  }

  @ParameterizedTest
  @MethodSource("unknownConfigurations")
  @DisplayName("Should leave optional facts null when the probe did not supply them")
  void shouldLeaveOptionalFactsNullWhenTheProbeDidNotSupplyThem(DolbyVision dolbyVision) {
    var details =
        unknownDetails()
            .dynamicRange(
                new VideoDynamicRange(
                    CompatibleBase.UNSPECIFIED, Presence.UNSPECIFIED, dolbyVision))
            .build();

    var dto = VideoFormatDetailsDto.from(details);

    assertThat(dto.codecProfile()).isNull();
    assertThat(dto.codecLevel()).isNull();
    assertThat(dto.bitDepth()).isNull();
    assertThat(dto.chromaSubsampling()).isNull();
    assertThat(dto.masteringDisplay()).isNull();
    assertThat(dto.contentLightLevel()).isNull();
    assertThat(dto.dolbyVision().profile()).isNull();
    assertThat(dto.dolbyVision().level()).isNull();
    assertThat(dto.dolbyVision().baseLayerCompatibilityId()).isNull();
    assertThat(dto.hdr10Plus()).isEqualTo("UNSPECIFIED");
  }

  static Stream<DolbyVision> unknownConfigurations() {
    return Stream.of(
        new DolbyVision.Unspecified(),
        new DolbyVision.Absent(),
        DolbyVision.Present.builder().build());
  }

  private static VideoFormatDetails.VideoFormatDetailsBuilder unknownDetails() {
    return VideoFormatDetails.builder()
        .codecProfile(Optional.empty())
        .codecLevel(OptionalInt.empty())
        .bitDepth(OptionalInt.empty())
        .chromaSubsampling(Optional.empty())
        .masteringDisplay(Optional.empty())
        .contentLightLevel(Optional.empty())
        .bitstreamRewrites(List.of());
  }
}
