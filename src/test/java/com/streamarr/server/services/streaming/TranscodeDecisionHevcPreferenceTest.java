package com.streamarr.server.services.streaming;

import static com.streamarr.server.domain.streaming.ProbedCodecFamily.AV1;
import static com.streamarr.server.domain.streaming.ProbedCodecFamily.H264;
import static com.streamarr.server.domain.streaming.ProbedCodecFamily.HEVC;
import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.av1EntryBuilder;
import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.declarationBuilderFor;
import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.defaultDeclarationBuilder;
import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.h264EntryBuilder;
import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.hevcEntryBuilder;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.streaming.ClientCapabilityDeclaration;
import com.streamarr.server.domain.streaming.EncodeCodecProfile;
import com.streamarr.server.domain.streaming.Fraction;
import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.ProbedCodecFamily;
import com.streamarr.server.domain.streaming.TranscodeDecision;
import com.streamarr.server.domain.streaming.TranscodeMode;
import com.streamarr.server.fixtures.ProbeFixture;
import com.streamarr.server.fixtures.VideoPlaybackFactsFixture;
import com.streamarr.server.properties.StreamingProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.Builder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The HEVC preference of streamarr/streamarr-server#58 under the amended ADR 0009: among codec
 * families whose entries keep the same frame rate, hardware AV1 and HEVC come first, then H.264,
 * then software AV1 and HEVC, and each codec family needs both the client's declaration and a
 * worker-verified encoder.
 */
@Tag("UnitTest")
@DisplayName("Transcode Decision HEVC Preference Tests")
class TranscodeDecisionHevcPreferenceTest {

  private static final List<String> CODEC_FAMILIES = List.of("h264", "hevc", "av1");

  private final TranscodeDecisionService service =
      new TranscodeDecisionService(
          StreamingProperties.builder().targetSegmentDuration(Duration.ofSeconds(6)).build(),
          new SimpleMeterRegistry());

  @ParameterizedTest(name = "{0}")
  @MethodSource("everyDeclarationAndWorkerEncoders")
  @DisplayName(
      "Should encode HEVC exactly when the client declares it, a worker verified it and no codec"
          + " family ranks ahead, and keep the earlier choice otherwise when the video must be"
          + " encoded")
  void shouldEncodeHevcExactlyWhenItsThreeConditionsHoldAndKeepTheEarlierChoiceOtherwise(
      EncoderCase encoderCase) {
    var decision =
        service.decide(
            TranscodeDecisionRequest.builder()
                .source(sourceNoClientPlays())
                .capabilityDeclaration(declaring(encoderCase.declared()))
                .encodableVideoCodecFamilies(encoderCase.verified())
                .hardwareEncodableVideoCodecFamilies(encoderCase.hardware())
                .build());

    assertThat(decision.map(TranscodeDecision::videoCodecFamily))
        .isEqualTo(expectedEncode(encoderCase));
  }

  // Every non-empty declaration over H.264, HEVC and AV1, every set of worker-verified codec
  // families, and every subset of those that a worker encodes in hardware.
  static Stream<EncoderCase> everyDeclarationAndWorkerEncoders() {
    var cases = new ArrayList<EncoderCase>();
    for (var declared : subsetsOf(CODEC_FAMILIES)) {
      if (declared.isEmpty()) {
        continue;
      }

      for (var verified : subsetsOf(CODEC_FAMILIES)) {
        for (var hardware : subsetsOf(List.copyOf(verified))) {
          cases.add(
              EncoderCase.builder()
                  .declared(declared)
                  .verified(verified)
                  .hardware(hardware)
                  .build());
        }
      }
    }

    return cases.stream();
  }

  // The issue's three conditions decide HEVC; any other case keeps the decision that the server
  // made before it encoded HEVC: hardware AV1, then H.264, then software AV1.
  private static Optional<String> expectedEncode(EncoderCase encoderCase) {
    if (choosesHevc(encoderCase)) {
      return Optional.of("hevc");
    }

    return Stream.of(
            Optional.of("av1").filter(_ -> encoderCase.isHardwareEligible("av1")),
            Optional.of("h264").filter(_ -> encoderCase.isEligible("h264")),
            Optional.of("av1").filter(_ -> encoderCase.isEligible("av1")))
        .flatMap(Optional::stream)
        .findFirst();
  }

