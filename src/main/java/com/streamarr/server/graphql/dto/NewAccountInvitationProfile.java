package com.streamarr.server.graphql.dto;

import com.streamarr.server.domain.auth.ProfileKind;

public record NewAccountInvitationProfile(
    String name, ProfileKind kind, Integer maximumAllowedRatingAge)
    implements AccountInvitationProfile {}
