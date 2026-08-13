package com.streamarr.server.graphql.dto;

import com.streamarr.server.domain.auth.ProfileClassification;
import java.util.UUID;

public record PortableProfileSummary(
    UUID id,
    String name,
    ProfileClassification classification,
    Integer maximumAllowedRatingAge,
    boolean pinProtected) {}
