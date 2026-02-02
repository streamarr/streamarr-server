package com.streamarr.server.graphql.resolvers;

import com.netflix.graphql.dgs.DgsQueryExecutor;
import com.netflix.graphql.dgs.test.EnableDgsTest;
import com.streamarr.server.domain.ExternalAgentStrategy;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.LibraryBackend;
import com.streamarr.server.domain.LibraryStatus;
import com.streamarr.server.domain.media.MediaType;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.services.MovieService;
import com.streamarr.server.services.library.LibraryManagementService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;

@Tag("UnitTest")
@EnableDgsTest
@SpringBootTest(classes = {LibraryResolver.class})
@DisplayName("Library Resolver Tests")
class LibraryResolverTest {

  @Autowired private DgsQueryExecutor dgsQueryExecutor;

  @MockitoBean private LibraryRepository libraryRepository;

  @MockitoBean private LibraryManagementService libraryManagementService;

  @MockitoBean private MovieService movieService;

  @Test
  @DisplayName("Should return library when valid ID provided")
  void shouldReturnLibraryWhenValidIdProvided() {
    var libraryId = UUID.randomUUID();
    var library =
        Library.builder()
            .name("Movies")
            .filepath("/mpool/media/movies")
            .status(LibraryStatus.HEALTHY)
            .backend(LibraryBackend.LOCAL)
            .type(MediaType.MOVIE)
            .externalAgentStrategy(ExternalAgentStrategy.TMDB)
            .build();
    library.setId(libraryId);

    when(libraryRepository.findById(libraryId)).thenReturn(Optional.of(library));

    String name =
        dgsQueryExecutor.executeAndExtractJsonPath(
            String.format("{ library(id: \"%s\") { name filepath } }", libraryId),
            "data.library.name");

    assertThat(name).isEqualTo("Movies");
  }

  @Test
  @DisplayName("Should return true when scanLibrary called with valid ID")
  void shouldReturnTrueWhenScanLibraryCalledWithValidId() {
    doNothing().when(libraryManagementService).scanLibrary(any(UUID.class));

    Boolean result =
        dgsQueryExecutor.executeAndExtractJsonPath(
            String.format("mutation { scanLibrary(id: \"%s\") }", UUID.randomUUID()),
            "data.scanLibrary");

    assertThat(result).isTrue();
  }

  @Test
  @DisplayName("Should return error when invalid ID provided")
  void shouldReturnErrorWhenInvalidIdProvided() {
    var result = dgsQueryExecutor.execute("{ library(id: \"not-a-uuid\") { name } }");

    assertThat(result.getErrors()).isNotEmpty();
    assertThat(result.getErrors().get(0).getMessage()).contains("Invalid ID format");
  }
}
