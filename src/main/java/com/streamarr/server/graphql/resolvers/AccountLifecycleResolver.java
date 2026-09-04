package com.streamarr.server.graphql.resolvers;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsMutation;
import com.netflix.graphql.dgs.InputArgument;
import com.streamarr.server.graphql.dto.AccountAdministration;
import com.streamarr.server.graphql.inputs.AdministrativelyDeleteAccountInput;
import com.streamarr.server.graphql.inputs.DeleteMyAccountInput;
import com.streamarr.server.graphql.inputs.TransferAccountInput;
import com.streamarr.server.graphql.mutation.MutationPayloads;
import com.streamarr.server.graphql.mutation.identity.lifecycle.AdministrativelyDeleteAccountPayload;
import com.streamarr.server.graphql.mutation.identity.lifecycle.DeleteMyAccountPayload;
import com.streamarr.server.graphql.mutation.identity.lifecycle.IdentityLifecycleErrors;
import com.streamarr.server.graphql.mutation.identity.lifecycle.TransferAccountPayload;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.identity.AccountLifecycleService;
import com.streamarr.server.services.identity.AccountLifecycleService.AdministrativelyDeleteAccountCommand;
import com.streamarr.server.services.identity.AccountLifecycleService.TransferAccountCommand;
import java.util.UUID;
import lombok.RequiredArgsConstructor;

@DgsComponent
@RequiredArgsConstructor
public class AccountLifecycleResolver {

  private final AuthorizationService authorizationService;
  private final AccountLifecycleService accountLifecycleService;

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
                IdentityLifecycleErrors::toTransferAccountError,
                TransferAccountPayload::new),
        () -> invalidTransferAccount("destinationHouseholdId"));
  }

  @DgsMutation
  public AdministrativelyDeleteAccountPayload administrativelyDeleteAccount(
      @InputArgument AdministrativelyDeleteAccountInput input) {
    var command =
        AdministrativelyDeleteAccountCommand.builder()
            .profileDisposition(input.profileDisposition())
            .reason(input.reason());
    return MutationPayloads.withUuid(
        input.accountId(),
        accountId -> administrativelyDeleteAccount(input, command.accountId(accountId)),
        () -> invalidAdministrativelyDeleteAccount("accountId"));
  }

  private AdministrativelyDeleteAccountPayload administrativelyDeleteAccount(
      AdministrativelyDeleteAccountInput input,
      AdministrativelyDeleteAccountCommand.AdministrativelyDeleteAccountCommandBuilder command) {
    if (input.replacementManagerAccountId() == null) {
      return administrativelyDeleteAccount(command.build());
    }

    return MutationPayloads.withUuid(
        input.replacementManagerAccountId(),
        replacementManagerAccountId ->
            administrativelyDeleteAccount(
                command.replacementManagerAccountId(replacementManagerAccountId).build()),
        () -> invalidAdministrativelyDeleteAccount("replacementManagerAccountId"));
  }

  private AdministrativelyDeleteAccountPayload administrativelyDeleteAccount(
      AdministrativelyDeleteAccountCommand command) {
    return MutationPayloads.payload(
        accountLifecycleService
            .administrativelyDeleteAccount(authorizationService.currentIdentity(), command)
            .map(UUID::toString),
        IdentityLifecycleErrors::toAdministrativelyDeleteAccountError,
        AdministrativelyDeleteAccountPayload::new);
  }

  @DgsMutation
  public DeleteMyAccountPayload deleteMyAccount(@InputArgument DeleteMyAccountInput input) {
    return MutationPayloads.payload(
        accountLifecycleService
            .deleteMyAccount(authorizationService.currentIdentity(), input.confirmation())
            .map(UUID::toString),
        IdentityLifecycleErrors::toDeleteMyAccountError,
        DeleteMyAccountPayload::new);
  }

  private static TransferAccountPayload invalidTransferAccount(String inputName) {
    return MutationPayloads.inputError(
        IdentityLifecycleErrors.invalidId(inputName), TransferAccountPayload::new);
  }

  private static AdministrativelyDeleteAccountPayload invalidAdministrativelyDeleteAccount(
      String inputName) {
    return MutationPayloads.inputError(
        IdentityLifecycleErrors.invalidId(inputName), AdministrativelyDeleteAccountPayload::new);
  }
}
