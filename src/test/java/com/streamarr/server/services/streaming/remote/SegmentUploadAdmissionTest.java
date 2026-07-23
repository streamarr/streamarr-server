package com.streamarr.server.services.streaming.remote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Segment Upload Admission Tests")
class SegmentUploadAdmissionTest {

  @Test
  @DisplayName("Should not inflate slot capacity when a ticket is closed repeatedly")
  void shouldNotInflateSlotCapacityWhenATicketIsClosedRepeatedly() {
    var admission = new SegmentUploadAdmission(1, 100);
    var ticket = admission.tryAdmit().orElseThrow();

    ticket.close();
    ticket.close();

    assertThat(admission.tryAdmit()).isPresent();
    assertThat(admission.tryAdmit()).isEmpty();
  }

  @Test
  @DisplayName("Should reject a non-positive byte reservation")
  void shouldRejectANonPositiveByteReservation() {
    var admission = new SegmentUploadAdmission(1, 100);
    try (var ticket = admission.tryAdmit().orElseThrow()) {
      assertThatThrownBy(() -> ticket.tryReserve(-50)).isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> ticket.tryReserve(0)).isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  @DisplayName("Should enforce the shared byte budget across tickets")
  void shouldEnforceTheSharedByteBudgetAcrossTickets() {
    var admission = new SegmentUploadAdmission(4, 100);
    var first = admission.tryAdmit().orElseThrow();
    var second = admission.tryAdmit().orElseThrow();
    var third = admission.tryAdmit().orElseThrow();

    assertThat(first.tryReserve(60)).isTrue();
    assertThat(second.tryReserve(60)).isFalse();
    assertThat(third.tryReserve(40)).isTrue();

    first.close();
    assertThat(second.tryReserve(60)).isTrue();
  }

  @Test
  @DisplayName("Should release the byte reservation together with the slot when a ticket closes")
  void shouldReleaseTheByteReservationTogetherWithTheSlotWhenATicketCloses() {
    var admission = new SegmentUploadAdmission(2, 100);
    var ticket = admission.tryAdmit().orElseThrow();
    assertThat(ticket.tryReserve(100)).isTrue();

    ticket.close();

    try (var successor = admission.tryAdmit().orElseThrow()) {
      assertThat(successor.tryReserve(100)).isTrue();
    }
  }

  @Test
  @DisplayName("Should refuse a reservation when the ticket is closed")
  void shouldRefuseAReservationWhenTheTicketIsClosed() {
    var admission = new SegmentUploadAdmission(1, 100);
    var ticket = admission.tryAdmit().orElseThrow();
    ticket.close();

    assertThatThrownBy(() -> ticket.tryReserve(10)).isInstanceOf(IllegalStateException.class);
  }
}
