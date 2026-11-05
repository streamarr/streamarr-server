package com.streamarr.server.graphql.subscriptions;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import reactor.core.scheduler.Scheduler;

@Tag("UnitTest")
@DisplayName("GraphQL Subscription Configuration Tests")
class GraphQlSubscriptionConfigurationTest {

  private static final long BOUND_SECONDS = 10;

  private final Scheduler deliveryScheduler =
      new GraphQlSubscriptionConfiguration().graphQlSubscriptionDeliveryScheduler();

  @AfterEach
  void disposeScheduler() {
    deliveryScheduler.dispose();
  }

  @Test
  @DisplayName("Should let a running delivery finish uninterrupted when its operation cancels")
  void shouldLetRunningDeliveryFinishUninterruptedWhenItsOperationCancels() throws Exception {
    var broadcaster =
        SubscriptionBroadcaster.<String>builder()
            .field("libraryStatusChanged")
            .meterRegistry(new SimpleMeterRegistry())
            .deliveryScheduler(deliveryScheduler)
            .operationBuffer(1)
            .build();
    var delivering = new CountDownLatch(1);
    var resume = new CountDownLatch(1);
    var resumed = new CompletableFuture<Boolean>();
    var operation =
        broadcaster
            .register()
            .subscribe(
                _ -> {
                  delivering.countDown();
                  try {
                    resumed.complete(resume.await(BOUND_SECONDS, TimeUnit.SECONDS));
                  } catch (InterruptedException _) {
                    resumed.complete(false);
                  }
                });
    broadcaster.broadcast("SCANNING");
    assertThat(delivering.await(BOUND_SECONDS, TimeUnit.SECONDS)).isTrue();

    operation.dispose();
    resume.countDown();

    assertThat(resumed.get(BOUND_SECONDS, TimeUnit.SECONDS)).isTrue();
  }
}
