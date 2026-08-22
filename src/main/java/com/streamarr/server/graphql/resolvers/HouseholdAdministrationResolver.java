package com.streamarr.server.graphql.resolvers;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsData;
import com.netflix.graphql.dgs.DgsMutation;
import com.netflix.graphql.dgs.DgsQuery;
import com.netflix.graphql.dgs.InputArgument;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.graphql.Ids;
import com.streamarr.server.graphql.cursor.CursorUtil;
import com.streamarr.server.graphql.cursor.CursorValidator;
import com.streamarr.server.graphql.cursor.RelayConnectionAdapter;
import com.streamarr.server.graphql.dataloaders.AdministrationAccountsLoaderKey;
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
import com.streamarr.server.services.pagination.MediaFilter;
import com.streamarr.server.services.pagination.MediaPage;
import com.streamarr.server.services.pagination.MediaPaginationOptions;
import com.streamarr.server.services.pagination.MediaPaginationOptionsResolver;
import com.streamarr.server.services.pagination.PaginationOptions;
import com.streamarr.server.services.pagination.PaginationService;
import graphql.relay.Connection;
import graphql.relay.DefaultConnection;
import graphql.relay.DefaultEdge;
import graphql.relay.Edge;
import graphql.schema.DataFetchingEnvironment;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.dataloader.DataLoader;

@DgsComponent
@RequiredArgsConstructor
public class HouseholdAdministrationResolver {

  private static final int DEFAULT_PAGE_SIZE = 100;

  private final AuthorizationService authorizationService;
  private final HouseholdAdministrationService householdAdministrationService;
  private final AdministrationQueryService administrationQueryService;
  private final PaginationService paginationService;
  private final CursorUtil cursorUtil;
  private final CursorValidator cursorValidator;
  private final RelayConnectionAdapter relayConnectionAdapter;

  @DgsQuery
  public HouseholdAdministration householdAdministration(@InputArgument String householdId) {
    return administrationQueryService
        .householdAdministration(authorizationService.currentIdentity(), Ids.parseUuid(householdId))
        .map(HouseholdAdministration::from)
        .orElse(null);
  }

  @DgsQuery
  public Connection<HouseholdAdministration> households(DataFetchingEnvironment dfe) {
    var options = mediaOptions(dfe);
    var page =
        administrationQueryService.households(authorizationService.currentIdentity(), options);
    return mapConnection(
        relayConnectionAdapter.toConnection(page, options), HouseholdAdministration::from);
  }

  @DgsData(parentType = "HouseholdAdministration", field = "accounts")
  public CompletableFuture<Connection<AccountAdministration>> accounts(
      DataFetchingEnvironment dfe) {
    HouseholdAdministration household = dfe.getSource();
    var options = mediaOptions(dfe);
    DataLoader<AdministrationAccountsLoaderKey, MediaPage<UserAccount>> loader =
        dfe.getDataLoader("administrationAccounts");
    return loader
        .load(new AdministrationAccountsLoaderKey(household.id(), options))
        .thenApply(
            page ->
                mapConnection(
                    relayConnectionAdapter.toConnection(page, options),
                    AccountAdministration::from));
  }

  private MediaPaginationOptions mediaOptions(DataFetchingEnvironment dfe) {
    var paginationOptions = paginationOptions(dfe);
    var filter = MediaFilter.builder().build();
    return MediaPaginationOptionsResolver.resolve(
        paginationOptions,
        filter,
        cursorUtil::decodeMediaCursor,
        cursorValidator::validateCursorAgainstFilter);
  }

  private PaginationOptions paginationOptions(DataFetchingEnvironment dfe) {
    int first = dfe.getArgumentOrDefault("first", 0);
    String after = dfe.getArgument("after");
    int last = dfe.getArgumentOrDefault("last", 0);
    String before = dfe.getArgument("before");
    if (first == 0 && last == 0 && before != null) {
      return paginationService.getPaginationOptions(first, after, DEFAULT_PAGE_SIZE, before);
    }
    return paginationService.getPaginationOptions(
        first == 0 && last == 0 ? DEFAULT_PAGE_SIZE : first, after, last, before);
  }

  private static <S, T> Connection<T> mapConnection(
      Connection<S> connection, Function<S, T> mapper) {
    var edges =
        connection.getEdges().stream()
            .<Edge<T>>map(edge -> new DefaultEdge<>(mapper.apply(edge.getNode()), edge.getCursor()))
            .toList();
    return new DefaultConnection<>(edges, connection.getPageInfo());
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
    return MutationPayloads.withUuid(
        input.householdId(),
        householdId ->
            MutationPayloads.payload(
                householdAdministrationService
                    .renameHousehold(
                        authorizationService.currentIdentity(), householdId, input.name())
                    .map(HouseholdAdministration::from),
                AdministrationErrors::toRenameHouseholdError,
                RenameHouseholdPayload::new),
        () ->
            MutationPayloads.inputError(
                AdministrationErrors.invalidHouseholdId(), RenameHouseholdPayload::new));
  }
}
