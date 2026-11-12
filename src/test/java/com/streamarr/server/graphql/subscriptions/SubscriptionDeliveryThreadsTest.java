package com.streamarr.server.graphql.subscriptions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.support.BoundedTask;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;

@Tag("UnitTest")
@DisplayName("Subscription Delivery Threads Tests")
class SubscriptionDeliveryThreadsTest {

  private static final Duration BOUND = Duration.ofSeconds(10);
  private static final String DELIVERY_FINISHED = "delivery finished";
  private static final String CLOSED = "closed";

  private final SubscriptionDeliveryThreads deliveryThreads = new SubscriptionDeliveryThreads();
  private final Queue<String> events = new ConcurrentLinkedQueue<>();

  @AfterEach
  void closeDeliveryThreads() {
    deliveryThreads.close();
  }

  @Test
  @DisplayName("Should deliver on named virtual threads when a value is broadcast")
  void shouldDeliverOnNamedVirtualThreadsWhenValueIsBroadcast() throws Exception {
    var broadcaster = broadcaster(1);
    var deliveredOn = broadcaster.register().map(_ -> Thread.currentThread()).next().toFuture();

    broadcaster.broadcast(1);

    var thread = deliveredOn.get(BOUND.toSeconds(), TimeUnit.SECONDS);
    assertThat(thread.isVirtual()).as("delivery thread is virtual").isTrue();
    assertThat(thread.getName()).startsWith("graphql-subscription-delivery-");
  }

  @Test
  @DisplayName("Should deliver an operation's values in broadcast order when many are broadcast")
  void shouldDeliverOperationsValuesInBroadcastOrderWhenManyAreBroadcast() throws Exception {
    var values = IntStream.range(0, 64).boxed().toList();
    var broadcaster = broadcaster(values.size());
    var received = broadcaster.register().take(values.size()).collectList().toFuture();

    values.forEach(broadcaster::broadcast);

    assertThat(received.get(BOUND.toSeconds(), TimeUnit.SECONDS)).containsExactlyElementsOf(values);
  }

  @Test
  @DisplayName("Should let a running delivery finish uninterrupted when its operation cancels")
  void shouldLetRunningDeliveryFinishUninterruptedWhenItsOperationCancels() {
    var delivery = ParkedDelivery.start(broadcaster(1), events);

    delivery.cancelOperation();
    delivery.resume();

    assertThat(delivery.outcome()).isEqualTo(DeliveryOutcome.RESUMED);
  }

  @Test
  @DisplayName("Should end a new operation with a rejection when the delivery threads are closed")
  void shouldEndNewOperationWithRejectionWhenDeliveryThreadsAreClosed() {
    var broadcaster = broadcaster(1);
    deliveryThreads.close();
    var delivered = broadcaster.register().next().toFuture();

    broadcaster.broadcast(1);

    assertThat(delivered)
        .failsWithin(BOUND)
        .withThrowableOfType(ExecutionException.class)
        .withCauseInstanceOf(RejectedExecutionException.class);
  }

  @Test
  @DisplayName(
      "Should wait for a running delivery without interrupting it when the delivery threads close")
  void shouldWaitForRunningDeliveryWithoutInterruptingItWhenDeliveryThreadsClose()
      throws InterruptedException {
    var delivery = ParkedDelivery.start(broadcaster(1), events);
    var closing =
        Thread.ofPlatform()
            .start(
                () -> {
                  deliveryThreads.close();
                  events.add(CLOSED);
                });
    await().atMost(BOUND).until(() -> isWaitingOrFinished(closing));

    delivery.resume();

    assertThat(closing.join(BOUND)).as("close returned").isTrue();
    assertThat(events).containsExactly(DELIVERY_FINISHED, CLOSED);
    assertThat(delivery.outcome()).isEqualTo(DeliveryOutcome.RESUMED);
  }

