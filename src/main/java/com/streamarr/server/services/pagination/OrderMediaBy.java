package com.streamarr.server.services.pagination;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * What a Library's items sort by, the kind of value each sorts by, and where items lacking it sit.
 */
@RequiredArgsConstructor
@Getter
public enum OrderMediaBy {
  TITLE(SortValueKind.TEXT, AbsentValuePlacement.AS_LARGEST),
  ADDED(SortValueKind.INSTANT, AbsentValuePlacement.NOT_ALLOWED),
  RELEASE_DATE(SortValueKind.DATE, AbsentValuePlacement.LAST),
  RUNTIME(SortValueKind.NUMBER, AbsentValuePlacement.LAST),
  LAST_WATCHED(SortValueKind.INSTANT, AbsentValuePlacement.LAST);

  private final SortValueKind valueKind;
  private final AbsentValuePlacement absentValues;
}
