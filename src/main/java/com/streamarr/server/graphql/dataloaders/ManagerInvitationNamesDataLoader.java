package com.streamarr.server.graphql.dataloaders;

import com.netflix.graphql.dgs.DgsDataLoader;
import com.streamarr.server.services.identity.ManagerInvitationNames;
import com.streamarr.server.services.identity.ManagerInvitationParties;
import com.streamarr.server.services.identity.ManagerInvitationPartiesService;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import lombok.RequiredArgsConstructor;
import org.dataloader.MappedBatchLoader;

/**
 * The names each manager invitation of a response shows, read together. The invitations reached the
 * response only through a read their reader may make, so the key carries no reader.
 */
@DgsDataLoader(name = ManagerInvitationNamesDataLoader.NAME)
@RequiredArgsConstructor
public class ManagerInvitationNamesDataLoader
    implements MappedBatchLoader<ManagerInvitationParties, ManagerInvitationNames> {

  public static final String NAME = "managerInvitationNames";

  private final ManagerInvitationPartiesService partiesService;

  @Override
  public CompletionStage<Map<ManagerInvitationParties, ManagerInvitationNames>> load(
      Set<ManagerInvitationParties> keys) {
    return CompletableFuture.completedFuture(partiesService.currentNames(keys));
  }
}
