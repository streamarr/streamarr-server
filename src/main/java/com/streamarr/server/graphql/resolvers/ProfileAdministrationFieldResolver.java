package com.streamarr.server.graphql.resolvers;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsData;
import com.streamarr.server.domain.auth.ProfileAuthority;
import com.streamarr.server.graphql.dataloaders.ProfileReaderDetailsDataLoader;
import com.streamarr.server.graphql.dataloaders.ReaderLoaderKey;
import com.streamarr.server.graphql.dto.HouseholdSummary;
import com.streamarr.server.graphql.dto.ProfileAdministration;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.identity.ProfileReaderDetails;
import graphql.schema.DataFetchingEnvironment;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import org.dataloader.DataLoader;

/** The fields of a Profile's administrative details that depend on who reads them. */
@DgsComponent
@RequiredArgsConstructor
public class ProfileAdministrationFieldResolver {

  private final AuthorizationService authorizationService;

  @DgsData(parentType = "ProfileAdministration", field = "readerAuthority")
  public CompletableFuture<ProfileAuthority> readerAuthority(DataFetchingEnvironment dfe) {
    return readerDetails(dfe)
        .thenApply(view -> view == null ? null : view.authority().orElse(null));
  }

  @DgsData(parentType = "ProfileAdministration", field = "household")
  public CompletableFuture<HouseholdSummary> household(DataFetchingEnvironment dfe) {
    return readerDetails(dfe)
        .thenApply(
            details ->
                details == null
                    ? null
                    : details
                        .household()
                        .map(
                            household ->
                                new HouseholdSummary(household.getId(), household.getName()))
                        .orElse(null));
  }

  private CompletableFuture<ProfileReaderDetails> readerDetails(DataFetchingEnvironment dfe) {
    ProfileAdministration profile = dfe.getSource();
    DataLoader<ReaderLoaderKey<UUID>, ProfileReaderDetails> loader =
        dfe.getDataLoader(ProfileReaderDetailsDataLoader.NAME);
    return loader.load(new ReaderLoaderKey<>(authorizationService.currentIdentity(), profile.id()));
  }
}
