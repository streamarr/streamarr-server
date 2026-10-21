package com.streamarr.server.graphql.dataloaders;

import com.streamarr.server.services.pagination.PageRequest;
import java.util.UUID;

public record AdministrationAccountsLoaderKey(UUID householdId, PageRequest request) {}
