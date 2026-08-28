package com.streamarr.server.graphql.dto;

public sealed interface AccountInvitationProfile
    permits ExistingAccountInvitationProfile, NewAccountInvitationProfile {}
