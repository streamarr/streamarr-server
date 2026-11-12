package com.streamarr.server.services.library;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.LibraryStatus;
import com.streamarr.server.fakes.FakeLibraryRepository;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Library Query Service Tests")
class LibraryQueryServiceTest {

  private final FakeLibraryRepository libraryRepository = new FakeLibraryRepository();
  private final LibraryQueryService service =
      LibraryQueryService.builder().libraries(libraryRepository).build();

  @Test
  @DisplayName("Should return the saved library when reading its latest committed row")
  void shouldReturnSavedLibraryWhenReadingItsLatestCommittedRow() {
    var library =
        libraryRepository.save(
            LibraryFixtureCreator.buildFakeLibrary().toBuilder()
                .status(LibraryStatus.SCANNING)
                .build());

    assertThat(service.findLatestCommitted(library.getId()))
        .hasValueSatisfying(
            found -> {
              assertThat(found.getId()).isEqualTo(library.getId());
              assertThat(found.getStatus()).isEqualTo(LibraryStatus.SCANNING);
            });
  }

  @Test
  @DisplayName("Should return empty when reading the latest committed row of a missing library")
  void shouldReturnEmptyWhenReadingLatestCommittedRowOfMissingLibrary() {
    assertThat(service.findLatestCommitted(UUID.randomUUID())).isEmpty();
  }
}
