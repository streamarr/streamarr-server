package com.streamarr.server.services.library;

import com.streamarr.server.services.metadata.ImageRefreshMode;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;

/** The scan or refresh that a request asks a library to start. */
public record NewLibraryOperation(
    @NonNull UUID libraryId,
    @NonNull LibraryOperationKind kind,
    @NonNull Optional<ImageRefreshMode> imageRefreshMode) {

  public NewLibraryOperation {
    if ((kind == LibraryOperationKind.REFRESH) != imageRefreshMode.isPresent()) {
      throw new IllegalArgumentException("Only a refresh carries an image refresh mode");
    }
  }

  public static NewLibraryOperation scan(@NonNull UUID libraryId) {
    return new NewLibraryOperation(libraryId, LibraryOperationKind.SCAN, Optional.empty());
  }

  public static NewLibraryOperation refresh(
      @NonNull UUID libraryId, @NonNull ImageRefreshMode imageRefreshMode) {
    return new NewLibraryOperation(
        libraryId, LibraryOperationKind.REFRESH, Optional.of(imageRefreshMode));
  }
}
