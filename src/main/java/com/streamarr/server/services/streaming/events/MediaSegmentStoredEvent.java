package com.streamarr.server.services.streaming.events;

import java.util.UUID;
import lombok.NonNull;

/**
 * A job attempt's upload stored a media segment of the stream session. {@code storedSegmentName} is
 * the name under which the segment store keeps the segment. A job attempt that stores a segment
 * again under a name leaves the stored segment untouched and still announces it.
 */
public record MediaSegmentStoredEvent(
    @NonNull UUID streamSessionId, @NonNull String storedSegmentName) {}
