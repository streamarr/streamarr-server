package com.streamarr.server.graphql.resolvers;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsData;
import com.netflix.graphql.dgs.DgsMutation;
import com.netflix.graphql.dgs.DgsQuery;
import com.netflix.graphql.dgs.InputArgument;
import com.streamarr.server.graphql.Ids;
import com.streamarr.server.graphql.cursor.ListConnections;
import com.streamarr.server.graphql.dto.AccountAdministration;
import com.streamarr.server.graphql.dto.HouseholdAdministration;
import com.streamarr.server.graphql.inputs.CreateHouseholdInput;
import com.streamarr.server.graphql.inputs.RenameHouseholdInput;
import com.streamarr.server.graphql.mutation.MutationPayloads;
import com.streamarr.server.graphql.mutation.administration.AdministrationErrors;
import com.streamarr.server.graphql.mutation.administration.CreateHouseholdPayload;
import com.streamarr.server.graphql.mutation.administration.RenameHouseholdPayload;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.identity.AdministrationQueryService;
import com.streamarr.server.services.identity.HouseholdAdministrationService;
import com.streamarr.server.services.pagination.PaginationOptions;
import com.streamarr.server.services.pagination.PaginationService;
import graphql.relay.Connection;
import graphql.schema.DataFetchingEnvironment;
import lombok.RequiredArgsConstructor;

@DgsComponent
@RequiredArgsConstructor
public class HouseholdAdministrationResolver {

  private final AuthorizationService authorizationService;
  private final HouseholdAdministrationService householdAdministrationService;
  private final AdministrationQueryService administrationQueryService;
  private final PaginationService paginationService;

  @DgsQuery
  public HouseholdAdministration householdAdministration(@InputArgument String householdId) {
    return administrationQueryService
        .householdAdministration(authorizationService.currentIdentity(), Ids.parseUuid(householdId))
        .map(HouseholdAdministration::from)
        .orElse(null);
  }

  @DgsQuery
  public Connection<HouseholdAdministration> households(DataFetchingEnvironment dfe) {
    var all =
        administrationQueryService.households(authorizationService.currentIdentity()).stream()
            .map(HouseholdAdministration::from)
            .toList();
    return ListConnections.page(all, household -> household.id().toString(), options(dfe));
  }

  @DgsData(parentType = "HouseholdAdministration", field = "accounts")
  public Connection<AccountAdministration> accounts(DataFetchingEnvironment dfe) {
    HouseholdAdministration household = dfe.getSource();
    var accounts =
        administrationQueryService.accountsOf(household.id()).stream()
            .map(AccountAdministration::from)
            .toList();
    return ListConnections.page(accounts, account -> account.id().toString(), options(dfe));
  }

  private PaginationOptions options(DataFetchingEnvironment dfe) {
    int first = dfe.getArgumentOrDefault("first", 0);
    String after = dfe.getArgument("after");
    int last = dfe.getArgumentOrDefault("last", 0);
    String before = dfe.getArgument("before");
    return paginationService.getPaginationOptions(
        first == 0 && last == 0 && before == null ? 100 : first, after, last, before);
  }

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
