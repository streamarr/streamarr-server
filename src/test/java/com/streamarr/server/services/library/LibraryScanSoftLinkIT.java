package com.streamarr.server.services.library;

import static com.streamarr.server.jooq.generated.tables.SkippedFile.SKIPPED_FILE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.LibraryStatus;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.jooq.generated.enums.SkippedFileReason;
import com.streamarr.server.jooq.generated.tables.records.SkippedFileRecord;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.services.filepath.FilepathCodec;
import com.streamarr.server.support.LibraryOperationDriver;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

@Tag("IntegrationTest")
@DisplayName("Library scan soft link integration tests")
class LibraryScanSoftLinkIT extends AbstractIntegrationTest {

  @Autowired private LibraryOperationDriver libraryOperationDriver;
  @Autowired private LibraryRepository libraryRepository;
  @Autowired private MediaFileRepository mediaFileRepository;
  @Autowired private DSLContext dsl;

  @TempDir Path tempDir;

  private Path libraryFolder;
  private Path elsewhere;
  private Library library;

  @BeforeEach
  void saveALibraryInARealFolder() throws IOException {
    libraryFolder = Files.createDirectory(tempDir.resolve("library"));
    elsewhere = Files.createDirectory(tempDir.resolve("elsewhere"));
    library =
        libraryRepository.saveAndFlush(
            LibraryFixtureCreator.buildFakeLibrary().toBuilder()
                .filepathUri(FilepathCodec.encode(libraryFolder))
                .build());
  }

  @AfterEach
  void removeTheLibrary() {
    libraryRepository.deleteById(library.getId());
  }

  @Test
  @DisplayName("Should store a skipped file for each soft link when a scan finds soft links")
  void shouldStoreASkippedFileForEachSoftLinkWhenAScanFindsSoftLinks() throws IOException {
    var fileLink =
        Files.createSymbolicLink(
            libraryFolder.resolve("File (2002).mkv"),
            Files.createFile(elsewhere.resolve("File (2002).mkv")));
    var folderTarget = Files.createDirectory(elsewhere.resolve("Folder (2003)"));
    Files.createFile(folderTarget.resolve("Folder (2003).mkv"));
    var folderLink = Files.createSymbolicLink(libraryFolder.resolve("Folder (2003)"), folderTarget);

    libraryOperationDriver.scanToEnd(library.getId());

    assertThat(skippedFiles())
        .extracting(SkippedFileRecord::getFilepathUri, SkippedFileRecord::getReason)
        .containsExactlyInAnyOrder(
            tuple(FilepathCodec.encode(fileLink), SkippedFileReason.SOFT_LINK),
            tuple(FilepathCodec.encodeEntry(folderLink), SkippedFileReason.SOFT_LINK));
    assertThat(mediaFileRepository.findByLibraryId(library.getId())).isEmpty();
    assertThat(statusOfLibrary()).isEqualTo(LibraryStatus.HEALTHY);
  }

  @Test
  @DisplayName("Should keep only the latest scan's skipped files when the library is scanned again")
  void shouldKeepOnlyTheLatestScansSkippedFilesWhenTheLibraryIsScannedAgain() throws IOException {
    var target = Files.createFile(elsewhere.resolve("Linked (2002).mkv"));
    var keptLink = Files.createSymbolicLink(libraryFolder.resolve("Kept (2002).mkv"), target);
    var removedLink = Files.createSymbolicLink(libraryFolder.resolve("Removed (2002).mkv"), target);
    libraryOperationDriver.scanToEnd(library.getId());
    var firstScanId = skippedFiles().getFirst().getLibraryOperationId();
    Files.delete(removedLink);

    libraryOperationDriver.scanToEnd(library.getId());

    assertThat(skippedFiles())
        .singleElement()
        .satisfies(
            row -> {
              assertThat(row.getFilepathUri()).isEqualTo(FilepathCodec.encode(keptLink));
              assertThat(row.getLibraryOperationId()).isNotEqualTo(firstScanId);
            });
  }

  private List<SkippedFileRecord> skippedFiles() {
    return dsl.selectFrom(SKIPPED_FILE).where(SKIPPED_FILE.LIBRARY_ID.eq(library.getId())).fetch();
  }

  private LibraryStatus statusOfLibrary() {
    return libraryRepository.findById(library.getId()).orElseThrow().getStatus();
  }
}
