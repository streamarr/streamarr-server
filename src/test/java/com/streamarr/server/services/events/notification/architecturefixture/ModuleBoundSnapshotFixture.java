package com.streamarr.server.services.events.notification.architecturefixture;

import com.streamarr.server.services.notification.NotificationKindCatalog;

/** A notification event type that leans on the Notifications module's catalog of kinds. */
public record ModuleBoundSnapshotFixture(NotificationKindCatalog kinds) {}
