package com.streamarr.server.services.pagination;

/** A matching item can have a null sort value; absence of this record means it did not match. */
public record ItemSortValue(Object value) {}
