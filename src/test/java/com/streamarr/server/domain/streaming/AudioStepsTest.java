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

  private static final String STEREO_STEPS = "aac 2 128000, aac 2 96000";

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
            .sessionAudio(
                encode("eac3", ChannelLayout.FIVE_POINT_ONE_SIDE).bitrate(768_000L).build())
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
            .sessionAudio(
                encode("ac3", ChannelLayout.FIVE_POINT_ONE_SIDE).bitrate(640_000L).build())
            .entries(entries(AudioCodec.AC3, 6))
            .steps(List.of("ac3 6 640000", "ac3 6 448000", "ac3 6 384000", STEREO_STEPS))
            .build(),
        StepsCase.builder()
            .sessionAudio(encode("aac", ChannelLayout.FIVE_POINT_ONE).bitrate(384_000L).build())
            .entries(entries(AudioCodec.AAC, 6))
            .steps(List.of("aac 6 384000", "aac 6 320000", STEREO_STEPS))
            .build());
  }

  // An AAC encode has at most six channels, so a copied AAC 7.1 track steps through AAC 5.1, which
  // needs no entry beyond the one that accepts AAC 7.1 (ADR 0050).
  @ParameterizedTest(name = "{0}")
  @MethodSource("aacAbove51")
  @DisplayName(
      "Should step a copied AAC track above 5.1 straight to AAC 5.1 before stereo when the track"
          + " has more than 5.1")
  void shouldStepACopiedAacTrackAbove51StraightToAac51BeforeStereoWhenTheTrackHasMoreThan51(
      StepsCase stepsCase) {
    assertSteps(stepsCase);
  }

  static Stream<StepsCase> aacAbove51() {
    var aac71 = StepsCase.builder().entries(entries(AudioCodec.AAC, 8));
    return Stream.of(
        aac71
            .sessionAudio(copy("aac").channels(8).bitrate(640_000L).build())
            .steps(List.of("aac 8 640000", "aac 6 320000", STEREO_STEPS))
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
                    "aac 8 512000", "eac3 6 448000", "eac3 6 384000", "aac 6 320000", STEREO_STEPS))
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
            .sessionAudio(encode("aac", ChannelLayout.STEREO).bitrate(128_000L).build())
            .entries(List.of(aacEntryBuilder().build()))
            .steps(List.of(STEREO_STEPS))
            .build());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("monoTracks")
  @DisplayName("Should step a mono track only to AAC mono at 96 kb/s when the track is mono")
  void shouldStepAMonoTrackOnlyToAacMonoAt96KbpsWhenTheTrackIsMono(StepsCase stepsCase) {
    assertSteps(stepsCase);
  }

  static Stream<StepsCase> monoTracks() {
    return Stream.of(
        StepsCase.builder()
            .sessionAudio(encode("aac", ChannelLayout.MONO).bitrate(96_000L).build())
            .entries(List.of(aacEntryBuilder().build()))
            .steps(List.of("aac 1 96000"))
            .build(),
        StepsCase.builder()
            .sessionAudio(copy("ac3").channels(1).bitrate(128_000L).build())
            .entries(entries(AudioCodec.AC3, 6))
            .steps(List.of("ac3 1 128000", "aac 1 96000"))
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
            trackOf(6),
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

    var steps =
        AudioSteps.below(
            copy("ac3").channels(6).bitrate(640_000L).build(), trackOf(6), declaration);

    assertThat(steps.highestFirst().subList(1, steps.highestFirst().size()))
        .isNotEmpty()
        .allSatisfy(
            step -> {
              assertThat(step.mode()).isEqualTo(AudioMode.TRANSCODE);
              assertThat(step.sourceCodecString()).isEmpty();
            });
    assertThat(steps.sessionAudio().mode()).isEqualTo(AudioMode.COPY);
  }

  // ADR 0050 prices multichannel AAC at 64 kb/s for each full-band channel: 320 kb/s for 5.0 as
  // for 5.1.
  @Test
  @DisplayName(
      "Should count every channel as full-band when a multichannel encode has no low-frequency"
          + " channel")
  void shouldCountEveryChannelAsFullBandWhenAMultichannelEncodeHasNoLowFrequencyChannel() {
    var declaration =
        defaultDeclarationBuilder()
            .audio(List.of(aacEntryBuilder().maxChannels(5).build()))
            .build();

    var steps =
        AudioSteps.below(
            encode("aac", ChannelLayout.FIVE_POINT_ZERO).bitrate(384_000L).build(),
            trackOf(6),
            declaration);

    assertThat(describe(steps)).isEqualTo("aac 5 384000, aac 5 320000, " + STEREO_STEPS);
  }

  // A copied AAC 7.1 track beside an E-AC-3 entry: E-AC-3 and AC-3 write the side surround pair,
  // AAC writes 5.1 (ADR 0050).
  @Test
  @DisplayName("Should name the encoder's own layout on every step below the session's audio")
  void shouldNameTheEncodersOwnLayoutOnEveryStepBelowTheSessionsAudio() {
    var declaration =
        defaultDeclarationBuilder()
            .audio(
                List.of(
                    aacEntryBuilder().maxChannels(8).build(),
                    audioEntryBuilder(AudioCodec.EAC3).maxChannels(6).build()))
            .build();

    var steps =
        AudioSteps.below(
            copy("aac").channels(8).bitrate(512_000L).build(),
            new AudioTrackChannels(8, Optional.of("7.1")),
            declaration);

    assertThat(steps.highestFirst())
        .extracting(AudioDecision::channelLayout)
        .containsExactly(
            Optional.empty(),
            Optional.of(ChannelLayout.FIVE_POINT_ONE_SIDE),
            Optional.of(ChannelLayout.FIVE_POINT_ONE_SIDE),
            Optional.of(ChannelLayout.FIVE_POINT_ONE),
            Optional.of(ChannelLayout.STEREO),
            Optional.of(ChannelLayout.STEREO));
  }

  // A 7.0 track encodes in AAC 5.0, which AAC prices at 64 kb/s for each of its five channels.
  @Test
  @DisplayName("Should step a copied 7.0 AAC track through AAC 5.0 when AAC accepts 8 channels")
  void shouldStepACopied70AacTrackThroughAac50WhenAacAccepts8Channels() {
    var declaration =
        defaultDeclarationBuilder()
            .audio(List.of(aacEntryBuilder().maxChannels(8).build()))
            .build();

    var steps =
        AudioSteps.below(
            copy("aac").channels(7).bitrate(448_000L).build(),
            new AudioTrackChannels(7, Optional.of("7.0")),
            declaration);

    assertThat(steps.highestFirst().get(1).channelLayout()).contains(ChannelLayout.FIVE_POINT_ZERO);
    assertThat(describe(steps)).isEqualTo("aac 7 448000, aac 5 320000, " + STEREO_STEPS);
  }

  @Test
  @DisplayName(
      "Should refuse audio steps when the session's audio, the track or the declaration is missing")
  void shouldRefuseAudioStepsWhenTheSessionsAudioTheTrackOrTheDeclarationIsMissing() {
    var declaration = defaultDeclarationBuilder().build();
    var sessionAudio = AudioDecision.stereoAac();
    var track = trackOf(2);

    assertThatNullPointerException()
        .isThrownBy(() -> AudioSteps.below(null, track, declaration))
        .withMessageContaining("sessionAudio");
    assertThatNullPointerException()
        .isThrownBy(() -> AudioSteps.below(sessionAudio, null, declaration))
        .withMessageContaining("track");
    assertThatNullPointerException()
        .isThrownBy(() -> AudioSteps.below(sessionAudio, track, null))
        .withMessageContaining("declaration");
  }

  @Test
  @DisplayName("Should keep only the session's audio when the session has no audio")
  void shouldKeepOnlyTheSessionsAudioWhenTheSessionHasNoAudio() {
    var steps =
        AudioSteps.below(AudioDecision.none(), trackOf(2), defaultDeclarationBuilder().build());

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

  private static AudioDecision.AudioDecisionBuilder encode(String codec, ChannelLayout layout) {
    return AudioDecision.builder()
        .mode(AudioMode.TRANSCODE)
        .codec(codec)
        .channels(layout.getChannels())
        .channelLayout(Optional.of(layout));
  }

  private static AudioDecision.AudioDecisionBuilder copy(String codec) {
    return AudioDecision.builder()
        .mode(AudioMode.COPY)
        .codec(codec)
        .sourceCodecString(Optional.of("copied"));
  }

  // A track whose layout the probe did not store.
  private static AudioTrackChannels trackOf(int channels) {
    return new AudioTrackChannels(channels, Optional.empty());
  }

  private static void assertSteps(StepsCase stepsCase) {
    var declaration = defaultDeclarationBuilder().audio(stepsCase.entries()).build();

    var sessionAudio = stepsCase.sessionAudio();
    var steps = AudioSteps.below(sessionAudio, trackOf(sessionAudio.channels()), declaration);

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
