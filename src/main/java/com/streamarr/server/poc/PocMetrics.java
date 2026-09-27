package com.streamarr.server.poc;

import com.streamarr.server.config.http.PacedTmdbGate;
import com.streamarr.server.services.library.admission.AdmissionRuntime;
import com.streamarr.server.services.library.admission.TaskOutcome;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** Registers the throwaway benchmark meters. The in-process sampler reads the same sources. */
@Component
public class PocMetrics {

  public PocMetrics(
      MeterRegistry registry, AdmissionRuntime runtime, PacedTmdbGate gate, HikariLive hikari) {
    Gauge.builder("poc.scan.inflight", runtime, AdmissionRuntime::inFlight)
        .description("Admitted scan or refresh tasks that have not finished")
        .register(registry);

    for (var outcome : TaskOutcome.values()) {
      FunctionCounter.builder("poc.scan.completed", runtime, r -> r.completed(outcome))
          .tag("outcome", outcome.tag())
          .register(registry);
    }

    FunctionCounter.builder("poc.scan.completed", runtime, AdmissionRuntime::failedCount)
        .tag("outcome", "failed")
        .register(registry);

    Gauge.builder("poc.admission.limit", runtime, AdmissionRuntime::admissionLimit)
        .description("Admission limit of the active strategy; NaN when it has none")
        .register(registry);

    Gauge.builder("poc.tmdb.backlog", gate, g -> g.backlogNanos() / 1e6)
        .baseUnit("milliseconds")
        .description("Queued TMDB schedule: the wait a request reserving now would get")
        .register(registry);

    FunctionCounter.builder("poc.tmdb.requests", gate, PacedTmdbGate::apiRequests)
        .tag("kind", "api")
        .register(registry);
    FunctionCounter.builder("poc.tmdb.requests", gate, PacedTmdbGate::imageRequests)
        .tag("kind", "image")
        .register(registry);
    FunctionCounter.builder("poc.tmdb.retries", gate, PacedTmdbGate::retries).register(registry);

    Gauge.builder("poc.hikari.awaiting", hikari, HikariLive::awaiting).register(registry);
    Gauge.builder("poc.hikari.active", hikari, HikariLive::active).register(registry);
  }
}
