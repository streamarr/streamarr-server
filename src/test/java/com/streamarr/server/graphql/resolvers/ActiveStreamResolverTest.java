package com.streamarr.server.graphql.resolvers;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.streamarr.server.domain.media.Series;
import com.streamarr.server.graphql.subscriptions.ActiveStreamBroadcaster;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.streaming.ActiveStreamService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Active Stream Resolver Tests")
class ActiveStreamResolverTest {

  private final ActiveStreamResolver resolver =
      new ActiveStreamResolver(
          mock(AuthorizationService.class),
          mock(ActiveStreamService.class),
          mock(ActiveStreamBroadcaster.class));

  @Test
  @DisplayName("Should refuse to name the media type when the media is neither a movie nor episode")
  void shouldRefuseToNameTheMediaTypeWhenTheMediaIsNeitherAMovieNorEpisode() {
    var series = Series.builder().title("A Series").build();

    assertThatThrownBy(() -> resolver.resolveActiveStreamMedia(series))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unknown active stream media type: Series");
  }
}
