package com.streamarr.server.services.auth.events;

import com.streamarr.server.domain.auth.ChangedShare;
import lombok.NonNull;

/**
 * A write changed one Profile share, or is about to delete it with its Profile or its Household. It
 * is published inside the writer's transaction, so a listener after commit hears only changes that
 * committed.
 */
public record ProfileShareChangedEvent(@NonNull ChangedShare share) {}
