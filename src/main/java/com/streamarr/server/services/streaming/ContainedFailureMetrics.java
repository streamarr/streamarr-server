package com.streamarr.server.services.streaming;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.NonNull;
import org.springframework.stereotype.Component;

/** Counts the failures that streaming catches and carries on from. */
@Component
public class ContainedFailureMetrics {

  private static final String LEARNED_STREAM_END_STORE_FAILURES =
      "streamarr.streaming.learned_stream_end_store_failures";
  private static final String SEGMENT_UPLOAD_DISCARD_FAILURES =
      "streamarr.streaming.segment_upload_discard_failures";
  private static final String RETENTION_READ_FAILURES =
      "streamarr.streaming.retention_read_failures";
  private static final String DISK_SPACE_READ_FAILURES =
      "streamarr.streaming.disk_space_read_failures";

  private final MeterRegistry meterRegistry;
  private final Counter learnedStreamEndStoreFailures;
  private final Counter segmentUploadDiscardFailures;

  public ContainedFailureMetrics(@NonNull MeterRegistry meterRegistry) {
    this.meterRegistry = meterRegistry;
    learnedStreamEndStoreFailures =
        Counter.builder(LEARNED_STREAM_END_STORE_FAILURES)
            .description(
                "Stream end times that a completed job attempt reported but the database did not"
                    + " store")
            .register(meterRegistry);
    segmentUploadDiscardFailures =
        Counter.builder(SEGMENT_UPLOAD_DISCARD_FAILURES)
            .description(
                "Ended segment uploads whose temporary file could not be deleted from the segment"
                    + " folder")
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
    meterRegistry.counter(RETENTION_READ_FAILURES).increment();
  }

  /** The free space of the segment folder could not be read. */
  void countDiskSpaceReadFailure() {
    meterRegistry.counter(DISK_SPACE_READ_FAILURES).increment();
  }
}
