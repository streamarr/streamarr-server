package com.streamarr.server.services.events.library;

import java.util.UUID;
import lombok.NonNull;

/**
 * A stream session started to copy the video of a file whose streams the server has not measured,
 * so the copy costs its unmeasured cost (ADR 0054).
 */
public record UnmeasuredCopyStartedEvent(@NonNull UUID mediaFileId) {}
