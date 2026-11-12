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
        ManagerInvitationParties.builder()
            .profileId(Optional.of(alex.getId()))
            .storedProfileName("Alex")
            .inviterAccountId(Optional.of(sam.getId()))
            .storedInviterDisplayName("Sam")
            .build();

    assertThat(service.currentNames(Set.of(parties)))
        .containsExactlyEntriesOf(
            Map.of(
                parties,
                ManagerInvitationNames.builder()
                    .profileName("Alex Smith")
                    .inviterDisplayName("Sam Smith")
                    .build()));
  }

  @Test
  @DisplayName("Should show the stored names when the Profile and inviter are deleted")
  void shouldShowTheStoredNamesWhenTheProfileAndInviterAreDeleted() {
    var cleared =
        ManagerInvitationParties.builder()
            .profileId(Optional.empty())
            .storedProfileName("Alex")
            .inviterAccountId(Optional.empty())
            .storedInviterDisplayName("Sam")
            .build();
    var vanished =
        ManagerInvitationParties.builder()
            .profileId(Optional.of(UUID.randomUUID()))
            .storedProfileName("Cabin Kid")
            .inviterAccountId(Optional.of(UUID.randomUUID()))
            .storedInviterDisplayName("Mara")
            .build();

    assertThat(service.currentNames(Set.of(cleared, vanished)))
        .containsEntry(
            cleared,
            ManagerInvitationNames.builder().profileName("Alex").inviterDisplayName("Sam").build())
        .containsEntry(
            vanished,
            ManagerInvitationNames.builder()
                .profileName("Cabin Kid")
                .inviterDisplayName("Mara")
                .build());
  }
}
