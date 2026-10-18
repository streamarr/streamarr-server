package com.streamarr.server.repositories;

import com.streamarr.server.services.pagination.MediaFilter;
import java.util.List;
import java.util.UUID;
import lombok.Builder;
import org.jooq.Field;
import org.jooq.SortField;

@Builder
public record MediaSeek(
    MediaFilter filter,
    Field<?> sortColumn,
    List<SortField<?>> orderByColumns,
    UUID cursorId,
    boolean reversePage) {}
