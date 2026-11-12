package com.streamarr.server.domain.pagination;

import lombok.NonNull;

/** A listed item and where it sits in its list. */
public record Row<T>(@NonNull T item, @NonNull Position position) {}
