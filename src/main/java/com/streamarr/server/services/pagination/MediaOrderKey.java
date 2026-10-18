package com.streamarr.server.services.pagination;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HexFormat;
import org.jooq.SortOrder;

public final class MediaOrderKey {
  private MediaOrderKey() {}

  public static String encode(MediaOrder order) {
    var bytes = new ByteArrayOutputStream();
    bytes.writeBytes(valueBytes(order));
    bytes.writeBytes(
        ByteBuffer.allocate(16)
            .putLong(order.itemId().getMostSignificantBits())
            .putLong(order.itemId().getLeastSignificantBits())
            .array());
    var payload = bytes.toByteArray();
    if (order.direction() == SortOrder.DESC) {
      for (int i = 0; i < payload.length; i++) {
        payload[i] = (byte) ~payload[i];
      }
    }

    var nullsFirst =
        order.direction() == SortOrder.DESC
            && (order.by() == OrderMediaBy.TITLE || order.by() == OrderMediaBy.ADDED);
    var prefix = (order.value() == null) == nullsFirst ? "0" : "1";
    return "1:" + prefix + HexFormat.of().formatHex(payload);
  }

  private static byte[] valueBytes(MediaOrder order) {
    if (order.value() == null) {
      return new byte[0];
    }

    var value = order.value().toString();
    return switch (order.by()) {
      case TITLE -> terminatedTitle(value);
      case RUNTIME -> number(Long.parseLong(value));
      case RELEASE_DATE -> number(LocalDate.parse(value).toEpochDay());
      case ADDED, LAST_WATCHED -> timestamp(Instant.parse(value));
    };
  }

  private static byte[] terminatedTitle(String value) {
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
