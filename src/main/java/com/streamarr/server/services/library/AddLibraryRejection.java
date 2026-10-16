package com.streamarr.server.services.library;

import com.streamarr.server.domain.Library;
import java.nio.file.Path;
import lombok.NonNull;

/** Every expected reason {@code addLibrary} refuses; each maps to one schema error type. */
public sealed interface AddLibraryRejection {

  record NameRequired() implements AddLibraryRejection {}

  record PathRequired() implements AddLibraryRejection {}

  record PathNotFound() implements AddLibraryRejection {}

  record PathNotDirectory() implements AddLibraryRejection {}

  record PathNotReadable() implements AddLibraryRejection {}

  /** Another library already uses the path; {@code conflictingPath} is that library's path. */
  record PathAlreadyRegistered(@NonNull Library conflictingLibrary, @NonNull Path conflictingPath)
      implements AddLibraryRejection {}

  /** The path overlaps another library's path; {@code conflictingPath} is that library's path. */
  record PathOverlap(
      @NonNull LibraryPathOverlap relation,
      @NonNull Library conflictingLibrary,
      @NonNull Path conflictingPath)
      implements AddLibraryRejection {}
}
