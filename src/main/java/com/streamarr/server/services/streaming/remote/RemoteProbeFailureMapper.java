package com.streamarr.server.services.streaming.remote;

import com.streamarr.server.domain.media.ItemFailureReason;
import com.streamarr.server.domain.streaming.ProbeError;
import com.streamarr.server.exceptions.ProbeCancelledException;
import com.streamarr.server.exceptions.ProbeContractViolationException;
import com.streamarr.server.exceptions.ProbeExecutionException;
import com.streamarr.server.exceptions.ProbeWorkersBusyException;
import com.streamarr.transcode.v1.ProbeAttemptResult;

/** Maps the failure that a worker reports for a probe attempt. */
final class RemoteProbeFailureMapper {

  private RemoteProbeFailureMapper() {}

  /**
   * The error of a failure that ends the attempt for good.
   *
   * @throws ProbeWorkersBusyException when the attempt waits for a worker without a failure
   * @throws ProbeCancelledException when the worker cancelled the attempt
   * @throws ProbeExecutionException when the attempt may succeed on a retry
   * @throws ProbeContractViolationException when the result reports no outcome
   */
  static ProbeError terminalError(ProbeAttemptResult result) {
    return switch (result.getFailure()) {
      case PROBE_FAILURE_INVALID_MEDIA -> ProbeError.INVALID_MEDIA;
      case PROBE_FAILURE_NO_VIDEO_STREAM -> ProbeError.NO_VIDEO_STREAM;
      case PROBE_FAILURE_RESULT_TOO_LARGE -> ProbeError.RESULT_TOO_LARGE;
      // The worker lowered the probe's capacity after the server sent the probe. The probe stays
      // pending without a failure, and any worker with a free unit can take it.
      case PROBE_FAILURE_NO_FREE_CAPACITY ->
          throw new ProbeWorkersBusyException(
              "The worker had no free unit of the probe's capacity when the probe arrived");
      case PROBE_FAILURE_CANCELLED ->
          throw new ProbeCancelledException("Worker cancelled the probe: " + result.getFailure());
      case PROBE_FAILURE_SOURCE_UNAVAILABLE ->
          throw retryable(ItemFailureReason.SOURCE_INACCESSIBLE, result);
      case PROBE_FAILURE_UNSUPPORTED_VERSION ->
          throw retryable(ItemFailureReason.MISCONFIGURED, result);
      case PROBE_FAILURE_UNSPECIFIED ->
          throw new ProbeContractViolationException("Worker probe result reports no outcome");
      case PROBE_FAILURE_EXECUTION_FAILED, UNRECOGNIZED ->
          throw retryable(ItemFailureReason.TEMPORARY, result);
    };
  }

  private static ProbeExecutionException retryable(
      ItemFailureReason reason, ProbeAttemptResult result) {
    return new ProbeExecutionException(
        reason, "Worker probe reported a retryable failure: " + result.getFailure());
  }
}
