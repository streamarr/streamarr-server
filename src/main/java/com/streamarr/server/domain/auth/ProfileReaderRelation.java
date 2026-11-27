package com.streamarr.server.domain.auth;

import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;

/**
 * What one Account is to one Profile, read from their stored relationships: whether it manages or
 * supervises the Profile, and whether the Profile belongs to the Account's own Household.
 */
public record ProfileReaderRelation(
    @NonNull UUID profileId,
    @NonNull UUID householdId,
    @NonNull Optional<ProfileAuthority> authority,
    boolean belongsToReadersHousehold) {}
