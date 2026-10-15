package com.streamarr.server.services.streaming;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.MediaSegmentTimeline;
import com.streamarr.server.domain.streaming.StreamSession;

/**
 * The one derivation of a probed media file's media segment timeline, shared by the media playlist
 * and every job attempt so that both advertise the same media segments.
 */
final class MediaSegmentTimelines {

  private MediaSegmentTimelines() {}

  static MediaSegmentTimeline of(MediaProbe probe, StreamingProperties properties) {
    return new MediaSegmentTimeline.PlainGrid(probe.duration(), properties.targetSegmentDuration());
  }

  static MediaSegmentTimeline of(StreamSession session, StreamingProperties properties) {
    return of(session.getMediaProbe(), properties);
  }
}
