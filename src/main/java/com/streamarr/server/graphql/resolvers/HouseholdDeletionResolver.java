package com.streamarr.server.graphql.resolvers;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsMutation;
import com.netflix.graphql.dgs.DgsQuery;
import com.netflix.graphql.dgs.InputArgument;
import com.streamarr.server.graphql.Ids;
import com.streamarr.server.graphql.cursor.ConnectionArguments;
import com.streamarr.server.graphql.cursor.PositionCursorCodec;
import com.streamarr.server.graphql.cursor.RelayConnectionAdapter;
import com.streamarr.server.graphql.dto.HouseholdDeletionPreview;
import com.streamarr.server.graphql.dto.ProfileActivityDetails;
import com.streamarr.server.graphql.dto.ProfileDeletionPreview;
import com.streamarr.server.graphql.dto.SecurityAuditEventDetails;
import com.streamarr.server.graphql.inputs.DeleteEmptyHouseholdInput;
import com.streamarr.server.graphql.inputs.DeleteLastAccountAndHouseholdInput;
import com.streamarr.server.graphql.inputs.DeleteLastAccountAndHouseholdPreservingPersonalProfileInput;
import com.streamarr.server.graphql.inputs.TransferLastAccountAndDeleteHouseholdInput;
import com.streamarr.server.graphql.mutation.MutationPayloads;
import com.streamarr.server.graphql.mutation.household.deletion.DeleteEmptyHouseholdPayload;
import com.streamarr.server.graphql.mutation.household.deletion.DeleteLastAccountAndHouseholdPayload;
import com.streamarr.server.graphql.mutation.household.deletion.DeleteLastAccountAndHouseholdPreservingPersonalProfilePayload;
import com.streamarr.server.graphql.mutation.household.deletion.HouseholdDeletionErrors;
import com.streamarr.server.graphql.mutation.household.deletion.TransferLastAccountAndDeleteHouseholdPayload;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.identity.HouseholdDeletionService;
import com.streamarr.server.services.identity.HouseholdDeletionService.DeleteEmptyHouseholdCommand;
import com.streamarr.server.services.identity.HouseholdDeletionService.DeleteLastAccountAndHouseholdCommand;
import com.streamarr.server.services.identity.HouseholdDeletionService.DeleteLastAccountAndHouseholdPreservingPersonalProfileCommand;
import com.streamarr.server.services.identity.HouseholdDeletionService.HouseholdDeletionPreflightDetails;
import com.streamarr.server.services.identity.HouseholdDeletionService.TransferLastAccountAndDeleteHouseholdCommand;
import com.streamarr.server.services.pagination.ListIdentity;
import com.streamarr.server.services.pagination.PaginationService;
import com.streamarr.server.services.pagination.ServerScope;
import graphql.relay.Connection;
import graphql.schema.DataFetchingEnvironment;
import java.util.UUID;
import lombok.RequiredArgsConstructor;

@DgsComponent
@RequiredArgsConstructor
public class HouseholdDeletionResolver {

  private static final int DEFAULT_PAGE_SIZE = 100;
  private static final ListIdentity SECURITY_AUDIT =
      new ListIdentity("security-audit-events", ServerScope.SERVER);
  private static final String HOUSEHOLD_ID = "householdId";

  private final AuthorizationService authorizationService;
  private final HouseholdDeletionService householdDeletionService;
  private final PaginationService paginationService;
  private final PositionCursorCodec positionCursors;
  private final RelayConnectionAdapter relayConnectionAdapter;

  @DgsMutation
  public DeleteEmptyHouseholdPayload deleteEmptyHousehold(
      @InputArgument DeleteEmptyHouseholdInput input) {
    return MutationPayloads.withUuid(
        input.householdId(),
        householdId ->
            MutationPayloads.payload(
                householdDeletionService
                    .deleteEmptyHousehold(
                        authorizationService.currentIdentity(),
                        DeleteEmptyHouseholdCommand.builder()
                            .householdId(householdId)
                            .reason(input.reason())
                            .build())
                    .map(UUID::toString),
                HouseholdDeletionErrors::toDeleteEmptyHouseholdError,
                DeleteEmptyHouseholdPayload::new),
        () -> invalidDeleteEmptyHousehold(HOUSEHOLD_ID));
  }

