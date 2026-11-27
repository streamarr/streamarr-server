package com.streamarr.server.services.identity;

import com.streamarr.server.domain.pagination.Position;
import com.streamarr.server.domain.pagination.SortDirection;
import com.streamarr.server.domain.pagination.SortValue;
import com.streamarr.server.fixtures.ProfileFixture;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.services.pagination.ListPage;
import com.streamarr.server.services.pagination.PageRequest;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.PagedListContract;
import com.streamarr.server.support.ProfileCastSupport;
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
@DisplayName("Managed Profile paging")
class ManagedProfilesPagedListIT
    extends PagedListContract<ManagedProfileDetails, ManagedProfilesPagedListIT.Scope> {

  private static final UUID LOWEST_ID = new UUID(0, 0);

  @Autowired private ProfileManagementQueryService profileManagementQuery;
  @Autowired private ProfileCastSupport cast;
  @Autowired private ProfileRepository profiles;
  @Autowired private TransactionTemplate transactions;
  @Autowired private AuthTestSupport auth;

  /**
   * An Account that manages the Profiles, its own Personal Profile among them, and a name prefix
   * only this test's Profiles share.
   */
  record Scope(AuthTestSupport.TestIdentity manager, String namePrefix) {}

  @Override
  protected Scope createScope() {
    var prefix =
        IntStream.range(0, 12)
            .mapToObj(_ -> String.valueOf((char) ('a' + ThreadLocalRandom.current().nextInt(26))))
            .collect(Collectors.joining());
    return new Scope(auth.createIdentity(), prefix);
  }

  @Override
  protected void deleteScope(Scope scope) {
    auth.deleteIdentity(scope.manager());
  }

  // Mixed case proves the list compares names without case. Profiles available nowhere may share
  // a name, so equal ranks tie.
  @Override
  protected UUID add(Scope scope, int rank) {
    return cast.unavailableProfileManagedBy(
            scope.manager(),
            ProfileFixture.defaultProfileBuilder().name(scope.namePrefix().toUpperCase() + rank))
        .getId();
  }

  @Override
  protected void remove(Scope scope, UUID item) {
    transactions.executeWithoutResult(
        _ -> {
          profiles.deleteById(item);
          profiles.flush();
        });
  }

  @Override
  protected ListPage<ManagedProfileDetails> page(Scope scope, PageRequest request) {
    return profileManagementQuery.managedProfiles(auth.identityOf(scope.manager()), request);
  }

  @Override
  protected UUID idOf(ManagedProfileDetails details) {
    return details.administration().profile().getId();
  }

  @Override
  protected SortDirection tieDirection() {
    return SortDirection.ASC;
  }

  @Override
  protected boolean sortValuesCanTie() {
    return true;
  }

  // Names are compared lowercased, and letters sort after digits in every collation.
  @Override
  protected Optional<Position> positionBeforeArrangedItems(Scope scope) {
    return Optional.of(new Position(new SortValue.OfText(scope.namePrefix()), LOWEST_ID));
  }

  @Override
  protected Optional<Position> positionAfterArrangedItems(Scope scope) {
    return Optional.of(new Position(new SortValue.OfText(scope.namePrefix() + "z"), LOWEST_ID));
  }
}
