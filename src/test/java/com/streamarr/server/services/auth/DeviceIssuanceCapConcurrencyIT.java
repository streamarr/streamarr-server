package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.config.security.DeviceAuthProperties;
import com.streamarr.server.exceptions.TooManyDeviceAttemptsException;
import com.streamarr.server.repositories.auth.DeviceAuthorizationRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
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
  void shouldNeverExceedOutstandingCapWhenIssuanceRequestedConcurrently() throws Exception {
    var cap = properties.maxOutstandingCodes();
    // Fill to one below the cap, so every racing caller sees room for exactly one more.
    for (var issued = 0; issued < cap - 1; issued++) {
      deviceAuthorizationService.issue("Filler");
    }

    var racers = 8;
    var start = new CyclicBarrier(racers);
    var attempts =
        IntStream.range(0, racers)
            .mapToObj(
                _ ->
                    (java.util.concurrent.Callable<IssuedDeviceCode>)
                        () -> {
                          start.await(20, TimeUnit.SECONDS);
                          return deviceAuthorizationService.issue("Racer");
                        })
            .toList();
    var accepted = new ArrayList<IssuedDeviceCode>();
    var refused = new ArrayList<Throwable>();

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var futures = executor.invokeAll(attempts, 20, TimeUnit.SECONDS);
      assertThat(futures).noneMatch(java.util.concurrent.Future::isCancelled);
      for (var future : futures) {
        try {
          accepted.add(future.get());
        } catch (ExecutionException refusal) {
          refused.add(refusal.getCause());
        }
      }
    }

    // Exactly one slot was free, so exactly one racer may win and the table must land on the cap.
    assertThat(accepted).hasSize(1);
    assertThat(refused)
        .hasSize(racers - 1)
        .allSatisfy(
            failure -> assertThat(failure).isInstanceOf(TooManyDeviceAttemptsException.class));
    assertThat(authorizationRepository.countOutstanding(Instant.now())).isEqualTo(cap);
  }
}
