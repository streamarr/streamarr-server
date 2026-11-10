package com.streamarr.server.domain.auth;

import java.util.UUID;
import lombok.NonNull;

/** The shares into one Household that a list keeps: those its status filter keeps. */
public record HouseholdShareFilter(
    @NonNull UUID householdId, @NonNull ShareStatusFilter statuses) {}
