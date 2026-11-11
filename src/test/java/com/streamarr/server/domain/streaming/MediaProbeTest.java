package com.streamarr.server.domain.streaming;

import static com.streamarr.server.fixtures.ProbeFixture.audioStreamBuilder;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.media.LearnedStreamEnd;
import com.streamarr.server.domain.media.SourceFileSnapshot;
import com.streamarr.server.fixtures.StreamSessionFixture;
import com.streamarr.server.fixtures.VideoPlaybackFactsFixture;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("UnitTest")
@DisplayName("Media Probe Tests")
class MediaProbeTest {

  private static final String MATROSKA_DEMUXER = "matroska,webm";
  private static final String MOV_DEMUXER = "mov,mp4,m4a,3gp,3g2,mj2";

  @ParameterizedTest
  @ValueSource(strings = {"hdmv_pgs_subtitle", "dvd_subtitle", "dvb_subtitle"})
  @DisplayName(
      "Should find a forced image subtitle when its language matches the first audio track")
  void shouldFindAForcedImageSubtitleWhenItsLanguageMatchesTheFirstAudioTrack(String codec) {
    var probe = probe(audio("eng"), audio("fra"), subtitle(codec, "eng").isForced(true).build());

    assertThat(probe.hasForcedImageSubtitleInAnAudioLanguage()).isTrue();
  }

  @Test
  @DisplayName("Should find a forced image subtitle when its language matches a later audio track")
  void shouldFindAForcedImageSubtitleWhenItsLanguageMatchesALaterAudioTrack() {
    var probe =
        probe(
            audio("eng"),
            audio("fra"),
            subtitle("hdmv_pgs_subtitle", "fra").isForced(true).build());

    assertThat(probe.hasForcedImageSubtitleInAnAudioLanguage()).isTrue();
  }

  @Test
  @DisplayName("Should match the languages without regard to case")
  void shouldMatchTheLanguagesWithoutRegardToCase() {
    var probe = probe(audio("eng"), subtitle("hdmv_pgs_subtitle", "ENG").isForced(true).build());

    assertThat(probe.hasForcedImageSubtitleInAnAudioLanguage()).isTrue();
  }

  // ISO 639-2 gives 20 languages a bibliographic and a terminological code, and files carry either,
  // or the ISO 639-1 code.
  @ParameterizedTest(name = "audio {0}, subtitle {1}")
  @CsvSource({"fra, fre", "fre, fra", "deu, ger", "zho, chi", "en, eng", "eng, en", "fr, fre"})
  @DisplayName(
      "Should find a forced image subtitle when its language code is an alias of the audio's")
  void shouldFindAForcedImageSubtitleWhenItsLanguageCodeIsAnAliasOfTheAudios(
      String audioLanguage, String subtitleLanguage) {
    var probe =
        probe(
            audio("eng"),
            audio(audioLanguage),
            subtitle("hdmv_pgs_subtitle", subtitleLanguage).isForced(true).build());

    assertThat(probe.hasForcedImageSubtitleInAnAudioLanguage()).isTrue();
  }

  @Test
  @DisplayName("Should find no forced subtitle when two unknown two-letter codes differ")
  void shouldFindNoForcedSubtitleWhenTwoUnknownTwoLetterCodesDiffer() {
    var probe = probe(audio("qq"), subtitle("hdmv_pgs_subtitle", "qz").isForced(true).build());

    assertThat(probe.hasForcedImageSubtitleInAnAudioLanguage()).isFalse();
  }

  @Test
  @DisplayName("Should find no forced subtitle when the forced image track matches no audio track")
  void shouldFindNoForcedSubtitleWhenTheForcedImageTrackMatchesNoAudioTrack() {
    var probe =
        probe(
            audio("eng"),
            audio("fra"),
            subtitle("hdmv_pgs_subtitle", "deu").isForced(true).build());

    assertThat(probe.hasForcedImageSubtitleInAnAudioLanguage()).isFalse();
  }

