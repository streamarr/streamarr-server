package com.streamarr.server.graphql.dataloaders;

import com.netflix.graphql.dgs.DgsDataLoader;
import com.streamarr.server.services.identity.RegistrationAuthorizer;
import com.streamarr.server.services.identity.RegistrationAuthorizerService;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.dataloader.MappedBatchLoader;

/** The display name of whoever linked each Device of a response, read together per reader. */
@DgsDataLoader(name = RegistrationAuthorizerNameDataLoader.NAME)
@RequiredArgsConstructor
public class RegistrationAuthorizerNameDataLoader
    implements MappedBatchLoader<ReaderLoaderKey<RegistrationAuthorizer>, String> {

  public static final String NAME = "registrationAuthorizerName";

  private final RegistrationAuthorizerService registrationAuthorizerService;

  @Override
  public CompletionStage<Map<ReaderLoaderKey<RegistrationAuthorizer>, String>> load(
      Set<ReaderLoaderKey<RegistrationAuthorizer>> keys) {
    return CompletableFuture.completedFuture(
        ReaderLoaderKey.loadByReader(
            keys, registrationAuthorizerService::displayNames, Function.identity()));
  }
}
