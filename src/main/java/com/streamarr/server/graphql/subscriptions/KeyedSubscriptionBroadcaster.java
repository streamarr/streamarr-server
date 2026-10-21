package com.streamarr.server.graphql.subscriptions;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import lombok.Builder;
import lombok.NonNull;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.scheduler.Scheduler;

/**
 * Routes one subscription field's values to the operations registered for one key, such as a
 * Profile id. Each registration receives every value broadcast to its key while it is registered,
 * in broadcast order, and never a value broadcast to another key. Delivery and overflow behave as
 * in {@link SubscriptionBroadcaster}. The field's gauge counts registrations of every key and is
 * never tagged by key.
 */
public final class KeyedSubscriptionBroadcaster<K, T> {

  private final Map<K, Set<FluxSink<T>>> registrationsByKey = new ConcurrentHashMap<>();
  private final SubscriptionDelivery delivery;

  @Builder
  private KeyedSubscriptionBroadcaster(
      @NonNull String field,
      @NonNull MeterRegistry meterRegistry,
      @NonNull Scheduler deliveryScheduler,
      int operationBuffer) {
    this.delivery = new SubscriptionDelivery(field, deliveryScheduler, operationBuffer);
    delivery.countRegistrations(
        meterRegistry,
        registrationsByKey,
        registrations -> registrations.values().stream().mapToInt(Set::size).sum());
  }

  /** The subscription field this broadcaster serves, as named in the schema. */
  public String field() {
    return delivery.field();
  }

  /**
   * A lazy registration for one key: subscribing registers the operation, cancelling or overflowing
   * releases it. The last release of a key removes the key.
   */
  public Flux<T> register(@NonNull K key) {
    return register(key, () -> true);
  }

  /**
   * Checks the scope after registering, so completing a scope concurrently with its existence check
   * cannot strand a registration. A missing scope completes without an initial value.
   */
  public Flux<T> register(@NonNull K key, @NonNull BooleanSupplier scopeExists) {
    return delivery.register(
        sink -> {
          add(key, sink);
          sink.onDispose(() -> remove(key, sink));
          if (!scopeExists.getAsBoolean()) {
            sink.complete();
          }
        });
  }

  /** Completes the key's current registrations after their already-queued values. */
  public void complete(@NonNull K key) {
    var registrations = registrationsByKey.remove(key);
    if (registrations == null) {
      return;
    }

    registrations.forEach(FluxSink::complete);
  }

  public void broadcast(@NonNull K key, T value) {
    var registrations = registrationsByKey.get(key);
    if (registrations == null) {
      return;
    }

    registrations.forEach(sink -> sink.next(value));
  }

  // compute serializes adding and removing per key, so a release that empties a key never drops a
  // registration that arrives at the same time.
  private void add(K key, FluxSink<T> sink) {
    registrationsByKey.compute(
        key,
        (_, registrations) -> {
          var sinks = registrations;
          if (sinks == null) {
            sinks = ConcurrentHashMap.newKeySet();
          }

          sinks.add(sink);
          return sinks;
        });
  }

  private void remove(K key, FluxSink<T> sink) {
    registrationsByKey.computeIfPresent(
        key,
        (_, registrations) -> {
          registrations.remove(sink);
          if (registrations.isEmpty()) {
            return null;
          }

          return registrations;
        });
  }
}
