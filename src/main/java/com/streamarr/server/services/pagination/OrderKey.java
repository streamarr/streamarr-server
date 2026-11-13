package com.streamarr.server.services.pagination;

import com.streamarr.server.domain.pagination.AbsentValuePlacement;
import com.streamarr.server.domain.pagination.ListOrder;
import com.streamarr.server.domain.pagination.Position;
import com.streamarr.server.domain.pagination.SortDirection;
import com.streamarr.server.domain.pagination.SortValue;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;

/**
 * The order key of a position in a list: keys from one list compare as ascending ASCII strings in
 * the list's order, ties included.
 */
public final class OrderKey {
  private OrderKey() {}

  public static String encode(ListOrder order, Position position) {
    var bytes = new ByteArrayOutputStream();
    bytes.writeBytes(valueBytes(position.value()));
    bytes.writeBytes(
        ByteBuffer.allocate(16)
            .putLong(position.itemId().getMostSignificantBits())
            .putLong(position.itemId().getLeastSignificantBits())
            .array());
    var payload = bytes.toByteArray();
    if (order.direction() == SortDirection.DESC) {
      for (int i = 0; i < payload.length; i++) {
        payload[i] = (byte) ~payload[i];
      }
    }

    var absentFirst =
        order.direction() == SortDirection.DESC
            && order.absentValues() != AbsentValuePlacement.LAST;
    var absent = position.value() instanceof SortValue.Absent;
    var prefix = absent == absentFirst ? "0" : "1";
    return "1:" + prefix + HexFormat.of().formatHex(payload);
  }

  private static byte[] valueBytes(SortValue value) {
    return switch (value) {
      case SortValue.OfText(var text) -> terminated(text);
      case SortValue.OfInstant(var instant) -> timestamp(instant);
      case SortValue.OfDate(var date) -> number(date.toEpochDay());
      case SortValue.OfNumber(var number) -> number(number);
      case SortValue.Absent _ -> new byte[0];
    };
  }

  private static byte[] terminated(String value) {
    var bytes = value.getBytes(StandardCharsets.UTF_8);
    return Arrays.copyOf(bytes, bytes.length + 1);
  }

  private static byte[] number(long value) {
    return ByteBuffer.allocate(8).putLong(value ^ Long.MIN_VALUE).array();
  }

  private static byte[] timestamp(Instant value) {
    return ByteBuffer.allocate(12)
        .putLong(value.getEpochSecond() ^ Long.MIN_VALUE)
        .putInt(value.getNano())
        .array();
  }
}
