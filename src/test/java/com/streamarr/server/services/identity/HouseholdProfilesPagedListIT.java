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
@DisplayName("Household Profile paging")
class HouseholdProfilesPagedListIT
    extends PagedListContract<HouseholdProfileDetails, HouseholdProfilesPagedListIT.Scope> {

  private static final UUID LOWEST_ID = new UUID(0, 0);

  @Autowired private ProfileManagementQueryService profileManagementQuery;
  @Autowired private ProfileCastSupport cast;
  @Autowired private ProfileRepository profiles;
  @Autowired private TransactionTemplate transactions;
  @Autowired private AuthTestSupport auth;

  /**
   * A HouseholdAdmin reading its own Household, whose Personal Profile also lists there, and a name
   * prefix only this test's Profiles share.
   */
  record Scope(AuthTestSupport.TestIdentity admin, String namePrefix) {}

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
    auth.deleteIdentity(scope.admin());
  }

  // Mixed case proves the list compares names without case. Names of the Profiles available in a
  // Household never tie.
  @Override
  protected UUID add(Scope scope, int rank) {
    return cast.profileManagedBy(
            scope.admin(),
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
  protected ListPage<HouseholdProfileDetails> page(Scope scope, PageRequest request) {
    return profileManagementQuery.householdProfiles(
        auth.identityOf(scope.admin()), scope.admin().household().getId(), request);
  }

  @Override
  protected UUID idOf(HouseholdProfileDetails details) {
    return details.administration().profile().getId();
  }

  @Override
  protected SortDirection tieDirection() {
    return SortDirection.ASC;
  }

  @Override
  protected boolean sortValuesCanTie() {
    return false;
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
