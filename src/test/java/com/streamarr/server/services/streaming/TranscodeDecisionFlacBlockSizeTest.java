package com.streamarr.server.services.streaming;

import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.aacEntryBuilder;
import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.audioEntryBuilder;
import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.defaultDeclarationBuilder;
import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.h264EntryBuilder;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.streaming.AudioCapabilityEntry;
import com.streamarr.server.domain.streaming.AudioCodec;
import com.streamarr.server.domain.streaming.AudioDecision;
import com.streamarr.server.domain.streaming.AudioMode;
import com.streamarr.server.domain.streaming.AudioStreamProperties;
import com.streamarr.server.domain.streaming.ChannelLayout;
import com.streamarr.server.domain.streaming.ClientCapabilityDeclaration;
import com.streamarr.server.domain.streaming.DecisionReason;
import com.streamarr.server.domain.streaming.SourceContainer;
import com.streamarr.server.domain.streaming.StreamInfo;
import com.streamarr.server.domain.streaming.VideoQuality;
import com.streamarr.server.fixtures.ProbeFixture;
import com.streamarr.server.fixtures.StreamSessionFixture;
import com.streamarr.server.fixtures.VideoPlaybackFactsFixture;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * How a FLAC capability entry's minimum block size binds an HLS copy and direct play of a FLAC
 * track (ADR 0043, ADR 0049). Each source's first audio track is a 5.1 FLAC track, read by a client
 * whose FLAC entry accepts 6 channels. A refused copy takes the server's E-AC-3, AC-3 or AAC encode
 * until the FLAC encode of streamarr/streamarr-server#503 replaces it.
 */
@Tag("UnitTest")
@DisplayName("Transcode Decision FLAC Block Size Tests")
class TranscodeDecisionFlacBlockSizeTest {

  // The minimum that Apple's CoreMedia needs: it drops a FLAC track with smaller blocks (ADR 0049).
  private static final int APPLE_MIN_BLOCK_SIZE = 192;
  private static final int SURROUND = 6;
  private static final Set<String> EVERY_VIDEO_CODEC_FAMILY = Set.of("h264", "hevc", "av1");

  private final TranscodeDecisionService service =
      new TranscodeDecisionService(
          StreamingProperties.builder().targetSegmentDuration(Duration.ofSeconds(6)).build(),
          new SimpleMeterRegistry());

  @ParameterizedTest(name = "{0}")
  @MethodSource("blockSizesBelowTheMinimumOrUnknown")
  @DisplayName(
      "Should encode a FLAC track when its block size is below the entry's minimum or unknown")
  void shouldEncodeAFlacTrackWhenItsBlockSizeIsBelowTheEntrysMinimumOrUnknown(
      String scenario, StreamInfo track) {
    var declaration = declaringFlac(flacEntry().minBlockSize(OptionalInt.of(APPLE_MIN_BLOCK_SIZE)));

    var audio = decideAudio(track, declaration);

    assertThat(audio.mode()).isEqualTo(AudioMode.TRANSCODE);
    assertThat(audio.codec()).isEqualTo("eac3");
    assertThat(audio.channelLayout()).contains(ChannelLayout.FIVE_POINT_ONE_SIDE);
  }

  static Stream<Arguments> blockSizesBelowTheMinimumOrUnknown() {
    return Stream.of(
        Arguments.of("a block size of 144", flacTrack(OptionalInt.of(144))),
        Arguments.of("a block size one below the minimum", flacTrack(OptionalInt.of(191))),
        Arguments.of("no STREAMINFO block size", flacTrack(OptionalInt.empty())),
        Arguments.of("a probe version before 7", flacTrackWithoutAudioProperties()));
  }

  @ParameterizedTest(name = "a block size of {0}")
  @MethodSource("blockSizesAtOrAboveTheMinimum")
  @DisplayName("Should copy a FLAC track when its block size reaches the entry's minimum")
  void shouldCopyAFlacTrackWhenItsBlockSizeReachesTheEntrysMinimum(int blockSize) {
    var declaration = declaringFlac(flacEntry().minBlockSize(OptionalInt.of(APPLE_MIN_BLOCK_SIZE)));

    var audio = decideAudio(flacTrack(OptionalInt.of(blockSize)), declaration);

    assertThat(audio.mode()).isEqualTo(AudioMode.COPY);
    assertThat(audio.codec()).isEqualTo("flac");
    assertThat(audio.channels()).isEqualTo(SURROUND);
  }