  @Test
  @DisplayName("Should find no forced subtitle when the image track in the language is not forced")
  void shouldFindNoForcedSubtitleWhenTheImageTrackInTheLanguageIsNotForced() {
    var probe = probe(audio("eng"), subtitle("hdmv_pgs_subtitle", "eng").build());

    assertThat(probe.hasForcedImageSubtitleInAnAudioLanguage()).isFalse();
  }

  @Test
  @DisplayName("Should find no forced subtitle when the forced track in the language is text")
  void shouldFindNoForcedSubtitleWhenTheForcedTrackInTheLanguageIsText() {
    var probe = probe(audio("eng"), subtitle("subrip", "eng").isForced(true).build());

    assertThat(probe.hasForcedImageSubtitleInAnAudioLanguage()).isFalse();
  }

  @Test
  @DisplayName("Should find no forced subtitle when no audio track names a language")
  void shouldFindNoForcedSubtitleWhenNoAudioTrackNamesALanguage() {
    var probe =
        probe(
            audioStreamBuilder("aac").build(),
            subtitle("hdmv_pgs_subtitle", "eng").isForced(true).build());

    assertThat(probe.hasForcedImageSubtitleInAnAudioLanguage()).isFalse();
  }

  // The session plays the first audio stream that the probe lists, and every audio decision reads
  // the probe's audio facts.
  @Test
  @DisplayName(
      "Should list the named audio track first and take the audio facts from it when a session"
          + " names a later track")
  void shouldListTheNamedAudioTrackFirstAndTakeTheAudioFactsFromItWhenASessionNamesALaterTrack() {
    var video = StreamInfo.builder().index(0).codecType("video").build();
    var english = audioIn("eng").index(1).build();
    var french =
        audioStreamBuilder("ac3")
            .index(2)
            .language(Optional.of("fra"))
            .channels(OptionalInt.of(6))
            .bitrate(OptionalLong.of(448_000))
            .audioProperties(
                Optional.of(
                    AudioStreamProperties.builder()
                        .channelLayout(Optional.of("5.1(side)"))
                        .build()))
            .build();
    var subtitle = subtitle("subrip", "eng").index(3).build();

    var named = probe(video, english, french, subtitle).withSessionAudioTrackAt(1);

    assertThat(named.streams()).containsExactly(video, french, english, subtitle);
    assertThat(named.audioCodec()).isEqualTo("ac3");
    assertThat(named.audioChannels()).hasValue(6);
    assertThat(named.audioBitrate()).hasValue(448_000);
    assertThat(named.audioChannelLayout()).contains("5.1(side)");
    assertThat(named.sessionAudioTrackPosition()).hasValue(1);
  }

  @Test
  @DisplayName("Should keep the probe when a session names its first audio track")
  void shouldKeepTheProbeWhenASessionNamesItsFirstAudioTrack() {
    var probe = probe(audioIn("eng").index(1).build(), audioIn("fra").index(2).build());

    assertThat(probe.withSessionAudioTrackAt(0)).isEqualTo(probe);
  }

  // Chromium and Firefox start on a Matroska file's first audio track whatever its default flags
  // say. The streams are listed second track first, as for a session that names the second track,
  // so the answer must come from stream order.
  @ParameterizedTest(name = "first default {0}, second default {1}")
  @CsvSource({"false, false", "true, false", "true, true", "false, true"})
  @DisplayName(
      "Should start every non-selecting player on a Matroska file's first audio track whatever its"
          + " default flags")
  void shouldStartEveryNonSelectingPlayerOnAMatroskaFilesFirstAudioTrackWhateverItsDefaultFlags(
      boolean firstIsDefault, boolean secondIsDefault) {
    var probe =
        probeIn(
            MATROSKA_DEMUXER,
            Optional.empty(),
            audioIn("fra").index(2).isDefault(secondIsDefault).build(),
            audioIn("eng").index(1).isDefault(firstIsDefault).build());

    assertThat(probe.startsOnTheFirstAudioTrack()).isTrue();
  }

