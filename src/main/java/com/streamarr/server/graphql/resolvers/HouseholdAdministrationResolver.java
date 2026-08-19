package com.streamarr.server.graphql.resolvers;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsMutation;
import com.netflix.graphql.dgs.InputArgument;
import com.streamarr.server.graphql.Ids;
import com.streamarr.server.graphql.dto.HouseholdAdministration;
import com.streamarr.server.graphql.inputs.CreateHouseholdInput;
import com.streamarr.server.graphql.inputs.RenameHouseholdInput;
import com.streamarr.server.graphql.mutation.MutationPayloads;
import com.streamarr.server.graphql.mutation.administration.AdministrationErrors;
import com.streamarr.server.graphql.mutation.administration.CreateHouseholdPayload;
import com.streamarr.server.graphql.mutation.administration.RenameHouseholdPayload;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.identity.HouseholdAdministrationService;
import lombok.RequiredArgsConstructor;

@DgsComponent
@RequiredArgsConstructor
public class HouseholdAdministrationResolver {

  private final AuthorizationService authorizationService;
  private final HouseholdAdministrationService householdAdministrationService;

  @DgsMutation
  public CreateHouseholdPayload createHousehold(@InputArgument CreateHouseholdInput input) {
    return MutationPayloads.payload(
        householdAdministrationService
            .createHousehold(authorizationService.currentIdentity(), input.name())
            .map(HouseholdAdministration::from),
        AdministrationErrors::toCreateHouseholdError,
        CreateHouseholdPayload::new);
  }

  @DgsMutation
  public RenameHouseholdPayload renameHousehold(@InputArgument RenameHouseholdInput input) {
    return MutationPayloads.payload(
        householdAdministrationService
            .renameHousehold(
                authorizationService.currentIdentity(),
                Ids.parseUuid(input.householdId()),
                input.name())
            .map(HouseholdAdministration::from),
        AdministrationErrors::toRenameHouseholdError,
        RenameHouseholdPayload::new);
  }
}
