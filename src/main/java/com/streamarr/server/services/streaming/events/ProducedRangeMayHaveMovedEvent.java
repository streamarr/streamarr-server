package com.streamarr.server.services.streaming.events;

import java.util.UUID;
import lombok.NonNull;

/**
 * A stream session of the playback session, for its current format attempt or a pending audio track
 * switch, stored a media segment, so the produced range of its active stream may have moved.
 */
public record ProducedRangeMayHaveMovedEvent(@NonNull UUID playbackSessionId) {}
