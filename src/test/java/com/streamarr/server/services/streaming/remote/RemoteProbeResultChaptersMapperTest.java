package com.streamarr.server.services.streaming.remote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.domain.streaming.Chapter;
import com.streamarr.server.domain.streaming.ChapterKnowledge;
import com.streamarr.server.domain.streaming.ProbeOutcome;
import com.streamarr.server.exceptions.ProbeExecutionException;
import com.streamarr.transcode.v1.ProbeAttemptResult;
import com.streamarr.transcode.v1.ProbeChapter;
import com.streamarr.transcode.v1.ProbeContainerInfo;
import com.streamarr.transcode.v1.ProbeMediaInfo;
import com.streamarr.transcode.v1.ProbeStreamInfo;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("UnitTest")
@DisplayName("Remote Probe Result Chapters Mapper Tests")
class RemoteProbeResultChaptersMapperTest {

  private static final int PROBE_VERSION_WITH_CHAPTERS = 4;

  private final RemoteProbeResultMapper mapper = new RemoteProbeResultMapper();

  @Test
  @DisplayName(
      "Should keep each chapter in reported order when a probe version 4 result lists them")
  void shouldKeepEachChapterInReportedOrderWhenProbeVersion4ResultListsThem() {
    var container =
        ProbeContainerInfo.newBuilder()
            .addChapters(chapter(0, -46_000_000, 300).setTitle("Opening Credits"))
            .addChapters(chapter(1_500, 0, 2_400))
            .addChapters(chapter(300, 0, 1_500).setTitle("Middle"))
            .addChapters(ProbeChapter.newBuilder());

    var chapters = chaptersOf(result(PROBE_VERSION_WITH_CHAPTERS, container));

    assertThat(chapters)
        .isEqualTo(
            new ChapterKnowledge.Known(
                List.of(
                    Chapter.builder()
                        .startTime(Optional.of(Duration.ofMillis(-46)))
                        .endTime(Optional.of(Duration.ofSeconds(300)))
                        .title(Optional.of("Opening Credits"))
                        .build(),
                    Chapter.builder()
                        .startTime(Optional.of(Duration.ofSeconds(1_500)))
                        .endTime(Optional.of(Duration.ofSeconds(2_400)))
                        .build(),
                    Chapter.builder()
                        .startTime(Optional.of(Duration.ofSeconds(300)))
                        .endTime(Optional.of(Duration.ofSeconds(1_500)))
                        .title(Optional.of("Middle"))
                        .build(),
                    Chapter.builder().build())));
  }

  @Test
  @DisplayName("Should know the container has no chapters when a probe version 4 result lists none")
  void shouldKnowTheContainerHasNoChaptersWhenProbeVersion4ResultListsNone() {
    var chapters = chaptersOf(result(PROBE_VERSION_WITH_CHAPTERS, ProbeContainerInfo.newBuilder()));

    assertThat(chapters).isEqualTo(new ChapterKnowledge.Known(List.of()));
  }

  @Test
  @DisplayName("Should keep the chapters omitted when a probe version 4 result omits them")
  void shouldKeepTheChaptersOmittedWhenProbeVersion4ResultOmitsThem() {
    var chapters =
        chaptersOf(
            result(
                PROBE_VERSION_WITH_CHAPTERS,
                ProbeContainerInfo.newBuilder().setChaptersOmitted(true)));

    assertThat(chapters).isEqualTo(new ChapterKnowledge.Omitted());
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 3})
  @DisplayName("Should report no chapters when the result's probe version reports none")
  void shouldReportNoChaptersWhenTheResultsProbeVersionReportsNone(int probeVersion) {
    var chapters =
        chaptersOf(
            result(probeVersion, ProbeContainerInfo.newBuilder().addChapters(chapter(0, 0, 60))));

    assertThat(chapters).isEqualTo(new ChapterKnowledge.NotReported());
  }

  @Test
  @DisplayName("Should reject the result when it omits chapters and still lists some")
  void shouldRejectTheResultWhenItOmitsChaptersAndStillListsSome() {
    var report =
        report(
            result(
                PROBE_VERSION_WITH_CHAPTERS,
                ProbeContainerInfo.newBuilder()
                    .setChaptersOmitted(true)
                    .addChapters(chapter(0, 0, 60))));

    assertThatThrownBy(() -> mapper.map(report)).isInstanceOf(ProbeExecutionException.class);
  }

  @Test
  @DisplayName("Should keep a title of 512 bytes when a chapter reports one")
  void shouldKeepATitleOf512BytesWhenAChapterReportsOne() {
    var title = "é".repeat(256);

    var chapters =
        chaptersOf(
            result(
                PROBE_VERSION_WITH_CHAPTERS,
                ProbeContainerInfo.newBuilder().addChapters(chapter(0, 0, 60).setTitle(title))));

    assertThat(chapters)
        .isInstanceOfSatisfying(
            ChapterKnowledge.Known.class,
            known -> assertThat(known.chapters().getFirst().title()).contains(title));
  }

  @Test
  @DisplayName("Should reject the result when a chapter title exceeds 512 bytes")
  void shouldRejectTheResultWhenAChapterTitleExceeds512Bytes() {
    var report =
        report(
            result(
                PROBE_VERSION_WITH_CHAPTERS,
                ProbeContainerInfo.newBuilder()
                    .addChapters(chapter(0, 0, 60).setTitle("é".repeat(256) + "a"))));

    assertThatThrownBy(() -> mapper.map(report)).isInstanceOf(ProbeExecutionException.class);
  }

  @ParameterizedTest
  @CsvSource({"315576000001,0", "0,1000000000", "1,-1", "-1,1"})
  @DisplayName("Should reject the result when a chapter time violates the protobuf contract")
  void shouldRejectTheResultWhenAChapterTimeViolatesTheProtobufContract(long seconds, int nanos) {
    var startReport =
        report(
            result(
                PROBE_VERSION_WITH_CHAPTERS,
                ProbeContainerInfo.newBuilder().addChapters(chapter(seconds, nanos, 60))));
    var endChapter = ProbeChapter.newBuilder();
    endChapter.getEndTimeBuilder().setSeconds(seconds).setNanos(nanos);
    var endReport =
        report(
            result(
                PROBE_VERSION_WITH_CHAPTERS,
                ProbeContainerInfo.newBuilder().addChapters(endChapter)));

    assertThatThrownBy(() -> mapper.map(startReport)).isInstanceOf(ProbeExecutionException.class);
    assertThatThrownBy(() -> mapper.map(endReport)).isInstanceOf(ProbeExecutionException.class);
  }

  private ChapterKnowledge chaptersOf(ProbeAttemptResult result) {
    var outcome = (ProbeOutcome.Success) mapper.map(report(result));
    return outcome.container().chapters();
  }

  private static ProbeChapter.Builder chapter(long startSeconds, int startNanos, long endSeconds) {
    var chapter = ProbeChapter.newBuilder();
    chapter.getStartTimeBuilder().setSeconds(startSeconds).setNanos(startNanos);
    chapter.getEndTimeBuilder().setSeconds(endSeconds);
    return chapter;
  }

  private static ProbeAttemptResult result(int probeVersion, ProbeContainerInfo.Builder container) {
    return ProbeAttemptResult.newBuilder()
        .setProbeVersion(probeVersion)
        .setMedia(
            ProbeMediaInfo.newBuilder()
                .setContainer(container)
                .addStreams(ProbeStreamInfo.newBuilder().setCodecType("video")))
        .build();
  }

  private static ProbeAttemptReport report(ProbeAttemptResult result) {
    return new ProbeAttemptReport(result, List.of());
  }
}
