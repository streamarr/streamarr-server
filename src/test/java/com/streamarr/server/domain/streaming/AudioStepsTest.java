package com.streamarr.server.domain.streaming;

import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.aacEntryBuilder;
import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.audioEntryBuilder;
import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.defaultDeclarationBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import lombok.Builder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("UnitTest")
@DisplayName("Audio Steps Tests")
class AudioStepsTest {

  private static final String STEREO_STEPS = "aac 2 128000, aac 2 96000, aac 2 64000";

  @ParameterizedTest(name = "{0}")
  @MethodSource("surroundEncodes")
  @DisplayName(
      "Should step a surround encode through its codec's multichannel bitrates and then AAC stereo"
          + " when the session encodes the audio")
  void
      shouldStepASurroundEncodeThroughItsCodecsMultichannelBitratesAndThenAacStereoWhenTheSessionEncodesTheAudio(
          StepsCase stepsCase) {
    assertSteps(stepsCase);
  }

  static Stream<StepsCase> surroundEncodes() {
    return Stream.of(
        StepsCase.builder()
            .sessionAudio(encode("eac3").channels(6).bitrate(768_000L).build())
            .entries(entries(AudioCodec.EAC3, 6))
            .steps(
                List.of(
                    "eac3 6 768000",
                    "eac3 6 640000",
                    "eac3 6 448000",
                    "eac3 6 384000",
                    STEREO_STEPS))
            .build(),
        StepsCase.builder()
            .sessionAudio(encode("ac3").channels(6).bitrate(640_000L).build())
            .entries(entries(AudioCodec.AC3, 6))
            .steps(List.of("ac3 6 640000", "ac3 6 448000", "ac3 6 384000", STEREO_STEPS))
            .build(),
        StepsCase.builder()
            .sessionAudio(encode("aac").channels(6).bitrate(384_000L).build())
            .entries(entries(AudioCodec.AAC, 6))
            .steps(List.of("aac 6 384000", "aac 6 320000", STEREO_STEPS))
            .build());
  }

  // AAC 5.1 at 320 kb/s needs no entry beyond the one that accepts AAC 7.1.
  @ParameterizedTest(name = "{0}")
  @MethodSource("aacAbove51")
  @DisplayName(
      "Should step AAC above 5.1 through AAC 5.1 before stereo when the track has more than 5.1")
  void shouldStepAacAbove51ThroughAac51BeforeStereoWhenTheTrackHasMoreThan51(StepsCase stepsCase) {
    assertSteps(stepsCase);
  }

  static Stream<StepsCase> aacAbove51() {
    var aac71 = StepsCase.builder().entries(entries(AudioCodec.AAC, 8));
    return Stream.of(
        aac71
            .sessionAudio(encode("aac").channels(8).bitrate(512_000L).build())
            .steps(List.of("aac 8 512000", "aac 8 448000", "aac 6 320000", STEREO_STEPS))
            .build(),
        aac71
            .sessionAudio(copy("aac").channels(8).bitrate(640_000L).build())
            .steps(List.of("aac 8 640000", "aac 8 448000", "aac 6 320000", STEREO_STEPS))
            .build(),
        aac71
            .sessionAudio(copy("aac").channels(8).bitrate(400_000L).build())
            .steps(List.of("aac 8 400000", "aac 6 320000", STEREO_STEPS))
            .build());
  }

  // A copied codec that the server also encodes adds its own multichannel bitrates: the steps of
  // both codecs below the copy, highest bitrate first, the copied codec first at an equal bitrate.
  @ParameterizedTest(name = "{0}")
  @MethodSource("copiesInACodecTheServerEncodes")
  @DisplayName(
      "Should step a copy through its own codec's and the server's encode's multichannel bitrates,"
          + " highest first, when the server encodes the copied codec")
  void
      shouldStepACopyThroughItsOwnCodecsAndTheServersEncodesMultichannelBitratesHighestFirstWhenTheServerEncodesTheCopiedCodec(
          StepsCase stepsCase) {
    assertSteps(stepsCase);
  }

