package com.streamarr.server.services.library;

import static com.streamarr.server.fixtures.AuthenticatedIdentityFixture.accountScopedBuilder;
import static com.streamarr.server.fixtures.AuthenticatedIdentityFixture.profileScopedBuilder;
import static com.streamarr.server.fixtures.PaginationFixture.buildForwardOptions;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.domain.media.MediaType;
import com.streamarr.server.exceptions.AuthorizationUnavailableException;
import com.streamarr.server.exceptions.ProfileRequiredException;
import com.streamarr.server.fakes.FakeAuthorizationService;
import com.streamarr.server.fakes.FakeLibraryRepository;
import com.streamarr.server.services.authorization.AuthorizationUnit;
import com.streamarr.server.services.authorization.Decision;
import com.streamarr.server.services.authorization.Intent;
import com.streamarr.server.services.pagination.MediaFilter;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

@Tag("UnitTest")
class LibraryQueryServiceTest {
  private final FakeAuthorizationService authorization =
      new FakeAuthorizationService(profileScopedBuilder().build());
  private final LibraryQueryService service =
      new LibraryQueryService(new FakeLibraryRepository(), null, null, authorization);

  @Test
  @DisplayName("Should refuse Library queries when viewing Profile activity is denied")
  void shouldRefuseLibraryQueriesWhenViewingProfileActivityIsDenied() {
    var identity = profileScopedBuilder().build();
    var libraryId = UUID.randomUUID();
    var options = buildForwardOptions(1, MediaFilter.builder().libraryId(libraryId).build());
    authorization.denyAll();

    assertThatThrownBy(() -> service.findAll(identity)).isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> service.findById(identity, libraryId))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> service.getItems(identity, MediaType.MOVIE, options))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  @DisplayName("Should require a selected Profile when querying Libraries")
  void shouldRequireSelectedProfileWhenQueryingLibraries() {
    var identity = accountScopedBuilder().build();

    assertThatThrownBy(() -> service.findAll(identity))
        .isInstanceOf(ProfileRequiredException.class);
  }

  @Test
  @DisplayName("Should fail closed when the Library authorization decision is unavailable")
  void shouldFailClosedWhenLibraryAuthorizationDecisionIsUnavailable() {
    var identity = profileScopedBuilder().build();
    authorization.failWith(Decision.FailureCause.EVALUATION_ERROR);

    assertThatThrownBy(() -> service.findAll(identity))
        .isInstanceOf(AuthorizationUnavailableException.class);
  }

  @Test
  @DisplayName("Should return the catalog when viewing the selected Profile activity is allowed")
  void shouldReturnCatalogWhenViewingSelectedProfileActivityIsAllowed() {
    var identity = profileScopedBuilder().build();
    authorization.decideUnitWith(
        intent ->
            intent.equals(new Intent.ViewProfileActivity(identity.profileId()))
                ? new Decision.Allowed<>(AuthorizationUnit.INSTANCE)
                : new Decision.Denied<>(Decision.DenialReason.POLICY));

    assertThat(service.findAll(identity)).isEmpty();
    assertThat(service.findById(identity, UUID.randomUUID())).isEmpty();
  }
}
