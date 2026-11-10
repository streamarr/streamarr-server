package com.streamarr.server.graphql.dataloaders;

import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.identity.ShareReference;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;

/** One share's party as one reader asks for it; the reader decides what the party shows. */
public record ShareLoaderKey(AuthenticatedIdentity reader, ShareReference share) {

  /**
   * Loads the keys reader by reader, each reader's shares in one call, and maps what each call
   * found to its keys. A share the call leaves out has no value.
   */
  static <T, R> Map<ShareLoaderKey, R> loadByReader(
      Collection<ShareLoaderKey> keys,
      BiFunction<AuthenticatedIdentity, Set<ShareReference>, Map<ShareReference, T>> load,
      Function<T, R> shown) {
    var byReader =
        keys.stream()
            .collect(
                Collectors.groupingBy(
                    ShareLoaderKey::reader,
                    Collectors.mapping(ShareLoaderKey::share, Collectors.toSet())));

    var loaded = new HashMap<ShareLoaderKey, R>();
    byReader.forEach(
        (reader, shares) ->
            load.apply(reader, shares)
                .forEach(
                    (share, party) ->
                        loaded.put(new ShareLoaderKey(reader, share), shown.apply(party))));
    return loaded;
  }
}
