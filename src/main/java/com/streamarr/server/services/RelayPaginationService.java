package com.streamarr.server.services;

import com.streamarr.server.domain.BaseEntity;
import com.streamarr.server.graphql.cursor.PaginationDirection;
import com.streamarr.server.graphql.cursor.PaginationOptions;
import graphql.relay.Connection;
import graphql.relay.DefaultConnection;
import graphql.relay.DefaultPageInfo;
import graphql.relay.Edge;
import graphql.relay.PageInfo;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class RelayPaginationService {

    private static final int MAX_PAGE_SIZE = 500;

    public PaginationOptions getPaginationOptions(int first,
                                                  String after,
                                                  int last,
                                                  String before) {

        var cursor = getCursor(after, before);

        if (cursor.isEmpty()) {
            var limit = getLimit(first, last, PaginationDirection.FORWARD);

            return PaginationOptions.builder()
                .cursor(cursor)
                .limit(limit)
                .paginationDirection(PaginationDirection.FORWARD)
                .build();
        }

        var direction = getDirection(after, before);
        var limit = getLimit(first, last, direction);

        return PaginationOptions.builder()
            .cursor(cursor)
            .paginationDirection(direction)
            .limit(limit)
            .build();
    }

    public PaginationDirection getDirection(String after, String before) {

        var afterIsBlank = StringUtils.isBlank(after);
        var beforeIsBlank = StringUtils.isBlank(before);

        if (!afterIsBlank && !beforeIsBlank) {
            throw new RuntimeException("Cannot request with both after and before simultaneously.");
        }

        return afterIsBlank ?
            PaginationDirection.REVERSE : PaginationDirection.FORWARD;
    }

    public Optional<String> getCursor(String after, String before) {

        var afterIsBlank = StringUtils.isBlank(after);
        var beforeIsBlank = StringUtils.isBlank(before);

        if (afterIsBlank && beforeIsBlank) {
            return Optional.empty();
        }

        return afterIsBlank ? Optional.of(before) : Optional.of(after);
    }

    public int getLimit(int first, int last, PaginationDirection direction) {

        // TODO: Should not request with both first and last simultaneously.

        return direction.equals(PaginationDirection.REVERSE) ? validateGreaterThanZeroButLessThanMax("last", last) : validateGreaterThanZeroButLessThanMax("first", first);
    }

    private int validateGreaterThanZeroButLessThanMax(String fieldName, int pageSize) {

        if (pageSize <= 0) {
            throw new RuntimeException(fieldName + " must be greater than zero");
        }

        if (pageSize > MAX_PAGE_SIZE) {
            throw new RuntimeException(fieldName + " must be less than " + MAX_PAGE_SIZE);
        }

        return pageSize;
    }

    public <T> void pruneListByLimitGivenDirection(List<T> list, int limit, PaginationDirection direction) {
        if (list.size() - 1 <= limit) {
            return;
        }

        if (direction.equals(PaginationDirection.REVERSE)) {
            list.remove(0);
            return;
        }

        list.remove(list.size() - 1);
    }

    // limit = 1
    // 0 - no results = emptyConnection / line 117
    // 1 - only the cursor = emptyConnection() / line 152
    // 2 - the cursor plus 1 result = 1 result
    // 3 - the cursor plus 1 result & 1 extra = 1 result / 1 pruned

    // Param should only be of BaseEntity extension
    public <T> Connection<T> buildConnection(List<Edge<? extends BaseEntity<?>>> edges, PaginationOptions options, Optional<UUID> cursorId) {

        if (edges.size() == 0) {
            return emptyConnection();
        }

        var limit = options.getLimit();
        var direction = options.getPaginationDirection();

        var isListLargerThanLimit = edges.size() - 1 > limit;

        // TODO: should we use subList() here?
        pruneListByLimitGivenDirection(edges, limit, direction);

        // TODO: can we actually reach this?
        if (edges.size() == 0) {
            return emptyConnection();
        }

        var hasPreviousPage = false;
        var hasNextPage = false;

        if (direction.equals(PaginationDirection.FORWARD)) {
            hasNextPage = isListLargerThanLimit;
        } else {
            hasPreviousPage = isListLargerThanLimit;
        }

        if (cursorId.isPresent()) {
            if (direction.equals(PaginationDirection.FORWARD)) {
                var node = (BaseEntity<?>) edges.get(0).getNode();

                hasPreviousPage = node.getId().equals(cursorId.get());
                edges.remove(0);
            } else {
                var node = (BaseEntity<?>) edges.get(edges.size() - 1).getNode();

                hasNextPage = node.getId().equals(cursorId.get());
                edges.remove(edges.size() - 1);
            }

            // TODO: We should hit this case when only the cursor is returned
            if (edges.size() == 0) {
                return emptyConnection();
            }
        }

        var firstEdge = edges.get(0);
        var lastEdge = edges.get(edges.size() - 1);

        var pageInfo = new DefaultPageInfo(
            firstEdge.getCursor(),
            lastEdge.getCursor(),
            hasPreviousPage,
            hasNextPage
        );

        return new DefaultConnection(edges, pageInfo);
    }

    private <T> Connection<T> emptyConnection() {
        PageInfo pageInfo = new DefaultPageInfo(null, null, false, false);
        return new DefaultConnection<>(Collections.emptyList(), pageInfo);
    }

}
