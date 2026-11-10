package com.streamarr.server.services.library;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.LibraryStatus;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.LibraryRepository;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("IntegrationTest")
@DisplayName("Library Query Service Integration Tests")
class LibraryQueryServiceIT extends AbstractIntegrationTest {

  @Autowired private LibraryQueryService libraryQueryService;
  @Autowired private LibraryRepository libraryRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private TransactionTemplate transactionTemplate;

  private Library library;

  @BeforeEach
  void saveLibrary() {
    library = libraryRepository.save(LibraryFixtureCreator.buildFakeLibrary());
  }

  @AfterEach
  void deleteLibrary() {
    libraryRepository.deleteById(library.getId());
  }

  @Test
  @DisplayName(
      "Should return the committed status when an after-commit callback reads a library that its"
          + " transaction loaded")
  void shouldReturnCommittedStatusWhenAfterCommitCallbackReadsLibraryThatItsTransactionLoaded() {
    var readAfterCommit = new AtomicReference<Optional<Library>>(Optional.empty());

    transactionTemplate.executeWithoutResult(
        _ -> {
          assertThat(libraryRepository.findById(library.getId()))
              .map(Library::getStatus)
              .contains(LibraryStatus.HEALTHY);
          // A write that bypasses the persistence context leaves its managed copy HEALTHY.
          jdbcTemplate.update(
              "update library set status = 'UNHEALTHY' where id = ?", library.getId());
          TransactionSynchronizationManager.registerSynchronization(
              new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                  readAfterCommit.set(libraryQueryService.findLatestCommitted(library.getId()));
                }
              });
        });

    assertThat(readAfterCommit.get()).map(Library::getStatus).contains(LibraryStatus.UNHEALTHY);
  }
}
