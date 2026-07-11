package com.streamarr.server.graphql.dto;

import java.util.List;
import java.util.UUID;
import lombok.Builder;

@Builder
public record Me(
    UUID accountId,
    String email,
    String displayName,
    String role,
    String scope,
    List<Membership> memberships) {}
