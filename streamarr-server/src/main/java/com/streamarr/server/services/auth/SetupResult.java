package com.streamarr.server.services.auth;

import com.streamarr.server.domain.auth.Household;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.UserAccount;
import lombok.Builder;

@Builder
public record SetupResult(UserAccount admin, Household household, Profile profile) {}
