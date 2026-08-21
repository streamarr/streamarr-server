package com.streamarr.server.services.library;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.services.events.library.LibraryAddedEvent;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("IntegrationTest")
@DisplayName("Library Added Listener Integration Tests")
@Import(LibraryAddedListenersIT.TriggerConfiguration.class)
class LibraryAddedListenersIT extends AbstractIntegrationTest {

  @Autowired private ApplicationEventPublisher eventPublisher;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private CapturingLibraryTriggers triggers;

  @AfterEach
  void clearTriggers() {
    triggers.scans.clear();
    triggers.watches.clear();
  }

  @Test
  @DisplayName("Should start scanning and watching when the adding transaction commits")
  void shouldStartScanningAndWatchingWhenAddingTransactionCommits() {
    var event = new LibraryAddedEvent(UUID.randomUUID(), "file:///movies");

    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            _ -> {
              eventPublisher.publishEvent(event);
              assertThat(triggers.scans).isEmpty();
              assertThat(triggers.watches).isEmpty();
            });

    assertThat(triggers.scans).containsExactly(event.libraryId());
    assertThat(triggers.watches).containsExactly(event.filepathUri());
  }

  @Test
  @DisplayName("Should not start scanning or watching when the adding transaction rolls back")
  void shouldNotStartScanningOrWatchingWhenAddingTransactionRollsBack() {
    var event = new LibraryAddedEvent(UUID.randomUUID(), "file:///movies");

    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            status -> {
              eventPublisher.publishEvent(event);
              status.setRollbackOnly();
            });

    assertThat(triggers.scans).isEmpty();
    assertThat(triggers.watches).isEmpty();
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class TriggerConfiguration {

    @Bean
    @Primary
    CapturingLibraryTriggers capturingLibraryTriggers() {
      return new CapturingLibraryTriggers();
    }
  }

  static class CapturingLibraryTriggers implements LibraryScanTrigger, LibraryWatchTrigger {

    final CopyOnWriteArrayList<UUID> scans = new CopyOnWriteArrayList<>();
    final CopyOnWriteArrayList<String> watches = new CopyOnWriteArrayList<>();

    @Override
    public void triggerAsyncScan(UUID libraryId) {
      scans.add(libraryId);
    }

    @Override
    public void triggerAsyncWatch(String filepathUri) {
      watches.add(filepathUri);
    }
  }
}
