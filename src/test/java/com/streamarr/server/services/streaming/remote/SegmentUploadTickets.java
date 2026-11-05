package com.streamarr.server.services.streaming.remote;

final class SegmentUploadTickets {

  private SegmentUploadTickets() {}

  static SegmentUploadAdmission.Ticket admitted(SegmentUploadAdmission.Admission admission) {
    return switch (admission) {
      case SegmentUploadAdmission.Admitted(var ticket) -> ticket;
      case SegmentUploadAdmission.Refused _ ->
          throw new AssertionError("Expected an admitted segment upload, got " + admission);
    };
  }
}
