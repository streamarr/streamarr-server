package com.streamarr.server.graphql.dataloaders;

import com.netflix.graphql.dgs.DgsDataLoader;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.services.identity.AdministrationQueryService;
import com.streamarr.server.services.pagination.MediaPage;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.dataloader.MappedBatchLoader;

@DgsDataLoader(name = "administrationAccounts")
@RequiredArgsConstructor
public class AdministrationAccountsDataLoader
    implements MappedBatchLoader<AdministrationAccountsLoaderKey, MediaPage<UserAccount>> {

  private final AdministrationQueryService administrationQueryService;

  @Override
  public CompletionStage<Map<AdministrationAccountsLoaderKey, MediaPage<UserAccount>>> load(
      Set<AdministrationAccountsLoaderKey> keys) {
    var result = new HashMap<AdministrationAccountsLoaderKey, MediaPage<UserAccount>>();
    var keysByOptions =
        keys.stream()
            .collect(Collectors.groupingBy(AdministrationAccountsLoaderKey::paginationOptions));

    for (var entry : keysByOptions.entrySet()) {
      var householdIds =
          entry.getValue().stream()
              .map(AdministrationAccountsLoaderKey::householdId)
              .collect(Collectors.toSet());
      var pages = administrationQueryService.accountPagesOf(householdIds, entry.getKey());
      entry.getValue().forEach(key -> result.put(key, pages.get(key.householdId())));
    }

    return CompletableFuture.completedFuture(result);
  }
}
