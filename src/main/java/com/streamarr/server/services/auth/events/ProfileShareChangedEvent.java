package com.streamarr.server.services.auth.events;

import com.streamarr.server.domain.auth.ChangedShare;
import lombok.NonNull;

/**
 * A write changed one Profile share, or is about to delete it with its Profile or its Household. It
 * is published inside the writer's transaction, so a listener after commit hears only changes that
 * committed. {@code live} says whether the share was pending or active when the change was made:
 * still is, or was until this change. The target Household's HouseholdAdmins follow a share only
 * while it is live, so they hear the change that ends it and none after.
 */
public record ProfileShareChangedEvent(@NonNull ChangedShare share, boolean live) {}
