package com.streamarr.server.graphql.resolvers;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsMutation;
import com.netflix.graphql.dgs.DgsQuery;
import com.netflix.graphql.dgs.InputArgument;
import com.streamarr.server.graphql.Ids;
import com.streamarr.server.graphql.cursor.InvalidCursorException;
import com.streamarr.server.graphql.cursor.KeysetConnections;
import com.streamarr.server.graphql.cursor.ListConnections;
import com.streamarr.server.graphql.dto.ProfileActivityView;
import com.streamarr.server.graphql.dto.SecurityAuditEventView;
import com.streamarr.server.graphql.inputs.TearDownHouseholdInput;
import com.streamarr.server.graphql.mutation.MutationPayloads;
import com.streamarr.server.graphql.mutation.teardown.TearDownHouseholdPayload;
import com.streamarr.server.graphql.mutation.teardown.TeardownErrors;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.identity.HouseholdTeardownService;
import com.streamarr.server.services.identity.HouseholdTeardownService.FinalAccountDisposition;
import com.streamarr.server.services.identity.HouseholdTeardownService.SecurityAuditPageRequest;
import com.streamarr.server.services.identity.HouseholdTeardownService.TearDownHouseholdCommand;
import com.streamarr.server.services.identity.HouseholdTeardownService.TeardownPreflightView;
import com.streamarr.server.services.pagination.PaginationOptions;
import com.streamarr.server.services.pagination.PaginationService;
import graphql.relay.Connection;
import graphql.schema.DataFetchingEnvironment;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import lombok.RequiredArgsConstructor;

@DgsComponent
@RequiredArgsConstructor
public class TeardownResolver {

  private final AuthorizationService authorizationService;
  private final HouseholdTeardownService householdTeardownService;
  private final PaginationService paginationService;

  @DgsMutation
  public TearDownHouseholdPayload tearDownHousehold(@InputArgument TearDownHouseholdInput input) {
    return MutationPayloads.payload(
        householdTeardownService
            .tearDownHousehold(
                authorizationService.currentIdentity(),
                TearDownHouseholdCommand.builder()
                    .householdId(Ids.parseUuid(input.householdId()))
                    .reason(input.reason())
                    .finalAccount(dispositionOf(input))
                    .build())
            .map(UUID::toString),
        TeardownErrors::toTearDownError,
        TearDownHouseholdPayload::new);
  }

  @DgsQuery
  public TeardownPreflightView teardownPreflight(@InputArgument String householdId) {
    return householdTeardownService
        .teardownPreflight(authorizationService.currentIdentity(), Ids.parseUuid(householdId))
        .orElse(null);
  }

  /** True database keyset paging: the after cursor names the last row already seen. */
  @DgsQuery
  public Connection<SecurityAuditEventView> securityAuditEvents(DataFetchingEnvironment dfe) {
    var options = options(dfe);
    Instant beforeOccurredAt = null;
    UUID beforeId = null;
    var cursor = options.getCursor().orElse(null);
    if (cursor != null) {
      var key = decodeCursor(cursor);
      var separator = key.lastIndexOf('|');
      if (separator < 0) {
        throw new InvalidCursorException("Cursor is not valid.");
      }
      try {
        beforeOccurredAt = Instant.parse(key.substring(0, separator));
        beforeId = UUID.fromString(key.substring(separator + 1));
      } catch (DateTimeException | IllegalArgumentException _) {
        throw new InvalidCursorException("Cursor is not valid.");
      }
    }
    var page =
        householdTeardownService
            .securityAuditEvents(
                authorizationService.currentIdentity(),
                SecurityAuditPageRequest.builder()
                    .direction(options.getPaginationDirection())
                    .cursorOccurredAt(beforeOccurredAt)
                    .cursorId(beforeId)
                    .limit(options.getLimit())
                    .build())
            .stream()
            .map(SecurityAuditEventView::from)
            .toList();
    return KeysetConnections.page(page, SecurityAuditEventView::cursorKey, options);
  }

  @DgsQuery
  public Connection<ProfileActivityView> profileActivity(
      @InputArgument String profileId, DataFetchingEnvironment dfe) {
    var activity =
        householdTeardownService
            .profileActivity(authorizationService.currentIdentity(), Ids.parseUuid(profileId))
            .stream()
            .map(ProfileActivityView::from)
            .toList();
    return ListConnections.page(activity, view -> view.id().toString(), options(dfe));
  }

  private static FinalAccountDisposition dispositionOf(TearDownHouseholdInput input) {
    if (input.finalAccount() == null) {
      return null;
    }
    return FinalAccountDisposition.builder()
        .choice(input.finalAccount().choice())
        .destinationHouseholdId(
            input.finalAccount().destinationHouseholdId() == null
                ? null
                : Ids.parseUuid(input.finalAccount().destinationHouseholdId()))
        .replacementManagerAccountId(
            input.finalAccount().replacementManagerAccountId() == null
                ? null
                : Ids.parseUuid(input.finalAccount().replacementManagerAccountId()))
        .build();
  }

  private static String decodeCursor(String cursor) {
    try {
      return new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
    } catch (IllegalArgumentException _) {
      throw new InvalidCursorException("Cursor is not valid.");
    }
  }

  private PaginationOptions options(DataFetchingEnvironment dfe) {
    int first = dfe.getArgumentOrDefault("first", 0);
    String after = dfe.getArgument("after");
    int last = dfe.getArgumentOrDefault("last", 0);
    String before = dfe.getArgument("before");
    return paginationService.getPaginationOptions(
        first == 0 && last == 0 && before == null ? 100 : first, after, last, before);
  }
}
