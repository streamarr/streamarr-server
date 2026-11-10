package com.streamarr.server.graphql.resolvers;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsData;
import com.streamarr.server.graphql.dataloaders.ShareHouseholdDataLoader;
import com.streamarr.server.graphql.dataloaders.ShareLoaderKey;
import com.streamarr.server.graphql.dataloaders.ShareOffererNameDataLoader;
import com.streamarr.server.graphql.dataloaders.ShareProfileDataLoader;
import com.streamarr.server.graphql.dto.HouseholdSummary;
import com.streamarr.server.graphql.dto.ProfileShareDetails;
import com.streamarr.server.graphql.dto.ProfileSummary;
import com.streamarr.server.services.authorization.AuthorizationService;
import graphql.schema.DataFetchingEnvironment;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import org.dataloader.DataLoader;

/** The parties a share names, each loaded for the reader who asks and null when hidden. */
@DgsComponent
@RequiredArgsConstructor
public class ProfileSharePartiesResolver {

  private final AuthorizationService authorizationService;

  @DgsData(parentType = "ProfileShare", field = "profile")
  public CompletableFuture<ProfileSummary> profile(DataFetchingEnvironment dfe) {
    DataLoader<ShareLoaderKey, ProfileSummary> loader =
        dfe.getDataLoader(ShareProfileDataLoader.NAME);
    return loader.load(keyOf(dfe));
  }

  @DgsData(parentType = "ProfileShare", field = "offeredByDisplayName")
  public CompletableFuture<String> offeredByDisplayName(DataFetchingEnvironment dfe) {
    DataLoader<ShareLoaderKey, String> loader = dfe.getDataLoader(ShareOffererNameDataLoader.NAME);
    return loader.load(keyOf(dfe));
  }

  @DgsData(parentType = "ProfileShare", field = "household")
  public CompletableFuture<HouseholdSummary> household(DataFetchingEnvironment dfe) {
    DataLoader<ShareLoaderKey, HouseholdSummary> loader =
        dfe.getDataLoader(ShareHouseholdDataLoader.NAME);
    return loader.load(keyOf(dfe));
  }

  private ShareLoaderKey keyOf(DataFetchingEnvironment dfe) {
    ProfileShareDetails share = dfe.getSource();
    return new ShareLoaderKey(authorizationService.currentIdentity(), share.parties());
  }
}
