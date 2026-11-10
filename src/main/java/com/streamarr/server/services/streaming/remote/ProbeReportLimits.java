package com.streamarr.server.services.streaming.remote;

import java.time.Duration;
import lombok.Builder;
import lombok.NonNull;

/**
 * The bounds that the server enforces on the probe reports that workers send in report calls. The
 * server states the keyframe limit, the measured keyframe interval limit, the length limit and
 * {@link #REPORT_CALLS_PER_WORKER} to every worker whose session it accepts.
 *
 * @param keyframeLimit the most keyframes in one probe report
 * @param measuredKeyframeIntervalLimit the most measured keyframe intervals in one probe report,
 *     added up over its measured streams
 * @param lengthLimitBytes the length of the longest probe report
 * @param idleTimeout how long a report call may leave a message that the server asked for unsent
 * @param memoryBudgetBytes the most bytes of probe reports that the server reads at once
 */
@Builder
public record ProbeReportLimits(
    int keyframeLimit,
    int measuredKeyframeIntervalLimit,
    long lengthLimitBytes,
    @NonNull Duration idleTimeout,
    long memoryBudgetBytes) {

  // One connection's calls, less the worker session and the worker's segment uploads.
  static final int REPORT_CALLS_PER_WORKER =
      WorkerSessionServer.MAXIMUM_CONCURRENT_CALLS_PER_CONNECTION
          - 1
          - SegmentUploadLimits.MAXIMUM_SEGMENT_UPLOADS_PER_WORKER;

  static final ProbeReportLimits DEFAULT = builder().build();

  // The server reads a probe report's bytes into one array before it decodes them.
  private static final long LONGEST_LENGTH_LIMIT_BYTES = Integer.MAX_VALUE;

  public ProbeReportLimits {
    requirePositive(keyframeLimit, "keyframe limit");
    requirePositive(measuredKeyframeIntervalLimit, "measured keyframe interval limit");
    requirePositive(lengthLimitBytes, "length limit");
    requirePositive(memoryBudgetBytes, "memory budget");
    if (!idleTimeout.isPositive()) {
      throw new IllegalArgumentException("Worker probe report idle timeout must be positive");
    }

    // A probe report that one message of a report call can hold must not exceed the length limit.
    if (lengthLimitBytes < WorkerSessionServer.MAXIMUM_INBOUND_MESSAGE_BYTES) {
      throw new IllegalArgumentException(
          "Worker probe report length limit must be at least %d bytes"
              .formatted(WorkerSessionServer.MAXIMUM_INBOUND_MESSAGE_BYTES));
    }

    if (lengthLimitBytes > LONGEST_LENGTH_LIMIT_BYTES) {
      throw new IllegalArgumentException(
          "Worker probe report length limit must be at most %d bytes"
              .formatted(LONGEST_LENGTH_LIMIT_BYTES));
    }

    // Otherwise a probe report at the length limit would wait for the budget without end.
    if (memoryBudgetBytes < lengthLimitBytes) {
      throw new IllegalArgumentException(
          "Worker probe report memory budget must be at least the probe report length limit");
    }
  }

  private static void requirePositive(long value, String setting) {
    if (value <= 0) {
      throw new IllegalArgumentException(
          "Worker probe report %s must be positive".formatted(setting));
    }
  }

  public static class ProbeReportLimitsBuilder {
    // Lombok's generated build() reads these defaults.
    @SuppressWarnings("java:S1068")
    private int keyframeLimit = 150_000;

    @SuppressWarnings("java:S1068")
    private int measuredKeyframeIntervalLimit = 1_000_000;

    @SuppressWarnings("java:S1068")
    private long lengthLimitBytes = 16L * 1024 * 1024;

    @SuppressWarnings("java:S1068")
    private Duration idleTimeout = Duration.ofSeconds(30);

    @SuppressWarnings("java:S1068")
    private long memoryBudgetBytes = 64L * 1024 * 1024;
  }
}
