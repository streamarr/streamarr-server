package com.streamarr.server.repositories.auth;

import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import lombok.NonNull;

public record ProfileHouseholdShareInsertResult(
    @NonNull ProfileHouseholdShare share, boolean inserted) {}
