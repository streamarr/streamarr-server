package com.streamarr.server.services.streaming.events;

import java.util.UUID;
import lombok.NonNull;

/**
 * A job attempt of the playback session's current format attempt stored a media segment, so the
 * produced range of its active stream may have moved.
 */
public record PlaybackSessionSegmentStoredEvent(@NonNull UUID playbackSessionId) {}
