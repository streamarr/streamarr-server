package com.streamarr.server.graphql.dto;

import java.util.List;

public record HouseholdTeardownPreview(
    int accountCount, List<ProfileDeletionPreview> profilesToDelete, int visitingProfileCount) {}
