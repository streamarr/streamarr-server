package com.streamarr.server.graphql.dataloaders;

import com.netflix.graphql.dgs.DgsDataLoader;
import com.streamarr.server.services.identity.ProfileSharePartiesService;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.dataloader.MappedBatchLoader;

/** The display name of the Account that offered each share of a response, for each reader. */
@DgsDataLoader(name = ShareOffererNameDataLoader.NAME)
@RequiredArgsConstructor
public class ShareOffererNameDataLoader implements MappedBatchLoader<ShareLoaderKey, String> {

  public static final String NAME = "shareOffererName";

  private final ProfileSharePartiesService sharePartiesService;

  @Override
  public CompletionStage<Map<ShareLoaderKey, String>> load(Set<ShareLoaderKey> keys) {
    return CompletableFuture.completedFuture(
        ShareLoaderKey.loadByReader(keys, sharePartiesService::offererNames, Function.identity()));
  }
}
