package com.streamarr.server.services.streaming;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.NonNull;
import org.springframework.stereotype.Component;

/** Measures direct play: open responses, bytes served, and requests by their answer. */
@Component
public class DirectPlayMetrics {

  private static final String OPEN_RESPONSES = "streamarr.streaming.direct_play_open_responses";
  private static final String BYTES_SERVED = "streamarr.streaming.direct_play_bytes_served";
  private static final String REQUESTS = "streamarr.streaming.direct_play_requests";

  private final MeterRegistry meterRegistry;
  private final AtomicInteger openResponses = new AtomicInteger();
  private final Counter bytesServed;

  public DirectPlayMetrics(@NonNull MeterRegistry meterRegistry) {
    this.meterRegistry = meterRegistry;
    Gauge.builder(OPEN_RESPONSES, openResponses, AtomicInteger::get)
        .description("Direct-play responses whose source file is open")
        .register(meterRegistry);
    bytesServed =
        Counter.builder(BYTES_SERVED)
            .description("Bytes read from direct-play source files for responses")
            .baseUnit("bytes")
            .register(meterRegistry);
  }

  /** Counts one answer to a direct-play request by its HTTP status. */
  public void countAnswer(int status) {
    Counter.builder(REQUESTS)
        .description("Direct-play requests, by the status of their answer")
        .tag("status", String.valueOf(status))
        .register(meterRegistry)
        .increment();
  }

  void countOpenedSource() {
    openResponses.incrementAndGet();
  }

  void countClosedSource() {
    openResponses.decrementAndGet();
  }

  void countBytesServed(long count) {
    bytesServed.increment(count);
  }
}
