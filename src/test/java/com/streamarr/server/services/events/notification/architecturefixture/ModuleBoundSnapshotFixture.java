package com.streamarr.server.services.events.notification.architecturefixture;

import com.streamarr.server.services.notification.StoredNotification;

/** A notification event type that leans on what the Notifications module stores. */
public record ModuleBoundSnapshotFixture(StoredNotification stored) {}
