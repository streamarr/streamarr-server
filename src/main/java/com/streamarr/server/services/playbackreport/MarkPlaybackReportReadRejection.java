package com.streamarr.server.services.playbackreport;

/** Why no playback report was marked read. */
public sealed interface MarkPlaybackReportReadRejection {

  record PlaybackReportMissing() implements MarkPlaybackReportReadRejection {}
}