  // In an MP4 or MOV file, Chromium starts on the first audio track whose enabled flag is set,
  // which ffprobe reports as the default flag, and Firefox on the first audio track. They agree
  // unless only a later track is enabled. The streams are listed second track first.
  @ParameterizedTest(name = "major brand {0}, first enabled {1}, second enabled {2}")
  @CsvSource({
    "isom, false, false, true",
    "isom, true, false, true",
    "isom, true, true, true",
    "isom, false, true, false",
    "'qt  ', false, true, false"
  })
  @DisplayName(
      "Should start every non-selecting player on an MP4 or MOV file's first audio track unless"
          + " only a later one is enabled")
  void
      shouldStartEveryNonSelectingPlayerOnAnMp4OrMovFilesFirstAudioTrackUnlessOnlyALaterOneIsEnabled(
          String majorBrand,
          boolean firstIsEnabled,
          boolean secondIsEnabled,
          boolean startsOnTheFirst) {
    var probe =
        probeIn(
            MOV_DEMUXER,
            Optional.of(majorBrand),
            audioIn("fra").index(2).isDefault(secondIsEnabled).build(),
            audioIn("eng").index(1).isDefault(firstIsEnabled).build());

    assertThat(probe.startsOnTheFirstAudioTrack()).isEqualTo(startsOnTheFirst);
  }

  // Once any audio or video track of an MP4 or MOV file is enabled, Chromium skips every audio
  // track that is not, so it plays no audio when only the video track is enabled.
  @ParameterizedTest(name = "first audio track enabled {0}")
  @CsvSource({"false, false", "true, true"})
  @DisplayName(
      "Should start every non-selecting player on an MP4 file's first audio track beside an enabled"
          + " video track only when that audio track is enabled")
  void
      shouldStartEveryNonSelectingPlayerOnAnMp4FilesFirstAudioTrackBesideAnEnabledVideoTrackOnlyWhenThatAudioTrackIsEnabled(
          boolean firstIsEnabled, boolean startsOnTheFirst) {
    var probe =
        probeIn(
            MOV_DEMUXER,
            Optional.of("isom"),
            StreamInfo.builder().index(0).codecType("video").isDefault(true).build(),
            audioIn("eng").index(1).isDefault(firstIsEnabled).build(),
            audioIn("fra").index(2).build());

    assertThat(probe.startsOnTheFirstAudioTrack()).isEqualTo(startsOnTheFirst);
  }

  // An MP4 file reads the enabled flags, so the answer must not depend on a first audio track.
  @Test
  @DisplayName("Should start a non-selecting player on the first audio track when there is none")
  void shouldStartANonSelectingPlayerOnTheFirstAudioTrackWhenThereIsNone() {
    var probe =
        probeIn(
            MOV_DEMUXER,
            Optional.of("isom"),
            StreamInfo.builder().index(0).codecType("video").isDefault(true).build(),
            subtitle("mov_text", "eng").index(1).build());

    assertThat(probe.startsOnTheFirstAudioTrack()).isTrue();
  }

  // BT.709 is the matrix of high-definition video and BT.601 that of standard definition. ADR 0013
  // draws the line at 1280 wide or more than 576 high.
  @ParameterizedTest(name = "{0}x{1} as {2}")
  @CsvSource({
    "1920, 1080, BT709",
    "1280, 720, BT709",
    "1280, 536, BT709",
    "1024, 577, BT709",
    "1279, 576, BT601",
    "720, 576, BT601",
    "720, 480, BT601"
  })
  @DisplayName(
      "Should assume BT.709 when an SDR picture that names no matrix is at least 1280 wide or more"
          + " than 576 high and BT.601 when it is smaller")
  void
      shouldAssumeBt709WhenAnSdrPictureThatNamesNoMatrixIsAtLeast1280WideOrMoreThan576HighAndBt601WhenItIsSmaller(
          int width, int height, AssumedSourceMatrix expected) {
    var probe =
        probeWithoutAMatrix(VideoPlaybackFactsFixture.SDR).width(width).height(height).build();

    assertThat(probe.assumedSourceMatrix()).contains(expected);
  }

