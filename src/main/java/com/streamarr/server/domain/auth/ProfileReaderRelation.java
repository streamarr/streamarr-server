package com.streamarr.server.domain.auth;

import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;

/** What one Account is to one Profile, read from their stored relationships. */
public record ProfileReaderRelation(
    @NonNull UUID profileId, @NonNull Optional<ProfileAuthority> authority) {}
