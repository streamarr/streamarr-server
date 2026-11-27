package com.streamarr.server.services.identity;

import com.streamarr.server.domain.auth.Household;
import com.streamarr.server.domain.auth.Profile;
import java.util.List;
import lombok.NonNull;

/**
 * What a change of a Profile's kind or Content Ceiling, or a new Profile, would lock in one
 * Household: the other Profiles locked there after it that are not locked now, by name, and whether
 * the changed or new Profile itself would be newly locked there.
 */
public record HouseholdLocks(
    @NonNull Household household, @NonNull List<Profile> profiles, boolean locksThisProfile) {}
