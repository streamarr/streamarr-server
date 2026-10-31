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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("UnitTest")
@DisplayName("Audio Steps Tests")
class AudioStepsTest {

  private static final String STEREO_STEPS = "aac 2 128000, aac 2 96000, aac 2 64000";

  @ParameterizedTest(name = "{0}")
  @MethodSource("encodedSessionAudio")
  @DisplayName(
      "Should step an encode through its codec's multichannel bitrates and then AAC stereo when the"
          + " session encodes the audio")
  void
      shouldStepAnEncodeThroughItsCodecsMultichannelBitratesAndThenAacStereoWhenTheSessionEncodesTheAudio(
          String sessionAudio, List<AudioCapabilityEntry> entries, String expectedSteps) {
    var declaration = defaultDeclarationBuilder().audio(entries).build();

    var steps = AudioSteps.below(encode(sessionAudio), declaration);

    assertThat(describe(steps)).isEqualTo(expectedSteps);
  }

  static Stream<Arguments> encodedSessionAudio() {
    return Stream.of(
        Arguments.of(
            "eac3 6 768000",
            entries(AudioCodec.EAC3, 6),
            "eac3 6 768000, eac3 6 640000, eac3 6 448000, eac3 6 384000, " + STEREO_STEPS),
        Arguments.of(
            "ac3 6 640000",
            entries(AudioCodec.AC3, 6),
            "ac3 6 640000, ac3 6 448000, ac3 6 384000, " + STEREO_STEPS),
        Arguments.of(
            "aac 6 384000",
            entries(AudioCodec.AAC, 6),
            "aac 6 384000, aac 6 320000, " + STEREO_STEPS),
        Arguments.of(
            "aac 8 512000",
            entries(AudioCodec.AAC, 8),
            "aac 8 512000, aac 8 448000, " + STEREO_STEPS),
        Arguments.of("aac 2 128000", List.of(aacEntryBuilder().build()), STEREO_STEPS),
        Arguments.of("aac 1 64000", List.of(aacEntryBuilder().build()), "aac 1 64000"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("copiedSessionAudio")
  @DisplayName(
      "Should step a copy through the encode that the server would choose for the track when the"
          + " session copies the audio")
  void
      shouldStepACopyThroughTheEncodeThatTheServerWouldChooseForTheTrackWhenTheSessionCopiesTheAudio(
          String sessionAudio, List<AudioCapabilityEntry> entries, String expectedSteps) {
    var declaration = defaultDeclarationBuilder().audio(entries).build();

    var steps = AudioSteps.below(copy(sessionAudio), declaration);

    assertThat(describe(steps)).isEqualTo(expectedSteps);
  }

  // A copied codec that the server does not encode steps through the encode that the server would
  // choose, E-AC-3 and then AC-3. A copy at or below every multichannel bitrate of that encode goes
  // straight to the stereo steps.
  static Stream<Arguments> copiedSessionAudio() {
    return Stream.of(
        Arguments.of(
            "ac3 6 640000",
            entries(AudioCodec.AC3, 6),
            "ac3 6 640000, ac3 6 448000, ac3 6 384000, " + STEREO_STEPS),
        Arguments.of(
            "opus 6 450000",
            List.of(
                aacEntryBuilder().build(),
                audioEntryBuilder(AudioCodec.OPUS).maxChannels(6).build(),
                audioEntryBuilder(AudioCodec.EAC3).maxChannels(6).build()),
            "opus 6 450000, eac3 6 448000, eac3 6 384000, " + STEREO_STEPS),
        Arguments.of(
            "flac 6 2500000",
            List.of(
                aacEntryBuilder().build(),
                audioEntryBuilder(AudioCodec.FLAC).maxChannels(6).build(),
                audioEntryBuilder(AudioCodec.AC3).maxChannels(6).build()),
            "flac 6 2500000, ac3 6 448000, ac3 6 384000, " + STEREO_STEPS),
        Arguments.of(
            "aac 6 384000",
            List.of(
                aacEntryBuilder().maxChannels(6).build(),
                audioEntryBuilder(AudioCodec.EAC3).maxChannels(6).build()),
            "aac 6 384000, " + STEREO_STEPS),
        Arguments.of(
            "eac3 6 384000", entries(AudioCodec.EAC3, 6), "eac3 6 384000, " + STEREO_STEPS),
        Arguments.of(
            "aac 2 256000", List.of(aacEntryBuilder().build()), "aac 2 256000, " + STEREO_STEPS),
        Arguments.of("ac3 1 96000", entries(AudioCodec.AC3, 6), "ac3 1 96000, aac 1 64000"));
  }

  @Test
  @DisplayName(
      "Should step a surround track straight to AAC stereo when the player declares only stereo AAC")
  void shouldStepASurroundTrackStraightToAacStereoWhenThePlayerDeclaresOnlyStereoAac() {
    var steps =
        AudioSteps.below(
            copy("flac 6 2500000"),
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

    var steps = AudioSteps.below(copy("ac3 6 640000"), declaration);

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

    var steps = AudioSteps.below(encode("aac 4 128000"), declaration);

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

  private static AudioDecision encode(String described) {
    return decision(described).mode(AudioMode.TRANSCODE).build();
  }

  private static AudioDecision copy(String described) {
    return decision(described)
        .mode(AudioMode.COPY)
        .sourceCodecString(Optional.of("copied"))
        .build();
  }

  // "codec channels bitrate", such as "eac3 6 768000".
  private static AudioDecision.AudioDecisionBuilder decision(String described) {
    var parts = described.split(" ");
    return AudioDecision.builder()
        .codec(parts[0])
        .channels(Integer.parseInt(parts[1]))
        .bitrate(Long.parseLong(parts[2]));
  }

  private static String describe(AudioSteps steps) {
    return String.join(
        ", ",
        steps.highestFirst().stream()
            .map(step -> step.codec() + " " + step.channels() + " " + step.bitrate())
            .toList());
  }
}
