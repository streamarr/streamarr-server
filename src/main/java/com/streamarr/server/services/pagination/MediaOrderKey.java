package com.streamarr.server.services.pagination;

import com.streamarr.server.domain.pagination.ListOrder;
import com.streamarr.server.domain.pagination.Position;
import com.streamarr.server.domain.pagination.SortValue;
import com.streamarr.server.domain.pagination.SortValueKind;
import java.time.Instant;
import java.time.LocalDate;

public final class MediaOrderKey {
  private MediaOrderKey() {}

  public static String encode(MediaOrder order) {
    var by = order.by();
    return OrderKey.encode(
        ListOrder.builder()
            .valueKind(by.getValueKind())
            .direction(order.direction())
            .absentValues(by.getAbsentValues())
            .build(),
        new Position(sortValue(by.getValueKind(), order.value()), order.itemId()));
  }

  private static SortValue sortValue(SortValueKind kind, Object value) {
    if (value == null) {
      return new SortValue.Absent();
    }

    var text = value.toString();
    return switch (kind) {
      case TEXT -> new SortValue.OfText(text);
      case INSTANT -> new SortValue.OfInstant(Instant.parse(text));
      case DATE -> new SortValue.OfDate(LocalDate.parse(text));
      case NUMBER -> new SortValue.OfNumber(Long.parseLong(text));
    };
  }
}
