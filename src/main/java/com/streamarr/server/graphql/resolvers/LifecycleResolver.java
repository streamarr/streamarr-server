package com.streamarr.server.graphql.resolvers;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsMutation;
import com.netflix.graphql.dgs.InputArgument;
import com.streamarr.server.graphql.Ids;
import com.streamarr.server.graphql.dto.AccountAdministration;
import com.streamarr.server.graphql.dto.ProfileAdministration;
import com.streamarr.server.graphql.inputs.DeleteAccountInput;
import com.streamarr.server.graphql.inputs.DeleteMyAccountInput;
import com.streamarr.server.graphql.inputs.ForceDeleteProfileInput;
import com.streamarr.server.graphql.inputs.TransferAccountInput;
import com.streamarr.server.graphql.inputs.TransferProfileInput;
import com.streamarr.server.graphql.mutation.MutationPayloads;
import com.streamarr.server.graphql.mutation.lifecycle.DeleteAccountPayload;
import com.streamarr.server.graphql.mutation.lifecycle.DeleteMyAccountPayload;
import com.streamarr.server.graphql.mutation.lifecycle.ForceDeleteProfilePayload;
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
    return MutationPayloads.payload(
        accountLifecycleService
            .transferAccount(
                authorizationService.currentIdentity(),
                TransferAccountCommand.builder()
                    .accountId(Ids.parseUuid(input.accountId()))
                    .destinationHouseholdId(Ids.parseUuid(input.destinationHouseholdId()))
                    .sourceAccess(
                        input.sourceAccess() == null
                            ? AccountLifecycleService.SourceAccess.END
                            : input.sourceAccess())
                    .reason(input.reason())
                    .build())
            .map(AccountAdministration::from),
        LifecycleErrors::toTransferAccountError,
        TransferAccountPayload::new);
  }

  @DgsMutation
  public DeleteAccountPayload deleteAccount(@InputArgument DeleteAccountInput input) {
    return MutationPayloads.payload(
        accountLifecycleService
            .deleteAccount(
                authorizationService.currentIdentity(),
                DeleteAccountCommand.builder()
                    .accountId(Ids.parseUuid(input.accountId()))
                    .profileDisposition(input.profileDisposition())
                    .replacementManagerAccountId(
                        input.replacementManagerAccountId() == null
                            ? null
                            : Ids.parseUuid(input.replacementManagerAccountId()))
                    .reason(input.reason())
                    .build())
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
    return MutationPayloads.payload(
        profileLifecycleService
            .transferProfile(
                authorizationService.currentIdentity(),
                TransferProfileCommand.builder()
                    .profileId(Ids.parseUuid(input.profileId()))
                    .destinationHouseholdId(Ids.parseUuid(input.destinationHouseholdId()))
                    .localManagerAccountId(
                        input.profileManagerAccountId() == null
                            ? null
                            : Ids.parseUuid(input.profileManagerAccountId()))
                    .reason(input.reason())
                    .build())
            .map(profile -> ProfileAdministration.from(profile, false)),
        LifecycleErrors::toTransferProfileError,
        TransferProfilePayload::new);
  }

  @DgsMutation
  public ForceDeleteProfilePayload forceDeleteProfile(
      @InputArgument ForceDeleteProfileInput input) {
    return MutationPayloads.payload(
        profileLifecycleService
            .forceDeleteProfile(
                authorizationService.currentIdentity(),
                Ids.parseUuid(input.profileId()),
                input.reason())
            .map(UUID::toString),
        LifecycleErrors::toForceDeleteProfileError,
        ForceDeleteProfilePayload::new);
  }
}
