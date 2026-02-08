package com.streamarr.server.services;

import com.streamarr.server.domain.BaseAuditableEntity;
import com.streamarr.server.exceptions.InvalidPaginationArgumentException;
import com.streamarr.server.graphql.cursor.InvalidCursorException;
import com.streamarr.server.graphql.cursor.PaginationDirection;
import com.streamarr.server.graphql.cursor.PaginationOptions;
import graphql.relay.Connection;
import graphql.relay.DefaultConnection;
import graphql.relay.DefaultPageInfo;
import graphql.relay.Edge;
import graphql.relay.PageInfo;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

@Service
public class RelayPaginationService {

  private static final int MAX_PAGE_SIZE = 500;

  public PaginationOptions getPaginationOptions(int first, String after, int last, String before) {
    var cursor = getCursor(after, before);
    var direction = cursor.isEmpty() ? PaginationDirection.FORWARD : getDirection(after, before);
    var limit = getLimit(first, last, direction);

    return PaginationOptions.builder()
        .cursor(cursor)
        .paginationDirection(direction)
        .limit(limit)
        .build();
  }

  private PaginationDirection getDirection(String after, String before) {

    var afterIsBlank = StringUtils.isBlank(after);
    var beforeIsBlank = StringUtils.isBlank(before);

    if (!afterIsBlank && !beforeIsBlank) {
      throw new InvalidPaginationArgumentException(
          "Cannot request with both after and before simultaneously.");
    }

    return afterIsBlank ? PaginationDirection.REVERSE : PaginationDirection.FORWARD;
  }

  private Optional<String> getCursor(String after, String before) {

    var afterIsBlank = StringUtils.isBlank(after);
    var beforeIsBlank = StringUtils.isBlank(before);

    if (afterIsBlank && beforeIsBlank) {
      return Optional.empty();
    }

    return afterIsBlank ? Optional.of(before) : Optional.of(after);
  }

  private int getLimit(int first, int last, PaginationDirection direction) {
    return direction.equals(PaginationDirection.REVERSE)
        ? validateGreaterThanZeroButLessThanMax("last", last)
        : validateGreaterThanZeroButLessThanMax("first", first);
  }

  private int validateGreaterThanZeroButLessThanMax(String fieldName, int pageSize) {

    if (pageSize <= 0) {
      throw new InvalidPaginationArgumentException(fieldName + " must be greater than zero.");
    }

    if (pageSize > MAX_PAGE_SIZE) {
      throw new InvalidPaginationArgumentException(
          fieldName + " must be less than " + MAX_PAGE_SIZE + ".");
    }

    return pageSize;
  }

  public <T extends BaseAuditableEntity<?>> Connection<T> buildConnection(
      List<Edge<T>> edges, PaginationOptions options, Optional<UUID> cursorId) {

    if (edges.isEmpty()) {
      return emptyConnection();
    }

    var limit = options.getLimit();
    var direction = options.getPaginationDirection();

    var hasPreviousPage = false;
    var hasNextPage = false;

    if (cursorId.isPresent() && direction.equals(PaginationDirection.FORWARD)) {
      hasPreviousPage = edges.getFirst().getNode().getId().equals(cursorId.get());
      edges = edges.subList(1, edges.size());
    }

    if (cursorId.isPresent() && direction.equals(PaginationDirection.REVERSE)) {
      hasNextPage = edges.getLast().getNode().getId().equals(cursorId.get());
      edges = edges.subList(0, edges.size() - 1);
    }

    if (edges.isEmpty()) {
      return emptyConnection();
    }

    var isListLargerThanLimit = edges.size() > limit;

    if (direction.equals(PaginationDirection.FORWARD)) {
      hasNextPage = isListLargerThanLimit;
    } else {
      hasPreviousPage = isListLargerThanLimit;
    }

    edges = pruneListByLimitGivenDirection(edges, limit, direction);

    var firstEdge = edges.getFirst();
    var lastEdge = edges.getLast();

    var pageInfo =
        new DefaultPageInfo(
            firstEdge.getCursor(), lastEdge.getCursor(), hasPreviousPage, hasNextPage);

    return new DefaultConnection<>(edges, pageInfo);
  }

  private <T> Connection<T> emptyConnection() {
    PageInfo pageInfo = new DefaultPageInfo(null, null, false, false);
    return new DefaultConnection<>(Collections.emptyList(), pageInfo);
  }

  private <T> List<T> pruneListByLimitGivenDirection(
      List<T> list, int limit, PaginationDirection direction) {
    if (list.size() <= limit) {
      return list;
    }

    if (direction.equals(PaginationDirection.REVERSE)) {
      return list.subList(1, list.size());
    }

    return list.subList(0, list.size() - 1);
  }

  public <T> void validateCursorField(String fieldName, T prior, T current) {
    if (Objects.equals(prior, current)) {
      return;
    }

    throw new InvalidCursorException(
        "Prior query "
            + fieldName
            + " was '"
            + prior
            + "'"
            + " but new query "
            + fieldName
            + " is '"
            + current
            + "'");
  }
}
