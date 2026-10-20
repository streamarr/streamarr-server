package com.streamarr.server.repositories;

import com.streamarr.server.services.pagination.MediaFilter;
import java.util.List;
import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;
import org.jooq.Field;
import org.jooq.SortField;

@Builder
public record MediaSeek(
    @NonNull MediaFilter filter,
    @NonNull Field<?> sortColumn,
    @NonNull List<SortField<?>> orderByColumns,
    UUID cursorId,
    boolean reversePage) {}
