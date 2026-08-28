package com.streamarr.server.graphql.dto;

import com.streamarr.server.domain.auth.ProfileKind;
import java.util.UUID;

public record ExistingAccountInvitationProfile(
    UUID id, String name, ProfileKind kind, Integer maximumAllowedRatingAge)
    implements AccountInvitationProfile {}
