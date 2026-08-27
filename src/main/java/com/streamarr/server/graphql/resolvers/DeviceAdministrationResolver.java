package com.streamarr.server.graphql.resolvers;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsMutation;
import com.netflix.graphql.dgs.DgsQuery;
import com.netflix.graphql.dgs.InputArgument;
import com.streamarr.server.domain.auth.DeviceRegistration;
import com.streamarr.server.domain.auth.EsnBlock;
import com.streamarr.server.graphql.Ids;
import com.streamarr.server.graphql.cursor.ConnectionArguments;
import com.streamarr.server.graphql.cursor.CursorUtil;
import com.streamarr.server.graphql.cursor.RelayConnectionAdapter;
import com.streamarr.server.graphql.dto.DeviceRegistrationDetails;
import com.streamarr.server.graphql.dto.EsnBlockDetails;
import com.streamarr.server.graphql.inputs.BlockEsnInput;
import com.streamarr.server.graphql.inputs.BlockEsnServerWideInput;
import com.streamarr.server.graphql.inputs.RevokeDeviceRegistrationInput;
import com.streamarr.server.graphql.inputs.UnblockEsnInput;
import com.streamarr.server.graphql.inputs.UnblockEsnServerWideInput;
import com.streamarr.server.graphql.mutation.MutationPayloads;
import com.streamarr.server.graphql.mutation.devices.BlockEsnPayload;
import com.streamarr.server.graphql.mutation.devices.BlockEsnServerWidePayload;
import com.streamarr.server.graphql.mutation.devices.DeviceErrors;
import com.streamarr.server.graphql.mutation.devices.RevokeDeviceRegistrationPayload;
import com.streamarr.server.graphql.mutation.devices.UnblockEsnPayload;
import com.streamarr.server.graphql.mutation.devices.UnblockEsnServerWidePayload;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.identity.DeviceAdministrationService;
import com.streamarr.server.services.pagination.MediaPage;
import com.streamarr.server.services.pagination.PaginationService;
import graphql.relay.Connection;
import graphql.schema.DataFetchingEnvironment;
import java.util.UUID;
import lombok.RequiredArgsConstructor;

@DgsComponent
@RequiredArgsConstructor
public class DeviceAdministrationResolver {

  private static final int DEFAULT_PAGE_SIZE = 100;

  private final AuthorizationService authorizationService;
  private final DeviceAdministrationService deviceAdministrationService;
  private final PaginationService paginationService;
  private final CursorUtil cursorUtil;
  private final RelayConnectionAdapter relayConnectionAdapter;

  @DgsQuery
  public Connection<DeviceRegistrationDetails> householdDevices(
      @InputArgument String householdId, DataFetchingEnvironment dfe) {
    var options = ConnectionArguments.paginationOptions(paginationService, dfe, DEFAULT_PAGE_SIZE);
    var page =
        deviceAdministrationService.householdDevices(
            authorizationService.currentIdentity(),
            Ids.parseUuid(householdId),
            cursorUtil.decodeKeysetCursor(options));
    return toDeviceConnection(page);
  }

  @DgsQuery
  public Connection<EsnBlockDetails> esnBlocks(
      @InputArgument String householdId, DataFetchingEnvironment dfe) {
    var options = ConnectionArguments.paginationOptions(paginationService, dfe, DEFAULT_PAGE_SIZE);
    var page =
        deviceAdministrationService.esnBlocks(
            authorizationService.currentIdentity(),
            Ids.parseUuid(householdId),
            cursorUtil.decodeKeysetCursor(options));
    return toEsnBlockConnection(page);
  }

  @DgsQuery
  public Connection<EsnBlockDetails> serverEsnBlocks(DataFetchingEnvironment dfe) {
    var options = ConnectionArguments.paginationOptions(paginationService, dfe, DEFAULT_PAGE_SIZE);
    var page =
        deviceAdministrationService.serverEsnBlocks(
            authorizationService.currentIdentity(), cursorUtil.decodeKeysetCursor(options));
    return toEsnBlockConnection(page);
  }

  @DgsMutation
  public RevokeDeviceRegistrationPayload revokeDeviceRegistration(
      @InputArgument RevokeDeviceRegistrationInput input) {
    return MutationPayloads.payload(
        deviceAdministrationService
            .revokeDeviceRegistration(
                authorizationService.currentIdentity(), Ids.parseUuid(input.registrationId()))
            .map(UUID::toString),
        DeviceErrors::toRevokeError,
        RevokeDeviceRegistrationPayload::new);
  }

  @DgsMutation
  public BlockEsnPayload blockEsn(@InputArgument BlockEsnInput input) {
    return MutationPayloads.payload(
        deviceAdministrationService
            .blockEsn(
                authorizationService.currentIdentity(),
                Ids.parseUuid(input.householdId()),
                input.esn(),
                input.reason())
            .map(EsnBlockDetails::from),
        DeviceErrors::toBlockError,
        BlockEsnPayload::new);
  }

  @DgsMutation
  public BlockEsnServerWidePayload blockEsnServerWide(
      @InputArgument BlockEsnServerWideInput input) {
    return MutationPayloads.payload(
        deviceAdministrationService
            .blockEsnServerWide(authorizationService.currentIdentity(), input.esn(), input.reason())
            .map(EsnBlockDetails::from),
        DeviceErrors::toBlockServerWideError,
        BlockEsnServerWidePayload::new);
  }

  @DgsMutation
  public UnblockEsnPayload unblockEsn(@InputArgument UnblockEsnInput input) {
    return MutationPayloads.payload(
        deviceAdministrationService.unblockEsn(
            authorizationService.currentIdentity(),
            Ids.parseUuid(input.householdId()),
            input.esn()),
        DeviceErrors::toUnblockError,
        UnblockEsnPayload::new);
  }

  @DgsMutation
  public UnblockEsnServerWidePayload unblockEsnServerWide(
      @InputArgument UnblockEsnServerWideInput input) {
    return MutationPayloads.payload(
        deviceAdministrationService.unblockEsnServerWide(
            authorizationService.currentIdentity(), input.esn()),
        DeviceErrors::toUnblockServerWideError,
        UnblockEsnServerWidePayload::new);
  }

  private Connection<DeviceRegistrationDetails> toDeviceConnection(
      MediaPage<DeviceRegistration> page) {
    return relayConnectionAdapter.toConnection(
        page,
        item -> DeviceRegistrationDetails.from(item.item()),
        item -> cursorUtil.encodeKeysetCursor(item.item().getId()));
  }

  private Connection<EsnBlockDetails> toEsnBlockConnection(MediaPage<EsnBlock> page) {
    return relayConnectionAdapter.toConnection(
        page,
        item -> EsnBlockDetails.from(item.item()),
        item -> cursorUtil.encodeKeysetCursor(item.item().getId()));
  }
}
