package com.streamarr.server.graphql.resolvers;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsMutation;
import com.netflix.graphql.dgs.DgsQuery;
import com.netflix.graphql.dgs.InputArgument;
import com.streamarr.server.graphql.Ids;
import com.streamarr.server.graphql.cursor.ListConnections;
import com.streamarr.server.graphql.dto.DeviceRegistrationView;
import com.streamarr.server.graphql.dto.EsnBlockView;
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
import com.streamarr.server.services.pagination.PaginationOptions;
import com.streamarr.server.services.pagination.PaginationService;
import graphql.relay.Connection;
import graphql.schema.DataFetchingEnvironment;
import java.util.UUID;
import lombok.RequiredArgsConstructor;

@DgsComponent
@RequiredArgsConstructor
public class DeviceAdministrationResolver {

  private final AuthorizationService authorizationService;
  private final DeviceAdministrationService deviceAdministrationService;
  private final PaginationService paginationService;

  @DgsQuery
  public Connection<DeviceRegistrationView> householdDevices(
      @InputArgument String householdId, DataFetchingEnvironment dfe) {
    var devices =
        deviceAdministrationService
            .householdDevices(authorizationService.currentIdentity(), Ids.parseUuid(householdId))
            .stream()
            .map(DeviceRegistrationView::from)
            .toList();
    return ListConnections.page(devices, device -> device.id().toString(), options(dfe));
  }

  @DgsQuery
  public Connection<EsnBlockView> esnBlocks(
      @InputArgument String householdId, DataFetchingEnvironment dfe) {
    var blocks =
        deviceAdministrationService
            .esnBlocks(authorizationService.currentIdentity(), Ids.parseUuid(householdId))
            .stream()
            .map(EsnBlockView::from)
            .toList();
    return ListConnections.page(blocks, block -> block.id().toString(), options(dfe));
  }

  @DgsQuery
  public Connection<EsnBlockView> serverEsnBlocks(DataFetchingEnvironment dfe) {
    var blocks =
        deviceAdministrationService.serverEsnBlocks(authorizationService.currentIdentity()).stream()
            .map(EsnBlockView::from)
            .toList();
    return ListConnections.page(blocks, block -> block.id().toString(), options(dfe));
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
            .map(EsnBlockView::from),
        DeviceErrors::toBlockError,
        BlockEsnPayload::new);
  }

  @DgsMutation
  public BlockEsnServerWidePayload blockEsnServerWide(
      @InputArgument BlockEsnServerWideInput input) {
    return MutationPayloads.payload(
        deviceAdministrationService
            .blockEsnServerWide(authorizationService.currentIdentity(), input.esn(), input.reason())
            .map(EsnBlockView::from),
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

  private PaginationOptions options(DataFetchingEnvironment dfe) {
    int first = dfe.getArgumentOrDefault("first", 0);
    String after = dfe.getArgument("after");
    int last = dfe.getArgumentOrDefault("last", 0);
    String before = dfe.getArgument("before");
    if (first != 0 || last != 0) {
      return paginationService.getPaginationOptions(first, after, last, before);
    }
    if (before == null || before.isBlank()) {
      return paginationService.getPaginationOptions(100, after, 0, before);
    }
    return paginationService.getPaginationOptions(0, after, 100, before);
  }
}
