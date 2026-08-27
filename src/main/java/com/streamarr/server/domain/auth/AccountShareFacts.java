package com.streamarr.server.domain.auth;

import java.util.UUID;

/** The Account relationships that can authorize a Profile Share transition. */
public record AccountShareFacts(
    UUID householdId, HouseholdRole householdRole, UUID personalProfileId) {}
