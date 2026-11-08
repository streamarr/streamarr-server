package com.streamarr.server.graphql.dto;

import com.streamarr.server.domain.streaming.DolbyVision;
import com.streamarr.server.domain.streaming.DolbyVisionConfiguration;
import com.streamarr.server.services.streaming.VideoFormatDetails;
import java.util.List;
import java.util.OptionalInt;
import lombok.Builder;

@Builder
public record VideoFormatDetailsDto(
    String codecProfile,
    Integer codecLevel,
    Integer bitDepth,
    String chromaSubsampling,
    String compatibleBase,
    String hdr10Plus,
    DolbyVisionDetailsDto dolbyVision,
    MasteringDisplayDto masteringDisplay,
    ContentLightLevelDto contentLightLevel,
    List<String> bitstreamRewrites) {

  @Builder
  public record DolbyVisionDetailsDto(
      String presence,
      Integer profile,
      Integer level,
      Integer baseLayerCompatibilityId,
      String enhancementLayer,
      String observedRpu) {}

  public record MasteringDisplayDto(double minLuminance, double maxLuminance) {}

  public record ContentLightLevelDto(int maxContentLightLevel, int maxFrameAverageLightLevel) {}

  public static VideoFormatDetailsDto from(VideoFormatDetails details) {
    return VideoFormatDetailsDto.builder()
        .codecProfile(details.codecProfile().map(Enum::name).orElse(null))
        .codecLevel(nullable(details.codecLevel()))
        .bitDepth(nullable(details.bitDepth()))
        .chromaSubsampling(details.chromaSubsampling().map(Enum::name).orElse(null))
        .compatibleBase(details.dynamicRange().compatibleBase().name())
        .hdr10Plus(details.dynamicRange().hdr10Plus().name())
        .dolbyVision(dolbyVision(details.dynamicRange().dolbyVision()))
        .masteringDisplay(
            details
                .masteringDisplay()
                .map(
                    display ->
                        new MasteringDisplayDto(
                            display.minLuminance() / 10_000.0, display.maxLuminance() / 10_000.0))
                .orElse(null))
        .contentLightLevel(
            details
                .contentLightLevel()
                .map(
                    light ->
                        new ContentLightLevelDto(
                            light.maxContentLightLevel(), light.maxFrameAverageLightLevel()))
                .orElse(null))
        .bitstreamRewrites(details.bitstreamRewrites().stream().map(Enum::name).toList())
        .build();
  }

  private static DolbyVisionDetailsDto dolbyVision(DolbyVision dolbyVision) {
    return switch (dolbyVision) {
      case DolbyVision.Unspecified _ ->
          DolbyVisionDetailsDto.builder().presence("UNSPECIFIED").build();
      case DolbyVision.Absent _ -> DolbyVisionDetailsDto.builder().presence("ABSENT").build();
      case DolbyVision.Present present ->
          DolbyVisionDetailsDto.builder()
              .presence("PRESENT")
              .profile(
                  nullable(
                      present
                          .declared()
                          .map(DolbyVisionConfiguration::profile)
                          .orElseGet(OptionalInt::empty)))
              .level(
                  nullable(
                      present
                          .declared()
                          .map(DolbyVisionConfiguration::level)
                          .orElseGet(OptionalInt::empty)))
              .baseLayerCompatibilityId(
                  nullable(
                      present
                          .declared()
                          .map(DolbyVisionConfiguration::baseLayerCompatibilityId)
                          .orElseGet(OptionalInt::empty)))
              .enhancementLayer(present.enhancementLayer().name())
              .observedRpu(present.observedRpu().name())
              .build();
    };
  }

  private static Integer nullable(OptionalInt value) {
    return value.isPresent() ? value.getAsInt() : null;
  }
}
