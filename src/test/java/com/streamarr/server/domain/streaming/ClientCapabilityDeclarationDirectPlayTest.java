package com.streamarr.server.domain.streaming;

import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.aacEntryBuilder;
import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.audioEntryBuilder;
import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.h264EntryBuilder;
import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.hevcEntryBuilder;
import static com.streamarr.server.fixtures.ProbeFixture.audioStreamBuilder;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.fixtures.StreamSessionFixture;
import com.streamarr.server.fixtures.VideoPlaybackFactsFixture;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * How a client capability declaration covers a file for direct play (ADR 0049): one video entry
 * accepts the video stream and lists the file's source container, and every audio track of the file
 * is covered by an audio entry that lists the container.
 */
@Tag("UnitTest")
@DisplayName("Client Capability Declaration Direct Play Tests")
class ClientCapabilityDeclarationDirectPlayTest {

  private static final SourceContainer CONTAINER = SourceContainer.MATROSKA;

  @Test
  @DisplayName("Should cover the file when its video and its only audio track are covered")
  void shouldCoverTheFileWhenItsVideoAndItsOnlyAudioTrackAreCovered() {
    var declaration = declaration(List.of(directPlayAac().build()));

    assertThat(declaration.acceptsDirectPlay(source(stereoAac().build()), CONTAINER)).isTrue();
  }

  @Test
  @DisplayName("Should cover a file without audio when its video is covered")
  void shouldCoverAFileWithoutAudioWhenItsVideoIsCovered() {
    var declaration = declaration(List.of(directPlayAac().build()));

    assertThat(declaration.acceptsDirectPlay(source(), CONTAINER)).isTrue();
  }

  @Test
  @DisplayName("Should cover the file when a separate entry covers each of its audio tracks")
  void shouldCoverTheFileWhenASeparateEntryCoversEachOfItsAudioTracks() {
    var declaration =
        declaration(
            List.of(
                directPlayAac().build(),
                audioEntryBuilder(AudioCodec.AC3)
                    .maxChannels(6)
                    .directPlayContainers(List.of(CONTAINER))
                    .build()));

    assertThat(declaration.acceptsDirectPlay(source(stereoAac().build(), surroundAc3()), CONTAINER))
        .isTrue();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("uncoveredAudio")
  @DisplayName("Should refuse the file when one of its audio tracks is not covered")
  void shouldRefuseTheFileWhenOneOfItsAudioTracksIsNotCovered(
      String scenario, List<AudioCapabilityEntry> audio, List<StreamInfo> tracks) {
    var declaration = declaration(audio);

    assertThat(declaration.acceptsDirectPlay(source(tracks.toArray(StreamInfo[]::new)), CONTAINER))
        .isFalse();
  }

  static Stream<Arguments> uncoveredAudio() {
    var aacOnly = List.of(directPlayAac().build());
    return Stream.of(
        Arguments.of(
            "a second track in a codec without an entry",
            aacOnly,
            List.of(stereoAac().build(), surroundAc3())),
        Arguments.of(
            "more channels than the entry accepts",
            aacOnly,
            List.of(stereoAac().channels(OptionalInt.of(6)).build())),
        Arguments.of(
            "an unknown channel count",
            aacOnly,
            List.of(stereoAac().channels(OptionalInt.empty()).build())),
        Arguments.of(
            "an AAC profile that the declaration does not cover",
            aacOnly,
            List.of(stereoAac().codecString(Optional.of("mp4a.40.1")).build())),
        Arguments.of(
            "a codec that no entry can name",
            aacOnly,
            List.of(audioStreamBuilder("dts").channels(OptionalInt.of(6)).build())),
        Arguments.of(
            "an entry that applies to HLS alone",
            List.of(aacEntryBuilder().build()),
            List.of(stereoAac().build())),
        Arguments.of(
            "an entry that lists another container",
            List.of(directPlayAac().directPlayContainers(List.of(SourceContainer.AVI)).build()),
            List.of(stereoAac().build())));
  }

  @Test
  @DisplayName("Should refuse the file when the entry that accepts its video lists no container")
  void shouldRefuseTheFileWhenTheEntryThatAcceptsItsVideoListsNoContainer() {
    var declaration =
        ClientCapabilityDeclaration.builder()
            .video(
                List.of(
                    h264EntryBuilder().build(),
                    hevcEntryBuilder().directPlayContainers(List.of(CONTAINER)).build(),
                    h264EntryBuilder()
                        .maxWidth(1280)
                        .maxHeight(720)
                        .directPlayContainers(List.of(CONTAINER))
                        .build()))
            .audio(List.of(directPlayAac().build()))
            .build();

    assertThat(declaration.acceptsDirectPlay(source(stereoAac().build()), CONTAINER)).isFalse();
  }

  @Test
  @DisplayName("Should refuse the file when its video stream has no playback facts")
  void shouldRefuseTheFileWhenItsVideoStreamHasNoPlaybackFacts() {
    var declaration = declaration(List.of(directPlayAac().build()));
    var source =
        StreamSessionFixture.defaultProbeBuilder()
            .videoPlaybackFacts(Optional.empty())
            .streams(List.of(stereoAac().build()))
            .build();

    assertThat(declaration.acceptsDirectPlay(source, CONTAINER)).isFalse();
  }

  private static ClientCapabilityDeclaration declaration(List<AudioCapabilityEntry> audio) {
    return ClientCapabilityDeclaration.builder()
        .video(List.of(h264EntryBuilder().directPlayContainers(List.of(CONTAINER)).build()))
        .audio(audio)
        .build();
  }

  private static AudioCapabilityEntry.AudioCapabilityEntryBuilder directPlayAac() {
    return aacEntryBuilder().directPlayContainers(List.of(CONTAINER));
  }

  private static StreamInfo.StreamInfoBuilder stereoAac() {
    return audioStreamBuilder("aac").channels(OptionalInt.of(2));
  }

  private static StreamInfo surroundAc3() {
    return audioStreamBuilder("ac3").channels(OptionalInt.of(6)).build();
  }

  private static MediaProbe source(StreamInfo... audio) {
    var facts =
        VideoPlaybackFactsFixture.sdrH264FactsBuilder()
            .streamIndex(0)
            .videoProperties(
                Optional.of(VideoPlaybackFactsFixture.h264HighPropertiesBuilder().build()))
            .build();
    return StreamSessionFixture.defaultProbeBuilder()
        .videoPlaybackFacts(Optional.of(facts))
        .streams(List.of(audio))
        .build();
  }
}
