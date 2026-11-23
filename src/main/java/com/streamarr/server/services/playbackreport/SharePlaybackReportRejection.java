package com.streamarr.server.services.playbackreport;

/** Why the server stored no playback report. */
public sealed interface SharePlaybackReportRejection {

  /** The viewer owns no open playback session with that id. An unowned one reads as missing. */
  record PlaybackSessionMissing() implements SharePlaybackReportRejection {}

  record NoteTooLong(int maximumLength) implements SharePlaybackReportRejection {}

  record PlayerFigureInvalid(PlayerFigure figure) implements SharePlaybackReportRejection {}
}
