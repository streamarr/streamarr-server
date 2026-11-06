package com.streamarr.server.services.streaming.remote;

final class SegmentUploadTickets {

  private SegmentUploadTickets() {}

  static SegmentUploadAdmission.Ticket admitted(SegmentUploadAdmission.Admission admission) {
    return switch (admission) {
      case SegmentUploadAdmission.Admitted(var ticket) -> ticket;
      case SegmentUploadAdmission.WorkerLimitReached _,
          SegmentUploadAdmission.FleetLimitReached _ ->
          throw new AssertionError("Expected an admitted segment upload, got " + admission);
    };
  }
}
