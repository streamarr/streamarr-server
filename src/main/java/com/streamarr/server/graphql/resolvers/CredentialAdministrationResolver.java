package com.streamarr.server.graphql.resolvers;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsMutation;
import com.netflix.graphql.dgs.DgsQuery;
import com.netflix.graphql.dgs.InputArgument;
import com.streamarr.server.graphql.Ids;
import com.streamarr.server.graphql.cursor.CursorUtil;
import com.streamarr.server.graphql.cursor.RelayConnectionAdapter;
import com.streamarr.server.graphql.dto.AccountInvitationView;
import com.streamarr.server.graphql.dto.IssuedAccountInvitation;
import com.streamarr.server.graphql.dto.IssuedPasswordReset;
import com.streamarr.server.graphql.inputs.CancelAccountInvitationInput;
import com.streamarr.server.graphql.inputs.IssueAccountInvitationInput;
import com.streamarr.server.graphql.inputs.IssuePasswordResetInput;
import com.streamarr.server.graphql.mutation.MutationPayloads;
import com.streamarr.server.graphql.mutation.credentials.CancelAccountInvitationPayload;
import com.streamarr.server.graphql.mutation.credentials.CredentialErrors;
import com.streamarr.server.graphql.mutation.credentials.IssueAccountInvitationPayload;
import com.streamarr.server.graphql.mutation.credentials.IssuePasswordResetPayload;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.identity.AdministrationQueryService;
import com.streamarr.server.services.identity.CredentialIssuanceService;
import com.streamarr.server.services.identity.CredentialIssuanceService.IssueInvitationCommand;
import com.streamarr.server.services.pagination.PaginationOptions;
import com.streamarr.server.services.pagination.PaginationService;
import graphql.relay.Connection;
import graphql.schema.DataFetchingEnvironment;
import lombok.RequiredArgsConstructor;

@DgsComponent
@RequiredArgsConstructor
public class CredentialAdministrationResolver {

  private static final int DEFAULT_PAGE_SIZE = 100;

  private final AuthorizationService authorizationService;
  private final CredentialIssuanceService credentialIssuanceService;
  private final AdministrationQueryService administrationQueryService;
  private final PaginationService paginationService;
  private final CursorUtil cursorUtil;
  private final RelayConnectionAdapter relayConnectionAdapter;

  @DgsQuery
  public Connection<AccountInvitationView> accountInvitations(DataFetchingEnvironment dfe) {
    var options = options(dfe);
    var page =
        administrationQueryService.accountInvitations(
            authorizationService.currentIdentity(), cursorUtil.decodeKeysetCursor(options));
    return relayConnectionAdapter.toConnection(
        page,
        item -> AccountInvitationView.from(item.item()),
        item -> cursorUtil.encodeKeysetCursor(item.item().getId()));
  }

  @DgsMutation
  public IssueAccountInvitationPayload issueAccountInvitation(
      @InputArgument IssueAccountInvitationInput input) {
    return MutationPayloads.payload(
        credentialIssuanceService
            .issueAccountInvitation(
                authorizationService.currentIdentity(),
                IssueInvitationCommand.builder()
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
                        AccountInvitationView.from(issued.invitation()), issued.code())),
        CredentialErrors::toIssueError,
        IssueAccountInvitationPayload::new);
  }

  @DgsMutation
  public CancelAccountInvitationPayload cancelAccountInvitation(
      @InputArgument CancelAccountInvitationInput input) {
    return MutationPayloads.payload(
        credentialIssuanceService
            .cancelAccountInvitation(
                authorizationService.currentIdentity(), Ids.parseUuid(input.invitationId()))
            .map(AccountInvitationView::from),
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
                    new IssuedPasswordReset(
                        issued.resetCode().getAccountId(),
                        issued.code(),
                        issued.resetCode().getExpiresAt().toString())),
        CredentialErrors::toIssueResetError,
        IssuePasswordResetPayload::new);
  }

  private PaginationOptions options(DataFetchingEnvironment dfe) {
    int first = dfe.getArgumentOrDefault("first", 0);
    String after = dfe.getArgument("after");
    int last = dfe.getArgumentOrDefault("last", 0);
    String before = dfe.getArgument("before");
    if (first == 0 && last == 0 && before != null) {
      return paginationService.getPaginationOptions(first, after, DEFAULT_PAGE_SIZE, before);
    }

    return paginationService.getPaginationOptions(
        firstOrDefault(first, last, before), after, last, before);
  }

  private static int firstOrDefault(int first, int last, String before) {
    if (first == 0 && last == 0 && before == null) {
      return DEFAULT_PAGE_SIZE;
    }

    return first;
  }
}
