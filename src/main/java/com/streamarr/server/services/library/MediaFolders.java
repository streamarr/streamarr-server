package com.streamarr.server.services.library;

import com.streamarr.server.properties.RemoteTranscodeProperties;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.services.filepath.FilepathCodec;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** The folders that hold media: the media root and every library's folder. */
@Service
@RequiredArgsConstructor
public class MediaFolders {

  private final FileSystem fileSystem;
  private final RemoteTranscodeProperties remoteSource;
  private final LibraryRepository libraryRepository;

  /**
   * Reads the media folders as they are configured and stored. A folder may not exist.
   *
   * @throws IllegalArgumentException if a library's stored folder cannot be decoded
   */
  public List<Path> all() {
    var mediaRoot = fileSystem.getPath(remoteSource.sourceRoot());
    var libraryFolders =
        libraryRepository.findAll().stream()
            .map(library -> FilepathCodec.decode(fileSystem, library.getFilepathUri()));
    return Stream.concat(Stream.of(mediaRoot), libraryFolders).toList();
  }
}
