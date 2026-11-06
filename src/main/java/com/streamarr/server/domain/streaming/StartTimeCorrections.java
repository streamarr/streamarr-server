package com.streamarr.server.domain.streaming;

import java.util.Optional;
import java.util.Set;
import lombok.NonNull;

/**
 * Which source containers FFmpeg's command line corrects the start time of: those whose demuxer
 * allows timestamp discontinuities (ADR 0037, media time zero).
 */
public final class StartTimeCorrections {

  // ffprobe's format names for FFmpeg 8.1's demuxers that set AVFMT_TS_DISCONT.
  private static final Set<String> FORMATS_WITH_TIMESTAMP_DISCONTINUITIES =
      Set.of(
          "mpegts",
          "mpegtsraw",
          "mpeg",
          "ogg",
          "live_flv",
          "m4v",
          "hls",
          "dhav",
          "dvdvideo",
          "rcwt",
          "ty");

  private StartTimeCorrections() {}

  public static boolean isCorrectedBy(@NonNull Optional<String> containerFormat) {
    return containerFormat.filter(FORMATS_WITH_TIMESTAMP_DISCONTINUITIES::contains).isPresent();
  }
}
