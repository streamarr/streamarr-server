package com.streamarr.server.services.pagination;

import lombok.NonNull;

/**
 * Names the list and the scope a cursor belongs to, such as Continue Watching for one Profile. A
 * cursor continues only the list and scope that issued it.
 */
public record ListIdentity(@NonNull String list, @NonNull Object scope) {}