  static Stream<StepsCase> copiesInACodecTheServerEncodes() {
    return Stream.of(
        StepsCase.builder()
            .sessionAudio(copy("ac3").channels(6).bitrate(640_000L).build())
            .entries(entries(AudioCodec.AC3, 6))
            .steps(List.of("ac3 6 640000", "ac3 6 448000", "ac3 6 384000", STEREO_STEPS))
            .build(),
        StepsCase.builder()
            .sessionAudio(copy("ac3").channels(6).bitrate(640_000L).build())
            .entries(
                List.of(
                    aacEntryBuilder().build(),
                    audioEntryBuilder(AudioCodec.AC3).maxChannels(6).build(),
                    audioEntryBuilder(AudioCodec.EAC3).maxChannels(6).build()))
            .steps(List.of("ac3 6 640000", "ac3 6 448000", "ac3 6 384000", STEREO_STEPS))
            .build(),
        StepsCase.builder()
            .sessionAudio(copy("aac").channels(6).bitrate(640_000L).build())
            .entries(aac51AndEac351())
            .steps(
                List.of(
                    "aac 6 640000", "eac3 6 448000", "eac3 6 384000", "aac 6 320000", STEREO_STEPS))
            .build(),
        StepsCase.builder()
            .sessionAudio(copy("aac").channels(8).bitrate(512_000L).build())
            .entries(
                List.of(
                    aacEntryBuilder().maxChannels(8).build(),
                    audioEntryBuilder(AudioCodec.EAC3).maxChannels(6).build()))
            .steps(
                List.of(
                    "aac 8 512000", "aac 8 448000", "eac3 6 384000", "aac 6 320000", STEREO_STEPS))
            .build(),
        StepsCase.builder()
            .sessionAudio(copy("eac3").channels(8).bitrate(1_024_000L).build())
            .entries(entries(AudioCodec.EAC3, 8))
            .steps(
                List.of(
                    "eac3 8 1024000",
                    "eac3 6 640000",
                    "eac3 6 448000",
                    "eac3 6 384000",
                    STEREO_STEPS))
            .build());
  }

  // The server encodes E-AC-3 and then AC-3 in surround, but not Opus or FLAC.
  @ParameterizedTest(name = "{0}")
  @MethodSource("copiesInACodecTheServerDoesNotEncode")
  @DisplayName(
      "Should step a copy through the server's encode only when the server does not encode the"
          + " copied codec")
  void shouldStepACopyThroughTheServersEncodeOnlyWhenTheServerDoesNotEncodeTheCopiedCodec(
      StepsCase stepsCase) {
    assertSteps(stepsCase);
  }

