package com.streamarr.server.domain.streaming;

import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.aacEntryBuilder;
import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.audioEntryBuilder;
import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.defaultDeclarationBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import lombok.Builder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("UnitTest")
@DisplayName("HLS Audio Encodes Tests")
class HlsAudioEncodesTest {

  // Two AC-3 entries accept up to 2 and up to 6 channels, so AC-3 accepts up to 6.
  @Test
  @DisplayName(
      "Should take the largest maximum of a codec's entries for HLS when several entries name it")
  void shouldTakeTheLargestMaximumOfACodecsEntriesForHlsWhenSeveralEntriesNameIt() {
    var declaration =
        defaultDeclarationBuilder()
            .audio(
                List.of(
                    aacEntryBuilder().build(),
                    audioEntryBuilder(AudioCodec.AC3).maxChannels(2).build(),
                    audioEntryBuilder(AudioCodec.AC3).maxChannels(6).build()))
            .build();

    assertThat(HlsAudioEncodes.channelMaximum(declaration, "ac3")).hasValue(6);
  }

  @Test
  @DisplayName("Should accept no channels for a codec when only an entry outside HLS names it")
  void shouldAcceptNoChannelsForACodecWhenOnlyAnEntryOutsideHlsNamesIt() {
    var declaration =
        defaultDeclarationBuilder()
            .audio(
                List.of(
                    aacEntryBuilder().build(),
                    audioEntryBuilder(AudioCodec.EAC3).maxChannels(8).hls(false).build()))
            .build();

    assertThat(HlsAudioEncodes.channelMaximum(declaration, "eac3")).isEmpty();
  }

  // FFmpeg's E-AC-3 encoder stops at 5.1, so a 7.1 track encodes E-AC-3 5.1.
  @ParameterizedTest
  @ValueSource(ints = {8, 6})
  @DisplayName(
      "Should encode E-AC-3 with at most 5.1 when the player declares 7.1 E-AC-3 and the track has"
          + " more than two channels")
  void shouldEncodeEac3WithAtMost51WhenThePlayerDeclares71Eac3AndTheTrackHasMoreThanTwoChannels(
      int trackChannels) {
    assertThat(HlsAudioEncodes.forTrack(stereoAacAnd71Eac3(), trackOf(trackChannels)))
        .isEqualTo(encode("eac3").channels(6).bitrate(768_000L).build());
  }