  @Test
  @DisplayName(
      "Should assume a matrix by the picture's size when the compatible base of a picture that"
          + " names no matrix is unspecified")
  void
      shouldAssumeAMatrixByThePicturesSizeWhenTheCompatibleBaseOfAPictureThatNamesNoMatrixIsUnspecified() {
    var probe = probeWithoutAMatrix(VideoDynamicRange.UNSPECIFIED).width(1920).height(1080).build();

    assertThat(probe.assumedSourceMatrix()).contains(AssumedSourceMatrix.BT709);
  }

  @Test
  @DisplayName("Should assume no matrix when the video stream names one")
  void shouldAssumeNoMatrixWhenTheVideoStreamNamesOne() {
    var probe =
        probeOf(
            VideoPlaybackFactsFixture.sdrH264FactsBuilder()
                .videoProperties(
                    Optional.of(
                        VideoPlaybackFactsFixture.h264HighPropertiesBuilder()
                            .matrixCoefficients(Optional.of(MatrixCoefficients.SMPTE170M))
                            .build())));

    assertThat(probe.assumedSourceMatrix()).isEmpty();
  }

  // The size rule holds for SDR pictures only: PQ and HLG use BT.2020, and Dolby Vision without a
  // compatible base uses no YCbCr matrix.
  @ParameterizedTest(name = "{0}")
  @MethodSource("rangesThatAreNotSdr")
  @DisplayName(
      "Should assume no matrix when the compatible base of a picture that names no matrix is PQ,"
          + " HLG or none")
  void shouldAssumeNoMatrixWhenTheCompatibleBaseOfAPictureThatNamesNoMatrixIsPqHlgOrNone(
      String base, VideoDynamicRange range) {
    var probe = probeWithoutAMatrix(range).build();

    assertThat(probe.assumedSourceMatrix()).isEmpty();
  }

  static Stream<Arguments> rangesThatAreNotSdr() {
    return Stream.of(
        Arguments.of("PQ", VideoPlaybackFactsFixture.rangeWithoutEnhancement(CompatibleBase.PQ)),
        Arguments.of("HLG", VideoPlaybackFactsFixture.rangeWithoutEnhancement(CompatibleBase.HLG)),
        Arguments.of("none", VideoPlaybackFactsFixture.dolbyVisionProfile5()));
  }

  @Test
  @DisplayName("Should assume no matrix when the probe stored no color facts of the video stream")
  void shouldAssumeNoMatrixWhenTheProbeStoredNoColorFactsOfTheVideoStream() {
    var probe =
        probeOf(VideoPlaybackFactsFixture.sdrH264FactsBuilder().videoProperties(Optional.empty()));

    assertThat(probe.assumedSourceMatrix()).isEmpty();
  }

  @Test
  @DisplayName("Should assume no matrix when the probe stored no playback facts")
  void shouldAssumeNoMatrixWhenTheProbeStoredNoPlaybackFacts() {
    var probe =
        StreamSessionFixture.defaultProbeBuilder().videoPlaybackFacts(Optional.empty()).build();

    assertThat(probe.assumedSourceMatrix()).isEmpty();
  }

  // A stream of the given range whose stored color facts name no matrix.
  private static MediaProbe.MediaProbeBuilder probeWithoutAMatrix(VideoDynamicRange range) {
    return StreamSessionFixture.defaultProbeBuilder()
        .videoPlaybackFacts(
            Optional.of(
                VideoPlaybackFactsFixture.sdrH264FactsBuilder()
                    .dynamicRange(range)
                    .videoProperties(
                        Optional.of(
                            VideoPlaybackFactsFixture.h264HighPropertiesBuilder()
                                .matrixCoefficients(Optional.empty())
                                .build()))
                    .build()));
  }

