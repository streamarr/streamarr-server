package com.streamarr.server.services.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileKind;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.services.identity.IdentityQueryService.SelectableProfileDetails;
import com.streamarr.server.services.identity.ProfileAdministrationService.CreateProfileCommand;
import com.streamarr.server.services.mutation.Outcome;
import com.streamarr.server.services.pagination.ListPage;
import com.streamarr.server.services.pagination.PageRequest;
import com.streamarr.server.services.pagination.Position;
import com.streamarr.server.services.pagination.SortDirection;
import com.streamarr.server.services.pagination.SortValue;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.PagedListContract;
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
@DisplayName("Selectable Profile paging")
class SelectableProfilesPagedListIT
    extends PagedListContract<SelectableProfileDetails, SelectableProfilesPagedListIT.Scope> {

  private static final UUID LOWEST_ID = new UUID(0, 0);

  @Autowired private IdentityQueryService identityQuery;
  @Autowired private ProfileAdministrationService profileAdministration;
  @Autowired private ProfileRepository profiles;
  @Autowired private TransactionTemplate transactions;
  @Autowired private AuthTestSupport auth;

  /**
   * A HouseholdAdmin picking a Profile in its own Household, whose Personal Profile also lists
   * there, and a name prefix only this test's Profiles share.
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

  // Mixed case proves the list compares names without case. Names never tie within a Household.
  @Override
  protected UUID add(Scope scope, int rank) {
    var outcome =
        profileAdministration.createProfile(
            auth.identityOf(scope.admin()),
            CreateProfileCommand.builder()
                .householdId(scope.admin().household().getId())
                .name(scope.namePrefix().toUpperCase() + rank)
                .kind(ProfileKind.ADULT)
                .build());
    assertThat(outcome).isInstanceOf(Outcome.Accepted.class);
    return ((Outcome.Accepted<Profile, ?>) outcome).result().getId();
  }

  // The product refuses to delete a Profile available in a Household; deleting the row still
  // proves a page continues past it.
  @Override
  protected void remove(Scope scope, UUID item) {
    transactions.executeWithoutResult(
        _ -> {
          profiles.deleteById(item);
          profiles.flush();
        });
  }

  @Override
  protected ListPage<SelectableProfileDetails> page(Scope scope, PageRequest request) {
    return identityQuery.selectableProfiles(auth.identityOf(scope.admin()), request);
  }

  @Override
  protected UUID idOf(SelectableProfileDetails profile) {
    return profile.id();
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
