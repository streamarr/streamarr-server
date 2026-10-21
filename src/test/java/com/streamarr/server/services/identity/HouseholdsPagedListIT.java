package com.streamarr.server.services.identity;

import com.streamarr.server.domain.auth.Household;
import com.streamarr.server.repositories.auth.HouseholdRepository;
import com.streamarr.server.services.pagination.ListPage;
import com.streamarr.server.services.pagination.PageRequest;
import com.streamarr.server.services.pagination.Position;
import com.streamarr.server.services.pagination.SortDirection;
import com.streamarr.server.services.pagination.SortValue;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.PagedListContract;
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

@Tag("IntegrationTest")
@DisplayName("Household administration paging")
class HouseholdsPagedListIT extends PagedListContract<Household, HouseholdsPagedListIT.Scope> {

  private static final UUID LOWEST_ID = new UUID(0, 0);

  @Autowired private AdministrationQueryService administration;
  @Autowired private HouseholdRepository households;
  @Autowired private AuthTestSupport auth;

  /** A ServerAdmin, and a name prefix only this test's Households share. */
  record Scope(
      AuthTestSupport.TestIdentity admin, String namePrefix, List<UUID> arrangedHouseholds) {}

  @Override
  protected Scope createScope() {
    var prefix =
        IntStream.range(0, 12)
            .mapToObj(_ -> String.valueOf((char) ('a' + ThreadLocalRandom.current().nextInt(26))))
            .collect(Collectors.joining());
    return new Scope(auth.createAdminIdentity(), prefix, new ArrayList<>());
  }

  @Override
  protected void deleteScope(Scope scope) {
    households.deleteAllById(scope.arrangedHouseholds());
    auth.deleteIdentity(scope.admin());
  }

  @Override
  protected UUID add(Scope scope, int rank) {
    var household = households.saveAndFlush(Household.builder().name(nameOf(scope, rank)).build());
    scope.arrangedHouseholds().add(household.getId());
    return household.getId();
  }

  @Override
  protected void remove(Scope scope, UUID item) {
    households.deleteById(item);
    households.flush();
    scope.arrangedHouseholds().remove(item);
  }

  @Override
  protected ListPage<Household> page(Scope scope, PageRequest request) {
    return administration.households(auth.identityOf(scope.admin()), request);
  }

  @Override
  protected UUID idOf(Household household) {
    return household.getId();
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

  // Mixed case proves the list compares names without case.
  private static String nameOf(Scope scope, int rank) {
    return scope.namePrefix().toUpperCase() + rank;
  }
}
