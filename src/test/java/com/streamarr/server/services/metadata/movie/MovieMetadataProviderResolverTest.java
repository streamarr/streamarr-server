package com.streamarr.server.services.metadata.movie;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.ExternalAgentStrategy;
import com.streamarr.server.domain.Library;
import com.streamarr.server.services.metadata.MetadataSearchOutcome.TemporarilyUnavailable;
import com.streamarr.server.services.parsers.video.VideoFileParserResult;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Movie Metadata Provider Resolver Tests")
class MovieMetadataProviderResolverTest {

  @Test
  @DisplayName("Should return unavailable when no provider matches library strategy for search")
  void shouldReturnUnavailableWhenNoProviderMatchesLibraryStrategyForSearch() {
    var resolver = new MovieMetadataProviderResolver(List.of());
    var library =
        Library.builder().name("Movies").externalAgentStrategy(ExternalAgentStrategy.TMDB).build();
    var parserResult = VideoFileParserResult.builder().title("Inception").build();

    var result = resolver.search(library, parserResult);

    assertThat(result)
        .isInstanceOfSatisfying(
            TemporarilyUnavailable.class,
            unavailable ->
                assertThat(unavailable.cause())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("TMDB"));
  }
}
