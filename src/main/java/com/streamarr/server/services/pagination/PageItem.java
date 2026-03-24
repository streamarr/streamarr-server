package com.streamarr.server.services.pagination;

public record PageItem<T>(T item, Object sortValue) {}
