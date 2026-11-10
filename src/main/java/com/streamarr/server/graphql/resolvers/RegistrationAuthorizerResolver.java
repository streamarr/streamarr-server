package com.streamarr.server.graphql.resolvers;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsData;
import com.streamarr.server.graphql.dataloaders.ReaderLoaderKey;
import com.streamarr.server.graphql.dataloaders.RegistrationAuthorizerNameDataLoader;
import com.streamarr.server.graphql.dto.DeviceRegistrationDetails;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.identity.RegistrationAuthorizer;
import graphql.schema.DataFetchingEnvironment;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import org.dataloader.DataLoader;

/** The person who linked a Device, loaded for the reader who asks and null when hidden. */
@DgsComponent
@RequiredArgsConstructor
public class RegistrationAuthorizerResolver {

  private final AuthorizationService authorizationService;

  @DgsData(parentType = "DeviceRegistrationDetails", field = "authorizingAccountDisplayName")
  public CompletableFuture<String> authorizingAccountDisplayName(DataFetchingEnvironment dfe) {
    DeviceRegistrationDetails registration = dfe.getSource();
    DataLoader<ReaderLoaderKey<RegistrationAuthorizer>, String> loader =
        dfe.getDataLoader(RegistrationAuthorizerNameDataLoader.NAME);
    return registration
        .authorizer()
        .map(
            authorizer ->
                loader.load(
                    new ReaderLoaderKey<>(authorizationService.currentIdentity(), authorizer)))
        .orElseGet(() -> CompletableFuture.completedFuture(null));
  }
}
