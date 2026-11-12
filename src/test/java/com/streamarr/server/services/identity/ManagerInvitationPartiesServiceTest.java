package com.streamarr.server.services.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.fakes.FakeProfileRepository;
import com.streamarr.server.fakes.FakeUserAccountRepository;
import com.streamarr.server.fixtures.AccountFixture;
import com.streamarr.server.fixtures.ProfileFixture;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Manager Invitation Parties Service Tests")
class ManagerInvitationPartiesServiceTest {

  private final FakeProfileRepository profiles = new FakeProfileRepository();
  private final FakeUserAccountRepository accounts = new FakeUserAccountRepository(profiles);
  private final ManagerInvitationPartiesService service =
      new ManagerInvitationPartiesService(profiles, accounts);

  @Test
  @DisplayName("Should show the parties' current names when the Profile and inviter exist")
  void shouldShowThePartiesCurrentNamesWhenTheProfileAndInviterExist() {
    var alex = profiles.save(ProfileFixture.defaultProfileBuilder().name("Alex Smith").build());
    var sam =
        accounts.save(AccountFixture.defaultAccountBuilder().displayName("Sam Smith").build());
    var parties =
        new ManagerInvitationParties(
            Optional.of(alex.getId()), "Alex", Optional.of(sam.getId()), "Sam");

    assertThat(service.currentNames(Set.of(parties)))
        .containsExactlyEntriesOf(
            Map.of(parties, new ManagerInvitationNames("Alex Smith", "Sam Smith")));
  }

  @Test
  @DisplayName("Should show the stored names when the Profile and inviter are deleted")
  void shouldShowTheStoredNamesWhenTheProfileAndInviterAreDeleted() {
    var cleared = new ManagerInvitationParties(Optional.empty(), "Alex", Optional.empty(), "Sam");
    var vanished =
        new ManagerInvitationParties(
            Optional.of(UUID.randomUUID()), "Cabin Kid", Optional.of(UUID.randomUUID()), "Mara");

    assertThat(service.currentNames(Set.of(cleared, vanished)))
        .containsEntry(cleared, new ManagerInvitationNames("Alex", "Sam"))
        .containsEntry(vanished, new ManagerInvitationNames("Cabin Kid", "Mara"));
  }
}