  private static MediaProbe probeOf(VideoPlaybackFacts.VideoPlaybackFactsBuilder facts) {
    return StreamSessionFixture.defaultProbeBuilder()
        .videoPlaybackFacts(Optional.of(facts.build()))
        .build();
  }

  // A healthy file's audio can outlast its last picture. The session delivers both streams, so its
  // media ends where the audio ends, and a copy lists the audio after the last picture.
  @Test
  @DisplayName(
      "Should end the media at the audio's end when the session's audio outlasts the video")
  void shouldEndTheMediaAtTheAudiosEndWhenTheSessionsAudioOutlastsTheVideo() {
    var probe =
        probeLasting(Duration.ofSeconds(20), video(), audio(1))
            .withLearnedEndTimes(List.of(scannedEnd(0, 14_000), scannedEnd(1, 16_000)));

    assertThat(probe.mediaEnd()).isEqualTo(Duration.ofSeconds(16));
  }

  @Test
  @DisplayName(
      "Should end the media at the video's end when the video outlasts the session's audio")
  void shouldEndTheMediaAtTheVideosEndWhenTheVideoOutlastsTheSessionsAudio() {
    var probe =
        probeLasting(Duration.ofSeconds(20), video(), audio(1))
            .withLearnedEndTimes(List.of(scannedEnd(0, 14_167), scannedEnd(1, 14_101)));

    assertThat(probe.mediaEnd()).isEqualTo(Duration.ofMillis(14_167));
  }

  @Test
  @DisplayName("Should end the media at the video's end when the file has no audio stream")
  void shouldEndTheMediaAtTheVideosEndWhenTheFileHasNoAudioStream() {
    var probe =
        probeLasting(
                Duration.ofSeconds(20),
                video(),
                subtitle("subrip", "eng")
                    .index(1)
                    .endTime(Optional.of(Duration.ofSeconds(19)))
                    .build())
            .withLearnedEndTimes(List.of(scannedEnd(0, 14_167)));

    assertThat(probe.mediaEnd()).isEqualTo(Duration.ofMillis(14_167));
  }

  // The session delivers its own audio track only. A commentary track that runs on would otherwise
  // make every copy advertise time that it holds no media for.
  @Test
  @DisplayName(
      "Should end the media at the session's own streams when another audio track ends later")
  void shouldEndTheMediaAtTheSessionsOwnStreamsWhenAnotherAudioTrackEndsLater() {
    var probe =
        probeLasting(Duration.ofSeconds(30), video(), audio(1), audio(2))
            .withLearnedEndTimes(
                List.of(scannedEnd(0, 14_000), scannedEnd(1, 16_000), scannedEnd(2, 25_000)));

    assertThat(probe.mediaEnd()).isEqualTo(Duration.ofSeconds(16));
    assertThat(probe.withSessionAudioTrackAt(1).mediaEnd()).isEqualTo(Duration.ofSeconds(25));
  }

  @Test
  @DisplayName(
      "Should end the media at the container's duration when the server learned no end of the"
          + " session's audio")
  void shouldEndTheMediaAtTheContainersDurationWhenTheServerLearnedNoEndOfTheSessionsAudio() {
    var probe =
        probeLasting(Duration.ofSeconds(20), video(), audio(1))
            .withLearnedEndTimes(List.of(scannedEnd(0, 14_000)));

    assertThat(probe.mediaEnd()).isEqualTo(Duration.ofSeconds(20));
  }

  @Test
  @DisplayName(
      "Should end the media at the container's duration when the server learned no end of the"
          + " video")
  void shouldEndTheMediaAtTheContainersDurationWhenTheServerLearnedNoEndOfTheVideo() {
    var probe =
        probeLasting(Duration.ofSeconds(20), video(), audio(1))
            .withLearnedEndTimes(List.of(scannedEnd(1, 16_000)));

    assertThat(probe.mediaEnd()).isEqualTo(Duration.ofSeconds(20));
  }

