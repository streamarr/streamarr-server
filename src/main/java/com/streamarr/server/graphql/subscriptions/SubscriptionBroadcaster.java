package com.streamarr.server.graphql.subscriptions;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.Builder;
import lombok.NonNull;
import reactor.core.Exceptions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.scheduler.Scheduler;

/**
 * Fans one subscription field's values out to every registered operation in process. Each
 * registration receives every value broadcast while it is registered, in broadcast order. Delivery
 * runs on the delivery scheduler, so GraphQL field resolution and serialization never run on the
 * thread that broadcasts. An operation that falls more than its buffer behind ends with {@link
 * SubscriptionOverflowException}; the others keep receiving.
 */
public final class SubscriptionBroadcaster<T> {

  public static final String ACTIVE_REGISTRATIONS_METRIC = "streamarr.graphql.subscriptions.active";

  private final Set<FluxSink<T>> registrations = ConcurrentHashMap.newKeySet();
  private final String field;
  private final Scheduler deliveryScheduler;
  private final int operationBuffer;

  @Builder
  private SubscriptionBroadcaster(
      @NonNull String field,
      @NonNull MeterRegistry meterRegistry,
      @NonNull Scheduler deliveryScheduler,
      int operationBuffer) {
    if (operationBuffer <= 0) {
      throw new IllegalArgumentException("The operation buffer must be positive.");
    }

    this.field = field;
    this.deliveryScheduler = deliveryScheduler;
    this.operationBuffer = operationBuffer;
    Gauge.builder(ACTIVE_REGISTRATIONS_METRIC, registrations, Set::size)
        .description("Subscription operations registered for a GraphQL subscription field")
        .tag("field", field)
        .register(meterRegistry);
  }

  /** The subscription field this broadcaster serves, as named in the schema. */
  public String field() {
    return field;
  }

  /**
   * A lazy registration: subscribing registers the operation, cancelling or overflowing releases
   * it. The delivery stage requests at most the operation buffer ahead of its subscriber; a
   * broadcast that finds no outstanding demand overflows the operation at once.
   */
  public Flux<T> register() {
    return Flux.<T>create(
            sink -> {
              registrations.add(sink);
              sink.onDispose(() -> registrations.remove(sink));
            },
            FluxSink.OverflowStrategy.ERROR)
        .publishOn(deliveryScheduler, false, operationBuffer)
        .onErrorMap(Exceptions::isOverflow, _ -> new SubscriptionOverflowException(field));
  }

  public void broadcast(T value) {
    registrations.forEach(sink -> sink.next(value));
  }
}
