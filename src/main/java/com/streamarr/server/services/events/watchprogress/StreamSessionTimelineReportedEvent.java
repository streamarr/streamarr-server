package com.streamarr.server.services.events.watchprogress;

import java.util.UUID;
import lombok.NonNull;

/** A client reported the position and playing state of one of its stream sessions. */
public record StreamSessionTimelineReportedEvent(@NonNull UUID streamSessionId) {}
