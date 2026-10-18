package com.streamarr.server.services.streaming.remote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.domain.streaming.ProbeOutcome;
import com.streamarr.server.exceptions.ProbeExecutionException;
import com.streamarr.server.fixtures.WorkerProbeResultFixture;
import com.streamarr.transcode.v1.ChromaticityCoordinates;
import com.streamarr.transcode.v1.ContentLightLevel;
import com.streamarr.transcode.v1.MasteringDisplayColorVolume;
import com.streamarr.transcode.v1.ProbeAttemptResult;
import com.streamarr.transcode.v1.ProbeMediaInfo;
import com.streamarr.transcode.v1.ProbeStreamInfo;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("UnitTest")
@DisplayName("Remote Probe Metadata Mapping Tests")
class RemoteProbeResultMetadataMapperTest {

  private final RemoteProbeResultMapper mapper = new RemoteProbeResultMapper();

  @ParameterizedTest(name = "{0}")
  @MethodSource("malformedMetadata")
  @DisplayName("Should reject the result when its optional metadata violates the wire contract")
  void shouldRejectTheResultWhenItsOptionalMetadataViolatesTheWireContract(
      String description, Consumer<ProbeMediaInfo.Builder> change) {
    var media = WorkerProbeResultFixture.version4MediaBuilder();
    change.accept(media);
    var report = report(media, 4);

    assertThatThrownBy(() -> mapper.map(report)).isInstanceOf(ProbeExecutionException.class);
  }

  static Stream<Arguments> malformedMetadata() {
    return Stream.of(
        badDisplay("missing red", MasteringDisplayColorVolume.Builder::clearRed),
        badDisplay("missing green", MasteringDisplayColorVolume.Builder::clearGreen),
        badDisplay("missing blue", MasteringDisplayColorVolume.Builder::clearBlue),
        badDisplay("missing white point", MasteringDisplayColorVolume.Builder::clearWhitePoint),
        badDisplay("coordinate above 50000", display -> display.getRedBuilder().setX(50001)),
        badDisplay("unsigned coordinate overflow", display -> display.getBlueBuilder().setY(-1)),
        badStream(
            "CLL above 65535",
            stream ->
                stream.setContentLightLevel(
                    ContentLightLevel.newBuilder().setMaxContentLightLevel(65536))),
        badStream(
            "unsigned FALL overflow",
            stream ->
                stream.setContentLightLevel(
                    ContentLightLevel.newBuilder().setMaxFrameAverageLightLevel(-1))),
        badText("title above 512 UTF-8 bytes", stream -> stream.setTitle("é".repeat(256) + "x")),
        badText(
            "handler above 512 UTF-8 bytes",
            stream -> stream.setHandlerName("é".repeat(256) + "x")),
        badMedia(
            "omitted title still present",
            media -> media.setStreamTitlesOmitted(true).addStreams(audio().setTitle("English"))),
        badMedia(
            "omitted handler still present",
            media -> media.setStreamTitlesOmitted(true).addStreams(audio().setHandlerName(""))));
  }

  @Test
  @DisplayName("Should preserve present zero and boundary values when HDR metadata is complete")
  void shouldPreservePresentZeroAndBoundaryValuesWhenHdrMetadataIsComplete() {
    var media = WorkerProbeResultFixture.version4MediaBuilder();
    media
        .getStreamsBuilder(0)
        .setMasteringDisplay(display().setRed(ChromaticityCoordinates.newBuilder().setX(50000)))
        .setContentLightLevel(ContentLightLevel.getDefaultInstance());

    var stream = success(report(media, 4)).streams().getFirst();

    assertThat(stream.masteringDisplay()).isPresent();
    var stored = stream.masteringDisplay().orElseThrow();
    assertThat(stored.red().x()).isEqualTo(50000);
    assertThat(stored.red().y()).isZero();
    assertThat(stored.maxLuminance()).isZero();
    assertThat(stored.minLuminance()).isZero();
    assertThat(stream.contentLightLevel()).isPresent();
    assertThat(stream.contentLightLevel().orElseThrow().maxContentLightLevel()).isZero();
    assertThat(stream.contentLightLevel().orElseThrow().maxFrameAverageLightLevel()).isZero();
  }

