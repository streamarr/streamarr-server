package com.streamarr.server.repositories;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.ExternalIdentifier;
import com.streamarr.server.domain.ExternalSourceType;
import com.streamarr.server.domain.media.Series;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.media.SeriesRepository;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@Tag("IntegrationTest")
@DisplayName("Series Repository Integration Tests")
class SeriesRepositoryIT extends AbstractIntegrationTest {

  @Autowired private SeriesRepository seriesRepository;
  @Autowired private LibraryRepository libraryRepository;

  @Test
  @DisplayName("Should load external identifiers when finding series by library ID")
  void shouldLoadExternalIdentifiersWhenFindingSeriesByLibraryId() {
    var library = libraryRepository.save(LibraryFixtureCreator.buildFakeLibrary());
    var externalId =
        ExternalIdentifier.builder()
            .externalId("eager-series-1")
            .externalSourceType(ExternalSourceType.TMDB)
            .build();
    seriesRepository.saveAndFlush(
        Series.builder()
            .title("Eager External IDs Series")
            .externalIds(Set.of(externalId))
            .library(library)
            .build());

    var series = seriesRepository.findWithExternalIdsByLibrary_Id(library.getId());

    assertThat(series)
        .singleElement()
        .satisfies(
            candidate ->
                assertThat(candidate.getExternalIds())
                    .singleElement()
                    .extracting(ExternalIdentifier::getExternalId)
                    .isEqualTo("eager-series-1"));
  }
}
