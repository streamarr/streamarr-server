package com.streamarr.server.services.streaming.events;

import java.util.UUID;
import lombok.NonNull;

/**
 * The stream session's requested variant changed: its first media-segment request arrived, or a
 * media-segment request named another variant than the newest one before it.
 */
public record RequestedVariantChangedEvent(@NonNull UUID streamSessionId) {}
