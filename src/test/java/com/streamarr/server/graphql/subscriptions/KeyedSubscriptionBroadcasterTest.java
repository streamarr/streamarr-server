package com.streamarr.server.graphql.subscriptions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.support.ActiveSubscriptions;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

@Tag("UnitTest")
@DisplayName("Keyed Subscription Broadcaster Tests")
class KeyedSubscriptionBroadcasterTest {

  private static final String FIELD = "watchStateChanged";
  private static final Duration BOUND = Duration.ofSeconds(10);
  private static final int OPERATION_BUFFER = 2;

  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private final Scheduler deliveryScheduler = Schedulers.newSingle("keyed-broadcaster-test");
  private final KeyedSubscriptionBroadcaster<String, String> broadcaster =
      broadcasterBuilder().deliveryScheduler(deliveryScheduler).build();

  @AfterEach
  void disposeScheduler() {
    deliveryScheduler.dispose();
  }

  @Test
  @DisplayName("Should deliver only to the broadcast key's registrations when two keys register")
  void shouldDeliverOnlyToBroadcastKeysRegistrationsWhenTwoKeysRegister() {
    var first = new RecordingSubscriber(Long.MAX_VALUE);
    var second = new RecordingSubscriber(Long.MAX_VALUE);
    broadcaster.register("profile-a").subscribe(first);
    broadcaster.register("profile-b").subscribe(second);

    broadcaster.broadcast("profile-a", "change-for-a");
    broadcaster.broadcast("profile-b", "sentinel-for-b");

    assertThat(first.take()).isEqualTo("change-for-a");
    assertThat(second.take()).isEqualTo("sentinel-for-b");
  }

  @Test
  @DisplayName("Should deliver to every registration of the key in broadcast order")
  void shouldDeliverToEveryRegistrationOfKeyInBroadcastOrder() {
    var first = new RecordingSubscriber(Long.MAX_VALUE);
    var second = new RecordingSubscriber(Long.MAX_VALUE);
    broadcaster.register("profile-a").subscribe(first);
    broadcaster.register("profile-a").subscribe(second);

    broadcaster.broadcast("profile-a", "first");
    broadcaster.broadcast("profile-a", "second");

    assertThat(first.take()).isEqualTo("first");
    assertThat(first.take()).isEqualTo("second");
    assertThat(second.take()).isEqualTo("first");
    assertThat(second.take()).isEqualTo("second");
  }

  @Test
  @DisplayName("Should count registrations of every key on the field gauge")
  void shouldCountRegistrationsOfEveryKeyOnFieldGauge() {
    broadcaster.register("profile-a").subscribe(new RecordingSubscriber(Long.MAX_VALUE));
    broadcaster.register("profile-a").subscribe(new RecordingSubscriber(Long.MAX_VALUE));
    broadcaster.register("profile-b").subscribe(new RecordingSubscriber(Long.MAX_VALUE));

    assertThat(activeRegistrations()).isEqualTo(3);
  }

  @Test
  @DisplayName("Should keep the other registration of the key when one registration cancels")
  void shouldKeepOtherRegistrationOfKeyWhenOneRegistrationCancels() {
    var cancelled = new RecordingSubscriber(Long.MAX_VALUE);
    var kept = new RecordingSubscriber(Long.MAX_VALUE);
    broadcaster.register("profile-a").subscribe(cancelled);
    broadcaster.register("profile-a").subscribe(kept);

    cancelled.cancel();
    broadcaster.broadcast("profile-a", "after-cancel");

    assertThat(kept.take()).isEqualTo("after-cancel");
    assertThat(activeRegistrations()).isEqualTo(1);
  }

  @Test
  @DisplayName("Should register the key again when its last registration cancelled earlier")
  void shouldRegisterKeyAgainWhenItsLastRegistrationCancelledEarlier() {
    var cancelled = new RecordingSubscriber(Long.MAX_VALUE);
    broadcaster.register("profile-a").subscribe(cancelled);
    cancelled.cancel();
    assertThat(activeRegistrations()).isZero();
    var renewed = new RecordingSubscriber(Long.MAX_VALUE);

    broadcaster.register("profile-a").subscribe(renewed);
    broadcaster.broadcast("profile-a", "after-renewal");

    assertThat(renewed.take()).isEqualTo("after-renewal");
  }

  @Test
  @DisplayName("Should ignore a broadcast when the key has no registration")
  void shouldIgnoreBroadcastWhenKeyHasNoRegistration() {
    var other = new RecordingSubscriber(Long.MAX_VALUE);
    broadcaster.register("profile-b").subscribe(other);

    broadcaster.broadcast("profile-a", "nobody-listens");
    broadcaster.broadcast("profile-b", "sentinel");

    assertThat(other.take()).isEqualTo("sentinel");
  }

