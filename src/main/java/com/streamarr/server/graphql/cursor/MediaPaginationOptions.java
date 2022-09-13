package com.streamarr.server.graphql.cursor;

import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import java.util.Optional;
import java.util.UUID;

@Getter
@EqualsAndHashCode
@Builder(toBuilder = true)
public class MediaPaginationOptions {
    private final UUID cursorId;

    private final PaginationOptions paginationOptions;

    // TODO: List of filters?
    private final MediaFilter mediaFilter;

    public Optional<UUID> getCursorId() {
        return Optional.ofNullable(cursorId);
    }
}
