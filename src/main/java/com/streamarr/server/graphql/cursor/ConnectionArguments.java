package com.streamarr.server.graphql.cursor;

import com.streamarr.server.services.pagination.PaginationOptions;
import com.streamarr.server.services.pagination.PaginationService;
import graphql.schema.DataFetchingEnvironment;

/**
 * Reads the Relay connection arguments of a field and applies one default page size: a request that
 * names neither {@code first} nor {@code last} pages forward by the default, or backward from
 * {@code before} by the default when only that cursor is given.
 */
public final class ConnectionArguments {

  private ConnectionArguments() {}

  public static PaginationOptions paginationOptions(
      PaginationService paginationService, DataFetchingEnvironment dfe, int defaultPageSize) {
    int first = dfe.getArgumentOrDefault("first", 0);
    String after = dfe.getArgument("after");
    int last = dfe.getArgumentOrDefault("last", 0);
    String before = dfe.getArgument("before");
    if (first != 0 || last != 0) {
      return paginationService.getPaginationOptions(first, after, last, before);
    }

    if (before != null) {
      return paginationService.getPaginationOptions(0, after, defaultPageSize, before);
    }

    return paginationService.getPaginationOptions(defaultPageSize, after, 0, null);
  }
}
