package com.streamarr.server.services.library.admission.adaptive;

import com.netflix.concurrency.limits.MetricRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.function.Supplier;

/**
 * Publishes the limiter's {@code call} counters as {@code poc.adaptive.calls{workload,status}}:
 * {@code success}, {@code ignored} and {@code dropped} count released permits; {@code rejected}
 * counts the times the walker found no free permit and had to wait. Distributions and the limit
 * gauge are not published: {@code poc.admission.limit} already reports the limit.
 */
final class LimiterCallMetrics implements MetricRegistry {

  static final String CALLS = "poc.adaptive.calls";

  private final MeterRegistry registry;
  private final String workload;

  LimiterCallMetrics(MeterRegistry registry, String workload) {
    this.registry = registry;
    this.workload = workload;
  }

  @Override
  public SampleListener distribution(String id, String... tagNameValuePairs) {
    return _ -> {};
  }

  @Override
  public void gauge(String id, Supplier<Number> supplier, String... tagNameValuePairs) {
    // poc.admission.limit reports the limit.
  }

  @Override
  public Counter counter(String id, String... tagNameValuePairs) {
    var counter =
        io.micrometer.core.instrument.Counter.builder(CALLS)
            .tag("workload", workload)
            .tag("status", tagValue(tagNameValuePairs, "status"))
            .register(registry);
    return counter::increment;
  }

  private static String tagValue(String[] tagNameValuePairs, String name) {
    for (var index = 0; index + 1 < tagNameValuePairs.length; index += 2) {
      if (name.equals(tagNameValuePairs[index])) {
        return tagNameValuePairs[index + 1];
      }
    }

    return "unknown";
  }
}
