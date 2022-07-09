package com.streamarr.server.graphql.cursor;

import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import java.util.Optional;

@Getter
@EqualsAndHashCode
@Builder(toBuilder = true)
public class PaginationOptions {
    private final PaginationDirection paginationDirection;
    private final Optional<String> cursor;
    private final int limit;

    public PaginationDirection getPaginationDirection() {
        return (null == paginationDirection) ? PaginationDirection.FORWARD : paginationDirection;
    }

}
