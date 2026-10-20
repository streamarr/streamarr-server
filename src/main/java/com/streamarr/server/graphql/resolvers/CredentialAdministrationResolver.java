package com.streamarr.server.graphql.resolvers;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsMutation;
import com.netflix.graphql.dgs.DgsQuery;
import com.netflix.graphql.dgs.InputArgument;
import com.streamarr.server.domain.auth.AccountInvitation;
import com.streamarr.server.graphql.Ids;
import com.streamarr.server.graphql.cursor.ConnectionArguments;
import com.streamarr.server.graphql.cursor.PositionCursorCodec;
import com.streamarr.server.graphql.cursor.RelayConnectionAdapter;
import com.streamarr.server.graphql.dto.AccountInvitationDetails;
import com.streamarr.server.graphql.dto.IssuedAccountInvitation;
import com.streamarr.server.graphql.dto.IssuedPasswordReset;
import com.streamarr.server.graphql.inputs.CancelAccountInvitationInput;
import com.streamarr.server.graphql.inputs.IssueAccountInvitationForExistingProfileInput;
import com.streamarr.server.graphql.inputs.IssueAccountInvitationWithNewProfileInput;
import com.streamarr.server.graphql.inputs.IssuePasswordResetInput;
import com.streamarr.server.graphql.mutation.MutationPayloads;
import com.streamarr.server.graphql.mutation.credentials.CancelAccountInvitationPayload;
import com.streamarr.server.graphql.mutation.credentials.CredentialErrors;
import com.streamarr.server.graphql.mutation.credentials.IssueAccountInvitationForExistingProfilePayload;
import com.streamarr.server.graphql.mutation.credentials.IssueAccountInvitationWithNewProfilePayload;
import com.streamarr.server.graphql.mutation.credentials.IssuePasswordResetPayload;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.identity.AdministrationQueryService;
import com.streamarr.server.services.identity.CredentialIssuanceService;
import com.streamarr.server.services.identity.CredentialIssuanceService.AccountInvitationProfilePreview;
import com.streamarr.server.services.identity.CredentialIssuanceService.IssueInvitationForProfileCommand;
import com.streamarr.server.services.identity.CredentialIssuanceService.IssueInvitationWithNewProfileCommand;
import com.streamarr.server.services.pagination.ListIdentity;
import com.streamarr.server.services.pagination.PaginationService;
import com.streamarr.server.services.pagination.ServerScope;
import graphql.relay.Connection;
import graphql.schema.DataFetchingEnvironment;
import java.time.Clock;
import lombok.RequiredArgsConstructor;

@DgsComponent
@RequiredArgsConstructor
public class CredentialAdministrationResolver {

  private static final int DEFAULT_PAGE_SIZE = 100;
  private static final ListIdentity ACCOUNT_INVITATIONS =
      new ListIdentity("account-invitations", ServerScope.SERVER);

  private final AuthorizationService authorizationService;
  private final CredentialIssuanceService credentialIssuanceService;
  private final AdministrationQueryService administrationQueryService;
  private final PaginationService paginationService;
  private final PositionCursorCodec positionCursors;
  private final RelayConnectionAdapter relayConnectionAdapter;
  private final Clock clock;

  @DgsQuery
  public AccountInvitationProfilePreview accountInvitationProfilePreview(
      @InputArgument String profileId) {
    return credentialIssuanceService
        .accountInvitationProfilePreview(
            authorizationService.currentIdentity(), Ids.parseUuid(profileId))
        .orElse(null);
  }

  @DgsQuery
  public Connection<AccountInvitationDetails> accountInvitations(DataFetchingEnvironment dfe) {
    var request =
        positionCursors.pageRequest(
            ACCOUNT_INVITATIONS,
            ConnectionArguments.paginationOptions(paginationService, dfe, DEFAULT_PAGE_SIZE));
    var page =
        administrationQueryService.accountInvitations(
            authorizationService.currentIdentity(), request);
    return relayConnectionAdapter.toConnection(page, ACCOUNT_INVITATIONS, this::invitationDetails);
  }

  @DgsMutation
  public IssueAccountInvitationForExistingProfilePayload issueAccountInvitationForExistingProfile(
      @InputArgument IssueAccountInvitationForExistingProfileInput input) {
    return MutationPayloads.payload(
        credentialIssuanceService
            .issueAccountInvitationForProfile(
                authorizationService.currentIdentity(),
                IssueInvitationForProfileCommand.builder()
                    .recipientEmail(input.recipientEmail())
                    .profileId(Ids.parseUuid(input.profileId()))
                    .householdRole(input.householdRole())
                    .reofferHouseholdIds(
                        input.reofferHouseholdIds().stream().map(Ids::parseUuid).toList())
                    .build())
            .map(
                issued ->
                    new IssuedAccountInvitation(
                        invitationDetails(issued.invitation()), issued.code())),
        CredentialErrors::toIssueError,
        IssueAccountInvitationForExistingProfilePayload::new);
  }

  @DgsMutation
  public IssueAccountInvitationWithNewProfilePayload issueAccountInvitationWithNewProfile(
      @InputArgument IssueAccountInvitationWithNewProfileInput input) {
    return MutationPayloads.payload(
        credentialIssuanceService
            .issueAccountInvitationWithNewProfile(
                authorizationService.currentIdentity(),
                IssueInvitationWithNewProfileCommand.builder()
                    .recipientEmail(input.recipientEmail())
                    .householdId(Ids.parseUuid(input.householdId()))
                    .householdRole(input.householdRole())
                    .profileName(input.profileName())
                    .profileKind(input.profileKind())
                    .maximumAllowedRatingAge(input.maximumAllowedRatingAge())
                    .localManagerAccountId(
                        input.profileManagerAccountId() == null
                            ? null
                            : Ids.parseUuid(input.profileManagerAccountId()))
                    .build())
            .map(
                issued ->
                    new IssuedAccountInvitation(
                        invitationDetails(issued.invitation()), issued.code())),
        CredentialErrors::toIssueError,
        IssueAccountInvitationWithNewProfilePayload::new);
  }

  @DgsMutation
  public CancelAccountInvitationPayload cancelAccountInvitation(
      @InputArgument CancelAccountInvitationInput input) {
    return MutationPayloads.payload(
        credentialIssuanceService
            .cancelAccountInvitation(
                authorizationService.currentIdentity(), Ids.parseUuid(input.invitationId()))
            .map(this::invitationDetails),
        CredentialErrors::toCancelError,
        CancelAccountInvitationPayload::new);
  }

  @DgsMutation
  public IssuePasswordResetPayload issuePasswordReset(
      @InputArgument IssuePasswordResetInput input) {
    return MutationPayloads.payload(
        credentialIssuanceService
            .issuePasswordReset(
                authorizationService.currentIdentity(),
                Ids.parseUuid(input.accountId()),
                input.reason())
            .map(
                issued ->
                    IssuedPasswordReset.builder()
                        .accountId(issued.resetCode().getAccountId())
                        .code(issued.code())
                        .expiresAt(issued.resetCode().getExpiresAt().toString())
                        .build()),
        CredentialErrors::toIssueResetError,
        IssuePasswordResetPayload::new);
  }

  private AccountInvitationDetails invitationDetails(AccountInvitation invitation) {
    return AccountInvitationDetails.from(invitation, clock.instant());
  }
}
