package com.streamarr.server.graphql.dataloaders;

import com.netflix.graphql.dgs.DgsDataLoader;
import com.streamarr.server.services.identity.ProfileReaderDetails;
import com.streamarr.server.services.identity.ProfileReaderDetailsService;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.dataloader.MappedBatchLoader;

/** What each Profile of a response is to its reader, read together for each reader. */
@DgsDataLoader(name = ProfileReaderDetailsDataLoader.NAME)
@RequiredArgsConstructor
public class ProfileReaderDetailsDataLoader
    implements MappedBatchLoader<ReaderLoaderKey<UUID>, ProfileReaderDetails> {

  public static final String NAME = "profileReaderDetails";

  private final ProfileReaderDetailsService readerDetailsService;

  @Override
  public CompletionStage<Map<ReaderLoaderKey<UUID>, ProfileReaderDetails>> load(
      Set<ReaderLoaderKey<UUID>> keys) {
    return CompletableFuture.completedFuture(
        ReaderLoaderKey.loadByReader(keys, readerDetailsService::details, Function.identity()));
  }
}
