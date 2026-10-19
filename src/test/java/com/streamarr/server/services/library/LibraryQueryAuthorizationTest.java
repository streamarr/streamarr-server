package com.streamarr.server.services.library;

import static com.streamarr.server.fixtures.AuthenticatedIdentityFixture.accountScopedBuilder;
import static com.streamarr.server.fixtures.AuthenticatedIdentityFixture.profileScopedBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.exceptions.ProfileRequiredException;
import com.streamarr.server.fakes.FakeAuthorizationService;
import com.streamarr.server.fakes.FakeLibraryRepository;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
class LibraryQueryAuthorizationTest {
  @Test
  @DisplayName(
      "Should read Libraries from the selected Profile token when live authority has changed")
  void shouldReadLibrariesFromSelectedProfileTokenWhenLiveAuthorityHasChanged() {
    var identity = profileScopedBuilder().build();
    var authorization = new FakeAuthorizationService(identity).denyAll();
    var service = new LibraryQueryService(new FakeLibraryRepository(), null, null, authorization);

    assertThat(service.findAll(identity)).isEmpty();
    assertThat(service.findById(identity, UUID.randomUUID())).isEmpty();
  }

  @Test
  @DisplayName("Should refuse Library queries when the token has no selected Profile")
  void shouldRefuseLibraryQueriesWhenTokenHasNoSelectedProfile() {
    var identity = accountScopedBuilder().build();
    var service =
        new LibraryQueryService(
            new FakeLibraryRepository(), null, null, new FakeAuthorizationService(identity));

    assertThatThrownBy(() -> service.findAll(identity))
        .isInstanceOf(ProfileRequiredException.class);
    assertThatThrownBy(() -> service.findById(identity, UUID.randomUUID()))
        .isInstanceOf(ProfileRequiredException.class);
  }
}
