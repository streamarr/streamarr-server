package com.streamarr.server.graphql.dataloaders;

import com.netflix.graphql.dgs.DgsDataLoader;
import com.streamarr.server.domain.language.ProfileLanguagePreferences;
import com.streamarr.server.services.identity.ProfileReaderDetailsService;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.dataloader.MappedBatchLoader;

/**
 * The own language preferences of each Profile of a response, read together for each reader. A
 * Profile whose preferences the reader may not read has no value.
 */
@DgsDataLoader(name = ProfileLanguagePreferencesDataLoader.NAME)
@RequiredArgsConstructor
public class ProfileLanguagePreferencesDataLoader
    implements MappedBatchLoader<ReaderLoaderKey<UUID>, ProfileLanguagePreferences> {

  public static final String NAME = "profileLanguagePreferences";

  private final ProfileReaderDetailsService readerDetailsService;

  @Override
  public CompletionStage<Map<ReaderLoaderKey<UUID>, ProfileLanguagePreferences>> load(
      Set<ReaderLoaderKey<UUID>> keys) {
    return CompletableFuture.completedFuture(
        ReaderLoaderKey.loadByReader(
            keys, readerDetailsService::languagePreferences, Function.identity()));
  }
}
