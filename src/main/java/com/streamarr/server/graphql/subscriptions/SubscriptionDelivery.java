package com.streamarr.server.graphql.subscriptions;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.function.Consumer;
import java.util.function.ToDoubleFunction;
import lombok.NonNull;
import reactor.core.Exceptions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.scheduler.Scheduler;

/**
 * The delivery stage every broadcaster of one subscription field gives its registrations. Delivery
 * runs on the delivery scheduler, so GraphQL field resolution and serialization never run on the
 * thread that broadcasts. An operation that falls more than its buffer behind ends with {@link
 * SubscriptionOverflowException}.
 */
record SubscriptionDelivery(
    @NonNull String field, @NonNull Scheduler deliveryScheduler, int operationBuffer) {

  private static final String ACTIVE_REGISTRATIONS_METRIC =
      "streamarr.graphql.subscriptions.active";

  SubscriptionDelivery {
    if (operationBuffer <= 0) {
      throw new IllegalArgumentException("The operation buffer must be positive.");
    }
  }

  /** Reports the field's registered operations on the active-registrations gauge. */
  <S> void countRegistrations(
      MeterRegistry meterRegistry, S registrations, ToDoubleFunction<S> count) {
    Gauge.builder(ACTIVE_REGISTRATIONS_METRIC, registrations, count)
        .description("Subscription operations registered for a GraphQL subscription field")
        .tag("field", field)
        .register(meterRegistry);
  }

  /**
   * A lazy registration: subscribing runs {@code registration} with the operation's sink. The
   * delivery stage requests at most the operation buffer ahead of its subscriber; a value that
   * finds no outstanding demand overflows the operation at once.
   */
  <T> Flux<T> register(Consumer<FluxSink<T>> registration) {
    return Flux.create(registration, FluxSink.OverflowStrategy.ERROR)
        .publishOn(deliveryScheduler, false, operationBuffer)
        .onErrorMap(Exceptions::isOverflow, _ -> new SubscriptionOverflowException(field));
  }
}
