package com.streamarr.server.services.library;

import static com.streamarr.server.fixtures.AuthenticatedIdentityFixture.accountScopedBuilder;
import static com.streamarr.server.fixtures.AuthenticatedIdentityFixture.profileScopedBuilder;
import static com.streamarr.server.fixtures.PaginationFixture.firstRows;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.domain.media.MediaType;
import com.streamarr.server.exceptions.ProfileRequiredException;
import com.streamarr.server.fakes.FakeAuthorizationService;
import com.streamarr.server.fakes.FakeLibraryRepository;
import com.streamarr.server.fakes.FakeMovieRepository;
import com.streamarr.server.services.LibraryMoviesList;
import com.streamarr.server.services.MovieService;
import com.streamarr.server.services.authorization.Intent;
import com.streamarr.server.services.pagination.MediaFilter;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

@Tag("UnitTest")
class LibraryQueryAuthorizationTest {
  private static final MediaFilter ANY_LIBRARY = MediaFilter.builder().build();

  @Test
  @DisplayName(
      "Should read Libraries from the selected Profile token when live authority has changed")
  void shouldReadLibrariesFromSelectedProfileTokenWhenLiveAuthorityHasChanged() {
    var identity = profileScopedBuilder().build();
    var authorization = new FakeAuthorizationService(identity).denyAll();
    var service =
        LibraryQueryService.builder()
            .libraries(new FakeLibraryRepository())
            .movies(
                MovieService.builder()
                    .movieRepository(new FakeMovieRepository())
                    .libraryMoviesList(new LibraryMoviesList(new FakeMovieRepository()))
                    .events(_ -> {})
                    .build())
            .authorization(authorization)
            .build();

    assertThat(service.findAll(identity)).isEmpty();
    assertThat(service.findById(identity, UUID.randomUUID())).isEmpty();
    assertThat(service.getItems(identity, MediaType.MOVIE, ANY_LIBRARY, firstRows(10)).rows())
        .isEmpty();
  }

  @Test
  @DisplayName("Should refuse Library queries when the token has no selected Profile")
  void shouldRefuseLibraryQueriesWhenTokenHasNoSelectedProfile() {
    var identity = accountScopedBuilder().build();
    var libraryId = UUID.randomUUID();
    var firstPage = firstRows(10);
    var service =
        LibraryQueryService.builder()
            .libraries(new FakeLibraryRepository())
            .authorization(new FakeAuthorizationService(identity))
            .build();

    assertThatThrownBy(() -> service.findAll(identity))
        .isInstanceOf(ProfileRequiredException.class);
    assertThatThrownBy(() -> service.findById(identity, libraryId))
        .isInstanceOf(ProfileRequiredException.class);
    assertThatThrownBy(() -> service.getItems(identity, MediaType.MOVIE, ANY_LIBRARY, firstPage))
        .isInstanceOf(ProfileRequiredException.class);
    assertThatThrownBy(() -> service.requireSubscriptionProfile(identity))
        .isInstanceOf(ProfileRequiredException.class);
  }

  @Test
  @DisplayName(
      "Should allow a Library subscription when the selected Profile may view its own activity")
  void shouldAllowLibrarySubscriptionWhenSelectedProfileMayViewItsOwnActivity() {
    var identity = profileScopedBuilder().build();
    var authorization = new FakeAuthorizationService(identity);
    var service = LibraryQueryService.builder().authorization(authorization).build();

    service.requireSubscriptionProfile(identity);

    assertThat(authorization.recordedIntents())
        .containsExactly(new Intent.ViewProfileActivity(identity.profileId()));
  }

  @Test
  @DisplayName(
      "Should refuse a Library subscription when the selected Profile may not view its activity")
  void shouldRefuseLibrarySubscriptionWhenSelectedProfileMayNotViewItsActivity() {
    var identity = profileScopedBuilder().build();
    var authorization = new FakeAuthorizationService(identity).denyAll();
    var service = LibraryQueryService.builder().authorization(authorization).build();

    assertThatThrownBy(() -> service.requireSubscriptionProfile(identity))
        .isInstanceOf(AccessDeniedException.class);
  }
}
