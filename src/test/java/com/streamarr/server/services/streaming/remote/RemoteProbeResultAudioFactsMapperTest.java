package com.streamarr.server.services.streaming.remote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.domain.media.ProbeVersion;
import com.streamarr.server.domain.streaming.AudioStreamProperties;
import com.streamarr.server.domain.streaming.ProbeOutcome;
import com.streamarr.server.domain.streaming.StreamInfo;
import com.streamarr.server.exceptions.ProbeContractViolationException;
import com.streamarr.server.fixtures.WorkerProbeResultFixture;
import com.streamarr.transcode.v1.ProbeAttemptResult;
import com.streamarr.transcode.v1.ProbeMediaInfo;
import com.streamarr.transcode.v1.ProbeStreamInfo;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("UnitTest")
@DisplayName("Remote Probe Audio Facts Mapping Tests")
class RemoteProbeResultAudioFactsMapperTest {

  private static final int VERSION_6 = ProbeVersion.FIRST_TO_REPORT_AUDIO_FACTS - 1;
  private static final int VERSION_7 = ProbeVersion.FIRST_TO_REPORT_AUDIO_FACTS;

  private final RemoteProbeResultMapper mapper = new RemoteProbeResultMapper();

  @Test
  @DisplayName("Should keep each audio fact when probe version 7 reports it")
  void shouldKeepEachAudioFactWhenProbeVersion7ReportsIt() {
    var flac =
        flac()
            .setChannelLayout("3 channels (FL+FR+TFL)")
            .setSampleFormat("s32")
            .setBitDepth(24)
            .setIsComment(true)
            .setIsVisualImpaired(true)
            .setIsDescriptions(false)
            .setIsOriginal(true)
            .setIsDub(false)
            .setIsCleanEffects(true)
            .setIsDependent(true)
            .setFlacMinimumBlockSizeSamples(144);

    var stream = streamOf(VERSION_7, flac);

    assertThat(stream.bitDepth()).hasValue(24);
    assertThat(stream.comment()).contains(true);
    assertThat(stream.audioProperties())
        .contains(
            AudioStreamProperties.builder()
                .channelLayout(Optional.of("3 channels (FL+FR+TFL)"))
                .sampleFormat(Optional.of("s32"))
                .visualImpaired(true)
                .original(true)
                .cleanEffects(true)
                .dependent(true)
                .flacMinimumBlockSizeSamples(OptionalInt.of(144))
                .build());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("singleDispositions")
  @DisplayName("Should set only the disposition that probe version 7 reports as true")
  void shouldSetOnlyTheDispositionThatProbeVersion7ReportsAsTrue(
      String disposition,
      Consumer<ProbeStreamInfo.Builder> reported,
      AudioStreamProperties expected) {
    var audio = audio().setCodec("ac3");
    reported.accept(audio);

    var stream = streamOf(VERSION_7, audio);

    assertThat(stream.comment()).contains(false);
    assertThat(stream.audioProperties()).contains(expected);
  }

  static Stream<Arguments> singleDispositions() {
    return Stream.of(
        Arguments.of(
            "visual_impaired",
            (Consumer<ProbeStreamInfo.Builder>) stream -> stream.setIsVisualImpaired(true),
            AudioStreamProperties.builder().visualImpaired(true).build()),
        Arguments.of(
            "descriptions",
            (Consumer<ProbeStreamInfo.Builder>) stream -> stream.setIsDescriptions(true),
            AudioStreamProperties.builder().descriptions(true).build()),
        Arguments.of(
            "original",
            (Consumer<ProbeStreamInfo.Builder>) stream -> stream.setIsOriginal(true),
            AudioStreamProperties.builder().original(true).build()),
        Arguments.of(
            "dub",
            (Consumer<ProbeStreamInfo.Builder>) stream -> stream.setIsDub(true),
            AudioStreamProperties.builder().dub(true).build()),
        Arguments.of(
            "clean_effects",
            (Consumer<ProbeStreamInfo.Builder>) stream -> stream.setIsCleanEffects(true),
            AudioStreamProperties.builder().cleanEffects(true).build()),
        Arguments.of(
            "dependent",
            (Consumer<ProbeStreamInfo.Builder>) stream -> stream.setIsDependent(true),
            AudioStreamProperties.builder().dependent(true).build()));
  }

  @Test
  @DisplayName(
      "Should report no layout, format or block size and every disposition false when probe"
          + " version 7 leaves an audio stream's facts absent")
  void shouldReportNoLayoutFormatOrBlockSizeAndEveryDispositionFalseWhenFactsAreAbsent() {
    var stream = streamOf(VERSION_7, audio().setCodec("pcm_s16le"));

    assertThat(stream.comment()).contains(false);
    assertThat(stream.audioProperties()).contains(AudioStreamProperties.builder().build());
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 65_535})
  @DisplayName("Should keep a FLAC minimum block size at either end of STREAMINFO's 16 bits")
  void shouldKeepAFlacMinimumBlockSizeAtEitherEndOfStreaminfos16Bits(int samples) {
    var stream = streamOf(VERSION_7, flac().setFlacMinimumBlockSizeSamples(samples));

    assertThat(stream.audioProperties())
        .map(AudioStreamProperties::flacMinimumBlockSizeSamples)
        .contains(OptionalInt.of(samples));
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  @DisplayName("Should keep a subtitle stream's commentary flag when probe version 7 reports it")
  void shouldKeepASubtitleStreamsCommentaryFlagWhenProbeVersion7ReportsIt(boolean comment) {
    var stream = streamOf(VERSION_7, subtitle().setIsComment(comment));

    assertThat(stream.comment()).contains(comment);
    assertThat(stream.audioProperties()).isEmpty();
  }

  @Test
  @DisplayName(
      "Should count a subtitle stream's absent commentary flag as false in probe version 7")
  void shouldCountASubtitleStreamsAbsentCommentaryFlagAsFalseInProbeVersion7() {
    var stream = streamOf(VERSION_7, subtitle());

    assertThat(stream.comment()).contains(false);
  }

  @Test
  @DisplayName("Should report no audio fact for a video stream in probe version 7")
  void shouldReportNoAudioFactForAVideoStreamInProbeVersion7() {
    var video = success(VERSION_7, WorkerProbeResultFixture.version4MediaBuilder()).streams();

    assertThat(video.getFirst())
        .extracting(StreamInfo::comment, StreamInfo::audioProperties)
        .containsExactly(Optional.empty(), Optional.empty());
  }

  @Test
  @DisplayName("Should ignore every audio fact when a result of probe version 6 carries them")
  void shouldIgnoreEveryAudioFactWhenAResultOfProbeVersion6CarriesThem() {
    var flac =
        flac()
            .setChannelLayout("stereo")
            .setSampleFormat("s16")
            .setIsComment(true)
            .setIsDub(true)
            .setFlacMinimumBlockSizeSamples(4_608);

    var stream = streamOf(VERSION_6, flac);

    assertThat(stream.comment()).isEmpty();
    assertThat(stream.audioProperties()).isEmpty();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("misplacedAudioFacts")
  @DisplayName("Should reject the result when probe version 7 reports an audio fact out of place")
  void shouldRejectTheResultWhenProbeVersion7ReportsAnAudioFactOutOfPlace(
      String description, ProbeStreamInfo.Builder stream) {
    var media = WorkerProbeResultFixture.version4MediaBuilder().addStreams(stream);
    var report = report(VERSION_7, media);

    assertThatThrownBy(() -> mapper.map(report))
        .isInstanceOf(ProbeContractViolationException.class);
  }

  static Stream<Arguments> misplacedAudioFacts() {
    return Stream.of(
        misplaced("a FLAC block size on an AAC stream", audio().setCodec("aac"), flacBlock()),
        misplaced("a FLAC block size on a stream without a codec", audio(), flacBlock()),
        misplaced(
            "a FLAC block size beyond 16 bits",
            flac(),
            stream -> stream.setFlacMinimumBlockSizeSamples(65_536)),
        misplaced(
            "an unsigned FLAC block size overflow",
            flac(),
            stream -> stream.setFlacMinimumBlockSizeSamples(-1)),
        misplaced(
            "a channel layout on a subtitle stream",
            subtitle(),
            stream -> stream.setChannelLayout("stereo")),
        misplaced(
            "a sample format on a subtitle stream",
            subtitle(),
            stream -> stream.setSampleFormat("s16")),
        misplaced(
            "a visual impaired flag on a subtitle stream",
            subtitle(),
            stream -> stream.setIsVisualImpaired(false)),
        misplaced(
            "a descriptions flag on a subtitle stream",
            subtitle(),
            stream -> stream.setIsDescriptions(true)),
        misplaced(
            "an original flag on a subtitle stream",
            subtitle(),
            stream -> stream.setIsOriginal(true)),
        misplaced("a dub flag on a subtitle stream", subtitle(), stream -> stream.setIsDub(true)),
        misplaced(
            "a clean effects flag on a subtitle stream",
            subtitle(),
            stream -> stream.setIsCleanEffects(true)),
        misplaced(
            "a dependent flag on a subtitle stream",
            subtitle(),
            stream -> stream.setIsDependent(true)),
        misplaced("a FLAC block size on a subtitle stream", subtitle(), flacBlock()),
        misplaced(
            "a commentary flag on a second video stream",
            video(),
            stream -> stream.setIsComment(false)),
        misplaced(
            "a channel layout on a second video stream",
            video(),
            stream -> stream.setChannelLayout("stereo")));
  }

  private static Arguments misplaced(
      String description, ProbeStreamInfo.Builder stream, Consumer<ProbeStreamInfo.Builder> fact) {
    fact.accept(stream);
    return Arguments.of(description, stream);
  }

  private static Consumer<ProbeStreamInfo.Builder> flacBlock() {
    return stream -> stream.setFlacMinimumBlockSizeSamples(4_608);
  }

  private StreamInfo streamOf(int version, ProbeStreamInfo.Builder stream) {
    var media = WorkerProbeResultFixture.version4MediaBuilder().addStreams(stream);
    return success(version, media).streams().get(1);
  }

  private ProbeOutcome.Success success(int version, ProbeMediaInfo.Builder media) {
    return (ProbeOutcome.Success) mapper.map(report(version, media));
  }

  private static ProbeAttemptReport report(int version, ProbeMediaInfo.Builder media) {
    return new ProbeAttemptReport(
        ProbeAttemptResult.newBuilder().setProbeVersion(version).setMedia(media).build(),
        List.of());
  }

  private static ProbeStreamInfo.Builder audio() {
    return ProbeStreamInfo.newBuilder().setIndex(1).setCodecType("audio");
  }

  private static ProbeStreamInfo.Builder flac() {
    return audio().setCodec("flac");
  }

  private static ProbeStreamInfo.Builder subtitle() {
    return ProbeStreamInfo.newBuilder().setIndex(1).setCodecType("subtitle");
  }

  private static ProbeStreamInfo.Builder video() {
    return ProbeStreamInfo.newBuilder().setIndex(1).setCodecType("video");
  }
}
