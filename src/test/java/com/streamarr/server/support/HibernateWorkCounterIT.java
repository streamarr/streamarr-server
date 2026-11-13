package com.streamarr.server.support;

import static com.streamarr.server.support.GraphQlTestSupport.graphqlRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.Library;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.LibraryRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

@Tag("IntegrationTest")
@DisplayName("Hibernate Work Counter Integration Tests")
class HibernateWorkCounterIT extends AbstractIntegrationTest {

  private static final HibernateWorkCounter.Counts NO_WORK =
      new HibernateWorkCounter.Counts(0, Map.of());

  @Autowired private HibernateWorkCounter counter;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private MockMvc mockMvc;
  @Autowired private LibraryRepository libraryRepository;

  private final List<AuthTestSupport.TestIdentity> identities = new ArrayList<>();

  @AfterEach
  void deleteIdentities() {
    identities.forEach(authTestSupport::deleteIdentity);
  }

  @Test
  @DisplayName("Should count the same work for a request when another thread reads while it runs")
  void shouldCountTheSameWorkForARequestWhenAnotherThreadReadsWhileItRuns() throws Exception {
    var identity = createIdentity();
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    // The first request fills caches that later requests skip.
    readLibraryNameAs(identity, library);
    var requestAlone = counter.countAs(identity, () -> readLibraryNameAs(identity, library));

    var requestBesideAnotherThread =
        counter.countAs(
            identity,
            () -> {
              readLibraryNameAs(identity, library);
              readLibrariesOnAnotherThread();
              return null;
            });

    assertThat(requestBesideAnotherThread).isEqualTo(requestAlone);
  }

  @Test
  @DisplayName("Should count no work when only another thread reads while the work runs")
  void shouldCountNoWorkWhenOnlyAnotherThreadReadsWhileTheWorkRuns() throws Exception {
    var identity = createIdentity();
    libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());

    var counted =
        counter.countAs(
            identity,
            () -> {
              readLibrariesOnAnotherThread();
              return null;
            });

    assertThat(counted).isEqualTo(NO_WORK);
  }

  @Test
  @DisplayName("Should count a request's work when its data fetchers run on other threads")
  void shouldCountARequestsWorkWhenItsDataFetchersRunOnOtherThreads() throws Exception {
    var identity = createIdentity();
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());

    var counted = counter.countAs(identity, () -> readLibraryNameAs(identity, library));

    assertThat(counted.preparedStatements()).as("statements the request prepared").isPositive();
    assertThat(counted.loaded(Library.class)).as("libraries the request loaded").isPositive();
  }

  @Test
  @DisplayName("Should count no work when another identity's request runs")
  void shouldCountNoWorkWhenAnotherIdentitysRequestRuns() throws Exception {
    var identity = createIdentity();
    var otherIdentity = createIdentity();
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());

    var counted = counter.countAs(identity, () -> readLibraryNameAs(otherIdentity, library));

    assertThat(counted).isEqualTo(NO_WORK);
  }

  private AuthTestSupport.TestIdentity createIdentity() {
    var identity = authTestSupport.createIdentity();
    identities.add(identity);
    return identity;
  }

  private Object readLibraryNameAs(AuthTestSupport.TestIdentity identity, Library library)
      throws Exception {
    return mockMvc
        .perform(
            graphqlRequest(
                authTestSupport.profileBearer(identity),
                "{ library(id: \"%s\") { name } }".formatted(library.getId())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.library.name").value(library.getName()));
  }

  // The other thread acts as no Account, as scheduled jobs do.
  private void readLibrariesOnAnotherThread() throws Exception {
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var libraries = executor.submit(() -> libraryRepository.findAll());

      assertThat(libraries.get(10, TimeUnit.SECONDS)).isNotEmpty();
    }
  }
}
