package com.streamarr.server.services;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

/**
 * The order in which every transaction inserts the rows of one credit table: ascending source id
 * compared with {@link String#compareTo}. {@link CreditService} fixes the order of the tables.
 *
 * <p>An {@code INSERT ... ON CONFLICT DO NOTHING} waits for any open transaction that inserted the
 * same source id. With one total order, a transaction only waits for a key above every key it
 * holds, so concurrent saves cannot form a wait-for cycle. Ordering in SQL must compare source ids
 * the same way, for example with {@code COLLATE "C"}, not with a database collation.
 */
final class CreditInsertOrder {

  private CreditInsertOrder() {}

  /**
   * Returns {@code credits} in insert order. Checks every source id first, so a credit without one
   * throws {@link IllegalArgumentException} before any credit is inserted.
   */
  static <T> List<T> sortedBySourceId(
      Collection<T> credits, Function<T, String> sourceIdOf, String creditName) {
    credits.forEach(credit -> requireSourceId(sourceIdOf.apply(credit), creditName));

    return credits.stream().sorted(Comparator.comparing(sourceIdOf)).toList();
  }

  private static void requireSourceId(String sourceId, String creditName) {
    if (sourceId == null) {
      throw new IllegalArgumentException(creditName + " sourceId must not be null");
    }
  }
}
