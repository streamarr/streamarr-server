package com.streamarr.server.graphql.resolvers;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsMutation;
import com.netflix.graphql.dgs.DgsQuery;
import com.netflix.graphql.dgs.InputArgument;
import com.streamarr.server.domain.auth.SecurityAuditEventRecordView;
import com.streamarr.server.domain.streaming.SessionProgress;
import com.streamarr.server.graphql.Ids;
import com.streamarr.server.graphql.cursor.ConnectionArguments;
import com.streamarr.server.graphql.cursor.CursorUtil;
import com.streamarr.server.graphql.cursor.InvalidCursorException;
import com.streamarr.server.graphql.cursor.RelayConnectionAdapter;
import com.streamarr.server.graphql.dto.HouseholdTeardownPreview;
import com.streamarr.server.graphql.dto.ProfileActivityDetails;
import com.streamarr.server.graphql.dto.ProfileDeletionPreview;
import com.streamarr.server.graphql.dto.SecurityAuditEventDetails;
import com.streamarr.server.graphql.inputs.LastAccountAction;
import com.streamarr.server.graphql.inputs.TearDownHouseholdInput;
import com.streamarr.server.graphql.mutation.MutationPayloads;
import com.streamarr.server.graphql.mutation.teardown.TearDownHouseholdPayload;
import com.streamarr.server.graphql.mutation.teardown.TeardownErrors;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.identity.HouseholdTeardownService;
import com.streamarr.server.services.identity.HouseholdTeardownService.FinalAccountChoice;
import com.streamarr.server.services.identity.HouseholdTeardownService.FinalAccountDisposition;
import com.streamarr.server.services.identity.HouseholdTeardownService.SecurityAuditPageRequest;
import com.streamarr.server.services.identity.HouseholdTeardownService.TearDownHouseholdCommand;
import com.streamarr.server.services.identity.HouseholdTeardownService.TeardownPreflightDetails;
import com.streamarr.server.services.pagination.MediaPage;
import com.streamarr.server.services.pagination.PaginationOptions;
import com.streamarr.server.services.pagination.PaginationService;
import graphql.relay.Connection;
import graphql.schema.DataFetchingEnvironment;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;

@DgsComponent
@RequiredArgsConstructor
public class TeardownResolver {

  private static final int DEFAULT_PAGE_SIZE = 100;

  private final AuthorizationService authorizationService;
  private final HouseholdTeardownService householdTeardownService;
  private final PaginationService paginationService;
  private final CursorUtil cursorUtil;
  private final RelayConnectionAdapter relayConnectionAdapter;

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
  public HouseholdTeardownPreview householdTeardownPreview(@InputArgument String householdId) {
    return householdTeardownService
        .teardownPreflight(authorizationService.currentIdentity(), Ids.parseUuid(householdId))
        .map(TeardownResolver::toDto)
        .orElse(null);
  }

  @DgsQuery
  public Connection<SecurityAuditEventDetails> securityAuditEvents(DataFetchingEnvironment dfe) {
    var options = ConnectionArguments.paginationOptions(paginationService, dfe, DEFAULT_PAGE_SIZE);
    var page =
        householdTeardownService.securityAuditEvents(
            authorizationService.currentIdentity(), auditPageRequest(options));
    return toAuditConnection(page);
  }

  @DgsQuery
  public Connection<ProfileActivityDetails> profileActivity(
      @InputArgument String profileId, DataFetchingEnvironment dfe) {
    var options = ConnectionArguments.paginationOptions(paginationService, dfe, DEFAULT_PAGE_SIZE);
    var page =
        householdTeardownService.profileActivity(
            authorizationService.currentIdentity(),
            Ids.parseUuid(profileId),
            cursorUtil.decodeKeysetCursor(options));
    return toActivityConnection(page);
  }

  private static FinalAccountDisposition dispositionOf(TearDownHouseholdInput input) {
    if (input.lastAccount() == null) {
      return null;
    }

    return FinalAccountDisposition.builder()
        .choice(choiceOf(input.lastAccount().choice()))
        .destinationHouseholdId(
            input.lastAccount().destinationHouseholdId() == null
                ? null
                : Ids.parseUuid(input.lastAccount().destinationHouseholdId()))
        .replacementManagerAccountId(
            input.lastAccount().replacementManagerAccountId() == null
                ? null
                : Ids.parseUuid(input.lastAccount().replacementManagerAccountId()))
        .build();
  }

  private static FinalAccountChoice choiceOf(LastAccountAction action) {
    return switch (action) {
      case TRANSFER -> FinalAccountChoice.TRANSFER;
      case DELETE -> FinalAccountChoice.DELETE;
      case DELETE_ACCOUNT_KEEP_PROFILE -> FinalAccountChoice.DELETE_KEEPING_PROFILE;
    };
  }

  private static HouseholdTeardownPreview toDto(TeardownPreflightDetails preview) {
    var profiles =
        preview.unlinkedProfiles().stream()
            .map(profile -> new ProfileDeletionPreview(profile.id(), profile.name()))
            .toList();
    return new HouseholdTeardownPreview(
        preview.accountCount(), profiles, preview.hostedVisitCount());
  }

  private SecurityAuditPageRequest auditPageRequest(PaginationOptions options) {
    var cursor =
        options
            .getCursor()
            .map(cursorUtil::decodeOpaqueCursor)
            .map(TeardownResolver::auditCursor)
            .orElse(null);
    return SecurityAuditPageRequest.builder()
        .direction(options.getPaginationDirection())
        .cursorOccurredAt(cursor == null ? null : cursor.occurredAt())
        .cursorId(cursor == null ? null : cursor.id())
        .limit(options.getLimit())
        .build();
  }

  private static AuditCursor auditCursor(String key) {
    var separator = key.lastIndexOf('|');
    if (separator < 0) {
      throw new InvalidCursorException("Cursor is not valid.");
    }

    try {
      return new AuditCursor(
          Instant.parse(key.substring(0, separator)),
          UUID.fromString(key.substring(separator + 1)));
    } catch (DateTimeException | IllegalArgumentException _) {
      throw new InvalidCursorException("Cursor is not valid.");
    }
  }

  private Connection<SecurityAuditEventDetails> toAuditConnection(
      MediaPage<SecurityAuditEventRecordView> page) {
    return relayConnectionAdapter.toConnection(
        page,
        item -> SecurityAuditEventDetails.from(item.item()),
        item -> cursorUtil.encodeOpaqueCursor(item.item().occurredAt() + "|" + item.item().id()));
  }

  private Connection<ProfileActivityDetails> toActivityConnection(MediaPage<SessionProgress> page) {
    return relayConnectionAdapter.toConnection(
        page,
        item -> ProfileActivityDetails.from(item.item()),
        item -> cursorUtil.encodeKeysetCursor(item.item().getId()));
  }

  private record AuditCursor(Instant occurredAt, UUID id) {}
}
