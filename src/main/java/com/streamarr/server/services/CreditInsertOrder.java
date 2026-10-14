package com.streamarr.server.services;

import java.util.Comparator;
import java.util.function.Function;

/**
 * The one order in which every transaction inserts credit rows: people, then genres, then
 * companies, and within each table ascending source id compared with {@link String#compareTo}.
 *
 * <p>An {@code INSERT ... ON CONFLICT DO NOTHING} waits for any open transaction that inserted the
 * same source id. With one total order, a transaction only waits for a key above every key it
 * holds, so concurrent saves cannot form a wait-for cycle. Ordering in SQL must compare source ids
 * the same way, for example with {@code COLLATE "C"}, not with a database collation.
 */
final class CreditInsertOrder {

  private CreditInsertOrder() {}

  static <T> Comparator<T> bySourceId(Function<T, String> sourceId) {
    return Comparator.comparing(sourceId);
  }
}
