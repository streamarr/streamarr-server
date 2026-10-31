package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.streaming.AudioDecision;
import com.streamarr.server.domain.streaming.AudioMode;
import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.VideoPlaybackFacts;
import java.util.OptionalLong;
import lombok.NonNull;

/**
 * What a video copy costs beside the stream session's audio: the AVERAGE-BANDWIDTH that the
 * multivariant playlist advertises for the copy and the cost that the viewer's maximum bitrate
 * bounds (ADR 0050).
 */
final class VideoCopyBandwidth {

  private VideoCopyBandwidth() {}

  /**
   * The copied video's bitrate plus the stream session's audio bitrate, counted so that it never
   * falls below what the player downloads. When the probe knows no bitrate for the copied video, as
   * for Matroska, the container bitrate stands in for it. That rate already holds every source
   * track, so a copied audio track adds nothing to it, while encoded audio adds its bitrate on top.
   * A copied audio track whose bitrate the probe does not know takes a nominal bitrate, which can
   * be far below a lossless track's rate, so a known container bitrate, which holds that track,
   * bounds the copy from below.
   */
  static long averageBandwidth(@NonNull MediaProbe source, @NonNull AudioDecision audio) {
    var copiesAudio = audio.mode() == AudioMode.COPY;
    var videoBitrate = copiedStreamBitrate(source);
    if (videoBitrate.isEmpty() && copiesAudio) {
      return Math.max(source.bitrate(), audio.bitrate());
    }

    var averageBandwidth = videoBitrate.orElse(source.bitrate()) + audio.bitrate();
    if (copiesAudio && source.audioBitrate().isEmpty()) {
      return Math.max(averageBandwidth, source.bitrate());
    }

    return averageBandwidth;
  }

  /** Whether the probe knows the copied video's bitrate or the container bitrate. */
  static boolean isKnown(@NonNull MediaProbe source) {
    return copiedStreamBitrate(source).isPresent() || source.bitrate() > 0;
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
