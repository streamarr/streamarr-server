package com.streamarr.server.graphql.resolvers;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsMutation;
import com.netflix.graphql.dgs.InputArgument;
import com.streamarr.server.graphql.dto.ProfileAdministration;
import com.streamarr.server.graphql.inputs.AdministrativelyDeleteProfileInput;
import com.streamarr.server.graphql.inputs.TransferProfileInput;
import com.streamarr.server.graphql.mutation.MutationPayloads;
import com.streamarr.server.graphql.mutation.identity.lifecycle.AdministrativelyDeleteProfilePayload;
import com.streamarr.server.graphql.mutation.identity.lifecycle.IdentityLifecycleErrors;
import com.streamarr.server.graphql.mutation.identity.lifecycle.TransferProfilePayload;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.identity.ProfileLifecycleService;
import com.streamarr.server.services.identity.ProfileLifecycleService.TransferProfileCommand;
import java.util.UUID;
import lombok.RequiredArgsConstructor;

@DgsComponent
@RequiredArgsConstructor
public class ProfileLifecycleResolver {

  private final AuthorizationService authorizationService;
  private final ProfileLifecycleService profileLifecycleService;

  @DgsMutation
  public TransferProfilePayload transferProfile(@InputArgument TransferProfileInput input) {
    var command = TransferProfileCommand.builder().reason(input.reason());
    return MutationPayloads.withUuid(
        input.profileId(),
        profileId -> transferProfileDestination(input, command.profileId(profileId)),
        () -> invalidTransferProfile("profileId"));
  }

  private TransferProfilePayload transferProfileDestination(
      TransferProfileInput input, TransferProfileCommand.TransferProfileCommandBuilder command) {
    return MutationPayloads.withUuid(
        input.destinationHouseholdId(),
        destinationHouseholdId ->
            transferProfileManager(input, command.destinationHouseholdId(destinationHouseholdId)),
        () -> invalidTransferProfile("destinationHouseholdId"));
  }

  private TransferProfilePayload transferProfileManager(
      TransferProfileInput input, TransferProfileCommand.TransferProfileCommandBuilder command) {
    if (input.profileManagerAccountId() == null) {
      return transferProfile(command.build());
    }

    return MutationPayloads.withUuid(
        input.profileManagerAccountId(),
        profileManagerAccountId ->
            transferProfile(command.localManagerAccountId(profileManagerAccountId).build()),
        () -> invalidTransferProfile("profileManagerAccountId"));
  }

  private TransferProfilePayload transferProfile(TransferProfileCommand command) {
    return MutationPayloads.payload(
        profileLifecycleService
            .transferProfile(authorizationService.currentIdentity(), command)
            .map(profile -> ProfileAdministration.from(profile, false)),
        IdentityLifecycleErrors::toTransferProfileError,
        TransferProfilePayload::new);
  }

  @DgsMutation
  public AdministrativelyDeleteProfilePayload administrativelyDeleteProfile(
      @InputArgument AdministrativelyDeleteProfileInput input) {
    return MutationPayloads.withUuid(
        input.profileId(),
        profileId ->
            MutationPayloads.payload(
                profileLifecycleService
                    .administrativelyDeleteProfile(
                        authorizationService.currentIdentity(), profileId, input.reason())
                    .map(UUID::toString),
                IdentityLifecycleErrors::toAdministrativelyDeleteProfileError,
                AdministrativelyDeleteProfilePayload::new),
        () -> invalidAdministrativelyDeleteProfile("profileId"));
  }

  private static TransferProfilePayload invalidTransferProfile(String inputName) {
    return MutationPayloads.inputError(
        IdentityLifecycleErrors.invalidId(inputName), TransferProfilePayload::new);
  }

  private static AdministrativelyDeleteProfilePayload invalidAdministrativelyDeleteProfile(
      String inputName) {
    return MutationPayloads.inputError(
        IdentityLifecycleErrors.invalidId(inputName), AdministrativelyDeleteProfilePayload::new);
  }
}
