package com.streamarr.server.graphql.resolvers;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsMutation;
import com.netflix.graphql.dgs.DgsQuery;
import com.netflix.graphql.dgs.InputArgument;
import com.streamarr.server.graphql.Ids;
import com.streamarr.server.graphql.dto.AccountAdministration;
import com.streamarr.server.graphql.inputs.DisableAccountInput;
import com.streamarr.server.graphql.inputs.EnableAccountInput;
import com.streamarr.server.graphql.inputs.GrantHouseholdAdminInput;
import com.streamarr.server.graphql.inputs.GrantServerAdminInput;
import com.streamarr.server.graphql.inputs.RenameAccountInput;
import com.streamarr.server.graphql.inputs.RevokeHouseholdAdminInput;
import com.streamarr.server.graphql.inputs.RevokeServerAdminInput;
import com.streamarr.server.graphql.mutation.MutationPayloads;
import com.streamarr.server.graphql.mutation.administration.AdministrationErrors;
import com.streamarr.server.graphql.mutation.administration.DisableAccountPayload;
import com.streamarr.server.graphql.mutation.administration.EnableAccountPayload;
import com.streamarr.server.graphql.mutation.administration.GrantHouseholdAdminPayload;
import com.streamarr.server.graphql.mutation.administration.GrantServerAdminPayload;
import com.streamarr.server.graphql.mutation.administration.RenameAccountPayload;
import com.streamarr.server.graphql.mutation.administration.RevokeHouseholdAdminPayload;
import com.streamarr.server.graphql.mutation.administration.RevokeServerAdminPayload;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.identity.AccountAdministrationService;
import com.streamarr.server.services.identity.AdministrationQueryService;
import lombok.RequiredArgsConstructor;

@DgsComponent
@RequiredArgsConstructor
public class AccountAdministrationResolver {

  private final AuthorizationService authorizationService;
  private final AccountAdministrationService accountAdministrationService;
  private final AdministrationQueryService administrationQueryService;

  @DgsQuery
  public AccountAdministration accountAdministration(@InputArgument String accountId) {
    return administrationQueryService
        .accountAdministration(authorizationService.currentIdentity(), Ids.parseUuid(accountId))
        .map(AccountAdministration::from)
        .orElse(null);
  }

  @DgsMutation
  public GrantServerAdminPayload grantServerAdmin(@InputArgument GrantServerAdminInput input) {
    return MutationPayloads.withUuid(
        input.accountId(),
        accountId ->
            MutationPayloads.payload(
                accountAdministrationService
                    .grantServerAdmin(
                        authorizationService.currentIdentity(), accountId, input.reason())
                    .map(AccountAdministration::from),
                AdministrationErrors::toGrantServerAdminError,
                GrantServerAdminPayload::new),
        () ->
            MutationPayloads.inputError(
                AdministrationErrors.invalidAccountId(), GrantServerAdminPayload::new));
  }

  @DgsMutation
  public RevokeServerAdminPayload revokeServerAdmin(@InputArgument RevokeServerAdminInput input) {
    return MutationPayloads.withUuid(
        input.accountId(),
        accountId ->
            MutationPayloads.payload(
                accountAdministrationService
                    .revokeServerAdmin(
                        authorizationService.currentIdentity(), accountId, input.reason())
                    .map(AccountAdministration::from),
                AdministrationErrors::toRevokeServerAdminError,
                RevokeServerAdminPayload::new),
        () ->
            MutationPayloads.inputError(
                AdministrationErrors.invalidAccountId(), RevokeServerAdminPayload::new));
  }

  @DgsMutation
  public GrantHouseholdAdminPayload grantHouseholdAdmin(
      @InputArgument GrantHouseholdAdminInput input) {
    return MutationPayloads.withUuid(
        input.accountId(),
        accountId ->
            MutationPayloads.payload(
                accountAdministrationService
                    .grantHouseholdAdmin(authorizationService.currentIdentity(), accountId)
                    .map(AccountAdministration::from),
                AdministrationErrors::toGrantHouseholdAdminError,
                GrantHouseholdAdminPayload::new),
        () ->
            MutationPayloads.inputError(
                AdministrationErrors.invalidAccountId(), GrantHouseholdAdminPayload::new));
  }

  @DgsMutation
  public RevokeHouseholdAdminPayload revokeHouseholdAdmin(
      @InputArgument RevokeHouseholdAdminInput input) {
    return MutationPayloads.withUuid(
        input.accountId(),
        accountId ->
            MutationPayloads.payload(
                accountAdministrationService
                    .revokeHouseholdAdmin(authorizationService.currentIdentity(), accountId)
                    .map(AccountAdministration::from),
                AdministrationErrors::toRevokeHouseholdAdminError,
                RevokeHouseholdAdminPayload::new),
        () ->
            MutationPayloads.inputError(
                AdministrationErrors.invalidAccountId(), RevokeHouseholdAdminPayload::new));
  }

  @DgsMutation
  public DisableAccountPayload disableAccount(@InputArgument DisableAccountInput input) {
    return MutationPayloads.withUuid(
        input.accountId(),
        accountId ->
            MutationPayloads.payload(
                accountAdministrationService
                    .disableAccount(authorizationService.currentIdentity(), accountId)
                    .map(AccountAdministration::from),
                AdministrationErrors::toDisableAccountError,
                DisableAccountPayload::new),
        () ->
            MutationPayloads.inputError(
                AdministrationErrors.invalidAccountId(), DisableAccountPayload::new));
  }

  @DgsMutation
  public EnableAccountPayload enableAccount(@InputArgument EnableAccountInput input) {
    return MutationPayloads.withUuid(
        input.accountId(),
        accountId ->
            MutationPayloads.payload(
                accountAdministrationService
                    .enableAccount(authorizationService.currentIdentity(), accountId)
                    .map(AccountAdministration::from),
                AdministrationErrors::toEnableAccountError,
                EnableAccountPayload::new),
        () ->
            MutationPayloads.inputError(
                AdministrationErrors.invalidAccountId(), EnableAccountPayload::new));
  }

  @DgsMutation
  public RenameAccountPayload renameAccount(@InputArgument RenameAccountInput input) {
    return MutationPayloads.withUuid(
        input.accountId(),
        accountId ->
            MutationPayloads.payload(
                accountAdministrationService
                    .renameAccount(
                        authorizationService.currentIdentity(), accountId, input.displayName())
                    .map(AccountAdministration::from),
                AdministrationErrors::toRenameAccountError,
                RenameAccountPayload::new),
        () ->
            MutationPayloads.inputError(
                AdministrationErrors.invalidAccountId(), RenameAccountPayload::new));
  }
}
