package com.streamarr.server.repositories;

import com.streamarr.server.services.pagination.MediaFilter;
import java.util.UUID;
import lombok.Builder;
import org.jooq.Field;
import org.jooq.SortField;

@Builder
public record MediaSeek(
    MediaFilter filter,
    Field<?> sortColumn,
    SortField<?>[] orderByColumns,
    UUID cursorId,
    boolean reversePage) {}