  static Stream<Integer> blockSizesAtOrAboveTheMinimum() {
    return Stream.of(APPLE_MIN_BLOCK_SIZE, 4_608);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("everyBlockSize")
  @DisplayName("Should copy a FLAC track of any block size when the entry gives no minimum")
  void shouldCopyAFlacTrackOfAnyBlockSizeWhenTheEntryGivesNoMinimum(
      String scenario, StreamInfo track) {
    var audio = decideAudio(track, declaringFlac(flacEntry()));

    assertThat(audio.mode()).isEqualTo(AudioMode.COPY);
    assertThat(audio.codec()).isEqualTo("flac");
  }

  static Stream<Arguments> everyBlockSize() {
    return Stream.of(
        Arguments.of("a block size of 144", flacTrack(OptionalInt.of(144))),
        Arguments.of("a block size of 4,608", flacTrack(OptionalInt.of(4_608))),
        Arguments.of("an unknown block size", flacTrack(OptionalInt.empty())));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("encodesInPlaceOfARefusedCopy")
  @DisplayName(
      "Should take the server's E-AC-3, AC-3 or AAC encode when the FLAC copy's block size is"
          + " refused")
  void shouldTakeTheServersEncodeWhenTheFlacCopysBlockSizeIsRefused(
      String scenario, List<AudioCapabilityEntry> encodeEntries, String codec, int channels) {
    var entries = new ArrayList<>(encodeEntries);
    entries.add(flacEntry().minBlockSize(OptionalInt.of(APPLE_MIN_BLOCK_SIZE)).build());
    var declaration = defaultDeclarationBuilder().audio(entries).build();

    var audio = decideAudio(flacTrack(OptionalInt.of(144)), declaration);

    assertThat(audio.mode()).isEqualTo(AudioMode.TRANSCODE);
    assertThat(audio.codec()).isEqualTo(codec);
    assertThat(audio.channels()).isEqualTo(channels);
  }

  static Stream<Arguments> encodesInPlaceOfARefusedCopy() {
    var stereoAac = aacEntryBuilder().build();
    return Stream.of(
        Arguments.of(
            "E-AC-3 when the client declares it",
            List.of(stereoAac, surround(AudioCodec.EAC3), surround(AudioCodec.AC3)),
            "eac3",
            SURROUND),
        Arguments.of(
            "AC-3 without E-AC-3", List.of(stereoAac, surround(AudioCodec.AC3)), "ac3", SURROUND),
        Arguments.of("stereo AAC without either", List.of(stereoAac), "aac", 2));
  }

  // ADR 0049: an output that needs values from two capability entries fits neither.
  @Test
  @DisplayName(
      "Should encode a FLAC track when one entry accepts its channels and another its block size")
  void shouldEncodeAFlacTrackWhenOneEntryAcceptsItsChannelsAndAnotherItsBlockSize() {
    var declaration =
        defaultDeclarationBuilder()
            .audio(
                List.of(
                    aacEntryBuilder().build(),
                    surround(AudioCodec.EAC3),
                    audioEntryBuilder(AudioCodec.FLAC).maxChannels(2).build(),
                    flacEntry().minBlockSize(OptionalInt.of(4_608)).build()))
            .build();

    var audio = decideAudio(flacTrack(OptionalInt.of(144)), declaration);

    assertThat(audio.mode()).isEqualTo(AudioMode.TRANSCODE);
    assertThat(audio.codec()).isEqualTo("eac3");
  }

  @Test
  @DisplayName(
      "Should direct-play a file with a FLAC track when its block size reaches the entry's minimum")
  void shouldDirectPlayAFileWithAFlacTrackWhenItsBlockSizeReachesTheEntrysMinimum() {
    var request = directPlayRequest(flacTrack(OptionalInt.of(4_608)));

    assertThat(service.decideDirectPlay(request))
        .isEqualTo(new DirectPlayChoice.Accepted(SourceContainer.MATROSKA));
  }

  // Direct play hands the player the same FLAC bytes as an HLS copy, so CoreMedia drops them too.
  @ParameterizedTest(name = "{0}")
  @MethodSource("blockSizesBelowTheMinimumOrUnknown")
  @DisplayName(
      "Should play a file over HLS when its FLAC track's block size is below the entry's minimum or"
          + " unknown")
  void shouldPlayAFileOverHlsWhenItsFlacTracksBlockSizeIsBelowTheEntrysMinimumOrUnknown(
      String scenario, StreamInfo track) {
    assertThat(service.decideDirectPlay(directPlayRequest(track)))
        .isEqualTo(
            new DirectPlayChoice.Refused(List.of(DecisionReason.DIRECT_PLAY_AUDIO_NOT_DECLARED)));
  }

  private static DirectPlayRequest directPlayRequest(StreamInfo track) {
    var matroska = List.of(SourceContainer.MATROSKA);
    var declaration =
        ClientCapabilityDeclaration.builder()
            .video(List.of(h264EntryBuilder().directPlayContainers(matroska).build()))
            .audio(
                List.of(
                    aacEntryBuilder().build(),
                    flacEntry()
                        .directPlayContainers(matroska)
                        .minBlockSize(OptionalInt.of(APPLE_MIN_BLOCK_SIZE))
                        .build()))
            .build();
    var source =
        StreamSessionFixture.defaultProbeBuilder()
            .containerFormat(Optional.of("matroska,webm"))
            .videoPlaybackFacts(
                Optional.of(
                    VideoPlaybackFactsFixture.sdrH264FactsBuilder()
                        .streamIndex(0)
                        .videoProperties(
                            Optional.of(
                                VideoPlaybackFactsFixture.h264HighPropertiesBuilder().build()))
                        .build()))
            .audioCodec("flac")
            .streams(List.of(track))
            .build();
    return DirectPlayRequest.builder()
        .source(source)
        .capabilityDeclaration(declaration)
        .videoFormatConstraint(VideoFormatConstraint.DIRECT_PLAY_OR_COPY_OR_ENCODE)
        .viewerLimits(ViewerLimits.builder().quality(VideoQuality.ORIGINAL).build())
        .build();
  }

  private AudioDecision decideAudio(StreamInfo track, ClientCapabilityDeclaration declaration) {
    var source =
        StreamSessionFixture.defaultProbeBuilder()
            .audioCodec("flac")
            .audioChannels(OptionalInt.of(SURROUND))
            .audioBitrate(OptionalLong.empty())
            .streams(List.of(track))
            .build();
    return service
        .decide(
            TranscodeDecisionRequest.builder()
                .source(source)
                .capabilityDeclaration(declaration)
                .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                .build())
        .orElseThrow()
        .audioDecision();
  }

  private static ClientCapabilityDeclaration declaringFlac(
      AudioCapabilityEntry.AudioCapabilityEntryBuilder flac) {
    return defaultDeclarationBuilder()
        .audio(List.of(aacEntryBuilder().build(), surround(AudioCodec.EAC3), flac.build()))
        .build();
  }

  private static AudioCapabilityEntry.AudioCapabilityEntryBuilder flacEntry() {
    return audioEntryBuilder(AudioCodec.FLAC).maxChannels(SURROUND);
  }

  private static AudioCapabilityEntry surround(AudioCodec codec) {
    return audioEntryBuilder(codec).maxChannels(SURROUND).build();
  }

  private static StreamInfo flacTrack(OptionalInt blockSize) {
    return flacTrackBuilder()
        .audioProperties(
            Optional.of(
                AudioStreamProperties.builder().flacMinimumBlockSizeSamples(blockSize).build()))
        .build();
  }

  private static StreamInfo flacTrackWithoutAudioProperties() {
    return flacTrackBuilder().build();
  }

  private static StreamInfo.StreamInfoBuilder flacTrackBuilder() {
    return ProbeFixture.audioStreamBuilder("flac").index(1).channels(OptionalInt.of(SURROUND));
  }
}
