package com.streamarr.server.services.pagination;

import java.util.Optional;
import lombok.Builder;
import lombok.NonNull;

/** A client's request for one page: its size, direction and the position it continues from. */
@Builder
public record PageRequest(
    @NonNull Optional<Position> position, @NonNull PaginationDirection direction, int limit) {}
