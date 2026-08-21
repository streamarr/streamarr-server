package com.streamarr.server.services.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.streamarr.server.config.LibraryScanProperties;
import com.streamarr.server.domain.Library;
import com.streamarr.server.fakes.FakeLibraryRepository;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.services.events.library.LibraryRemovedEvent;
import com.streamarr.server.services.filepath.FilepathCodec;
import com.streamarr.server.services.validation.IgnoredFileValidator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

@Tag("UnitTest")
@DisplayName("DirectoryWatchingService Tests")
class DirectoryWatchingServiceTest {

  @TempDir Path tempDir;

  private final LibraryRepository fakeLibraryRepository = new FakeLibraryRepository();
  private DirectoryWatchingService service;

  @BeforeEach
  void setUp() {
    service =
        new DirectoryWatchingService(
            fakeLibraryRepository,
            path -> true,
            null,
            new IgnoredFileValidator(new LibraryScanProperties(null, null, null)),
            null);
  }

  @AfterEach
  void tearDown() throws IOException {
    service.stopWatching();
  }

  @Test
  @DisplayName("Should skip setup when no directories are configured")
  void shouldSkipSetupWhenNoDirectoriesConfigured() {
    assertThatCode(() -> service.setup(List.of())).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("Should start watcher when first directory is added")
  void shouldStartWatcherWhenFirstDirectoryAdded() {
    assertThatCode(() -> service.addDirectory(tempDir)).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("Should restart watcher when additional directory is added")
  void shouldRestartWatcherWhenAdditionalDirectoryAdded() throws IOException {
    var subDir = tempDir.resolve("sub");
    Files.createDirectory(subDir);

    service.addDirectory(tempDir);

    assertThatCode(() -> service.addDirectory(subDir)).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("Should do nothing when removing from empty directory set")
  void shouldDoNothingWhenRemovingFromEmptyDirectorySet() {
    assertThatCode(() -> service.removeDirectory(tempDir)).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("Should do nothing when removing directory not in watch set")
  void shouldDoNothingWhenRemovingDirectoryNotInWatchSet() throws IOException {
    service.addDirectory(tempDir);

    var otherDir = tempDir.resolve("other");
    Files.createDirectory(otherDir);

    assertThatCode(() -> service.removeDirectory(otherDir)).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("Should stop watcher when last directory is removed")
  void shouldStopWatcherWhenLastDirectoryRemoved() throws IOException {
    service.addDirectory(tempDir);
    service.removeDirectory(tempDir);

    assertThatCode(() -> service.addDirectory(tempDir)).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("Should restart watcher with remaining directories after removal")
  void shouldRestartWatcherWithRemainingDirectories() throws IOException {
    var subDir = tempDir.resolve("remaining");
    Files.createDirectory(subDir);

    service.addDirectory(tempDir);
    service.addDirectory(subDir);

    service.removeDirectory(tempDir);

    assertThatCode(() -> service.stopWatching()).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("Should handle stopWatching when watcher is null")
  void shouldHandleStopWatchingWhenWatcherIsNull() {
    assertThatCode(() -> service.stopWatching()).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("Should close watcher and shutdown processor on stopWatching")
  void shouldCloseWatcherAndShutdownProcessor() throws IOException {
    service.addDirectory(tempDir);

    service.stopWatching();

    assertThatCode(() -> service.stopWatching()).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("Should stop watching directory on LibraryRemovedEvent")
  void shouldStopWatchingDirectoryOnLibraryRemovedEvent() throws IOException {
    service.addDirectory(tempDir);

    var event = new LibraryRemovedEvent(FilepathCodec.encode(tempDir), Set.of());

    assertThatCode(() -> service.onLibraryRemoved(event)).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("Should return immediately when asynchronous watching is triggered")
  void shouldReturnImmediatelyWhenAsyncWatchingIsTriggered() throws InterruptedException {
    var setupStarted = new CountDownLatch(1);
    var releaseSetup = new CountDownLatch(1);
    var blockingService = buildBlockingSetupService(setupStarted, releaseSetup);
    var filepathUri = FilepathCodec.encode(tempDir);
    var caller = Thread.ofPlatform().start(() -> blockingService.triggerAsyncWatch(filepathUri));

    try {
      assertThat(setupStarted.await(1, TimeUnit.SECONDS)).isTrue();
      assertThat(caller.join(Duration.ofSeconds(1))).isTrue();
    } finally {
      releaseSetup.countDown();
    }
  }

  @Test
  @DisplayName("Should not block calling thread when initializing with existing libraries")
  void shouldNotBlockCallingThreadWhenInitializingWithExistingLibraries()
      throws InterruptedException {
    fakeLibraryRepository.save(
        LibraryFixtureCreator.buildFakeLibrary().toBuilder()
            .filepathUri(FilepathCodec.encode(tempDir))
            .build());
    var setupStarted = new CountDownLatch(1);
    var releaseSetup = new CountDownLatch(1);
    var blockingService = buildBlockingSetupService(setupStarted, releaseSetup);
    var caller = Thread.ofPlatform().start(blockingService::afterPropertiesSet);

    try {
      assertThat(setupStarted.await(1, TimeUnit.SECONDS)).isTrue();
      assertThat(caller.join(Duration.ofSeconds(1))).isTrue();
    } finally {
      releaseSetup.countDown();
    }
  }

  @Test
  @DisplayName("Should not propagate an exception when asynchronous watcher setup fails")
  void shouldNotPropagateExceptionWhenAsyncWatcherSetupFails() throws InterruptedException {
    var logRecorded = new CountDownLatch(1);
    var logCapture = startLogCapture(logRecorded);
    var failingService = buildFailingSetupService();
    var filepathUri = FilepathCodec.encode(tempDir);

    try {
      failingService.triggerAsyncWatch(filepathUri);

      assertThat(logRecorded.await(1, TimeUnit.SECONDS)).isTrue();
      assertThat(logCapture.appender().list)
          .singleElement()
          .extracting(ILoggingEvent::getFormattedMessage)
          .isEqualTo("Failed to start watching directory for library: " + filepathUri);
    } finally {
      logCapture.close();
    }
  }

  @Test
  @DisplayName("Should not propagate exception when watcher setup fails on initialization")
  void shouldNotPropagateExceptionWhenSetupFailsOnInitialization() throws InterruptedException {
    fakeLibraryRepository.save(
        LibraryFixtureCreator.buildFakeLibrary().toBuilder()
            .filepathUri(FilepathCodec.encode(tempDir))
            .build());
    var logRecorded = new CountDownLatch(1);
    var logCapture = startLogCapture(logRecorded);
    var failingService = buildFailingSetupService();

    try {
      failingService.afterPropertiesSet();

      assertThat(logRecorded.await(1, TimeUnit.SECONDS)).isTrue();
      assertThat(logCapture.appender().list)
          .singleElement()
          .extracting(ILoggingEvent::getFormattedMessage)
          .isEqualTo("Failed to start library watcher");
    } finally {
      logCapture.close();
    }
  }

  private DirectoryWatchingService buildFailingSetupService() {
    return new DirectoryWatchingService(
        fakeLibraryRepository,
        path -> true,
        null,
        new IgnoredFileValidator(new LibraryScanProperties(null, null, null)),
        null) {
      @Override
      public void setup(List<Library> libraries) throws IOException {
        throw new IOException("simulated failure");
      }
    };
  }

  private DirectoryWatchingService buildBlockingSetupService(
      CountDownLatch setupStarted, CountDownLatch releaseSetup) {
    return new DirectoryWatchingService(
        fakeLibraryRepository,
        path -> true,
        null,
        new IgnoredFileValidator(new LibraryScanProperties(null, null, null)),
        null) {
      @Override
      public void setup(List<Library> libraries) throws IOException {
        setupStarted.countDown();
        try {
          releaseSetup.await();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IOException("interrupted while blocking setup", e);
        }
      }
    };
  }

  private LogCapture startLogCapture(CountDownLatch logRecorded) {
    var logger = (Logger) LoggerFactory.getLogger(DirectoryWatchingService.class);
    var appender =
        new ListAppender<ILoggingEvent>() {
          @Override
          protected void append(ILoggingEvent eventObject) {
            super.append(eventObject);
            logRecorded.countDown();
          }
        };
    appender.start();
    logger.addAppender(appender);
    return new LogCapture(logger, appender);
  }

  private record LogCapture(Logger logger, ListAppender<ILoggingEvent> appender)
      implements AutoCloseable {

    @Override
    public void close() {
      logger.detachAppender(appender);
      appender.stop();
    }
  }
}
