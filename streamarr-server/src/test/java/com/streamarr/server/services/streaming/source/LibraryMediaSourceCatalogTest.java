package com.streamarr.server.services.streaming.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.fakes.FakeLibraryRepository;
import com.streamarr.server.fakes.FakeMediaFileRepository;
import com.streamarr.server.fakes.SecurityExceptionFileSystem;
import com.streamarr.server.services.library.FilepathCodec;
import com.streamarr.transcode.engine.model.MediaSourceRef;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("UnitTest")
@DisplayName("Library Media Source Catalog Tests")
class LibraryMediaSourceCatalogTest {

  @TempDir private Path tempDirectory;

  private FileSystem fileSystem;
  private FakeLibraryRepository libraryRepository;
  private FakeMediaFileRepository mediaFileRepository;
  private MediaSourceCatalog catalog;

  @BeforeEach
  void setUp() {
    fileSystem = Jimfs.newFileSystem(Configuration.unix());
    libraryRepository = new FakeLibraryRepository();
    mediaFileRepository = new FakeMediaFileRepository();
    catalog = new LibraryMediaSourceCatalog(mediaFileRepository, libraryRepository, fileSystem);
  }

  @AfterEach
  void tearDown() throws IOException {
    fileSystem.close();
  }

  @Test
  @DisplayName("Should create portable reference when media file is inside library")
  void shouldCreatePortableReferenceWhenMediaFileIsInsideLibrary() throws IOException {
    var libraryRoot = createDirectory("/media");
    var mediaPath = createFile("/media/Films/Amélie (2001).mkv");
    var libraryId = saveLibrary(libraryRoot);
    var mediaFileId = saveMediaFile(libraryId, mediaPath);

    var reference = catalog.referenceFor(mediaFileId);

    assertThat(reference.namespaceId()).isEqualTo(libraryId);
    assertThat(reference.relativeKey()).isEqualTo("Films/Amélie (2001).mkv");
  }

  @Test
  @DisplayName("Should resolve readable media file when reference belongs to library")
  void shouldResolveReadableMediaFileWhenReferenceBelongsToLibrary() throws IOException {
    var libraryId = saveLibrary(createDirectory("/media"));
    var mediaPath = createFile("/media/Films/Arrival.mkv");

    var resolved = catalog.resolve(new MediaSourceRef(libraryId, "Films/Arrival.mkv"));

    assertThat(resolved).isEqualTo(mediaPath.toRealPath());
  }

  @Test
  @DisplayName("Should reject reference when catalog entry names a directory")
  void shouldRejectReferenceWhenCatalogEntryNamesDirectory() throws IOException {
    var libraryId = saveLibrary(createDirectory("/media"));
    var mediaFileId = saveMediaFile(libraryId, createDirectory("/media/Films"));

    assertUnavailable(() -> catalog.referenceFor(mediaFileId));
  }

  @Test
  @DisplayName("Should reject reference when media file is unknown")
  void shouldRejectReferenceWhenMediaFileIsUnknown() {
    assertUnavailable(() -> catalog.referenceFor(UUID.randomUUID()));
  }

  @Test
  @DisplayName("Should reject reference when owning library is unknown")
  void shouldRejectReferenceWhenOwningLibraryIsUnknown() {
    var mediaFileId = saveMediaFile(UUID.randomUUID(), "/media/movie.mkv");

    assertUnavailable(() -> catalog.referenceFor(mediaFileId));
  }

  @Test
  @DisplayName("Should reject reference when media file is missing")
  void shouldRejectReferenceWhenMediaFileIsMissing() throws IOException {
    var libraryId = saveLibrary(createDirectory("/media"));
    var mediaFileId = saveMediaFile(libraryId, "/media/missing.mkv");

    assertUnavailable(() -> catalog.referenceFor(mediaFileId));
  }

  @Test
  @DisplayName("Should reject reference when media file escapes library root")
  void shouldRejectReferenceWhenMediaFileEscapesLibraryRoot() throws IOException {
    var libraryId = saveLibrary(createDirectory("/media"));
    var mediaFileId = saveMediaFile(libraryId, createFile("/private/movie.mkv"));

    assertUnavailable(() -> catalog.referenceFor(mediaFileId));
  }

