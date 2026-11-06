package com.streamarr.server.services.streaming.events;

import java.util.UUID;
import lombok.NonNull;

/** A job attempt's upload stored a media segment of the stream session. */
public record MediaSegmentStoredEvent(@NonNull UUID streamSessionId) {}
