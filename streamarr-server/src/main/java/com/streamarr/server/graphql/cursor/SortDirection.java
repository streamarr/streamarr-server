package com.streamarr.server.graphql.cursor;

import org.jooq.SortOrder;

public enum SortDirection {
  ASC,
  DESC;

  public SortOrder toSortOrder() {
    return this == DESC ? SortOrder.DESC : SortOrder.ASC;
  }
}
