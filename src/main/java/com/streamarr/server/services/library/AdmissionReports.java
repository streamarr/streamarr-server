package com.streamarr.server.services.library;

import com.streamarr.server.domain.media.ItemFailureReason;
import com.streamarr.server.domain.media.ItemOutcome;
import com.streamarr.server.domain.media.MatchingFailure;
import com.streamarr.server.services.concurrency.AdmittedTaskReport;

/**
 * Decides what a scan's file task or a refresh's title task reports when it finishes (ADR 0041). A
 * task that did real processing reports its latency. A task that returned without real processing
 * reports that it was ignored, because its latency says nothing about capacity. A task whose
 * provider call failed temporarily reports a drop.
 */
final class AdmissionReports {

  private AdmissionReports() {}

  static AdmittedTaskReport forMatching(MatchingOutcome outcome) {
    return switch (outcome) {
      case MatchingOutcome.Matched _ -> AdmittedTaskReport.LATENCY;
      case MatchingOutcome.Skipped _, MatchingOutcome.Interrupted _ -> AdmittedTaskReport.IGNORED;
      case MatchingOutcome.NotMatched(var failure) -> forMatchingFailure(failure);
    };
  }

  static AdmittedTaskReport forRefresh(ItemOutcome outcome) {
    return switch (outcome) {
      case ItemOutcome.Succeeded _, ItemOutcome.Unavailable _ -> AdmittedTaskReport.LATENCY;
      case ItemOutcome.Failed(var reason, var _) -> forFailureReason(reason);
    };
  }

  private static AdmittedTaskReport forMatchingFailure(MatchingFailure failure) {
    return switch (failure.status()) {
      // A file whose name could not be parsed never reached the metadata provider.
      case METADATA_PARSING_FAILED -> AdmittedTaskReport.IGNORED;
      case METADATA_NOT_FOUND -> AdmittedTaskReport.LATENCY;
      case METADATA_UNAVAILABLE, ENRICHMENT_FAILED -> forFailureReason(failure.reason());
      case UNMATCHED, MATCHED ->
          throw new IllegalStateException("A matching failure cannot end in " + failure.status());
    };
  }

  private static AdmittedTaskReport forFailureReason(ItemFailureReason reason) {
    return switch (reason) {
      case TEMPORARY -> AdmittedTaskReport.DROP;
      case MISCONFIGURED -> AdmittedTaskReport.IGNORED;
      case DOWNLOAD_FAILED, INVALID_MEDIA, SOURCE_INACCESSIBLE -> AdmittedTaskReport.LATENCY;
    };
  }
}