  @Test
  @DisplayName("Should reject reference when symbolic link escapes library root")
  void shouldRejectReferenceWhenSymbolicLinkEscapesLibraryRoot() throws IOException {
    var libraryRoot = createDirectory("/media");
    var outsidePath = createFile("/private/movie.mkv");
    var link = libraryRoot.resolve("movie.mkv");
    Files.createSymbolicLink(link, outsidePath);
    var mediaFileId = saveMediaFile(saveLibrary(libraryRoot), link);

    assertUnavailable(() -> catalog.referenceFor(mediaFileId));
  }

  @Test
  @DisplayName("Should canonicalize symbolic link when target remains inside library")
  void shouldCanonicalizeSymbolicLinkWhenTargetRemainsInsideLibrary() throws IOException {
    var libraryRoot = createDirectory("/media");
    var target = createFile("/media/canonical/movie.mkv");
    var link = libraryRoot.resolve("aliases/movie.mkv");
    Files.createDirectories(link.getParent());
    Files.createSymbolicLink(link, target);
    var mediaFileId = saveMediaFile(saveLibrary(libraryRoot), link);

    var reference = catalog.referenceFor(mediaFileId);

    assertThat(reference.relativeKey()).isEqualTo("canonical/movie.mkv");
    assertThat(catalog.resolve(reference)).isEqualTo(target.toRealPath());
  }

  @Test
  @DisplayName("Should preserve percent escapes as literal filename data")
  void shouldPreservePercentEscapesAsLiteralFilenameData() throws IOException {
    var libraryId = saveLibrary(createDirectory("/media"));
    var mediaPath = createFile("/media/Films/%2e%2e.mkv");
    var mediaFileId = saveMediaFile(libraryId, mediaPath);

    var reference = catalog.referenceFor(mediaFileId);

    assertThat(reference.relativeKey()).isEqualTo("Films/%2e%2e.mkv");
    assertThat(catalog.resolve(reference)).isEqualTo(mediaPath.toRealPath());
  }

  @Test
  @DisplayName("Should reject resolution when source namespace is unknown")
  void shouldRejectResolutionWhenSourceNamespaceIsUnknown() {
    var source = new MediaSourceRef(UUID.randomUUID(), "Films/movie.mkv");

    assertUnavailable(() -> catalog.resolve(source));
  }

  @Test
  @DisplayName("Should reject resolution when referenced file is missing")
  void shouldRejectResolutionWhenReferencedFileIsMissing() throws IOException {
    var libraryId = saveLibrary(createDirectory("/media"));
    var source = new MediaSourceRef(libraryId, "Films/missing.mkv");

    assertUnavailable(() -> catalog.resolve(source));
  }

  @Test
  @DisplayName("Should reject resolution when symbolic link escapes library root")
  void shouldRejectResolutionWhenSymbolicLinkEscapesLibraryRoot() throws IOException {
    var libraryRoot = createDirectory("/media");
    var outsidePath = createFile("/private/movie.mkv");
    Files.createSymbolicLink(libraryRoot.resolve("movie.mkv"), outsidePath);
    var libraryId = saveLibrary(libraryRoot);
    var source = new MediaSourceRef(libraryId, "movie.mkv");

    assertUnavailable(() -> catalog.resolve(source));
  }

  @Test
  @DisplayName("Should revalidate containment whenever source is resolved")
  void shouldRevalidateContainmentWheneverSourceIsResolved() throws IOException {
    var libraryRoot = createDirectory("/media");
    var libraryId = saveLibrary(libraryRoot);
    var mediaPath = createFile("/media/movie.mkv");
    var source = new MediaSourceRef(libraryId, "movie.mkv");
    assertThat(catalog.resolve(source)).isEqualTo(mediaPath.toRealPath());

    var outsidePath = createFile("/private/movie.mkv");
    Files.delete(mediaPath);
    Files.createSymbolicLink(mediaPath, outsidePath);

    assertUnavailable(() -> catalog.resolve(source));
  }

  @Test
  @DisplayName("Should create forward-slash key when host filesystem uses backslashes")
  void shouldCreateForwardSlashKeyWhenHostFilesystemUsesBackslashes() throws IOException {
    fileSystem.close();
    fileSystem = Jimfs.newFileSystem(Configuration.windows());
    catalog = new LibraryMediaSourceCatalog(mediaFileRepository, libraryRepository, fileSystem);
    var libraryRoot = fileSystem.getPath("C:\\media");
    var mediaPath = libraryRoot.resolve("Films\\Amélie.mkv");
    Files.createDirectories(mediaPath.getParent());
    Files.createFile(mediaPath);
    var libraryId = saveLibrary(libraryRoot);
    var mediaFileId = saveMediaFile(libraryId, mediaPath);

    var reference = catalog.referenceFor(mediaFileId);

    assertThat(reference.relativeKey()).isEqualTo("Films/Amélie.mkv");
    assertThat(catalog.resolve(reference)).isEqualTo(mediaPath.toRealPath());
  }

