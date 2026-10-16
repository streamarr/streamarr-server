package com.streamarr.server.services.library;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Counts requests to start library operations and their executions, and times each operation. */
@Component
@RequiredArgsConstructor
public class LibraryOperationMetrics {

  static final String REQUESTS = "streamarr.library.operations.requests";
  static final String EXECUTIONS = "streamarr.library.operations.executions";
  static final String DURATION = "streamarr.library.operations.duration";

  private final MeterRegistry meterRegistry;

  /** How a request to start a library operation ended. */
  enum RequestOutcome {
    STORED,
    BUSY,
    NOT_FOUND,
    FAILED
  }

  /** How one execution of a library operation ended. */
  enum ExecutionResult {
    ENDED,
    INTERRUPTED,
    END_FAILED
  }

  void countRequest(LibraryOperationKind kind, RequestOutcome outcome) {
    Counter.builder(REQUESTS)
        .description("Requests to start a library operation, by outcome")
        .tag("kind", kind.tagValue())
        .tag("outcome", tagValue(outcome))
        .register(meterRegistry)
        .increment();
  }

  void countExecution(LibraryOperationKind kind, ExecutionResult result) {
    Counter.builder(EXECUTIONS)
        .description("Executions of library operations, by result")
        .tag("kind", kind.tagValue())
        .tag("result", tagValue(result))
        .register(meterRegistry)
        .increment();
  }

  void timeOperation(
      LibraryOperationKind kind, LibraryOperationConclusion conclusion, Duration duration) {
    Timer.builder(DURATION)
        .description("Time from the start of a library operation to its end")
        .tag("kind", kind.tagValue())
        .tag("conclusion", conclusion.tagValue())
        .register(meterRegistry)
        .record(duration);
  }

  private static String tagValue(Enum<?> value) {
    return value.name().toLowerCase(Locale.ROOT);
  }
}
