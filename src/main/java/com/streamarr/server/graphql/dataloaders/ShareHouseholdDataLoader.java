package com.streamarr.server.graphql.dataloaders;

import com.netflix.graphql.dgs.DgsDataLoader;
import com.streamarr.server.domain.auth.Household;
import com.streamarr.server.graphql.dto.HouseholdSummary;
import com.streamarr.server.services.identity.ProfileSharePartiesService;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import lombok.RequiredArgsConstructor;
import org.dataloader.MappedBatchLoader;

/** The Household each share of a response is into, read together for each reader. */
@DgsDataLoader(name = ShareHouseholdDataLoader.NAME)
@RequiredArgsConstructor
public class ShareHouseholdDataLoader
    implements MappedBatchLoader<ShareLoaderKey, HouseholdSummary> {

  public static final String NAME = "shareHousehold";

  private final ProfileSharePartiesService sharePartiesService;

  @Override
  public CompletionStage<Map<ShareLoaderKey, HouseholdSummary>> load(Set<ShareLoaderKey> keys) {
    return CompletableFuture.completedFuture(
        ShareLoaderKey.loadByReader(
            keys, sharePartiesService::households, ShareHouseholdDataLoader::summary));
  }

  private static HouseholdSummary summary(Household household) {
    return new HouseholdSummary(household.getId(), household.getName());
  }
}