  // A header's stream end is no better evidence than the header's duration. Only an end that the
  // server read from the stream's packets moves the media's end, so a stale stream header never
  // cuts a healthy file short.
  @Test
  @DisplayName(
      "Should end the media at the container's duration when only the container header states"
          + " where each stream ends")
  void
      shouldEndTheMediaAtTheContainersDurationWhenOnlyTheContainerHeaderStatesWhereEachStreamEnds() {
    var probe =
        probeLasting(
            Duration.ofSeconds(24), videoStatedToEndAt(4_000), audioStatedToEndAt(1, 4_000));

    assertThat(probe.mediaEnd()).isEqualTo(Duration.ofSeconds(24));
    assertThat(probe.videoEndTime()).contains(Duration.ofSeconds(4));
  }

  // A job attempt's FFmpeg can stop at a read error and still exit with status 0, so the end that
  // the attempt reports can lie before the real end: 12.291 s and 12.251 s of 20 s here. Such an
  // end fills a stream end that the header does not state, and never moves the media's end.
  @Test
  @DisplayName(
      "Should end the media at the container's duration when only a job attempt learned where each"
          + " stream ends")
  void shouldEndTheMediaAtTheContainersDurationWhenOnlyAJobAttemptLearnedWhereEachStreamEnds() {
    var probe =
        probeLasting(Duration.ofSeconds(20), video(), audio(1))
            .withLearnedEndTimes(
                List.of(
                    attemptLearnedEnd(0, Duration.ofMillis(12_291)),
                    attemptLearnedEnd(1, Duration.ofMillis(12_251))));

    assertThat(probe.mediaEnd()).isEqualTo(Duration.ofSeconds(20));
    assertThat(probe.videoEndTime()).contains(Duration.ofMillis(12_291));
  }

  // Packet times are not sample times. A Matroska file counts them in whole milliseconds, and an
  // AC-3 track's last packet can state less than its frame holds: a scan read 5.009 s where the
  // last decoded sample ends at 5.01356 s and the container states 5.014 s. A container that ends
  // less than 1 s after the scanned end is therefore not cut short, and its duration stands.
  @ParameterizedTest
  @CsvSource({"5014, 5009, 5014", "6008, 5009, 6008", "6009, 5009, 5009", "30000, 5009, 5009"})
  @DisplayName(
      "Should end the media at the container's duration unless the container runs at least 1 s"
          + " past the scanned end")
  void shouldEndTheMediaAtTheContainersDurationUnlessItRunsAtLeastOneSecondPastTheScannedEnd(
      long containerMillis, long scannedAudioEndMillis, long expectedMillis) {
    var probe =
        probeLasting(Duration.ofMillis(containerMillis), video(), audio(1))
            .withLearnedEndTimes(
                List.of(scannedEnd(0, 4_000), scannedEnd(1, scannedAudioEndMillis)));

    assertThat(probe.mediaEnd()).isEqualTo(Duration.ofMillis(expectedMillis));
  }

  // A file that was written to a pipe states no duration, so its header reads as zero.
  @ParameterizedTest
  @CsvSource({"0, 5009", "5000, 5009"})
  @DisplayName("Should end the media at the scanned end when it lies past the container's duration")
  void shouldEndTheMediaAtTheScannedEndWhenItLiesPastTheContainersDuration(
      long containerMillis, long scannedAudioEndMillis) {
    var probe =
        probeLasting(Duration.ofMillis(containerMillis), video(), audio(1))
            .withLearnedEndTimes(
                List.of(scannedEnd(0, 4_000), scannedEnd(1, scannedAudioEndMillis)));

    assertThat(probe.mediaEnd()).isEqualTo(Duration.ofMillis(scannedAudioEndMillis));
  }

