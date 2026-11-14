package com.streamarr.server.domain.auth;

import java.util.UUID;
import lombok.NonNull;

/**
 * The shares into one Household that a list keeps: those its status filter keeps and, when {@code
 * visitorsOnly}, only shares of visitors. A visitor is a Profile that belongs to another Household.
 */
public record HouseholdShareFilter(
    @NonNull UUID householdId, @NonNull ShareStatusFilter statuses, boolean visitorsOnly) {}
