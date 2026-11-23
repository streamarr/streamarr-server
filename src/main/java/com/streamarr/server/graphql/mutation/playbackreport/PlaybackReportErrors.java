package com.streamarr.server.graphql.mutation.playbackreport;

import com.streamarr.server.graphql.mutation.InputPath;
import com.streamarr.server.services.playbackreport.MarkPlaybackReportReadRejection;
import com.streamarr.server.services.playbackreport.PlayerFigure;
import com.streamarr.server.services.playbackreport.SharePlaybackReportRejection;

/** The exhaustive mapping from each playback report rejection to its schema error type. */
public final class PlaybackReportErrors {

  private static final String PLAYER = "player";

  private PlaybackReportErrors() {}

  public static SharePlaybackReportError toError(SharePlaybackReportRejection rejection) {
    return switch (rejection) {
      case SharePlaybackReportRejection.PlaybackSessionMissing _ ->
          new SharePlaybackReportError.PlaybackSessionClosedError(
              "This playback session has ended.");
      case SharePlaybackReportRejection.NoteTooLong(var maximumLength) ->
          new SharePlaybackReportError.PlaybackReportNoteTooLongError(
              "Shorten the note to %d characters.".formatted(maximumLength),
              InputPath.of("note"),
              maximumLength);
      case SharePlaybackReportRejection.PlayerFigureInvalid(var figure) ->
          new SharePlaybackReportError.PlayerFigureInvalidError(
              "The player sent a figure that is out of range.",
              InputPath.of(PLAYER, inputField(figure)));
    };
  }

  public static MarkPlaybackReportReadError toError(MarkPlaybackReportReadRejection rejection) {
    return switch (rejection) {
      case MarkPlaybackReportReadRejection.PlaybackReportMissing _ ->
          new MarkPlaybackReportReadError.PlaybackReportNotFoundError(
              "This report no longer exists.", InputPath.of("playbackReportId"));
    };
  }

  private static String inputField(PlayerFigure figure) {
    return switch (figure) {
      case QUALITY_STEP -> "qualityStep";
      case OBSERVED_BITRATE -> "observedBitrate";
      case BUFFER_SECONDS -> "bufferSeconds";
      case DROPPED_FRAMES -> "droppedFrames";
      case STALLS -> "stalls";
      case RESOLUTION -> "resolution";
      case VIDEO_CODEC -> "videoCodec";
      case AUDIO -> "audio";
      case DISPLAY -> "display";
      case AUDIO_OUTPUT -> "audioOutput";
      case SCREEN -> "screen";
      case BROWSER -> "browser";
    };
  }
}
