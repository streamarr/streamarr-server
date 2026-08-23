package com.streamarr.server.graphql.dataloaders;

import com.streamarr.server.services.pagination.MediaPaginationOptions;
import java.util.UUID;

public record AdministrationAccountsLoaderKey(
    UUID householdId, MediaPaginationOptions paginationOptions) {}
