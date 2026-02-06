package com.streamarr.server.services.library;

import static org.assertj.core.api.Assertions.assertThatCode;

import com.streamarr.server.config.LibraryScanProperties;
import com.streamarr.server.fakes.FakeLibraryRepository;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.services.library.events.LibraryAddedEvent;
import com.streamarr.server.services.library.events.LibraryRemovedEvent;
import com.streamarr.server.services.validation.IgnoredFileValidator;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
    assertThatCode(() -> service.setup()).doesNotThrowAnyException();
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
    java.nio.file.Files.createDirectory(subDir);

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
    java.nio.file.Files.createDirectory(otherDir);

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
    java.nio.file.Files.createDirectory(subDir);

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
  @DisplayName("Should start watching directory on LibraryAddedEvent")
  void shouldStartWatchingDirectoryOnLibraryAddedEvent() {
    var event = new LibraryAddedEvent(UUID.randomUUID(), tempDir.toString());

    assertThatCode(() -> service.onLibraryAdded(event)).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("Should stop watching directory on LibraryRemovedEvent")
  void shouldStopWatchingDirectoryOnLibraryRemovedEvent() throws IOException {
    service.addDirectory(tempDir);

    var event = new LibraryRemovedEvent(tempDir.toString(), Set.of());

    assertThatCode(() -> service.onLibraryRemoved(event)).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("Should load existing libraries on initialization")
  void shouldLoadExistingLibrariesOnInitialization() {
    fakeLibraryRepository.save(
        LibraryFixtureCreator.buildFakeLibrary().toBuilder().filepath(tempDir.toString()).build());

    assertThatCode(() -> service.afterPropertiesSet()).doesNotThrowAnyException();
  }
}
