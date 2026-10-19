package com.streamarr.server.services.library;

import static com.streamarr.server.fixtures.AuthenticatedIdentityFixture.accountScopedBuilder;
import static com.streamarr.server.fixtures.AuthenticatedIdentityFixture.profileScopedBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.exceptions.AuthorizationUnavailableException;
import com.streamarr.server.exceptions.ProfileRequiredException;
import com.streamarr.server.fakes.FakeAuthorizationService;
import com.streamarr.server.fakes.FakeLibraryRepository;
import com.streamarr.server.fakes.FakeMovieRepository;
import com.streamarr.server.fakes.FakeSeriesRepository;
import com.streamarr.server.services.authorization.AuthorizationUnit;
import com.streamarr.server.services.authorization.Decision;
import com.streamarr.server.services.authorization.Intent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

@Tag("UnitTest")
class LibraryContentsServiceTest {
  private final FakeAuthorizationService authorization =
      new FakeAuthorizationService(profileScopedBuilder().build());
  private final LibraryContentsService service =
      new LibraryContentsService(
          new FakeLibraryRepository(),
          new FakeMovieRepository(),
          new FakeSeriesRepository(),
          authorization);

  @Test
  @DisplayName("Should return the selected Profile when viewing its activity is allowed")
  void shouldReturnSelectedProfileWhenViewingItsActivityIsAllowed() {
    var identity = profileScopedBuilder().build();
    authorization.decideUnitWith(
        intent ->
            intent.equals(new Intent.ViewProfileActivity(identity.profileId()))
                ? new Decision.Allowed<>(AuthorizationUnit.INSTANCE)
                : new Decision.Denied<>(Decision.DenialReason.POLICY));

    assertThat(service.requireSubscriptionProfile(identity)).isEqualTo(identity.profileId());
  }

  @Test
  @DisplayName("Should require a Profile when no Profile is selected")
  void shouldRequireProfileWhenNoProfileIsSelected() {
    assertThatThrownBy(() -> service.requireSubscriptionProfile(accountScopedBuilder().build()))
        .isInstanceOf(ProfileRequiredException.class);
  }

  @Test
  @DisplayName("Should refuse a subscription when viewing Profile activity is denied")
  void shouldRefuseSubscriptionWhenViewingProfileActivityIsDenied() {
    authorization.denyAll();

    assertThatThrownBy(() -> service.requireSubscriptionProfile(profileScopedBuilder().build()))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  @DisplayName("Should fail closed when the authorization decision is unavailable")
  void shouldFailClosedWhenAuthorizationDecisionIsUnavailable() {
    authorization.failWith(Decision.FailureCause.EVALUATION_ERROR);

    assertThatThrownBy(() -> service.requireSubscriptionProfile(profileScopedBuilder().build()))
        .isInstanceOf(AuthorizationUnavailableException.class);
  }
}
