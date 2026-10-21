package com.streamarr.server.services.pagination;

/** Where a list places the items that have no value for its sort. */
public enum AbsentValuePlacement {
  /** Every item of the list has a sort value. */
  NOT_ALLOWED,

  /** After every item that has a value, whichever direction the list sorts in. */
  LAST,

  /** As though larger than every value: last when ascending, first when descending. */
  AS_LARGEST
}
