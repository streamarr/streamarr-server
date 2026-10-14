package com.streamarr.server.graphql.subscriptions;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.Builder;
import lombok.NonNull;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.scheduler.Scheduler;

/**
 * Fans one subscription field's values out to every registered operation in process. Each
 * registration receives every value broadcast while it is registered, in broadcast order. Delivery
 * runs on the delivery scheduler, so GraphQL field resolution and serialization never run on the
 * thread that broadcasts.
 */
public final class SubscriptionBroadcaster<T> {

  public static final String ACTIVE_REGISTRATIONS_METRIC = "streamarr.graphql.subscriptions.active";

  private final Set<FluxSink<T>> registrations = ConcurrentHashMap.newKeySet();
  private final String field;
  private final Scheduler deliveryScheduler;

  @Builder
  private SubscriptionBroadcaster(
      @NonNull String field,
      @NonNull MeterRegistry meterRegistry,
      @NonNull Scheduler deliveryScheduler) {
    this.field = field;
    this.deliveryScheduler = deliveryScheduler;
    Gauge.builder(ACTIVE_REGISTRATIONS_METRIC, registrations, Set::size)
        .description("Subscription operations registered for a GraphQL subscription field")
        .tag("field", field)
        .register(meterRegistry);
  }

  /** The subscription field this broadcaster serves, as named in the schema. */
  public String field() {
    return field;
  }

  /** A lazy registration: subscribing registers the operation, cancelling releases it. */
  public Flux<T> register() {
    return Flux.<T>create(
            sink -> {
              registrations.add(sink);
              sink.onDispose(() -> registrations.remove(sink));
            })
        .publishOn(deliveryScheduler);
  }

  public void broadcast(T value) {
    registrations.forEach(sink -> sink.next(value));
  }
}
