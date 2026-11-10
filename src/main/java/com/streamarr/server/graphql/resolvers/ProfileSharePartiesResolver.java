package com.streamarr.server.graphql.resolvers;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsData;
import com.streamarr.server.graphql.dataloaders.ReaderLoaderKey;
import com.streamarr.server.graphql.dataloaders.ShareHouseholdDataLoader;
import com.streamarr.server.graphql.dataloaders.ShareOffererNameDataLoader;
import com.streamarr.server.graphql.dataloaders.ShareProfileDataLoader;
import com.streamarr.server.graphql.dto.HouseholdSummary;
import com.streamarr.server.graphql.dto.ProfileShareDetails;
import com.streamarr.server.graphql.dto.ProfileSummary;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.identity.ShareReference;
import com.streamarr.server.services.identity.SharedProfile;
import graphql.schema.DataFetchingEnvironment;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.dataloader.DataLoader;

/** The parties a share names, each loaded for the reader who asks and null when hidden. */
@DgsComponent
@RequiredArgsConstructor
public class ProfileSharePartiesResolver {

  private final AuthorizationService authorizationService;

  @DgsData(parentType = "ProfileShare", field = "profile")
  public CompletableFuture<ProfileSummary> profile(DataFetchingEnvironment dfe) {
    return fromSharedProfile(dfe, ProfileSummary::from);
  }

  @DgsData(parentType = "ProfileShare", field = "offeredByDisplayName")
  public CompletableFuture<String> offeredByDisplayName(DataFetchingEnvironment dfe) {
    DataLoader<ReaderLoaderKey<ShareReference>, String> loader =
        dfe.getDataLoader(ShareOffererNameDataLoader.NAME);
    return loader.load(keyOf(dfe));
  }

  @DgsData(parentType = "ProfileShare", field = "household")
  public CompletableFuture<HouseholdSummary> household(DataFetchingEnvironment dfe) {
    DataLoader<ReaderLoaderKey<ShareReference>, HouseholdSummary> loader =
        dfe.getDataLoader(ShareHouseholdDataLoader.NAME);
    return loader.load(keyOf(dfe));
  }

  // A reader who may not see the share's Profile loads none, so each field read from it is null.
  private <T> CompletableFuture<T> fromSharedProfile(
      DataFetchingEnvironment dfe, Function<SharedProfile, T> shown) {
    DataLoader<ReaderLoaderKey<ShareReference>, SharedProfile> loader =
        dfe.getDataLoader(ShareProfileDataLoader.NAME);
    return loader.load(keyOf(dfe)).thenApply(shared -> shared == null ? null : shown.apply(shared));
  }

  private ReaderLoaderKey<ShareReference> keyOf(DataFetchingEnvironment dfe) {
    ProfileShareDetails share = dfe.getSource();
    return new ReaderLoaderKey<>(authorizationService.currentIdentity(), share.parties());
  }
}
