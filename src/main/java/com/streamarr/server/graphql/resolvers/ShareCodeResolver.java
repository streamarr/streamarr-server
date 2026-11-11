package com.streamarr.server.graphql.resolvers;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsMutation;
import com.netflix.graphql.dgs.DgsQuery;
import com.netflix.graphql.dgs.InputArgument;
import com.streamarr.server.domain.auth.ShareCodeStatus;
import com.streamarr.server.graphql.Ids;
import com.streamarr.server.graphql.cursor.ConnectionArguments;
import com.streamarr.server.graphql.cursor.PositionCursorCodec;
import com.streamarr.server.graphql.cursor.RelayConnectionAdapter;
import com.streamarr.server.graphql.dto.IssuedShareCodeDetails;
import com.streamarr.server.graphql.dto.ShareCodeDetails;
import com.streamarr.server.graphql.inputs.IssueShareCodeInput;
import com.streamarr.server.graphql.inputs.WithdrawShareCodeInput;
import com.streamarr.server.graphql.mutation.MutationPayloads;
import com.streamarr.server.graphql.mutation.sharing.IssueShareCodePayload;
import com.streamarr.server.graphql.mutation.sharing.ShareErrors;
import com.streamarr.server.graphql.mutation.sharing.WithdrawShareCodePayload;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.identity.HouseholdShareCodesScope;
import com.streamarr.server.services.identity.NewShareCode;
import com.streamarr.server.services.identity.ShareCodeService;
import com.streamarr.server.services.identity.ShareCodeWithIssuer;
import com.streamarr.server.services.pagination.ListIdentity;
import com.streamarr.server.services.pagination.PaginationService;
import graphql.relay.Connection;
import graphql.schema.DataFetchingEnvironment;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;

/** Share codes (ADR 0024 §Share codes): a Household's codes, and issuing and withdrawing one. */
@DgsComponent
@RequiredArgsConstructor
public class ShareCodeResolver {

  private static final int DEFAULT_PAGE_SIZE = 100;

  private final AuthorizationService authorizationService;
  private final ShareCodeService shareCodeService;
  private final PaginationService paginationService;
  private final PositionCursorCodec positionCursors;
  private final RelayConnectionAdapter relayConnectionAdapter;
  private final Clock clock;

  @DgsQuery
  public Connection<ShareCodeDetails> householdShareCodes(
      @InputArgument String householdId,
      @InputArgument List<ShareCodeStatus> statuses,
      DataFetchingEnvironment dfe) {
    var household =
        new HouseholdShareCodesScope(
            Ids.parseUuid(householdId), Objects.requireNonNullElse(statuses, List.of()));
    var list = new ListIdentity("household-share-codes", household);
    var page =
        shareCodeService.householdShareCodes(
            authorizationService.currentIdentity(),
            household,
            positionCursors.pageRequest(
                list,
                ConnectionArguments.paginationOptions(paginationService, dfe, DEFAULT_PAGE_SIZE)));
    return relayConnectionAdapter.toConnection(page, list, this::details);
  }

  @DgsMutation
  public IssueShareCodePayload issueShareCode(@InputArgument IssueShareCodeInput input) {
    return MutationPayloads.payload(
        shareCodeService
            .issueShareCode(
                authorizationService.currentIdentity(), Ids.parseUuid(input.householdId()))
            .map(this::issued),
        ShareErrors::toIssueShareCodeError,
        IssueShareCodePayload::new);
  }

  @DgsMutation
  public WithdrawShareCodePayload withdrawShareCode(@InputArgument WithdrawShareCodeInput input) {
    return MutationPayloads.payload(
        shareCodeService
            .withdrawShareCode(
                authorizationService.currentIdentity(), Ids.parseUuid(input.shareCodeId()))
            .map(this::details),
        ShareErrors::toWithdrawShareCodeError,
        WithdrawShareCodePayload::new);
  }

  private IssuedShareCodeDetails issued(NewShareCode issued) {
    return new IssuedShareCodeDetails(details(issued.shareCode()), issued.code());
  }

  private ShareCodeDetails details(ShareCodeWithIssuer shareCode) {
    return ShareCodeDetails.from(shareCode, clock.instant());
  }
}
