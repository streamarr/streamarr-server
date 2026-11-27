package com.streamarr.server.domain.auth;

import java.util.UUID;
import lombok.NonNull;

/**
 * The Profiles of one Household that Household settings lists for one reader: those that belong
 * there and that the reader manages, and, when {@code everyAvailableProfile}, every Profile
 * available there too, the visitors shared in among them.
 */
public record HouseholdProfilesFilter(
    @NonNull UUID householdId, @NonNull UUID readerAccountId, boolean everyAvailableProfile) {}
