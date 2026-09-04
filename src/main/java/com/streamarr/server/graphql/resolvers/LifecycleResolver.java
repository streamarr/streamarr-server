package com.streamarr.server.graphql.resolvers;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsMutation;
import com.netflix.graphql.dgs.InputArgument;
import com.streamarr.server.graphql.dto.AccountAdministration;
import com.streamarr.server.graphql.dto.ProfileAdministration;
import com.streamarr.server.graphql.inputs.AdministrativelyDeleteProfileInput;
import com.streamarr.server.graphql.inputs.DeleteAccountInput;
import com.streamarr.server.graphql.inputs.DeleteMyAccountInput;
import com.streamarr.server.graphql.inputs.TransferAccountInput;
import com.streamarr.server.graphql.inputs.TransferProfileInput;
import com.streamarr.server.graphql.mutation.MutationPayloads;
import com.streamarr.server.graphql.mutation.lifecycle.AdministrativelyDeleteProfilePayload;
import com.streamarr.server.graphql.mutation.lifecycle.DeleteAccountPayload;
import com.streamarr.server.graphql.mutation.lifecycle.DeleteMyAccountPayload;
import com.streamarr.server.graphql.mutation.lifecycle.LifecycleErrors;
import com.streamarr.server.graphql.mutation.lifecycle.TransferAccountPayload;
import com.streamarr.server.graphql.mutation.lifecycle.TransferProfilePayload;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.identity.AccountLifecycleService;
import com.streamarr.server.services.identity.AccountLifecycleService.DeleteAccountCommand;
import com.streamarr.server.services.identity.AccountLifecycleService.TransferAccountCommand;
import com.streamarr.server.services.identity.ProfileLifecycleService;
import com.streamarr.server.services.identity.ProfileLifecycleService.TransferProfileCommand;
import java.util.UUID;
import lombok.RequiredArgsConstructor;

@DgsComponent
@RequiredArgsConstructor
public class LifecycleResolver {

  private final AuthorizationService authorizationService;
  private final AccountLifecycleService accountLifecycleService;
  private final ProfileLifecycleService profileLifecycleService;

  @DgsMutation
  public TransferAccountPayload transferAccount(@InputArgument TransferAccountInput input) {
    var command =
        TransferAccountCommand.builder()
            .sourceAccess(
                input.sourceAccess() == null
                    ? AccountLifecycleService.SourceAccess.END
                    : input.sourceAccess())
            .reason(input.reason());
    return MutationPayloads.withUuid(
        input.accountId(),
        accountId -> transferAccount(input, command.accountId(accountId)),
        () -> invalidTransferAccount("accountId"));
  }

  private TransferAccountPayload transferAccount(
      TransferAccountInput input, TransferAccountCommand.TransferAccountCommandBuilder command) {
    return MutationPayloads.withUuid(
        input.destinationHouseholdId(),
        destinationHouseholdId ->
            MutationPayloads.payload(
                accountLifecycleService
                    .transferAccount(
                        authorizationService.currentIdentity(),
                        command.destinationHouseholdId(destinationHouseholdId).build())
                    .map(AccountAdministration::from),
                LifecycleErrors::toTransferAccountError,
                TransferAccountPayload::new),
        () -> invalidTransferAccount("destinationHouseholdId"));
  }

  @DgsMutation
  public DeleteAccountPayload deleteAccount(@InputArgument DeleteAccountInput input) {
    var command =
        DeleteAccountCommand.builder()
            .profileDisposition(input.profileDisposition())
            .reason(input.reason());
    return MutationPayloads.withUuid(
        input.accountId(),
        accountId -> deleteAccount(input, command.accountId(accountId)),
        () -> invalidDeleteAccount("accountId"));
  }

  private DeleteAccountPayload deleteAccount(
      DeleteAccountInput input, DeleteAccountCommand.DeleteAccountCommandBuilder command) {
    if (input.replacementManagerAccountId() == null) {
      return deleteAccount(command.build());
    }

    return MutationPayloads.withUuid(
        input.replacementManagerAccountId(),
        replacementManagerAccountId ->
            deleteAccount(command.replacementManagerAccountId(replacementManagerAccountId).build()),
        () -> invalidDeleteAccount("replacementManagerAccountId"));
  }

  private DeleteAccountPayload deleteAccount(DeleteAccountCommand command) {
    return MutationPayloads.payload(
        accountLifecycleService
            .deleteAccount(authorizationService.currentIdentity(), command)
            .map(UUID::toString),
        LifecycleErrors::toDeleteAccountError,
        DeleteAccountPayload::new);
  }

  @DgsMutation
  public DeleteMyAccountPayload deleteMyAccount(@InputArgument DeleteMyAccountInput input) {
    return MutationPayloads.payload(
        accountLifecycleService
            .deleteMyAccount(authorizationService.currentIdentity(), input.confirmation())
            .map(UUID::toString),
        LifecycleErrors::toDeleteMyAccountError,
        DeleteMyAccountPayload::new);
  }

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
        LifecycleErrors::toTransferProfileError,
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
                LifecycleErrors::toAdministrativelyDeleteProfileError,
                AdministrativelyDeleteProfilePayload::new),
        () -> invalidAdministrativelyDeleteProfile("profileId"));
  }

  private static TransferAccountPayload invalidTransferAccount(String inputName) {
    return MutationPayloads.inputError(
        LifecycleErrors.invalidId(inputName), TransferAccountPayload::new);
  }

  private static DeleteAccountPayload invalidDeleteAccount(String inputName) {
    return MutationPayloads.inputError(
        LifecycleErrors.invalidId(inputName), DeleteAccountPayload::new);
  }

  private static TransferProfilePayload invalidTransferProfile(String inputName) {
    return MutationPayloads.inputError(
        LifecycleErrors.invalidId(inputName), TransferProfilePayload::new);
  }

  private static AdministrativelyDeleteProfilePayload invalidAdministrativelyDeleteProfile(
      String inputName) {
    return MutationPayloads.inputError(
        LifecycleErrors.invalidId(inputName), AdministrativelyDeleteProfilePayload::new);
  }
}
