package com.streamarr.server.services.streaming.source;

import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.services.library.FilepathCodec;
import com.streamarr.transcode.engine.model.MediaSourceRef;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class LibraryMediaSourceCatalog implements MediaSourceCatalog {

  private final MediaFileRepository mediaFileRepository;
  private final LibraryRepository libraryRepository;
  private final FileSystem fileSystem;

  @Override
  public MediaSourceRef referenceFor(UUID mediaFileId) {
    var mediaFile =
        mediaFileRepository.findById(mediaFileId).orElseThrow(MediaSourceUnavailableException::new);
    var library =
        libraryRepository
            .findById(mediaFile.getLibraryId())
            .orElseThrow(MediaSourceUnavailableException::new);

    try {
      var libraryRoot = toRealPath(library.getFilepathUri());
      var sourcePath = requireAvailableFile(libraryRoot, toRealPath(mediaFile.getFilepathUri()));
      var relativePath = libraryRoot.relativize(sourcePath);
      var relativeKey = relativePath.toString().replace(fileSystem.getSeparator(), "/");
      return new MediaSourceRef(library.getId(), relativeKey);
    } catch (IOException | SecurityException | IllegalArgumentException exception) {
      throw new MediaSourceUnavailableException(exception);
    }
  }

  @Override
  public Path resolve(MediaSourceRef source) {
    var library =
        libraryRepository
            .findById(source.namespaceId())
            .orElseThrow(MediaSourceUnavailableException::new);

    try {
      var libraryRoot = toRealPath(library.getFilepathUri());
      var sourcePath = resolveKey(libraryRoot, source.relativeKey()).toRealPath();
      return requireAvailableFile(libraryRoot, sourcePath);
    } catch (IOException | SecurityException | IllegalArgumentException exception) {
      throw new MediaSourceUnavailableException(exception);
    }
  }

  private Path toRealPath(String filepathUri) throws IOException {
    return FilepathCodec.decode(fileSystem, filepathUri).toRealPath();
  }

  private static Path resolveKey(Path libraryRoot, String relativeKey) {
    var sourcePath = libraryRoot;
    for (var segment : relativeKey.split("/")) {
      sourcePath = sourcePath.resolve(segment);
    }
    return sourcePath;
  }

  private static Path requireAvailableFile(Path libraryRoot, Path sourcePath) {
    if (!sourcePath.startsWith(libraryRoot)
        || !Files.isRegularFile(sourcePath)
        || !Files.isReadable(sourcePath)) {
      throw new MediaSourceUnavailableException();
    }
    return sourcePath;
  }
}
