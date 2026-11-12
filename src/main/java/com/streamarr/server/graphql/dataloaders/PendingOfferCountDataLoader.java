package com.streamarr.server.graphql.dataloaders;

import com.netflix.graphql.dgs.DgsDataLoader;
import com.streamarr.server.services.identity.ProfileSharingService;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.dataloader.MappedBatchLoader;

/**
 * How many offers wait for each Household of a response to answer them, counted together for each
 * reader.
 */
@DgsDataLoader(name = PendingOfferCountDataLoader.NAME)
@RequiredArgsConstructor
public class PendingOfferCountDataLoader
    implements MappedBatchLoader<ReaderLoaderKey<UUID>, Integer> {

  public static final String NAME = "pendingOfferCount";

  private final ProfileSharingService profileSharingService;

  @Override
  public CompletionStage<Map<ReaderLoaderKey<UUID>, Integer>> load(
      Set<ReaderLoaderKey<UUID>> keys) {
    return CompletableFuture.completedFuture(
        ReaderLoaderKey.loadByReader(
            keys, profileSharingService::pendingOfferCounts, Function.identity()));
  }
}
