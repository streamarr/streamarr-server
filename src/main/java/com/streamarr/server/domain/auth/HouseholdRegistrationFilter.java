package com.streamarr.server.domain.auth;

import java.util.UUID;
import lombok.NonNull;

/**
 * The registrations of one Household that a list keeps: those in one status and, when {@code
 * visitorsOnly}, only those a visitor linked. A visitor is an Account that is not a member of the
 * Household.
 */
public record HouseholdRegistrationFilter(
    @NonNull UUID householdId, @NonNull DeviceRegistrationStatus status, boolean visitorsOnly) {}
