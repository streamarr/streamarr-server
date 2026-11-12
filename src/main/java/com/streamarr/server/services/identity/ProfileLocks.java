package com.streamarr.server.services.identity;

import java.util.List;
import lombok.NonNull;

/**
 * What a change of a Profile's kind or Content Ceiling, or a new Profile, would lock, as its caller
 * may already see it (ADR 0024 §PIN safety): whether the changed or new Profile itself would lock
 * in any Household where it is available, the Households where the caller may see it is available,
 * and whether Profiles the caller may not see would lock too.
 */
public record ProfileLocks(
    boolean locksThisProfile,
    @NonNull List<HouseholdLocks> households,
    boolean otherProfilesWillLock) {}