  private static boolean choosesHevc(EncoderCase encoderCase) {
    var hevcFirstInHardware =
        encoderCase.isHardwareEligible("hevc") && !encoderCase.isHardwareEligible("av1");
    var hevcFirstInSoftware = !encoderCase.isEligible("h264") && !encoderCase.isEligible("av1");
    return encoderCase.isEligible("hevc") && (hevcFirstInHardware || hevcFirstInSoftware);
  }

  @Test
  @DisplayName(
      "Should copy an HEVC source rather than encode HEVC when the client plays the source and a"
          + " worker encodes HEVC in hardware")
  void shouldCopyAnHevcSourceRatherThanEncodeHevcWhenTheClientPlaysTheSource() {
    var source =
        sourceBuilder("hevc")
            .videoPlaybackFacts(
                Optional.of(
                    VideoPlaybackFactsFixture.sdrH264FactsBuilder()
                        .streamIndex(0)
                        .codecString(Optional.of("hvc1.1.6.L120.90"))
                        .build()))
            .build();

    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(source)
                    .capabilityDeclaration(declaring(Set.of("hevc", "h264")))
                    .encodableVideoCodecFamilies(Set.copyOf(CODEC_FAMILIES))
                    .hardwareEncodableVideoCodecFamilies(Set.of("hevc"))
                    .build())
            .orElseThrow();

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.REMUX);
    assertThat(decision.encodeCodecProfile()).isEmpty();
  }

  @Test
  @DisplayName(
      "Should force H.265 Main when the decision encodes HEVC for a client that declares only HEVC")
  void shouldForceH265MainWhenTheDecisionEncodesHevcForAClientThatDeclaresOnlyHevc() {
    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(sourceNoClientPlays())
                    .capabilityDeclaration(declaring(Set.of("hevc")))
                    .encodableVideoCodecFamilies(Set.copyOf(CODEC_FAMILIES))
                    .build())
            .orElseThrow();

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.VIDEO_TRANSCODE);
    assertThat(decision.encodeCodecProfile()).contains(EncodeCodecProfile.HEVC_MAIN);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("entryFrameRatesBeforeThePreference")
  @DisplayName(
      "Should encode the codec family whose entry keeps the higher frame rate before the HEVC"
          + " preference when the entries allow different frame rates")
  void shouldEncodeTheFamilyThatKeepsTheHigherFrameRateBeforeTheHevcPreference(
      FrameRateCase frameRateCase) {
    var declaration =
        defaultDeclarationBuilder()
            .video(
                List.of(
                    h264EntryBuilder().maxFrameRate(frameRateCase.h264MaxFrameRate()).build(),
                    hevcEntryBuilder().maxFrameRate(frameRateCase.hevcMaxFrameRate()).build(),
                    av1EntryBuilder().maxFrameRate(frameRateCase.av1MaxFrameRate()).build()))
            .build();

    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(sourceNoClientPlaysAt60())
                    .capabilityDeclaration(declaration)
                    .encodableVideoCodecFamilies(Set.copyOf(CODEC_FAMILIES))
                    .hardwareEncodableVideoCodecFamilies(frameRateCase.hardware())
                    .build())
            .orElseThrow();

    assertThat(decision.videoCodecFamily()).isEqualTo(frameRateCase.encodedFamily());
  }

  // ADR 0049 keeps the higher frame rate first, even from a software encoder (ADR 0009).
  static Stream<FrameRateCase> entryFrameRatesBeforeThePreference() {
    return Stream.of(
        FrameRateCase.builder()
            .scenario("software HEVC at 60 fps beats H.264 at 30 fps")
            .h264MaxFrameRate(30)
            .hevcMaxFrameRate(60)
            .av1MaxFrameRate(30)
            .hardware(Set.of())
            .encodedFamily("hevc")
            .build(),
        FrameRateCase.builder()
            .scenario("H.264 at 60 fps beats hardware HEVC at 30 fps")
            .h264MaxFrameRate(60)
            .hevcMaxFrameRate(30)
            .av1MaxFrameRate(30)
            .hardware(Set.of("hevc"))
            .encodedFamily("h264")
            .build(),
        FrameRateCase.builder()
            .scenario("software HEVC at 60 fps beats hardware AV1 at 30 fps")
            .h264MaxFrameRate(30)
            .hevcMaxFrameRate(60)
            .av1MaxFrameRate(30)
            .hardware(Set.of("av1"))
            .encodedFamily("hevc")
            .build(),
        FrameRateCase.builder()
            .scenario("software AV1 beats software HEVC at the same 60 fps")
            .h264MaxFrameRate(30)
            .hevcMaxFrameRate(60)
            .av1MaxFrameRate(60)
            .hardware(Set.of())
            .encodedFamily("av1")
            .build(),
        FrameRateCase.builder()
            .scenario("hardware HEVC beats software AV1 at the same 60 fps")
            .h264MaxFrameRate(30)
            .hevcMaxFrameRate(60)
            .av1MaxFrameRate(60)
            .hardware(Set.of("hevc"))
            .encodedFamily("hevc")
            .build());
  }

  // One SDR entry for each declared codec family, in H.264, HEVC, AV1 order.
  private static ClientCapabilityDeclaration declaring(Set<String> declared) {
    var families =
        Stream.of(H264, HEVC, AV1)
            .filter(family -> declared.contains(family.name().toLowerCase(Locale.ROOT)))
            .toArray(ProbedCodecFamily[]::new);
    return declarationBuilderFor(families).build();
  }

  // An MPEG-2 source, which no client declares, so the video is always encoded.
  private static MediaProbe sourceNoClientPlays() {
    return sourceBuilder("mpeg2video").build();
  }

  private static MediaProbe sourceNoClientPlaysAt60() {
    return sourceBuilder("mpeg2video")
        .framerate(OptionalDouble.of(60))
        .videoPlaybackFacts(
            Optional.of(
                VideoPlaybackFactsFixture.sdrH264FactsBuilder()
                    .streamIndex(0)
                    .codecString(Optional.empty())
                    .frameRateRule(VideoPlaybackFactsFixture.declaredRateRule(new Fraction(60, 1)))
                    .build()))
        .build();
  }

  private static MediaProbe.MediaProbeBuilder sourceBuilder(String videoCodec) {
    return MediaProbe.builder()
        .keyframes(ProbeFixture.keyframesEvery(Duration.ofSeconds(2), Duration.ofMinutes(120)))
        .duration(Duration.ofMinutes(120))
        .framerate(OptionalDouble.of(23.976))
        .width(1920)
        .height(1080)
        .videoCodec(videoCodec)
        .audioCodec("aac")
        .streams(List.of(ProbeFixture.audioStreamBuilder("aac").index(1).build()))
        .bitrate(5_000_000L)
        .audioChannels(OptionalInt.of(2))
        .audioBitrate(OptionalLong.of(128_000L))
        .videoPlaybackFacts(
            Optional.of(
                VideoPlaybackFactsFixture.sdrH264FactsBuilder()
                    .streamIndex(0)
                    .codecString(Optional.empty())
                    .build()));
  }

  private static List<Set<String>> subsetsOf(List<String> families) {
    var subsets = new ArrayList<Set<String>>();
    for (var mask = 0; mask < 1 << families.size(); mask++) {
      var subset = mask;
      subsets.add(
          families.stream()
              .filter(family -> (subset & (1 << families.indexOf(family))) != 0)
              .collect(Collectors.toUnmodifiableSet()));
    }

    return subsets;
  }

  @Builder
  record EncoderCase(Set<String> declared, Set<String> verified, Set<String> hardware) {

    boolean isEligible(String family) {
      return declared.contains(family) && verified.contains(family);
    }

    boolean isHardwareEligible(String family) {
      return declared.contains(family) && hardware.contains(family);
    }

    @Override
    public String toString() {
      return "declares %s, verified %s, hardware %s"
          .formatted(sorted(declared), sorted(verified), sorted(hardware));
    }

    private static List<String> sorted(Set<String> families) {
      return families.stream().sorted().toList();
    }
  }

  @Builder
  record FrameRateCase(
      String scenario,
      double h264MaxFrameRate,
      double hevcMaxFrameRate,
      double av1MaxFrameRate,
      Set<String> hardware,
      String encodedFamily) {

    @Override
    public String toString() {
      return scenario;
    }
  }
}
