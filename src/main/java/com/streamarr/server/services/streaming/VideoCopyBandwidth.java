package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.streaming.AudioDecision;
import com.streamarr.server.domain.streaming.AudioMode;
import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.VideoPlaybackFacts;
import java.util.OptionalLong;
import lombok.NonNull;

/**
 * What a video copy costs beside the stream session's audio: the AVERAGE-BANDWIDTH that the
 * multivariant playlist advertises for the copy (ADR 0050).
 */
final class VideoCopyBandwidth {

  private VideoCopyBandwidth() {}

  // A copy costs the copied stream's own bitrate plus the stream session's audio bitrate. A copied
  // audio track whose bitrate the probe does not know takes a nominal bitrate, which can be far
  // below a lossless track's rate. The container bitrate holds that track, so it bounds the copy
  // from below.
  static long averageBandwidth(@NonNull MediaProbe source, @NonNull AudioDecision audio) {
    var averageBandwidth = copiedVideoBitrate(source) + audio.bitrate();
    if (audio.mode() == AudioMode.COPY && source.audioBitrate().isEmpty()) {
      return Math.max(averageBandwidth, source.bitrate());
    }

    return averageBandwidth;
  }

  // When the probe stored no bitrate for the copied stream, the copy counts the container bitrate
  // as video. A known container bitrate also counts every other track of the source, so it never
  // falls below the video's own bitrate.
  private static long copiedVideoBitrate(MediaProbe source) {
    return copiedStreamBitrate(source).orElse(source.bitrate());
  }

  private static OptionalLong copiedStreamBitrate(MediaProbe source) {
    var streamIndex = source.videoPlaybackFacts().map(VideoPlaybackFacts::streamIndex);
    if (streamIndex.isEmpty()) {
      return OptionalLong.empty();
    }

    return source.streams().stream()
        .filter(stream -> stream.index() == streamIndex.get())
        .flatMapToLong(stream -> stream.bitrate().stream())
        .findFirst();
  }
}
