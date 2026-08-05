package com.streamarr.server.services.streaming.remote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.fakes.MutableClock;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Segment Upload Admission Tests")
class SegmentUploadAdmissionTest {

  private static final UUID WORKER_A = UUID.randomUUID();
  private static final UUID WORKER_B = UUID.randomUUID();

  @Test
  @DisplayName("Should not inflate slot capacity when a ticket is closed repeatedly")
  void shouldNotInflateSlotCapacityWhenATicketIsClosedRepeatedly() {
    var admission = new SegmentUploadAdmission(1, 100, 8);
    var ticket = admission.tryAdmit(UUID.randomUUID()).orElseThrow();

    ticket.close();
    ticket.close();

    assertThat(admission.tryAdmit(UUID.randomUUID())).isPresent();
    assertThat(admission.tryAdmit(UUID.randomUUID())).isEmpty();
  }

  @Test
  @DisplayName("Should reject a non-positive byte reservation when admitting an upload")
  void shouldRejectANonPositiveByteReservationWhenAdmittingUpload() {
    var admission = new SegmentUploadAdmission(1, 100, 8);
    try (var ticket = admission.tryAdmit(UUID.randomUUID()).orElseThrow()) {
      assertThatThrownBy(() -> ticket.tryReserve(-50)).isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> ticket.tryReserve(0)).isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  @DisplayName("Should enforce the shared byte budget across tickets when admitting an upload")
  void shouldEnforceTheSharedByteBudgetAcrossTicketsWhenAdmittingUpload() {
    var admission = new SegmentUploadAdmission(4, 100, 8);
    var first = admission.tryAdmit(UUID.randomUUID()).orElseThrow();
    var second = admission.tryAdmit(UUID.randomUUID()).orElseThrow();
    var third = admission.tryAdmit(UUID.randomUUID()).orElseThrow();

    assertThat(first.tryReserve(60)).isTrue();
    assertThat(second.tryReserve(41)).isFalse();
    assertThat(third.tryReserve(40)).isTrue();

    first.close();
    assertThat(second.tryReserve(60)).isTrue();
  }

  @Test
  @DisplayName("Should release the byte reservation together with the slot when a ticket closes")
  void shouldReleaseTheByteReservationTogetherWithTheSlotWhenATicketCloses() {
    var admission = new SegmentUploadAdmission(2, 100, 8);
    var ticket = admission.tryAdmit(UUID.randomUUID()).orElseThrow();
    assertThat(ticket.tryReserve(100)).isTrue();

    ticket.close();

    try (var successor = admission.tryAdmit(UUID.randomUUID()).orElseThrow()) {
      assertThat(successor.tryReserve(100)).isTrue();
    }
  }

  @Test
  @DisplayName("Should refuse a reservation when the ticket is closed")
  void shouldRefuseAReservationWhenTheTicketIsClosed() {
    var admission = new SegmentUploadAdmission(1, 100, 8);
    var ticket = admission.tryAdmit(UUID.randomUUID()).orElseThrow();
    ticket.close();

    assertThatThrownBy(() -> ticket.tryReserve(10)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("Should cap the uploads one worker can hold concurrently when admitting an upload")
  void shouldCapTheUploadsOneWorkerCanHoldConcurrentlyWhenAdmittingUpload() {
    var admission = new SegmentUploadAdmission(8, 100, 2);

    assertThat(admission.tryAdmit(WORKER_A)).isPresent();
    assertThat(admission.tryAdmit(WORKER_A)).isPresent();

    assertThat(admission.tryAdmit(WORKER_A)).isEmpty();
  }

  @Test
  @DisplayName(
      "Should keep one worker's wedged uploads from starving the rest of the fleet when admitting an upload")
  void shouldKeepOneWorkersWedgedUploadsFromStarvingTheFleetWhenAdmittingUpload() {
    var admission = new SegmentUploadAdmission(8, 100, 2);
    // Worker A opens its whole allowance and never closes either ticket.
    admission.tryAdmit(WORKER_A).orElseThrow();
    admission.tryAdmit(WORKER_A).orElseThrow();

    assertThat(admission.tryAdmit(WORKER_B)).isPresent();
  }

  @Test
  @DisplayName("Should return a worker's allowance when its ticket is closed")
  void shouldReturnAWorkersAllowanceWhenItsTicketIsClosed() {
    var admission = new SegmentUploadAdmission(8, 100, 1);
    var ticket = admission.tryAdmit(WORKER_A).orElseThrow();
    assertThat(admission.tryAdmit(WORKER_A)).isEmpty();

    ticket.close();
    ticket.close();

    assertThat(admission.tryAdmit(WORKER_A)).isPresent();
    assertThat(admission.tryAdmit(WORKER_A)).isEmpty();
  }

  @Test
  @DisplayName(
      "Should still bound total concurrent uploads across all workers when admitting an upload")
  void shouldStillBoundTotalConcurrentUploadsAcrossAllWorkersWhenAdmittingUpload() {
    var admission = new SegmentUploadAdmission(2, 100, 2);
    admission.tryAdmit(WORKER_A).orElseThrow();
    admission.tryAdmit(WORKER_A).orElseThrow();

    assertThat(admission.tryAdmit(WORKER_B)).isEmpty();
  }

  @Test
  @DisplayName(
      "Should reclaim an upload that outlived its deadline without completing when admitting an upload")
  void shouldReclaimAnUploadThatOutlivedItsDeadlineWithoutCompletingWhenAdmittingUpload() {
    var clock = new MutableClock();
    var admission = new SegmentUploadAdmission(1, 100, 8, Duration.ofSeconds(30), clock);
    // A stream that sent metadata and then stopped: slot taken, bytes reserved, never closed.
    var abandoned = admission.tryAdmit(WORKER_A).orElseThrow();
    assertThat(abandoned.tryReserve(100)).isTrue();
    assertThat(admission.tryAdmit(WORKER_B)).isEmpty();

    clock.advance(Duration.ofSeconds(31));

    try (var successor = admission.tryAdmit(WORKER_B).orElseThrow()) {
      assertThat(successor.tryReserve(100))
          .as("the abandoned reservation must be reclaimed, not merely its slot")
          .isTrue();
    }
  }

  @Test
  @DisplayName(
      "Should not reclaim an upload that is still inside its deadline when admitting an upload")
  void shouldNotReclaimAnUploadThatIsStillInsideItsDeadlineWhenAdmittingUpload() {
    var clock = new MutableClock();
    var admission = new SegmentUploadAdmission(1, 100, 8, Duration.ofSeconds(30), clock);
    admission.tryAdmit(WORKER_A).orElseThrow();

    clock.advance(Duration.ofSeconds(29));

    assertThat(admission.tryAdmit(WORKER_B)).isEmpty();
  }

  @Test
  @DisplayName("Should not double-release capacity when a reclaimed ticket is later closed")
  void shouldNotDoubleReleaseCapacityWhenAReclaimedTicketIsLaterClosed() {
    var clock = new MutableClock();
    var admission = new SegmentUploadAdmission(1, 100, 8, Duration.ofSeconds(30), clock);
    var abandoned = admission.tryAdmit(WORKER_A).orElseThrow();
    clock.advance(Duration.ofSeconds(31));
    var successor = admission.tryAdmit(WORKER_B).orElseThrow();

    // The wedged stream finally errors out and closes its already-reclaimed ticket.
    abandoned.close();

    assertThat(admission.tryAdmit(WORKER_A))
        .as("a reclaimed ticket must not hand back capacity a second time")
        .isEmpty();
    successor.close();
  }
}