  @DgsMutation
  public TransferLastAccountAndDeleteHouseholdPayload transferLastAccountAndDeleteHousehold(
      @InputArgument TransferLastAccountAndDeleteHouseholdInput input) {
    return MutationPayloads.withUuid(
        input.householdId(),
        householdId -> transferLastAccountAndDeleteHousehold(input, householdId),
        () -> invalidTransferLastAccountAndDeleteHousehold(HOUSEHOLD_ID));
  }

  private TransferLastAccountAndDeleteHouseholdPayload transferLastAccountAndDeleteHousehold(
      TransferLastAccountAndDeleteHouseholdInput input, UUID householdId) {
    return MutationPayloads.withUuid(
        input.destinationHouseholdId(),
        destinationHouseholdId ->
            MutationPayloads.payload(
                householdDeletionService
                    .transferLastAccountAndDeleteHousehold(
                        authorizationService.currentIdentity(),
                        TransferLastAccountAndDeleteHouseholdCommand.builder()
                            .householdId(householdId)
                            .destinationHouseholdId(destinationHouseholdId)
                            .reason(input.reason())
                            .build())
                    .map(UUID::toString),
                HouseholdDeletionErrors::toTransferLastAccountAndDeleteHouseholdError,
                TransferLastAccountAndDeleteHouseholdPayload::new),
        () -> invalidTransferLastAccountAndDeleteHousehold("destinationHouseholdId"));
  }

  @DgsMutation
  public DeleteLastAccountAndHouseholdPayload deleteLastAccountAndHousehold(
      @InputArgument DeleteLastAccountAndHouseholdInput input) {
    return MutationPayloads.withUuid(
        input.householdId(),
        householdId ->
            MutationPayloads.payload(
                householdDeletionService
                    .deleteLastAccountAndHousehold(
                        authorizationService.currentIdentity(),
                        DeleteLastAccountAndHouseholdCommand.builder()
                            .householdId(householdId)
                            .reason(input.reason())
                            .build())
                    .map(UUID::toString),
                HouseholdDeletionErrors::toDeleteLastAccountAndHouseholdError,
                DeleteLastAccountAndHouseholdPayload::new),
        () -> invalidDeleteLastAccountAndHousehold(HOUSEHOLD_ID));
  }

  @DgsMutation
  public DeleteLastAccountAndHouseholdPreservingPersonalProfilePayload
      deleteLastAccountAndHouseholdPreservingPersonalProfile(
          @InputArgument DeleteLastAccountAndHouseholdPreservingPersonalProfileInput input) {
    return MutationPayloads.withUuid(
        input.householdId(),
        householdId -> deleteLastAccountAndHouseholdPreservingPersonalProfile(input, householdId),
        () -> invalidDeleteLastAccountAndHouseholdPreservingPersonalProfile(HOUSEHOLD_ID));
  }

  private DeleteLastAccountAndHouseholdPreservingPersonalProfilePayload
      deleteLastAccountAndHouseholdPreservingPersonalProfile(
          DeleteLastAccountAndHouseholdPreservingPersonalProfileInput input, UUID householdId) {
    return MutationPayloads.withUuid(
        input.destinationHouseholdId(),
        destinationHouseholdId ->
            deleteLastAccountAndHouseholdPreservingPersonalProfile(
                input, householdId, destinationHouseholdId),
        () ->
            invalidDeleteLastAccountAndHouseholdPreservingPersonalProfile(
                "destinationHouseholdId"));
  }