  static Stream<AudioDecision> aacEncodesOfStereoAndMonoTracks() {
    return Stream.of(
        encode("aac").channels(2).bitrate(128_000L).build(),
        encode("aac").channels(1).bitrate(64_000L).build());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("aacEncodesOfStereoAndMonoTracks")
  @DisplayName(
      "Should encode AAC with the track's channels when the track has at most two channels")
  void shouldEncodeAacWithTheTracksChannelsWhenTheTrackHasAtMostTwoChannels(
      AudioDecision expected) {
    assertThat(HlsAudioEncodes.forTrack(stereoAacAnd71Eac3(), trackOf(expected.channels())))
        .isEqualTo(expected);
  }

  // FFmpeg's AC-3 and E-AC-3 encoders stop at 5.1; its AAC encoder keeps 7.1.
  static Stream<MultichannelEncode> codecsTheServerEncodesInSurround() {
    return Stream.of(
        MultichannelEncode.builder()
            .codec("aac")
            .trackChannels(8)
            .encode(encode("aac").channels(8).bitrate(512_000L).build())
            .build(),
        MultichannelEncode.builder()
            .codec("ac3")
            .trackChannels(8)
            .encode(encode("ac3").channels(6).bitrate(640_000L).build())
            .build(),
        MultichannelEncode.builder()
            .codec("eac3")
            .trackChannels(6)
            .encode(encode("eac3").channels(6).bitrate(768_000L).build())
            .build());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("codecsTheServerEncodesInSurround")
  @DisplayName(
      "Should encode the codec with the track's channels, up to 5.1 for AC-3 and E-AC-3, when the"
          + " server encodes the codec")
  void shouldEncodeTheCodecWithTheTracksChannelsUpTo51ForAc3AndEac3WhenTheServerEncodesTheCodec(
      MultichannelEncode expected) {
    var encode =
        HlsAudioEncodes.multichannelEncode(
            everyCodecIn71(), expected.codec(), trackOf(expected.trackChannels()));

    assertThat(encode).contains(expected.encode());
  }

  static Stream<MultichannelEncode> encodesThatKeepAtMostTwoChannels() {
    return Stream.of(
        MultichannelEncode.builder().codec("ac3").trackChannels(2).build(),
        MultichannelEncode.builder().codec("flac").trackChannels(6).build());
  }

  // The player declares FLAC 7.1, but the server does not encode FLAC.
  @ParameterizedTest(name = "{0}")
  @MethodSource("encodesThatKeepAtMostTwoChannels")
  @DisplayName(
      "Should offer no multichannel encode when the track has at most two channels or the server"
          + " does not encode the codec")
  void
      shouldOfferNoMultichannelEncodeWhenTheTrackHasAtMostTwoChannelsOrTheServerDoesNotEncodeTheCodec(
          MultichannelEncode request) {
    assertThat(
            HlsAudioEncodes.multichannelEncode(
                everyCodecIn71(), request.codec(), trackOf(request.trackChannels())))
        .isEmpty();
  }

  // A track with more channels than a maximum of 3 or 4 has no layout of its own at that count.
  @ParameterizedTest
  @ValueSource(ints = {3, 4})
  @DisplayName(
      "Should encode AAC stereo when a channel maximum of 3 or 4 caps every codec below the track's"
          + " channels")
  void shouldEncodeAacStereoWhenAChannelMaximumOf3Or4CapsEveryCodecBelowTheTracksChannels(
      int channelMaximum) {
    assertThat(HlsAudioEncodes.forTrack(everyCodecWithMaximum(channelMaximum), trackOf(6)))
        .isEqualTo(encode("aac").channels(2).bitrate(128_000L).build());
  }

  static Stream<MultichannelEncode> encodesThatAMaximumOf5Caps() {
    return Stream.of(
        MultichannelEncode.builder()
            .codec("aac")
            .trackChannels(6)
            .encode(encode("aac").channels(5).bitrate(384_000L).build())
            .build(),
        MultichannelEncode.builder()
            .codec("ac3")
            .trackChannels(8)
            .encode(encode("ac3").channels(5).bitrate(640_000L).build())
            .build(),
        MultichannelEncode.builder()
            .codec("eac3")
            .trackChannels(6)
            .encode(encode("eac3").channels(5).bitrate(768_000L).build())
            .build());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("encodesThatAMaximumOf5Caps")
  @DisplayName(
      "Should encode 5 channels at the codec's 5.1 bitrate when a channel maximum of 5 caps the"
          + " track")
  void shouldEncode5ChannelsAtTheCodecs51BitrateWhenAChannelMaximumOf5CapsTheTrack(
      MultichannelEncode expected) {
    var encode =
        HlsAudioEncodes.multichannelEncode(
            everyCodecWithMaximum(5), expected.codec(), trackOf(expected.trackChannels()));

    assertThat(encode).contains(expected.encode());
  }

  @Test
  @DisplayName("Should encode AAC 5.1 when a channel maximum of 7 caps an 8-channel track")
  void shouldEncodeAac51WhenAChannelMaximumOf7CapsAn8ChannelTrack() {
    assertThat(HlsAudioEncodes.multichannelEncode(everyCodecWithMaximum(7), "aac", trackOf(8)))
        .contains(encode("aac").channels(6).bitrate(384_000L).build());
  }

  @Test
  @DisplayName(
      "Should refuse to choose an encode when the declaration, the codec or the track is missing")
  void shouldRefuseToChooseAnEncodeWhenTheDeclarationTheCodecOrTheTrackIsMissing() {
    var declaration = defaultDeclarationBuilder().build();
    var track = trackOf(2);

    assertThatNullPointerException()
        .isThrownBy(() -> HlsAudioEncodes.channelMaximum(null, "aac"))
        .withMessageContaining("declaration");
    assertThatNullPointerException()
        .isThrownBy(() -> HlsAudioEncodes.channelMaximum(declaration, null))
        .withMessageContaining("codec");
    assertThatNullPointerException()
        .isThrownBy(() -> HlsAudioEncodes.forTrack(null, track))
        .withMessageContaining("declaration");
    assertThatNullPointerException()
        .isThrownBy(() -> HlsAudioEncodes.forTrack(declaration, null))
        .withMessageContaining("track");
    assertThatNullPointerException()
        .isThrownBy(() -> HlsAudioEncodes.aacForTrack(null, track))
        .withMessageContaining("declaration");
    assertThatNullPointerException()
        .isThrownBy(() -> HlsAudioEncodes.aacForTrack(declaration, null))
        .withMessageContaining("track");
    assertThatNullPointerException()
        .isThrownBy(() -> HlsAudioEncodes.multichannelEncode(null, "ac3", track))
        .withMessageContaining("declaration");
    assertThatNullPointerException()
        .isThrownBy(() -> HlsAudioEncodes.multichannelEncode(declaration, null, track))
        .withMessageContaining("codec");
    assertThatNullPointerException()
        .isThrownBy(() -> HlsAudioEncodes.multichannelEncode(declaration, "ac3", null))
        .withMessageContaining("track");
  }

  // A track whose layout the probe did not store.
  private static AudioTrackChannels trackOf(int channels) {
    return new AudioTrackChannels(channels, Optional.empty());
  }

  private static ClientCapabilityDeclaration stereoAacAnd71Eac3() {
    return defaultDeclarationBuilder()
        .audio(
            List.of(
                aacEntryBuilder().build(),
                audioEntryBuilder(AudioCodec.EAC3).maxChannels(8).build()))
        .build();
  }

  private static ClientCapabilityDeclaration everyCodecIn71() {
    return everyCodecWithMaximum(8);
  }

  // AAC, AC-3, E-AC-3 and FLAC, each with the same channel maximum.
  private static ClientCapabilityDeclaration everyCodecWithMaximum(int channelMaximum) {
    return defaultDeclarationBuilder()
        .audio(
            Stream.of(AudioCodec.AAC, AudioCodec.AC3, AudioCodec.EAC3, AudioCodec.FLAC)
                .map(codec -> audioEntryBuilder(codec).maxChannels(channelMaximum).build())
                .toList())
        .build();
  }

  private static AudioDecision.AudioDecisionBuilder encode(String codec) {
    return AudioDecision.builder().mode(AudioMode.TRANSCODE).codec(codec);
  }

  @Builder
  record MultichannelEncode(String codec, int trackChannels, AudioDecision encode) {}
}