  // The segment list counts in whole milliseconds. Counted down, an audio stream that ends at
  // 5.035041667 s would be advertised to 5.035 s, before its last sample ends.
  @Test
  @DisplayName(
      "Should count a scanned end up to the next whole millisecond when it falls between two")
  void shouldCountAScannedEndUpToTheNextWholeMillisecondWhenItFallsBetweenTwo() {
    var probe =
        probeLasting(Duration.ofSeconds(30), video(), audio(1))
            .withLearnedEndTimes(
                List.of(
                    scannedEnd(0, Duration.ofSeconds(4)),
                    scannedEnd(1, Duration.ofSeconds(5, 35_041_667))));

    assertThat(probe.mediaEnd()).isEqualTo(Duration.ofMillis(5_036));
  }

  // The header of a file cut near 14 s states 30 s for each stream. A keyframe packet scan read
  // where each stream's packets end.
  @Test
  @DisplayName(
      "Should end the media at the learned ends when the container header overstates each stream's"
          + " end")
  void shouldEndTheMediaAtTheLearnedEndsWhenTheContainerHeaderOverstatesEachStreamsEnd() {
    var stated =
        probeLasting(
            Duration.ofSeconds(30), videoStatedToEndAt(30_000), audioStatedToEndAt(1, 30_000));

    var learned = stated.withLearnedEndTimes(List.of(scannedEnd(0, 14_167), scannedEnd(1, 14_250)));

    assertThat(stated.mediaEnd()).isEqualTo(Duration.ofSeconds(30));
    assertThat(learned.mediaEnd()).isEqualTo(Duration.ofMillis(14_250));
    assertThat(learned.videoEndTime()).contains(Duration.ofMillis(14_167));
  }

  private static LearnedStreamEnd scannedEnd(int streamIndex, long endMillis) {
    return scannedEnd(streamIndex, Duration.ofMillis(endMillis));
  }

  private static LearnedStreamEnd scannedEnd(int streamIndex, Duration end) {
    return attemptLearnedEnd(streamIndex, end).toBuilder().fromPacketScan(true).build();
  }

  private static LearnedStreamEnd attemptLearnedEnd(int streamIndex, Duration end) {
    return LearnedStreamEnd.builder()
        .mediaFileId(UUID.randomUUID())
        .snapshot(new SourceFileSnapshot(1, Instant.EPOCH))
        .streamIndex(streamIndex)
        .endTime(end)
        .build();
  }

  // The default probe's chosen video stream is stream 0.
  private static StreamInfo video() {
    return StreamInfo.builder().index(0).codecType("video").build();
  }

  private static StreamInfo audio(int index) {
    return audioStreamBuilder("aac").index(index).build();
  }

  private static StreamInfo videoStatedToEndAt(long endMillis) {
    return video().toBuilder().endTime(Optional.of(Duration.ofMillis(endMillis))).build();
  }

  private static StreamInfo audioStatedToEndAt(int index, long endMillis) {
    return audio(index).toBuilder().endTime(Optional.of(Duration.ofMillis(endMillis))).build();
  }

  private static MediaProbe probeLasting(Duration containerDuration, StreamInfo... streams) {
    return StreamSessionFixture.defaultProbeBuilder()
        .duration(containerDuration)
        .streams(List.of(streams))
        .build();
  }

  private static MediaProbe probe(StreamInfo... streams) {
    return StreamSessionFixture.defaultProbeBuilder().streams(List.of(streams)).build();
  }

  private static MediaProbe probeIn(
      String demuxer, Optional<String> majorBrand, StreamInfo... streams) {
    return StreamSessionFixture.defaultProbeBuilder()
        .containerFormat(Optional.of(demuxer))
        .majorBrand(majorBrand)
        .streams(List.of(streams))
        .build();
  }

  private static StreamInfo audio(String language) {
    return audioIn(language).build();
  }

  private static StreamInfo.StreamInfoBuilder audioIn(String language) {
    return audioStreamBuilder("aac").language(Optional.of(language));
  }

  private static StreamInfo.StreamInfoBuilder subtitle(String codec, String language) {
    return StreamInfo.builder()
        .codecType("subtitle")
        .codec(Optional.of(codec))
        .language(Optional.of(language));
  }
}
