package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.config.security.DeviceAuthProperties;
import com.streamarr.server.exceptions.TooManyDeviceAttemptsException;
import com.streamarr.server.repositories.auth.DeviceAuthorizationRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The outstanding-code cap is advertised as a hard global limit, and it is the only thing bounding
 * how many guessable user codes exist at once. A count-then-insert sequence would let every
 * concurrent caller read the same under-cap count and insert anyway.
 */
@Tag("IntegrationTest")
@DisplayName("Device Issuance Cap Concurrency Integration Tests")
class DeviceIssuanceCapConcurrencyIT extends AbstractIntegrationTest {

  @Autowired private DeviceAuthorizationService deviceAuthorizationService;

  @Autowired private DeviceAuthorizationRepository authorizationRepository;

  @Autowired private DeviceAuthProperties properties;

  @AfterEach
  void deleteSeededRows() {
    authorizationRepository.deleteAll();
  }

  @Test
  @DisplayName("Should never exceed the outstanding cap when issuance is requested concurrently")
  void shouldNeverExceedOutstandingCapWhenIssuanceRequestedConcurrently() {
    var cap = properties.maxOutstandingCodes();
    // Fill to one below the cap, so every racing caller sees room for exactly one more.
    for (var issued = 0; issued < cap - 1; issued++) {
      deviceAuthorizationService.issue("Filler");
    }

    var racers = 8;
    var executor = Executors.newFixedThreadPool(racers);
    var startLatch = new CountDownLatch(1);
    var doneLatch = new CountDownLatch(racers);
    var accepted = new CopyOnWriteArrayList<IssuedDeviceCode>();
    var refused = new CopyOnWriteArrayList<Exception>();

    for (var racer = 0; racer < racers; racer++) {
      executor.submit(
          () -> {
            try {
              startLatch.await();
              accepted.add(deviceAuthorizationService.issue("Racer"));
            } catch (InterruptedException _) {
              Thread.currentThread().interrupt();
            } catch (Exception refusal) {
              refused.add(refusal);
            } finally {
              doneLatch.countDown();
            }
          });
    }

    startLatch.countDown();
    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(() -> assertThat(doneLatch.getCount()).isZero());
    executor.shutdown();

    // Exactly one slot was free, so exactly one racer may win and the table must land on the cap.
    assertThat(accepted).hasSize(1);
    assertThat(refused)
        .hasSize(racers - 1)
        .allSatisfy(
            failure -> assertThat(failure).isInstanceOf(TooManyDeviceAttemptsException.class));
    assertThat(authorizationRepository.countOutstanding(Instant.now())).isEqualTo(cap);
  }
}
