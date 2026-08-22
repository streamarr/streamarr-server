package com.streamarr.server.graphql.inputs;

import com.streamarr.server.domain.auth.ProfileKind;

public record ChangeProfileKindInput(String profileId, ProfileKind kind) {}
