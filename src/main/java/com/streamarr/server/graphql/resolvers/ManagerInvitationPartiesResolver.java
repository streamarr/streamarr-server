package com.streamarr.server.graphql.resolvers;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsData;
import com.streamarr.server.graphql.dataloaders.ManagerInvitationNamesDataLoader;
import com.streamarr.server.graphql.dto.ManagerInvitationDetails;
import com.streamarr.server.services.identity.ManagerInvitationNames;
import com.streamarr.server.services.identity.ManagerInvitationParties;
import graphql.schema.DataFetchingEnvironment;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.dataloader.DataLoader;

/** A manager invitation's names, read through the Profile and the inviter it names. */
@DgsComponent
public class ManagerInvitationPartiesResolver {

  @DgsData(parentType = "ManagerInvitationDetails", field = "profileName")
  public CompletableFuture<String> profileName(DataFetchingEnvironment dfe) {
    return names(dfe).thenApply(ManagerInvitationNames::profileName);
  }

  @DgsData(parentType = "ManagerInvitationDetails", field = "inviterDisplayName")
  public CompletableFuture<String> inviterDisplayName(DataFetchingEnvironment dfe) {
    return names(dfe).thenApply(ManagerInvitationNames::inviterDisplayName);
  }

  private static CompletableFuture<ManagerInvitationNames> names(DataFetchingEnvironment dfe) {
    ManagerInvitationDetails invitation = dfe.getSource();
    DataLoader<ManagerInvitationParties, ManagerInvitationNames> loader =
        dfe.getDataLoader(ManagerInvitationNamesDataLoader.NAME);
    return loader.load(
        ManagerInvitationParties.builder()
            .profileId(Optional.ofNullable(invitation.profileId()))
            .storedProfileName(invitation.profileName())
            .inviterAccountId(Optional.ofNullable(invitation.inviterAccountId()))
            .storedInviterDisplayName(invitation.inviterDisplayName())
            .build());
  }
}