  @Test
  @DisplayName("Should decode persisted file URIs through filepath codec")
  void shouldDecodePersistedFileUrisThroughFilepathCodec() throws IOException {
    var localCatalog =
        new LibraryMediaSourceCatalog(
            mediaFileRepository, libraryRepository, tempDirectory.getFileSystem());
    var libraryRoot = tempDirectory.resolve("media");
    var mediaPath = libraryRoot.resolve("Films/Arrival (2016).mkv");
    Files.createDirectories(mediaPath.getParent());
    Files.createFile(mediaPath);
    var libraryId = saveLibrary(FilepathCodec.encode(libraryRoot));
    var mediaFileId = saveMediaFile(libraryId, FilepathCodec.encode(mediaPath));

    var reference = localCatalog.referenceFor(mediaFileId);

    assertThat(reference.relativeKey()).isEqualTo("Films/Arrival (2016).mkv");
    assertThat(localCatalog.resolve(reference)).isEqualTo(mediaPath.toRealPath());
  }

  @Test
  @DisplayName("Should reject resolution when file access is denied")
  void shouldRejectResolutionWhenFileAccessIsDenied() throws IOException {
    var libraryId = saveLibrary(createDirectory("/media"));
    createFile("/media/movie.mkv");
    var restrictedFileSystem = new SecurityExceptionFileSystem(fileSystem);
    var restrictedCatalog =
        new LibraryMediaSourceCatalog(mediaFileRepository, libraryRepository, restrictedFileSystem);
    var source = new MediaSourceRef(libraryId, "movie.mkv");

    assertUnavailable(() -> restrictedCatalog.resolve(source));
  }

  @Test
  @DisplayName("Should reject resolution when media file is unreadable")
  void shouldRejectResolutionWhenMediaFileIsUnreadable() throws IOException {
    var libraryId = UUID.randomUUID();
    var libraryRoot = fileSystem.getPath("/media");
    Files.createDirectories(libraryRoot);
    Files.createFile(libraryRoot.resolve("movie.mkv"));
    libraryRepository.save(Library.builder().id(libraryId).filepathUri("/media").build());
    var unreadableFileSystem = new SecurityExceptionFileSystem(fileSystem, true);
    var unreadableCatalog =
        new LibraryMediaSourceCatalog(mediaFileRepository, libraryRepository, unreadableFileSystem);
    var source = new MediaSourceRef(libraryId, "movie.mkv");

    assertUnavailable(() -> unreadableCatalog.resolve(source));
  }

  @Test
  @DisplayName("Should reject resolution when reference names a directory")
  void shouldRejectResolutionWhenReferenceNamesDirectory() throws IOException {
    var libraryId = saveLibrary(createDirectory("/media"));
    createDirectory("/media/Films");
    var source = new MediaSourceRef(libraryId, "Films");

    assertUnavailable(() -> catalog.resolve(source));
  }

  private Path createDirectory(String path) throws IOException {
    return Files.createDirectories(fileSystem.getPath(path));
  }

  private Path createFile(String path) throws IOException {
    var file = fileSystem.getPath(path);
    Files.createDirectories(file.getParent());
    return Files.createFile(file);
  }

  private UUID saveLibrary(Path libraryRoot) {
    return saveLibrary(libraryRoot.toString());
  }

  private UUID saveLibrary(String filepathUri) {
    return libraryRepository.save(Library.builder().filepathUri(filepathUri).build()).getId();
  }

  private UUID saveMediaFile(UUID libraryId, Path mediaPath) {
    return saveMediaFile(libraryId, mediaPath.toString());
  }

  private UUID saveMediaFile(UUID libraryId, String filepathUri) {
    return mediaFileRepository
        .save(MediaFile.builder().libraryId(libraryId).filepathUri(filepathUri).build())
        .getId();
  }

  private static void assertUnavailable(ThrowingCallable operation) {
    assertThatThrownBy(operation).isInstanceOf(MediaSourceUnavailableException.class);
  }
}
