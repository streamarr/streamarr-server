package com.streamarr.server.services.streaming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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

  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private final HeldSegmentRequests heldRequests = new HeldSegmentRequests(meterRegistry);
  private final UUID sessionId = UUID.randomUUID();

  @Test
  @DisplayName("Should reject a missing meter registry when held requests are created")
  void shouldRejectMissingMeterRegistryWhenHeldRequestsAreCreated() {
    assertThatThrownBy(() -> new HeldSegmentRequests(null))
        .isInstanceOf(NullPointerException.class);
  }

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
  @DisplayName("Should count every request that holds a wake until the request closes it")
  void shouldCountEveryRequestThatHoldsWakeUntilTheRequestClosesIt() {
    var first = heldRequests.nextWake(sessionId, "720p");
    heldRequests.nextWake(sessionId, "720p");
    heldRequests.nextWake(sessionId, "1080p");

    first.close();

    assertThat(heldRequestCount()).isEqualTo(2);
  }

  @Test
  @DisplayName("Should fire a held wake when another request on its variant closed its own wake")
  void shouldFireHeldWakeWhenAnotherRequestOnItsVariantClosedItsOwnWake() throws Exception {
    var closed = heldRequests.nextWake(sessionId, "720p");
    var held = heldRequests.nextWake(sessionId, "720p");
    closed.close();

    heldRequests.wakeVariant(sessionId, "720p");

    assertThat(held.await(BOUNDED_WAIT)).isTrue();
  }

  @Test
  @DisplayName("Should keep the next wake held when a request closes a wake that already fired")
  void shouldKeepNextWakeHeldWhenARequestClosesAWakeThatAlreadyFired() throws Exception {
    var fired = heldRequests.nextWake(sessionId, "720p");
    heldRequests.wakeVariant(sessionId, "720p");
    var next = heldRequests.nextWake(sessionId, "720p");

    fired.close();
    heldRequests.wakeVariant(sessionId, "720p");

    assertThat(next.await(BOUNDED_WAIT)).isTrue();
    assertThat(heldRequestCount()).isZero();
  }

  @Test
  @DisplayName("Should count no request when every wake has fired")
  void shouldCountNoRequestWhenEveryWakeHasFired() {
    heldRequests.nextWake(sessionId, "720p");
    heldRequests.nextWake(sessionId, "1080p");

    heldRequests.wakeSession(sessionId);

    assertThat(heldRequestCount()).isZero();
  }

  private double heldRequestCount() {
    return meterRegistry.get("streamarr.streaming.held_segment_requests").gauge().value();
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
