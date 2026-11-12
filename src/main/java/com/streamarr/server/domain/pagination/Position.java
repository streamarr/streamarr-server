package com.streamarr.server.domain.pagination;

import java.util.UUID;
import lombok.NonNull;

/** Where an item sits in a list: its sort value, then its ID to break ties. */
public record Position(@NonNull SortValue value, @NonNull UUID itemId) {}
