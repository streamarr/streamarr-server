package com.streamarr.server.services.identity;

import com.streamarr.server.domain.auth.ShareCode;
import lombok.NonNull;

/**
 * A share code with the display name of the Account that issued it, for a reader who may list the
 * Household's codes. The name is null once that Account is deleted.
 */
public record ShareCodeWithIssuer(@NonNull ShareCode shareCode, String issuedByDisplayName) {}
