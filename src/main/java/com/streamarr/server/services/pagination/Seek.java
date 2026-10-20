package com.streamarr.server.services.pagination;

import java.util.Optional;
import lombok.Builder;
import lombok.NonNull;

/**
 * A read past a position. FORWARD reads toward the end of the list and REVERSE toward its start.
 * {@code includingPosition} also matches a row at the position itself.
 */
@Builder
public record Seek(
    @NonNull Optional<Position> position,
    @NonNull PaginationDirection direction,
    int limit,
    boolean includingPosition) {}
