package com.streamarr.server.services.pagination;

import java.util.List;

public record MediaPage<T>(List<PageItem<T>> items, boolean hasNextPage, boolean hasPreviousPage) {}
