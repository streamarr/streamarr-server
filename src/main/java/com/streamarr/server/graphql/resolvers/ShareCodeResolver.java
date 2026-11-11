package com.streamarr.server.graphql.resolvers;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsMutation;
import com.netflix.graphql.dgs.InputArgument;
import com.streamarr.server.graphql.Ids;
import com.streamarr.server.graphql.dto.IssuedShareCodeDetails;
import com.streamarr.server.graphql.dto.ShareCodeDetails;
import com.streamarr.server.graphql.inputs.IssueShareCodeInput;
import com.streamarr.server.graphql.mutation.MutationPayloads;
import com.streamarr.server.graphql.mutation.sharing.IssueShareCodePayload;
import com.streamarr.server.graphql.mutation.sharing.ShareErrors;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.identity.NewShareCode;
import com.streamarr.server.services.identity.ShareCodeService;
import java.time.Clock;
import lombok.RequiredArgsConstructor;

/** Share codes (ADR 0024 §Share codes): issuing one for a Household. */
@DgsComponent
@RequiredArgsConstructor
public class ShareCodeResolver {

  private final AuthorizationService authorizationService;
  private final ShareCodeService shareCodeService;
  private final Clock clock;

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

  private IssuedShareCodeDetails issued(NewShareCode issued) {
    return new IssuedShareCodeDetails(
        ShareCodeDetails.from(issued.shareCode(), clock.instant()), issued.code());
  }
}
