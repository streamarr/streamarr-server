package com.streamarr.server.graphql.subscriptions;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscription;
import reactor.core.publisher.BaseSubscriber;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

@Tag("UnitTest")
@DisplayName("Subscription Broadcaster Tests")
class SubscriptionBroadcasterTest {

  private static final Duration BOUND = Duration.ofSeconds(5);

  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private final Scheduler deliveryScheduler = Schedulers.newSingle("subscription-broadcaster-test");
  private final SubscriptionBroadcaster<String> broadcaster =
      SubscriptionBroadcaster.<String>builder()
          .field("libraryStatusChanged")
          .meterRegistry(meterRegistry)
          .deliveryScheduler(deliveryScheduler)
          .build();

  @AfterEach
  void disposeScheduler() {
    deliveryScheduler.dispose();
  }

  @Test
  @DisplayName("Should deliver every broadcast in order when the subscriber requests unbounded")
  void shouldDeliverEveryBroadcastInOrderWhenSubscriberRequestsUnbounded() {
    var subscriber = new RecordingSubscriber(Long.MAX_VALUE);
    broadcaster.register().subscribe(subscriber);

    broadcaster.broadcast("SCANNING");
    broadcaster.broadcast("HEALTHY");

    assertThat(subscriber.take()).isEqualTo("SCANNING");
    assertThat(subscriber.take()).isEqualTo("HEALTHY");
  }

  @Test
  @DisplayName("Should count a registration while it is subscribed")
  void shouldCountRegistrationWhileItIsSubscribed() {
    assertThat(activeRegistrations()).isZero();

    broadcaster.register().subscribe(new RecordingSubscriber(Long.MAX_VALUE));

    assertThat(activeRegistrations()).isEqualTo(1);
  }

  @Test
  @DisplayName("Should release the registration when the subscriber cancels")
  void shouldReleaseRegistrationWhenSubscriberCancels() {
    var subscriber = new RecordingSubscriber(Long.MAX_VALUE);
    broadcaster.register().subscribe(subscriber);

    subscriber.cancel();

    assertThat(activeRegistrations()).isZero();
  }

  private double activeRegistrations() {
    return meterRegistry
        .get("streamarr.graphql.subscriptions.active")
        .tag("field", "libraryStatusChanged")
        .gauge()
        .value();
  }

  /** Requests a fixed demand up front and keeps every signal for the test to take. */
  private static final class RecordingSubscriber extends BaseSubscriber<String> {

    private final long initialDemand;
    private final BlockingQueue<String> values = new LinkedBlockingQueue<>();

    private RecordingSubscriber(long initialDemand) {
      this.initialDemand = initialDemand;
    }

    @Override
    protected void hookOnSubscribe(Subscription subscription) {
      if (initialDemand > 0) {
        request(initialDemand);
      }
    }

    @Override
    protected void hookOnNext(String value) {
      values.add(value);
    }

    private String take() {
      try {
        var value = values.poll(BOUND.toMillis(), TimeUnit.MILLISECONDS);
        assertThat(value).as("a delivered value within %s", BOUND).isNotNull();
        return value;
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new AssertionError("Interrupted while waiting for a delivery", e);
      }
    }
  }
}
