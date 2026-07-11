package com.streamarr.server.services.watchprogress.events;

import java.util.UUID;

public record WatchStatusChangedEvent(UUID profileId, UUID collectableId) {}
