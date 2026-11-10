package com.streamarr.server.graphql.dataloaders;

import com.netflix.graphql.dgs.DgsDataLoader;
import com.streamarr.server.graphql.dto.ProfileSummary;
import com.streamarr.server.services.identity.ProfileSharePartiesService;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import lombok.RequiredArgsConstructor;
import org.dataloader.MappedBatchLoader;

/** The Profile on each share of a response, read together for each reader. */
@DgsDataLoader(name = ShareProfileDataLoader.NAME)
@RequiredArgsConstructor
public class ShareProfileDataLoader implements MappedBatchLoader<ShareLoaderKey, ProfileSummary> {

  public static final String NAME = "shareProfile";

  private final ProfileSharePartiesService sharePartiesService;

  @Override
  public CompletionStage<Map<ShareLoaderKey, ProfileSummary>> load(Set<ShareLoaderKey> keys) {
    return CompletableFuture.completedFuture(
        ShareLoaderKey.loadByReader(keys, sharePartiesService::profiles, ProfileSummary::from));
  }
}
