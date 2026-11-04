package com.streamarr.server.domain.streaming;

import java.util.Optional;
import lombok.NonNull;

/**
 * The keyframe time that a source container's demuxer seeks by when FFmpeg reads from a position
 * without its exact seek.
 */
public enum DemuxerSeekTime {
  /** Matroska, MPEG-TS and every other demuxer start reading at the keyframe's decode time. */
  DECODE_TIME,
  /**
   * An MP4 or MOV demuxer seeks by presentation time. A keyframe that B-frames follow decodes
   * before it presents, so its decode time lands the demuxer on the keyframe before it.
   */
  PRESENTATION_TIME;

  // ffprobe's format name for MP4 and MOV files.
  private static final String MP4_FORMAT_NAME = "mov,mp4,m4a,3gp,3g2,mj2";

  public static DemuxerSeekTime of(@NonNull Optional<String> containerFormat) {
    if (containerFormat.filter(MP4_FORMAT_NAME::equals).isPresent()) {
      return PRESENTATION_TIME;
    }

    return DECODE_TIME;
  }

  /** The keyframe's time in ticks of its positions' time base. */
  public long ticksOf(@NonNull KeyframePosition keyframe) {
    return switch (this) {
      case DECODE_TIME -> keyframe.decodeTime();
      case PRESENTATION_TIME -> keyframe.presentationTime();
    };
  }
}
