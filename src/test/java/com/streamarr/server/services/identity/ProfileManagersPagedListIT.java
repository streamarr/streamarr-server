package com.streamarr.server.services.identity;

import com.streamarr.server.domain.pagination.Position;
import com.streamarr.server.domain.pagination.SortDirection;
import com.streamarr.server.domain.pagination.SortValue;
import com.streamarr.server.repositories.auth.ProfileManagerRepository;
import com.streamarr.server.services.pagination.ListPage;
import com.streamarr.server.services.pagination.PageRequest;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.AuthTestSupport.TestIdentity;
import com.streamarr.server.support.PagedListContract;
import com.streamarr.server.support.ProfileCastSupport;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("IntegrationTest")
@DisplayName("Profile manager paging")
class ProfileManagersPagedListIT
    extends PagedListContract<ManagingAccountDetails, ProfileManagersPagedListIT.Scope> {

  private static final UUID LOWEST_ID = new UUID(0, 0);

  @Autowired private ProfileManagementQueryService profileManagementQuery;
  @Autowired private ProfileCastSupport cast;
  @Autowired private ProfileManagerRepository grants;
  @Autowired private TransactionTemplate transactions;
  @Autowired private AuthTestSupport auth;

  /**
   * The sovereign Account whose Personal Profile the managers manage, the Accounts this test
   * granted, and a display name prefix only those Accounts share.
   */
  record Scope(TestIdentity sovereign, List<TestIdentity> managers, String namePrefix) {}

  @Override
  protected Scope createScope() {
    var prefix =
        IntStream.range(0, 12)
            .mapToObj(_ -> String.valueOf((char) ('a' + ThreadLocalRandom.current().nextInt(26))))
            .collect(Collectors.joining());
    return new Scope(auth.createIdentity(), new ArrayList<>(), prefix);
  }

  @Override
  protected void deleteScope(Scope scope) {
    scope.managers().reversed().forEach(auth::deleteIdentity);
    auth.deleteIdentity(scope.sovereign());
  }

  // Mixed case proves the list compares names without case. Display names are not unique, so equal
  // ranks tie.
  @Override
  protected UUID add(Scope scope, int rank) {
    var manager = auth.createIdentity();
    scope.managers().add(manager);
    cast.rename(manager, scope.namePrefix().toUpperCase() + rank);
    cast.grant(manager, scope.sovereign().profile());
    return manager.account().getId();
  }

  @Override
  protected void remove(Scope scope, UUID item) {
    transactions.executeWithoutResult(
        _ ->
            grants.findByProfileId(scope.sovereign().profile().getId()).stream()
                .filter(grant -> grant.getAccountId().equals(item))
                .forEach(grants::delete));
  }

  @Override
  protected ListPage<ManagingAccountDetails> page(Scope scope, PageRequest request) {
    return profileManagementQuery.profileManagers(
        auth.identityOf(scope.sovereign()), scope.sovereign().profile().getId(), request);
  }

  @Override
  protected UUID idOf(ManagingAccountDetails details) {
    return details.account().getId();
  }

  @Override
  protected SortDirection tieDirection() {
    return SortDirection.ASC;
  }

  @Override
  protected boolean sortValuesCanTie() {
    return true;
  }

  // The sovereign Account sorts first with the empty sort value, before every arranged Account.
  @Override
  protected Optional<Position> positionBeforeArrangedItems(Scope scope) {
    return Optional.of(new Position(new SortValue.OfText(scope.namePrefix()), LOWEST_ID));
  }

  @Override
  protected Optional<Position> positionAfterArrangedItems(Scope scope) {
    return Optional.of(new Position(new SortValue.OfText(scope.namePrefix() + "z"), LOWEST_ID));
  }
}
