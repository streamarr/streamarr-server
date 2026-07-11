package com.streamarr.server.graphql.dto;

import java.util.List;
import java.util.UUID;
import lombok.Builder;

@Builder
public record Membership(
    UUID householdId,
    String householdName,
    String householdRole,
    List<SelectableProfile> profiles) {}
