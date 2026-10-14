package com.streamarr.server.graphql.subscriptions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
  private static final int OPERATION_BUFFER = 2;

  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private final Scheduler deliveryScheduler = Schedulers.newSingle("subscription-broadcaster-test");
  private final SubscriptionBroadcaster<String> broadcaster =
      SubscriptionBroadcaster.<String>builder()
          .field("libraryStatusChanged")
          .meterRegistry(meterRegistry)
          .deliveryScheduler(deliveryScheduler)
          .operationBuffer(OPERATION_BUFFER)
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

  @Test
  @DisplayName("Should fail only the operation whose buffer overflows when it stops requesting")
  void shouldFailOnlyOperationWhoseBufferOverflowsWhenItStopsRequesting() {
    // Immediate delivery renews the keeping-up operation's demand before the next broadcast. With
    // a delivery hop, a burst larger than the buffer can outrun even a subscriber that keeps up.
    var immediateMeterRegistry = new SimpleMeterRegistry();
    var immediateBroadcaster =
        SubscriptionBroadcaster.<String>builder()
            .field("libraryStatusChanged")
            .meterRegistry(immediateMeterRegistry)
            .deliveryScheduler(Schedulers.immediate())
            .operationBuffer(OPERATION_BUFFER)
            .build();
    var stalled = new RecordingSubscriber(0);
    var keepingUp = new RecordingSubscriber(Long.MAX_VALUE);
    immediateBroadcaster.register().subscribe(stalled);
    immediateBroadcaster.register().subscribe(keepingUp);

    for (var change = 0; change <= OPERATION_BUFFER; change++) {
      immediateBroadcaster.broadcast("change-" + change);
    }

    assertThat(stalled.awaitError()).isInstanceOf(SubscriptionOverflowException.class);
    for (var change = 0; change <= OPERATION_BUFFER; change++) {
      assertThat(keepingUp.take()).isEqualTo("change-" + change);
    }

    assertThat(activeRegistrations(immediateMeterRegistry)).isEqualTo(1);
  }

  @Test
  @DisplayName("Should deliver on the delivery scheduler when a value is broadcast")
  void shouldDeliverOnDeliverySchedulerWhenValueIsBroadcast() {
    var subscriber = new RecordingSubscriber(Long.MAX_VALUE);
    broadcaster.register().subscribe(subscriber);

    broadcaster.broadcast("SCANNING");

    subscriber.take();
    assertThat(subscriber.deliveryThreads())
        .singleElement()
        .isNotEqualTo(Thread.currentThread().getName())
        .asString()
        .startsWith("subscription-broadcaster-test");
  }

  @Test
  @DisplayName("Should refuse to build when a required collaborator is missing")
  void shouldRefuseToBuildWhenRequiredCollaboratorIsMissing() {
    var withoutField =
        SubscriptionBroadcaster.<String>builder()
            .meterRegistry(meterRegistry)
            .deliveryScheduler(deliveryScheduler)
            .operationBuffer(OPERATION_BUFFER);
    var withoutMeterRegistry =
        SubscriptionBroadcaster.<String>builder()
            .field("libraryStatusChanged")
            .deliveryScheduler(deliveryScheduler)
            .operationBuffer(OPERATION_BUFFER);
    var withoutDeliveryScheduler =
        SubscriptionBroadcaster.<String>builder()
            .field("libraryStatusChanged")
            .meterRegistry(meterRegistry)
            .operationBuffer(OPERATION_BUFFER);

    assertThatThrownBy(withoutField::build).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(withoutMeterRegistry::build).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(withoutDeliveryScheduler::build).isInstanceOf(NullPointerException.class);
  }

  @Test
  @DisplayName("Should refuse to build when the operation buffer is not positive")
  void shouldRefuseToBuildWhenOperationBufferIsNotPositive() {
    var builder =
        SubscriptionBroadcaster.<String>builder()
            .field("libraryStatusChanged")
            .meterRegistry(meterRegistry)
            .deliveryScheduler(deliveryScheduler)
            .operationBuffer(0);

    assertThatThrownBy(builder::build).isInstanceOf(IllegalArgumentException.class);
  }

  private double activeRegistrations() {
    return activeRegistrations(meterRegistry);
  }

  private static double activeRegistrations(SimpleMeterRegistry registry) {
    return registry
        .get("streamarr.graphql.subscriptions.active")
        .tag("field", "libraryStatusChanged")
        .gauge()
        .value();
  }

  /** Requests a fixed demand up front and keeps every signal for the test to take. */
  private static final class RecordingSubscriber extends BaseSubscriber<String> {

    private final long initialDemand;
    private final BlockingQueue<String> values = new LinkedBlockingQueue<>();
    private final Queue<String> deliveryThreads = new ConcurrentLinkedQueue<>();
    private final CompletableFuture<Throwable> error = new CompletableFuture<>();

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
      deliveryThreads.add(Thread.currentThread().getName());
      values.add(value);
    }

    @Override
    protected void hookOnError(Throwable throwable) {
      error.complete(throwable);
    }

    private Throwable awaitError() {
      try {
        return error.get(BOUND.toMillis(), TimeUnit.MILLISECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new AssertionError("Interrupted while waiting for an error", e);
      } catch (ExecutionException | TimeoutException e) {
        throw new AssertionError("No error within " + BOUND, e);
      }
    }

    private List<String> deliveryThreads() {
      return List.copyOf(deliveryThreads);
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
