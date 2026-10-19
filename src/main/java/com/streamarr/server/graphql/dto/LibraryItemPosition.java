package com.streamarr.server.graphql.dto;

import com.streamarr.server.services.pagination.MediaOrder;

public record LibraryItemPosition(boolean matchesFilter, String cursor, MediaOrder order) {}