  static Stream<StepsCase> copiesInACodecTheServerDoesNotEncode() {
    return Stream.of(
        StepsCase.builder()
            .sessionAudio(copy("opus").channels(6).bitrate(450_000L).build())
            .entries(
                List.of(
                    aacEntryBuilder().build(),
                    audioEntryBuilder(AudioCodec.OPUS).maxChannels(6).build(),
                    audioEntryBuilder(AudioCodec.EAC3).maxChannels(6).build()))
            .steps(List.of("opus 6 450000", "eac3 6 448000", "eac3 6 384000", STEREO_STEPS))
            .build(),
        StepsCase.builder()
            .sessionAudio(copy("flac").channels(6).bitrate(2_500_000L).build())
            .entries(
                List.of(
                    aacEntryBuilder().build(),
                    audioEntryBuilder(AudioCodec.FLAC).maxChannels(6).build(),
                    audioEntryBuilder(AudioCodec.AC3).maxChannels(6).build()))
            .steps(List.of("flac 6 2500000", "ac3 6 448000", "ac3 6 384000", STEREO_STEPS))
            .build());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("sessionAudioAtOrBelowAStepsBitrate")
  @DisplayName(
      "Should leave out a step when its bitrate is not below the bitrate of every step before it")
  void shouldLeaveOutAStepWhenItsBitrateIsNotBelowTheBitrateOfEveryStepBeforeIt(
      StepsCase stepsCase) {
    assertSteps(stepsCase);
  }

  // A copy at or below a multichannel bitrate skips that step, and a stereo encode at 128 kb/s
  // skips the 128 kb/s stereo step.
  static Stream<StepsCase> sessionAudioAtOrBelowAStepsBitrate() {
    return Stream.of(
        StepsCase.builder()
            .sessionAudio(copy("aac").channels(6).bitrate(384_000L).build())
            .entries(aac51AndEac351())
            .steps(List.of("aac 6 384000", "aac 6 320000", STEREO_STEPS))
            .build(),
        StepsCase.builder()
            .sessionAudio(copy("aac").channels(6).bitrate(320_000L).build())
            .entries(aac51AndEac351())
            .steps(List.of("aac 6 320000", STEREO_STEPS))
            .build(),
        StepsCase.builder()
            .sessionAudio(copy("eac3").channels(6).bitrate(384_000L).build())
            .entries(entries(AudioCodec.EAC3, 6))
            .steps(List.of("eac3 6 384000", STEREO_STEPS))
            .build(),
        StepsCase.builder()
            .sessionAudio(copy("aac").channels(2).bitrate(256_000L).build())
            .entries(List.of(aacEntryBuilder().build()))
            .steps(List.of("aac 2 256000", STEREO_STEPS))
            .build(),
        StepsCase.builder()
            .sessionAudio(encode("aac").channels(2).bitrate(128_000L).build())
            .entries(List.of(aacEntryBuilder().build()))
            .steps(List.of(STEREO_STEPS))
            .build());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("monoTracks")
  @DisplayName("Should step a mono track only to AAC mono at 64 kb/s when the track is mono")
  void shouldStepAMonoTrackOnlyToAacMonoAt64KbpsWhenTheTrackIsMono(StepsCase stepsCase) {
    assertSteps(stepsCase);
  }

  static Stream<StepsCase> monoTracks() {
    return Stream.of(
        StepsCase.builder()
            .sessionAudio(encode("aac").channels(1).bitrate(64_000L).build())
            .entries(List.of(aacEntryBuilder().build()))
            .steps(List.of("aac 1 64000"))
            .build(),
        StepsCase.builder()
            .sessionAudio(copy("ac3").channels(1).bitrate(96_000L).build())
            .entries(entries(AudioCodec.AC3, 6))
            .steps(List.of("ac3 1 96000", "aac 1 64000"))
            .build());
  }

  @Test
  @DisplayName(
      "Should step a surround track straight to AAC stereo when the player declares only stereo"
          + " AAC")
  void shouldStepASurroundTrackStraightToAacStereoWhenThePlayerDeclaresOnlyStereoAac() {
    var steps =
        AudioSteps.below(
            copy("flac").channels(6).bitrate(2_500_000L).build(),
            defaultDeclarationBuilder()
                .audio(
                    List.of(
                        aacEntryBuilder().build(),
                        audioEntryBuilder(AudioCodec.FLAC).maxChannels(6).build()))
                .build());

    assertThat(describe(steps)).isEqualTo("flac 6 2500000, " + STEREO_STEPS);
  }

  @Test
  @DisplayName("Should encode every step below the session's audio when the session copies it")
  void shouldEncodeEveryStepBelowTheSessionsAudioWhenTheSessionCopiesIt() {
    var declaration = defaultDeclarationBuilder().audio(entries(AudioCodec.AC3, 6)).build();

    var steps = AudioSteps.below(copy("ac3").channels(6).bitrate(640_000L).build(), declaration);

    assertThat(steps.highestFirst().subList(1, steps.highestFirst().size()))
        .isNotEmpty()
        .allSatisfy(
            step -> {
              assertThat(step.mode()).isEqualTo(AudioMode.TRANSCODE);
              assertThat(step.sourceCodecString()).isEmpty();
            });
    assertThat(steps.sessionAudio().mode()).isEqualTo(AudioMode.COPY);
  }

  // A player that declares AAC with at most 4 channels gets a 4-channel AAC encode at 128 kb/s. Its
  // multichannel step, 64 kb/s for each of its 4 channels, is not below it.
  @Test
  @DisplayName(
      "Should count every channel as full-band when a multichannel encode has no low-frequency"
          + " channel")
  void shouldCountEveryChannelAsFullBandWhenAMultichannelEncodeHasNoLowFrequencyChannel() {
    var declaration =
        defaultDeclarationBuilder()
            .audio(List.of(aacEntryBuilder().maxChannels(4).build()))
            .build();

    var steps = AudioSteps.below(encode("aac").channels(4).bitrate(128_000L).build(), declaration);

    assertThat(describe(steps)).isEqualTo("aac 4 128000, aac 2 96000, aac 2 64000");
  }

  @Test
  @DisplayName("Should refuse audio steps when the session's audio or the declaration is missing")
  void shouldRefuseAudioStepsWhenTheSessionsAudioOrTheDeclarationIsMissing() {
    var declaration = defaultDeclarationBuilder().build();
    var sessionAudio = AudioDecision.stereoAac();

    assertThatNullPointerException()
        .isThrownBy(() -> AudioSteps.below(null, declaration))
        .withMessageContaining("sessionAudio");
    assertThatNullPointerException()
        .isThrownBy(() -> AudioSteps.below(sessionAudio, null))
        .withMessageContaining("declaration");
  }

  @Test
  @DisplayName("Should keep only the session's audio when the session has no audio")
  void shouldKeepOnlyTheSessionsAudioWhenTheSessionHasNoAudio() {
    var steps = AudioSteps.below(AudioDecision.none(), defaultDeclarationBuilder().build());

    assertThat(steps.highestFirst()).containsExactly(AudioDecision.none());
    assertThat(steps.sessionAudio()).isEqualTo(AudioDecision.none());
  }

  @Test
  @DisplayName("Should refuse audio steps when they hold no session audio")
  void shouldRefuseAudioStepsWhenTheyHoldNoSessionAudio() {
    var noSteps = List.<AudioDecision>of();

    assertThatThrownBy(() -> new AudioSteps(noSteps)).isInstanceOf(IllegalArgumentException.class);
  }

  // Stereo AAC, which every declaration carries, beside one surround entry for the codec.
  private static List<AudioCapabilityEntry> entries(AudioCodec codec, int maxChannels) {
    if (codec == AudioCodec.AAC) {
      return List.of(aacEntryBuilder().maxChannels(maxChannels).build());
    }

    return List.of(
        aacEntryBuilder().build(), audioEntryBuilder(codec).maxChannels(maxChannels).build());
  }

  private static List<AudioCapabilityEntry> aac51AndEac351() {
    return List.of(
        aacEntryBuilder().maxChannels(6).build(),
        audioEntryBuilder(AudioCodec.EAC3).maxChannels(6).build());
  }

  private static AudioDecision.AudioDecisionBuilder encode(String codec) {
    return AudioDecision.builder().mode(AudioMode.TRANSCODE).codec(codec);
  }

  private static AudioDecision.AudioDecisionBuilder copy(String codec) {
    return AudioDecision.builder()
        .mode(AudioMode.COPY)
        .codec(codec)
        .sourceCodecString(Optional.of("copied"));
  }

  private static void assertSteps(StepsCase stepsCase) {
    var declaration = defaultDeclarationBuilder().audio(stepsCase.entries()).build();

    var steps = AudioSteps.below(stepsCase.sessionAudio(), declaration);

    assertThat(describe(steps)).isEqualTo(String.join(", ", stepsCase.steps()));
  }

  private static String describe(AudioSteps steps) {
    return String.join(
        ", ",
        steps.highestFirst().stream()
            .map(step -> step.codec() + " " + step.channels() + " " + step.bitrate())
            .toList());
  }

  // Each step as "codec channels bitrate", such as "eac3 6 768000", highest first.
  @Builder
  record StepsCase(
      AudioDecision sessionAudio, List<AudioCapabilityEntry> entries, List<String> steps) {}
}