  @Test
  @DisplayName(
      "Should interrupt a running delivery when the closing thread is interrupted while it waits")
  void shouldInterruptRunningDeliveryWhenClosingThreadIsInterruptedWhileItWaits() {
    var delivery = ParkedDelivery.start(broadcaster(1), events);
    var closedWithInterruptKept = new CompletableFuture<Boolean>();
    var closing =
        Thread.ofPlatform()
            .start(
                () -> {
                  deliveryThreads.close();
                  closedWithInterruptKept.complete(Thread.currentThread().isInterrupted());
                });
    await().atMost(BOUND).until(() -> closing.getState() == Thread.State.TIMED_WAITING);

    closing.interrupt();

    assertThat(delivery.outcome()).isEqualTo(DeliveryOutcome.INTERRUPTED);
    assertThat(closedWithInterruptKept).succeedsWithin(BOUND).isEqualTo(true);
  }

  @Test
  @DisplayName(
      "Should interrupt a running delivery and return when the delivery outlasts the close's"
          + " one-second wait")
  void shouldInterruptRunningDeliveryAndReturnWhenTheDeliveryOutlastsTheClosesWait()
      throws Exception {
    var delivery = ParkedDelivery.start(broadcaster(1), events);

    BoundedTask.runWithin(Duration.ofSeconds(3), deliveryThreads::close);

    assertThat(delivery.outcome()).isEqualTo(DeliveryOutcome.INTERRUPTED);
  }

  private SubscriptionBroadcaster<Integer> broadcaster(int operationBuffer) {
    return SubscriptionBroadcaster.<Integer>builder()
        .field("libraryStatusChanged")
        .meterRegistry(new SimpleMeterRegistry())
        .deliveryScheduler(deliveryThreads.scheduler())
        .operationBuffer(operationBuffer)
        .build();
  }

  // The closing thread either waits for the running delivery or, if close does not wait, is done.
  private static boolean isWaitingOrFinished(Thread thread) {
    var state = thread.getState();
    return state == Thread.State.TIMED_WAITING
        || state == Thread.State.WAITING
        || state == Thread.State.TERMINATED;
  }

  private enum DeliveryOutcome {
    RESUMED,
    INTERRUPTED,
    TIMED_OUT
  }

  // A delivery parked inside its subscriber until the test resumes it.
  private static final class ParkedDelivery {

    private final CountDownLatch delivering = new CountDownLatch(1);
    private final CountDownLatch resume = new CountDownLatch(1);
    private final CompletableFuture<DeliveryOutcome> outcome = new CompletableFuture<>();
    private final Queue<String> events;
    private Disposable operation;

    private ParkedDelivery(Queue<String> events) {
      this.events = events;
    }

    static ParkedDelivery start(
        SubscriptionBroadcaster<Integer> broadcaster, Queue<String> events) {
      var delivery = new ParkedDelivery(events);
      delivery.operation = broadcaster.register().subscribe(_ -> delivery.park());
      broadcaster.broadcast(1);
      delivery.awaitDelivering();
      return delivery;
    }

    void cancelOperation() {
      operation.dispose();
    }

    void resume() {
      resume.countDown();
    }

    DeliveryOutcome outcome() {
      return outcome.orTimeout(BOUND.toSeconds(), TimeUnit.SECONDS).join();
    }

    private void park() {
      delivering.countDown();
      try {
        if (!resume.await(BOUND.toSeconds(), TimeUnit.SECONDS)) {
          outcome.complete(DeliveryOutcome.TIMED_OUT);
          return;
        }

        events.add(DELIVERY_FINISHED);
        outcome.complete(DeliveryOutcome.RESUMED);
      } catch (InterruptedException _) {
        outcome.complete(DeliveryOutcome.INTERRUPTED);
      }
    }

    private void awaitDelivering() {
      try {
        assertThat(delivering.await(BOUND.toSeconds(), TimeUnit.SECONDS))
            .as("delivery started")
            .isTrue();
      } catch (InterruptedException failure) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Interrupted while awaiting the delivery.", failure);
      }
    }
  }
}
