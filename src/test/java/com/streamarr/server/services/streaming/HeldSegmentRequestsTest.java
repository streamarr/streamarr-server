package com.streamarr.server.services.streaming;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Held Segment Requests Tests")
class HeldSegmentRequestsTest {

  private static final Duration NO_WAIT = Duration.ZERO;
  private static final Duration BOUNDED_WAIT = Duration.ofSeconds(5);

  private final HeldSegmentRequests heldRequests = new HeldSegmentRequests();
  private final UUID sessionId = UUID.randomUUID();

  @Test
  @DisplayName("Should fire a taken wake when its variant wakes after the wake was taken")
  void shouldFireTakenWakeWhenItsVariantWakesAfterTheWakeWasTaken() throws Exception {
    var wake = heldRequests.nextWake(sessionId, "720p");

    heldRequests.wakeVariant(sessionId, "720p");

    assertThat(wake.await(BOUNDED_WAIT)).isTrue();
  }

  @Test
  @DisplayName("Should share one wake between every request held on the same variant")
  void shouldShareOneWakeBetweenEveryRequestHeldOnTheSameVariant() throws Exception {
    var first = heldRequests.nextWake(sessionId, "720p");
    var second = heldRequests.nextWake(sessionId, "720p");

    heldRequests.wakeVariant(sessionId, "720p");

    assertThat(first.await(BOUNDED_WAIT)).isTrue();
    assertThat(second.await(BOUNDED_WAIT)).isTrue();
  }

  @Test
  @DisplayName("Should leave a wake unfired when another variant of the session wakes")
  void shouldLeaveWakeUnfiredWhenAnotherVariantOfTheSessionWakes() throws Exception {
    var wake = heldRequests.nextWake(sessionId, "720p");

    heldRequests.wakeVariant(sessionId, "1080p");
    heldRequests.wakeVariant(UUID.randomUUID(), "720p");

    assertThat(wake.await(NO_WAIT)).isFalse();
  }

  @Test
  @DisplayName("Should hand out an unfired wake when it is taken after its variant woke")
  void shouldHandOutUnfiredWakeWhenItIsTakenAfterItsVariantWoke() throws Exception {
    heldRequests.nextWake(sessionId, "720p");
    heldRequests.wakeVariant(sessionId, "720p");

    var wake = heldRequests.nextWake(sessionId, "720p");

    assertThat(wake.await(NO_WAIT)).isFalse();
  }

  @Test
  @DisplayName("Should fire the wake of every variant of a session when the session wakes")
  void shouldFireWakeOfEveryVariantOfSessionWhenTheSessionWakes() throws Exception {
    var standard = heldRequests.nextWake(sessionId, "720p");
    var high = heldRequests.nextWake(sessionId, "1080p");
    var otherSession = heldRequests.nextWake(UUID.randomUUID(), "720p");

    heldRequests.wakeSession(sessionId);

    assertThat(standard.await(BOUNDED_WAIT)).isTrue();
    assertThat(high.await(BOUNDED_WAIT)).isTrue();
    assertThat(otherSession.await(NO_WAIT)).isFalse();
  }
}
