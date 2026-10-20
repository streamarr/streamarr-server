package com.streamarr.server.graphql.subscriptions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.support.ActiveSubscriptions;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

@Tag("UnitTest")
@DisplayName("Subscription Broadcaster Tests")
class SubscriptionBroadcasterTest {

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
    var subscriber = new RecordingSubscriber<String>(Long.MAX_VALUE);
    broadcaster.register().subscribe(subscriber);

    broadcaster.broadcast("SCANNING");
    broadcaster.broadcast("HEALTHY");

    assertThat(subscriber.take()).isEqualTo("SCANNING");
    assertThat(subscriber.take()).isEqualTo("HEALTHY");
  }

  @Test
  @DisplayName("Should count a registration when an operation subscribes")
  void shouldCountRegistrationWhenOperationSubscribes() {
    assertThat(activeRegistrations()).isZero();

    broadcaster.register().subscribe(new RecordingSubscriber<String>(Long.MAX_VALUE));

    assertThat(activeRegistrations()).isEqualTo(1);
  }

  @Test
  @DisplayName("Should release the registration when the subscriber cancels")
  void shouldReleaseRegistrationWhenSubscriberCancels() {
    var subscriber = new RecordingSubscriber<String>(Long.MAX_VALUE);
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
    var stalled = new RecordingSubscriber<String>(0);
    var keepingUp = new RecordingSubscriber<String>(Long.MAX_VALUE);
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
    var subscriber = new RecordingSubscriber<String>(Long.MAX_VALUE);
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
    return new ActiveSubscriptions(registry).count("libraryStatusChanged");
  }
}
