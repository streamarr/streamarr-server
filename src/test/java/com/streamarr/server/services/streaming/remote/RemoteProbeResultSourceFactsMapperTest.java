package com.streamarr.server.services.streaming.remote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.domain.media.ProbeVersion;
import com.streamarr.server.domain.streaming.ProbeOutcome;
import com.streamarr.server.exceptions.ProbeContractViolationException;
import com.streamarr.server.fixtures.WorkerProbeResultFixture;
import com.streamarr.transcode.v1.ProbeAttemptReport;
import com.streamarr.transcode.v1.ProbeAttemptResult;
import com.streamarr.transcode.v1.ProbeMediaInfo;
import com.streamarr.transcode.v1.ProbeStreamInfo;
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
@DisplayName("Remote Probe Source Facts Mapping Tests")
class RemoteProbeResultSourceFactsMapperTest {

  private static final int VERSION_6 = ProbeVersion.FIRST_TO_REPORT_SOURCE_FACTS - 1;
  private static final int VERSION_7 = ProbeVersion.FIRST_TO_REPORT_SOURCE_FACTS;

  private final RemoteProbeResultMapper mapper = new RemoteProbeResultMapper();

  @Test
  @DisplayName("Should keep the sample entry and the major brand when probe version 7 reports them")
  void shouldKeepTheSampleEntryAndTheMajorBrandWhenProbeVersion7ReportsThem() {
    var media = mp4Media("dvh1", "qt  ");

    var outcome = success(VERSION_7, media);

    assertThat(outcome.streams().getFirst().sampleEntry()).contains("dvh1");
    assertThat(outcome.container().majorBrand()).contains("qt  ");
    assertThat(outcome.mediaProbe().majorBrand()).contains("qt  ");
  }

  @Test
  @DisplayName("Should report neither fact when a probe version 7 result leaves both out")
  void shouldReportNeitherFactWhenAProbeVersion7ResultLeavesBothOut() {
    var outcome = success(VERSION_7, WorkerProbeResultFixture.version4MediaBuilder());

    assertThat(outcome.streams().getFirst().sampleEntry()).isEmpty();
    assertThat(outcome.container().majorBrand()).isEmpty();
  }

  @Test
  @DisplayName("Should ignore both facts when a result of probe version 6 carries them")
  void shouldIgnoreBothFactsWhenAResultOfProbeVersion6CarriesThem() {
    var outcome = success(VERSION_6, mp4Media("hvc1", "isom"));

    assertThat(outcome.streams().getFirst().sampleEntry()).isEmpty();
    assertThat(outcome.container().majorBrand()).isEmpty();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("misplacedSourceFacts")
  @DisplayName("Should reject the result when probe version 7 reports a source fact out of place")
  void shouldRejectTheResultWhenProbeVersion7ReportsASourceFactOutOfPlace(
      String description, Consumer<ProbeMediaInfo.Builder> fact) {
    var media = WorkerProbeResultFixture.version4MediaBuilder();
    fact.accept(media);
    var report = report(VERSION_7, media);

    assertThatThrownBy(() -> mapper.map(report))
        .isInstanceOf(ProbeContractViolationException.class);
  }

  static Stream<Arguments> misplacedSourceFacts() {
    return Stream.of(
        misplaced(
            "a sample entry on an audio stream",
            media ->
                media.addStreams(
                    ProbeStreamInfo.newBuilder()
                        .setIndex(1)
                        .setCodecType("audio")
                        .setSampleEntry("mp4a"))),
        misplaced("a three-character sample entry", sampleEntry("hvc")),
        misplaced("a five-character sample entry", sampleEntry("hvc1 ")),
        misplaced("a sample entry with a control character", sampleEntry("hv\tc")),
        misplaced("a sample entry with a letter beyond ASCII", sampleEntry("hvéc")),
        misplaced("a two-character major brand", majorBrand("qt")),
        misplaced("an empty major brand", majorBrand("")),
        misplaced("a major brand with a zero character", majorBrand("is\0m")));
  }

  @ParameterizedTest(name = "\"{0}\"")
  @ValueSource(strings = {"hvc1", "dva1", "av01", "mp4v"})
  @DisplayName("Should keep a sample entry of four printable ASCII characters")
  void shouldKeepASampleEntryOfFourPrintableAsciiCharacters(String sampleEntry) {
    var outcome = success(VERSION_7, mp4Media(sampleEntry, "isom"));

    assertThat(outcome.streams().getFirst().sampleEntry()).contains(sampleEntry);
  }

  private static Arguments misplaced(String description, Consumer<ProbeMediaInfo.Builder> fact) {
    return Arguments.of(description, fact);
  }

  private static Consumer<ProbeMediaInfo.Builder> sampleEntry(String sampleEntry) {
    return media -> media.getStreamsBuilder(0).setSampleEntry(sampleEntry);
  }

  private static Consumer<ProbeMediaInfo.Builder> majorBrand(String majorBrand) {
    return media -> media.getContainerBuilder().setMajorBrand(majorBrand);
  }

  private static ProbeMediaInfo.Builder mp4Media(String sampleEntry, String majorBrand) {
    var media = WorkerProbeResultFixture.version4MediaBuilder();
    sampleEntry(sampleEntry).andThen(majorBrand(majorBrand)).accept(media);
    return media;
  }

  private ProbeOutcome.Success success(int version, ProbeMediaInfo.Builder media) {
    return (ProbeOutcome.Success) mapper.map(report(version, media));
  }

  private static ProbeAttemptReport report(int version, ProbeMediaInfo.Builder media) {
    return ProbeAttemptReport.newBuilder()
        .setResult(ProbeAttemptResult.newBuilder().setProbeVersion(version).setMedia(media))
        .build();
  }
}
