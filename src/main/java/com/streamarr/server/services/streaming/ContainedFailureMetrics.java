package com.streamarr.server.services.streaming;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.NonNull;
import org.springframework.stereotype.Component;

/**
 * Counts the failures that streaming catches and carries on from, under one metric name. The {@code
 * operation} label names the operation that failed.
 */
@Component
public class ContainedFailureMetrics {

  private static final String CONTAINED_FAILURES = "streamarr.streaming.contained_failures";

  private final Counter learnedStreamEndStoreFailures;
  private final Counter segmentUploadDiscardFailures;
  private final Counter retentionReadFailures;
  private final Counter diskSpaceReadFailures;

  public ContainedFailureMetrics(@NonNull MeterRegistry meterRegistry) {
    learnedStreamEndStoreFailures = failuresOf("learned_stream_end_store", meterRegistry);
    segmentUploadDiscardFailures = failuresOf("segment_upload_discard", meterRegistry);
    retentionReadFailures = failuresOf("retention_read", meterRegistry);
    diskSpaceReadFailures = failuresOf("disk_space_read", meterRegistry);
  }

  private static Counter failuresOf(String operation, MeterRegistry meterRegistry) {
    return Counter.builder(CONTAINED_FAILURES)
        .description("Failures that streaming caught and carried on from, by the failed operation")
        .tag("operation", operation)
        .register(meterRegistry);
  }

  /** The database did not store a stream end time that a completed job attempt reported. */
  void countLearnedStreamEndStoreFailure() {
    learnedStreamEndStoreFailures.increment();
  }

  /** An ended segment upload's temporary file could not be deleted from the segment folder. */
  public void countSegmentUploadDiscardFailure() {
    segmentUploadDiscardFailures.increment();
  }

  /** The idle segment retention could not be read. */
  void countRetentionReadFailure() {
    retentionReadFailures.increment();
  }

  /** The free space of the segment folder could not be read. */
  void countDiskSpaceReadFailure() {
    diskSpaceReadFailures.increment();
  }
}
