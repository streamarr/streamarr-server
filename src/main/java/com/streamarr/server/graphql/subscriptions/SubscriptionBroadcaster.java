package com.streamarr.server.graphql.subscriptions;

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
 * thread that broadcasts. An operation that falls more than its buffer behind ends with {@link
 * SubscriptionOverflowException}; the others keep receiving.
 */
public final class SubscriptionBroadcaster<T> {

  private final Set<FluxSink<T>> registrations = ConcurrentHashMap.newKeySet();
  private final SubscriptionDelivery delivery;

  @Builder
  private SubscriptionBroadcaster(
      @NonNull String field,
      @NonNull MeterRegistry meterRegistry,
      @NonNull Scheduler deliveryScheduler,
      int operationBuffer) {
    this.delivery = new SubscriptionDelivery(field, deliveryScheduler, operationBuffer);
    delivery.countRegistrations(meterRegistry, registrations, Set::size);
  }

  /** The subscription field this broadcaster serves, as named in the schema. */
  public String field() {
    return delivery.field();
  }

  /**
   * A lazy registration: subscribing registers the operation, cancelling or overflowing releases
   * it. The delivery stage requests at most the operation buffer ahead of its subscriber; a
   * broadcast that finds no outstanding demand overflows the operation at once.
   */
  public Flux<T> register() {
    return delivery.register(
        sink -> {
          registrations.add(sink);
          sink.onDispose(() -> registrations.remove(sink));
        });
  }

  public void broadcast(T value) {
    registrations.forEach(sink -> sink.next(value));
  }
}
