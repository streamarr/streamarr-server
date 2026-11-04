package com.streamarr.server.services.streaming.events;

import java.util.UUID;
import lombok.NonNull;

/**
 * A playback session started, changed its format attempt or playing state, or ended. Listeners read
 * its current state; an ended playback session has none.
 */
public record PlaybackSessionChangedEvent(@NonNull UUID playbackSessionId) {}
