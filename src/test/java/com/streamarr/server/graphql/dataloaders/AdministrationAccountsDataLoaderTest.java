package com.streamarr.server.graphql.dataloaders;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.fakes.FakeAccountInvitationRepository;
import com.streamarr.server.fakes.FakeHouseholdRepository;
import com.streamarr.server.fakes.FakeProfileRepository;
import com.streamarr.server.fakes.FakeUserAccountRepository;
import com.streamarr.server.fixtures.AccountFixture;
import com.streamarr.server.services.identity.AdministrationQueryService;
import com.streamarr.server.services.pagination.MediaFilter;
import com.streamarr.server.services.pagination.MediaPaginationOptions;
import com.streamarr.server.services.pagination.PaginationOptions;
import com.streamarr.server.services.pagination.PaginationService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Administration Accounts Data Loader Tests")
class AdministrationAccountsDataLoaderTest {

  @Test
  @DisplayName("Should batch Households into one request when pagination options match")
  void shouldBatchHouseholdsIntoOneRequestWhenPaginationOptionsMatch() throws Exception {
    var firstHouseholdId = UUID.randomUUID();
    var secondHouseholdId = UUID.randomUUID();
    var accounts = new RecordingUserAccountRepository();
    accounts.save(account(firstHouseholdId, "First Account"));
    accounts.save(account(secondHouseholdId, "Second Account"));
    var options =
        MediaPaginationOptions.builder()
            .paginationOptions(PaginationOptions.builder().limit(10).build())
            .mediaFilter(MediaFilter.builder().build())
            .build();
    var firstKey = new AdministrationAccountsLoaderKey(firstHouseholdId, options);
    var secondKey = new AdministrationAccountsLoaderKey(secondHouseholdId, options);
    var service =
        new AdministrationQueryService(
            null,
            new FakeHouseholdRepository(),
            accounts,
            new PaginationService(),
            new FakeProfileRepository(),
            new FakeAccountInvitationRepository());
    var loader = new AdministrationAccountsDataLoader(service);

    var result = loader.load(Set.of(firstKey, secondKey)).toCompletableFuture().get();

    assertThat(accounts.requestedHouseholds)
        .containsExactly(Set.of(firstHouseholdId, secondHouseholdId));
    assertThat(result).containsOnlyKeys(firstKey, secondKey);
    assertThat(result.get(firstKey).items())
        .singleElement()
        .extracting(item -> item.item().getDisplayName())
        .isEqualTo("First Account");
    assertThat(result.get(secondKey).items())
        .singleElement()
        .extracting(item -> item.item().getDisplayName())
        .isEqualTo("Second Account");
  }

  private static UserAccount account(UUID householdId, String displayName) {
    return AccountFixture.defaultAccountBuilder()
        .householdId(householdId)
        .displayName(displayName)
        .build();
  }

  private static final class RecordingUserAccountRepository extends FakeUserAccountRepository {

    private final List<Set<UUID>> requestedHouseholds = new ArrayList<>();

    @Override
    public Map<UUID, List<UserAccount>> findAdministrationPages(
        Set<UUID> householdIds, MediaPaginationOptions options) {
      requestedHouseholds.add(Set.copyOf(householdIds));
      return super.findAdministrationPages(householdIds, options);
    }
  }
}
