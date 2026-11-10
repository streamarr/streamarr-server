package com.streamarr.server.graphql.dataloaders;

import com.streamarr.server.services.auth.AuthenticatedIdentity;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;

/** One item as one reader asks for it; the reader decides what the item shows. */
public record ReaderLoaderKey<I>(AuthenticatedIdentity reader, I item) {

  /**
   * Loads the keys reader by reader, each reader's items in one call, and maps what each call found
   * to its keys. An item the call leaves out has no value.
   */
  static <I, T, R> Map<ReaderLoaderKey<I>, R> loadByReader(
      Collection<ReaderLoaderKey<I>> keys,
      BiFunction<AuthenticatedIdentity, Set<I>, Map<I, T>> load,
      Function<T, R> shown) {
    var byReader =
        keys.stream()
            .collect(
                Collectors.groupingBy(
                    ReaderLoaderKey::reader,
                    Collectors.mapping(ReaderLoaderKey::item, Collectors.toSet())));

    var loaded = new HashMap<ReaderLoaderKey<I>, R>();
    byReader.forEach(
        (reader, items) ->
            load.apply(reader, items)
                .forEach(
                    (item, found) ->
                        loaded.put(new ReaderLoaderKey<>(reader, item), shown.apply(found))));
    return loaded;
  }
}
