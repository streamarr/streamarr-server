package com.streamarr.server.services.streaming.remote;

/** What the server decides when the first message of a report call names its probe attempt. */
sealed interface ProbeReportAdmission {

  /** The report call reports the attempt, and no other report call may. */
  record Admitted(ReportedProbeAttempt attempt) implements ProbeReportAdmission {}

  /** The worker's live session runs no such probe attempt. */
  record NotOwned() implements ProbeReportAdmission {}

  /** Another report call already reports the attempt. */
  record AlreadyReported() implements ProbeReportAdmission {}
}
