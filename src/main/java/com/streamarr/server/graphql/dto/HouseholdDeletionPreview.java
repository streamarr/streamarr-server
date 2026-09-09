package com.streamarr.server.graphql.dto;

import java.util.List;

public record HouseholdDeletionPreview(
    int accountCount, List<ProfileDeletionPreview> profilesToDelete, int visitingProfileCount) {}
