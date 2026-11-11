package com.streamarr.server.services.identity;

import com.streamarr.server.domain.auth.Household;
import com.streamarr.server.domain.auth.ProfileAuthority;
import java.util.Optional;
import lombok.NonNull;

/**
 * What a Profile is to the Account reading its administrative details, and the Household it belongs
 * to when that reader may know it.
 */
public record ProfileReaderDetails(
    @NonNull Optional<ProfileAuthority> authority, @NonNull Optional<Household> household) {}
