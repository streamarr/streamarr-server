package com.streamarr.server.graphql.dataloaders;

import com.netflix.graphql.dgs.DgsDataLoader;
import com.streamarr.server.services.identity.ProfileSharePartiesService;
import com.streamarr.server.services.identity.ShareReference;
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
public class ShareOffererNameDataLoader
    implements MappedBatchLoader<ReaderLoaderKey<ShareReference>, String> {

  public static final String NAME = "shareOffererName";

  private final ProfileSharePartiesService sharePartiesService;

  @Override
  public CompletionStage<Map<ReaderLoaderKey<ShareReference>, String>> load(
      Set<ReaderLoaderKey<ShareReference>> keys) {
    return CompletableFuture.completedFuture(
        ReaderLoaderKey.loadByReader(keys, sharePartiesService::offererNames, Function.identity()));
  }
}
