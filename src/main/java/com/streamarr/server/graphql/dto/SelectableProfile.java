package com.streamarr.server.graphql.dto;

import com.streamarr.server.domain.auth.ProfileKind;
import java.util.UUID;
import lombok.Builder;

@Builder
public record SelectableProfile(
    UUID id,
    String name,
    String picture,
    ProfileKind kind,
    boolean personal,
    boolean pinConfigured,
    boolean locked,
    boolean selected) {}