  @Test
  @DisplayName("Should fail only the operation whose buffer overflows when it stops requesting")
  void shouldFailOnlyOperationWhoseBufferOverflowsWhenItStopsRequesting() {
    // Immediate delivery renews the keeping-up operation's demand before the next broadcast.
    var immediateMeterRegistry = new SimpleMeterRegistry();
    var immediate =
        KeyedSubscriptionBroadcaster.<String, String>builder()
            .field(FIELD)
            .meterRegistry(immediateMeterRegistry)
            .deliveryScheduler(Schedulers.immediate())
            .operationBuffer(OPERATION_BUFFER)
            .build();
    var stalled = new RecordingSubscriber(0);
    var keepingUp = new RecordingSubscriber(Long.MAX_VALUE);
    immediate.register("profile-a").subscribe(stalled);
    immediate.register("profile-a").subscribe(keepingUp);

    for (var change = 0; change <= OPERATION_BUFFER; change++) {
      immediate.broadcast("profile-a", "change-" + change);
    }

    assertThat(stalled.awaitError()).isInstanceOf(SubscriptionOverflowException.class);
    for (var change = 0; change <= OPERATION_BUFFER; change++) {
      assertThat(keepingUp.take()).isEqualTo("change-" + change);
    }

    assertThat(new ActiveSubscriptions(immediateMeterRegistry).count(FIELD)).isEqualTo(1);
  }

  @Test
  @DisplayName(
      "Should keep a registration of the key when other registrations of the key cancel at the"
          + " same time")
  void shouldKeepRegistrationOfKeyWhenOtherRegistrationsOfKeyCancelAtSameTime() throws Exception {
    var threads = 8;
    var rounds = 200;
    var start = new CyclicBarrier(threads + 1);
    var kept = new RecordingSubscriber(Long.MAX_VALUE);

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var churn =
          IntStream.range(0, threads)
              .mapToObj(
                  _ ->
                      executor.submit(
                          () -> {
                            start.await(BOUND.toMillis(), TimeUnit.MILLISECONDS);
                            for (var round = 0; round < rounds; round++) {
                              var shortLived = new RecordingSubscriber(Long.MAX_VALUE);
                              broadcaster.register("profile-a").subscribe(shortLived);
                              shortLived.cancel();
                            }

                            return null;
                          }))
              .toList();
      start.await(BOUND.toMillis(), TimeUnit.MILLISECONDS);
      broadcaster.register("profile-a").subscribe(kept);
      for (var future : churn) {
        future.get(BOUND.toMillis(), TimeUnit.MILLISECONDS);
      }
    }

    broadcaster.broadcast("profile-a", "after-churn");

    assertThat(kept.take()).isEqualTo("after-churn");
    assertThat(activeRegistrations()).isEqualTo(1);
  }

  @Test
  @DisplayName("Should name its field when asked for the metric tag")
  void shouldNameItsFieldWhenAskedForMetricTag() {
    assertThat(broadcaster.field()).isEqualTo(FIELD);
  }

  @Test
  @DisplayName("Should refuse to build when a required collaborator is missing")
  void shouldRefuseToBuildWhenRequiredCollaboratorIsMissing() {
    var withoutField =
        KeyedSubscriptionBroadcaster.<String, String>builder()
            .meterRegistry(meterRegistry)
            .deliveryScheduler(deliveryScheduler)
            .operationBuffer(OPERATION_BUFFER);
    var withoutMeterRegistry =
        KeyedSubscriptionBroadcaster.<String, String>builder()
            .field(FIELD)
            .deliveryScheduler(deliveryScheduler)
            .operationBuffer(OPERATION_BUFFER);
    var withoutDeliveryScheduler =
        KeyedSubscriptionBroadcaster.<String, String>builder()
            .field(FIELD)
            .meterRegistry(meterRegistry)
            .operationBuffer(OPERATION_BUFFER);

    assertThatThrownBy(withoutField::build).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(withoutMeterRegistry::build).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(withoutDeliveryScheduler::build).isInstanceOf(NullPointerException.class);
  }

  @Test
  @DisplayName("Should refuse to build when the operation buffer is not positive")
  void shouldRefuseToBuildWhenOperationBufferIsNotPositive() {
    var builder = broadcasterBuilder().deliveryScheduler(deliveryScheduler).operationBuffer(0);

    assertThatThrownBy(builder::build).isInstanceOf(IllegalArgumentException.class);
  }

  private KeyedSubscriptionBroadcaster.KeyedSubscriptionBroadcasterBuilder<String, String>
      broadcasterBuilder() {
    return KeyedSubscriptionBroadcaster.<String, String>builder()
        .field(FIELD)
        .meterRegistry(meterRegistry)
        .operationBuffer(OPERATION_BUFFER);
  }

  private double activeRegistrations() {
    return new ActiveSubscriptions(meterRegistry).count(FIELD);
  }
}
