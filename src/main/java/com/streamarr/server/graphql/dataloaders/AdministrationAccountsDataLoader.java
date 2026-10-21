package com.streamarr.server.graphql.dataloaders;

import com.netflix.graphql.dgs.DgsDataLoader;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.services.identity.AdministrationQueryService;
import com.streamarr.server.services.pagination.ListPage;
import com.streamarr.server.services.pagination.PageRequest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import lombok.RequiredArgsConstructor;
import org.dataloader.MappedBatchLoader;

/**
 * Pages the Accounts of every Household in a response together. A Household requested twice with
 * different arguments, through aliases, is paged in a later batch.
 */
@DgsDataLoader(name = "administrationAccounts")
@RequiredArgsConstructor
public class AdministrationAccountsDataLoader
    implements MappedBatchLoader<AdministrationAccountsLoaderKey, ListPage<UserAccount>> {

  private final AdministrationQueryService administrationQueryService;

  @Override
  public CompletionStage<Map<AdministrationAccountsLoaderKey, ListPage<UserAccount>>> load(
      Set<AdministrationAccountsLoaderKey> keys) {
    var pages = new HashMap<AdministrationAccountsLoaderKey, ListPage<UserAccount>>();
    Collection<AdministrationAccountsLoaderKey> remaining = keys;
    while (!remaining.isEmpty()) {
      var batch = new LinkedHashMap<UUID, PageRequest>();
      var later = new ArrayList<AdministrationAccountsLoaderKey>();
      remaining.forEach(key -> addOrDefer(key, batch, later));
      administrationQueryService
          .accountPages(batch)
          .forEach(
              (householdId, page) ->
                  pages.put(
                      new AdministrationAccountsLoaderKey(householdId, batch.get(householdId)),
                      page));
      remaining = later;
    }

    return CompletableFuture.completedFuture(pages);
  }

  private static void addOrDefer(
      AdministrationAccountsLoaderKey key,
      Map<UUID, PageRequest> batch,
      List<AdministrationAccountsLoaderKey> later) {
    if (batch.putIfAbsent(key.householdId(), key.request()) != null) {
      later.add(key);
    }
  }
}