  private DeleteLastAccountAndHouseholdPreservingPersonalProfilePayload
      deleteLastAccountAndHouseholdPreservingPersonalProfile(
          DeleteLastAccountAndHouseholdPreservingPersonalProfileInput input,
          UUID householdId,
          UUID destinationHouseholdId) {
    return MutationPayloads.withUuid(
        input.replacementManagerAccountId(),
        replacementManagerAccountId ->
            MutationPayloads.payload(
                householdDeletionService
                    .deleteLastAccountAndHouseholdPreservingPersonalProfile(
                        authorizationService.currentIdentity(),
                        DeleteLastAccountAndHouseholdPreservingPersonalProfileCommand.builder()
                            .householdId(householdId)
                            .destinationHouseholdId(destinationHouseholdId)
                            .replacementManagerAccountId(replacementManagerAccountId)
                            .reason(input.reason())
                            .build())
                    .map(UUID::toString),
                HouseholdDeletionErrors
                    ::toDeleteLastAccountAndHouseholdPreservingPersonalProfileError,
                DeleteLastAccountAndHouseholdPreservingPersonalProfilePayload::new),
        () ->
            invalidDeleteLastAccountAndHouseholdPreservingPersonalProfile(
                "replacementManagerAccountId"));
  }

  @DgsQuery
  public HouseholdDeletionPreview householdDeletionPreview(@InputArgument String householdId) {
    return householdDeletionService
        .deletionPreflight(authorizationService.currentIdentity(), Ids.parseUuid(householdId))
        .map(HouseholdDeletionResolver::toDto)
        .orElse(null);
  }

  @DgsQuery
  public Connection<SecurityAuditEventDetails> securityAuditEvents(DataFetchingEnvironment dfe) {
    var request =
        positionCursors.pageRequest(
            SECURITY_AUDIT,
            ConnectionArguments.paginationOptions(paginationService, dfe, DEFAULT_PAGE_SIZE));
    var page =
        householdDeletionService.securityAuditEvents(
            authorizationService.currentIdentity(), request);
    return relayConnectionAdapter.toConnection(
        page, SECURITY_AUDIT, SecurityAuditEventDetails::from);
  }

  @DgsQuery
  public Connection<ProfileActivityDetails> profileActivity(
      @InputArgument String profileId, DataFetchingEnvironment dfe) {
    var profile = Ids.parseUuid(profileId);
    var list = new ListIdentity("profile-activity", profile);
    var request =
        positionCursors.pageRequest(
            list, ConnectionArguments.paginationOptions(paginationService, dfe, DEFAULT_PAGE_SIZE));
    var page =
        householdDeletionService.profileActivity(
            authorizationService.currentIdentity(), profile, request);
    return relayConnectionAdapter.toConnection(page, list, ProfileActivityDetails::from);
  }

  private static HouseholdDeletionPreview toDto(HouseholdDeletionPreflightDetails preview) {
    var profiles =
        preview.unlinkedProfiles().stream()
            .map(profile -> new ProfileDeletionPreview(profile.id(), profile.name()))
            .toList();
    return new HouseholdDeletionPreview(
        preview.accountCount(), profiles, preview.hostedVisitCount());
  }

  private static DeleteEmptyHouseholdPayload invalidDeleteEmptyHousehold(String inputName) {
    return MutationPayloads.inputError(
        HouseholdDeletionErrors.invalidId(inputName), DeleteEmptyHouseholdPayload::new);
  }

  private static TransferLastAccountAndDeleteHouseholdPayload
      invalidTransferLastAccountAndDeleteHousehold(String inputName) {
    return MutationPayloads.inputError(
        HouseholdDeletionErrors.invalidId(inputName),
        TransferLastAccountAndDeleteHouseholdPayload::new);
  }

  private static DeleteLastAccountAndHouseholdPayload invalidDeleteLastAccountAndHousehold(
      String inputName) {
    return MutationPayloads.inputError(
        HouseholdDeletionErrors.invalidId(inputName), DeleteLastAccountAndHouseholdPayload::new);
  }

  private static DeleteLastAccountAndHouseholdPreservingPersonalProfilePayload
      invalidDeleteLastAccountAndHouseholdPreservingPersonalProfile(String inputName) {
    return MutationPayloads.inputError(
        HouseholdDeletionErrors.invalidId(inputName),
        DeleteLastAccountAndHouseholdPreservingPersonalProfilePayload::new);
  }
}