  @Test
  @DisplayName(
      "Should retain original text when it reaches the UTF-8 limit and the handler is empty")
  void shouldRetainOriginalTextWhenItReachesTheUtf8LimitAndTheHandlerIsEmpty() {
    var title = "é".repeat(256);
    var media =
        WorkerProbeResultFixture.version4MediaBuilder()
            .addStreams(audio().setTitle(title).setHandlerName(""));

    var stored = success(report(media, 4));

    assertThat(stored.streamTitlesOmitted()).contains(false);
    assertThat(stored.streams().get(1).title()).contains(title);
    assertThat(stored.streams().get(1).handlerName()).contains("");
  }

  @Test
  @DisplayName("Should retain the omission marker when no stream names were reported")
  void shouldRetainTheOmissionMarkerWhenNoStreamNamesWereReported() {
    var media =
        WorkerProbeResultFixture.version4MediaBuilder()
            .setStreamTitlesOmitted(true)
            .addStreams(audio());

    var stored = success(report(media, 4));

    assertThat(stored.streamTitlesOmitted()).contains(true);
    assertThat(stored.streams())
        .allSatisfy(
            stream -> {
              assertThat(stream.title()).isEmpty();
              assertThat(stream.handlerName()).isEmpty();
            });
  }

  @Test
  @DisplayName("Should report metadata as unknown when an earlier probe version carries v4 fields")
  void shouldReportMetadataAsUnknownWhenAnEarlierProbeVersionCarriesV4Fields() {
    var media =
        WorkerProbeResultFixture.version4MediaBuilder()
            .setStreamTitlesOmitted(true)
            .addStreams(
                audio().setTitle("Ignored").setHandlerName("Ignored").setIsHearingImpaired(true));
    media
        .getStreamsBuilder(0)
        .setMasteringDisplay(display())
        .setContentLightLevel(ContentLightLevel.getDefaultInstance())
        .getEndTimeBuilder()
        .setSeconds(1);

    var stored = success(report(media, 3));

    assertThat(stored.streamTitlesOmitted()).isEmpty();
    assertThat(stored.streams())
        .allSatisfy(
            stream -> {
              assertThat(stream.endTime()).isEmpty();
              assertThat(stream.masteringDisplay()).isEmpty();
              assertThat(stream.contentLightLevel()).isEmpty();
              assertThat(stream.title()).isEmpty();
              assertThat(stream.handlerName()).isEmpty();
              assertThat(stream.hearingImpaired()).isEmpty();
            });
  }

  private ProbeOutcome.Success success(ProbeAttemptReport report) {
    return (ProbeOutcome.Success) mapper.map(report);
  }

  private static Arguments badDisplay(
      String name, Consumer<MasteringDisplayColorVolume.Builder> change) {
    return badStream(
        name,
        stream -> {
          var display = display();
          change.accept(display);
          stream.setMasteringDisplay(display);
        });
  }

  private static Arguments badStream(String name, Consumer<ProbeStreamInfo.Builder> change) {
    return badMedia(name, media -> change.accept(media.getStreamsBuilder(0)));
  }

  private static Arguments badText(String name, Consumer<ProbeStreamInfo.Builder> change) {
    return badMedia(
        name,
        media -> {
          var audio = audio();
          change.accept(audio);
          media.addStreams(audio);
        });
  }

  private static Arguments badMedia(String name, Consumer<ProbeMediaInfo.Builder> change) {
    return Arguments.of(name, change);
  }

  private static MasteringDisplayColorVolume.Builder display() {
    var coordinates = ChromaticityCoordinates.getDefaultInstance();
    return MasteringDisplayColorVolume.newBuilder()
        .setRed(coordinates)
        .setGreen(coordinates)
        .setBlue(coordinates)
        .setWhitePoint(coordinates);
  }

  private static ProbeStreamInfo.Builder audio() {
    return ProbeStreamInfo.newBuilder().setIndex(1).setCodecType("audio");
  }

  private static ProbeAttemptReport report(ProbeMediaInfo.Builder media, int version) {
    return new ProbeAttemptReport(
        ProbeAttemptResult.newBuilder().setProbeVersion(version).setMedia(media).build(),
        List.of());
  }
}
