package com.streamarr.server.services.library;

import static com.streamarr.server.fixtures.AuthenticatedIdentityFixture.accountScopedBuilder;
import static com.streamarr.server.fixtures.AuthenticatedIdentityFixture.profileScopedBuilder;
import static com.streamarr.server.fixtures.PaginationFixture.buildForwardOptions;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.domain.media.MediaType;
import com.streamarr.server.exceptions.ProfileRequiredException;
import com.streamarr.server.fakes.FakeAuthorizationService;
import com.streamarr.server.fakes.FakeLibraryRepository;
import com.streamarr.server.fakes.FakeMovieRepository;
import com.streamarr.server.services.MovieService;
import com.streamarr.server.services.pagination.MediaFilter;
import com.streamarr.server.services.pagination.MediaPaginationOptions;
import com.streamarr.server.services.pagination.PaginationService;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
class LibraryQueryAuthorizationTest {
  private static final MediaPaginationOptions FIRST_PAGE =
      buildForwardOptions(10, MediaFilter.builder().build());

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
                    .paginationService(new PaginationService())
                    .events(_ -> {})
                    .build())
            .authorization(authorization)
            .build();

    assertThat(service.findAll(identity)).isEmpty();
    assertThat(service.findById(identity, UUID.randomUUID())).isEmpty();
    assertThat(service.getItems(identity, MediaType.MOVIE, FIRST_PAGE).items()).isEmpty();
  }

  @Test
  @DisplayName("Should refuse Library queries when the token has no selected Profile")
  void shouldRefuseLibraryQueriesWhenTokenHasNoSelectedProfile() {
    var identity = accountScopedBuilder().build();
    var libraryId = UUID.randomUUID();
    var service =
        LibraryQueryService.builder()
            .libraries(new FakeLibraryRepository())
            .authorization(new FakeAuthorizationService(identity))
            .build();

    assertThatThrownBy(() -> service.findAll(identity))
        .isInstanceOf(ProfileRequiredException.class);
    assertThatThrownBy(() -> service.findById(identity, libraryId))
        .isInstanceOf(ProfileRequiredException.class);
    assertThatThrownBy(() -> service.getItems(identity, MediaType.MOVIE, FIRST_PAGE))
        .isInstanceOf(ProfileRequiredException.class);
  }
}
