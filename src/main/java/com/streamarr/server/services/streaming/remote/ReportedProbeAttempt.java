package com.streamarr.server.services.streaming.remote;

import com.streamarr.transcode.v1.ProbeAttemptReport;
import com.streamarr.transcode.v1.ProbeAttemptResult;
import com.streamarr.transcode.v1.ProbeRequest;

/**
 * A probe attempt that a report call reports. The attempt ends when the report call ends: each
 * method ends the attempt on its worker session and settles its outcome, and changes nothing when
 * the attempt has already ended.
 */
interface ReportedProbeAttempt {

  /** The request that started the attempt. */
  ProbeRequest request();

  /** Ends the attempt with the probe report. */
  void complete(ProbeAttemptReport report);

  /** Ends the attempt as misconfigured: the report names another probe version than the request. */
  void failForAnotherVersion(ProbeAttemptResult reported);

  /** Ends the attempt with a contract violation, which counts against its probe. */
  void failForContractViolation(String message);

  /** Ends the attempt as when its worker is lost, so that the probe runs again. */
  void failForRetry(String reason);
}
