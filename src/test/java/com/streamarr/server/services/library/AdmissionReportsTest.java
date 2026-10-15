package com.streamarr.server.services.library;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.media.ItemFailureReason;
import com.streamarr.server.domain.media.ItemOutcome;
import com.streamarr.server.domain.media.MatchingFailure;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.services.concurrency.AdmittedTaskReport;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("UnitTest")
@DisplayName("Admission reports")
class AdmissionReportsTest {

  static Stream<Arguments> matchingOutcomes() {
    return Stream.of(
        Arguments.of(new MatchingOutcome.Matched(), AdmittedTaskReport.LATENCY),
        Arguments.of(new MatchingOutcome.SkippedAsMatched(), AdmittedTaskReport.IGNORED),
        Arguments.of(new MatchingOutcome.Interrupted(), AdmittedTaskReport.IGNORED),
        Arguments.of(
            notMatched(MatchingFailure.of(MediaFileStatus.METADATA_PARSING_FAILED)),
            AdmittedTaskReport.IGNORED),
        Arguments.of(
            notMatched(MatchingFailure.of(MediaFileStatus.METADATA_NOT_FOUND)),
            AdmittedTaskReport.LATENCY),
        Arguments.of(
            notMatched(
                new MatchingFailure(
                    MediaFileStatus.METADATA_UNAVAILABLE, ItemFailureReason.TEMPORARY)),
            AdmittedTaskReport.DROP),
        Arguments.of(
            notMatched(
                new MatchingFailure(
                    MediaFileStatus.METADATA_UNAVAILABLE, ItemFailureReason.MISCONFIGURED)),
            AdmittedTaskReport.IGNORED),
        Arguments.of(
            notMatched(
                new MatchingFailure(
                    MediaFileStatus.ENRICHMENT_FAILED, ItemFailureReason.DOWNLOAD_FAILED)),
            AdmittedTaskReport.LATENCY));
  }

  @ParameterizedTest(name = "{0} reports {1}")
  @MethodSource("matchingOutcomes")
  @DisplayName("Should report what a file task's matching outcome says about capacity")
  void shouldReportWhatAFileTasksMatchingOutcomeSaysAboutCapacity(
      MatchingOutcome outcome, AdmittedTaskReport expected) {
    assertThat(AdmissionReports.forMatching(outcome)).isEqualTo(expected);
  }

  static Stream<Arguments> refreshOutcomes() {
    return Stream.of(
        Arguments.of(new ItemOutcome.Succeeded(), AdmittedTaskReport.LATENCY),
        Arguments.of(new ItemOutcome.Unavailable(), AdmittedTaskReport.LATENCY),
        Arguments.of(failed(ItemFailureReason.TEMPORARY), AdmittedTaskReport.DROP),
        Arguments.of(failed(ItemFailureReason.MISCONFIGURED), AdmittedTaskReport.IGNORED),
        Arguments.of(failed(ItemFailureReason.DOWNLOAD_FAILED), AdmittedTaskReport.LATENCY),
        Arguments.of(failed(ItemFailureReason.INVALID_MEDIA), AdmittedTaskReport.LATENCY),
        Arguments.of(failed(ItemFailureReason.SOURCE_INACCESSIBLE), AdmittedTaskReport.LATENCY));
  }

  @ParameterizedTest(name = "{0} reports {1}")
  @MethodSource("refreshOutcomes")
  @DisplayName("Should report what a refreshed title's outcome says about capacity")
  void shouldReportWhatARefreshedTitlesOutcomeSaysAboutCapacity(
      ItemOutcome outcome, AdmittedTaskReport expected) {
    assertThat(AdmissionReports.forRefresh(outcome)).isEqualTo(expected);
  }

  private static MatchingOutcome notMatched(MatchingFailure failure) {
    return new MatchingOutcome.NotMatched(failure);
  }

  private static ItemOutcome failed(ItemFailureReason reason) {
    return new ItemOutcome.Failed(reason, "detail");
  }
}
