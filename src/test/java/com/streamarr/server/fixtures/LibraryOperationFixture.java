package com.streamarr.server.fixtures;

import com.streamarr.server.domain.LibraryStatus;
import com.streamarr.server.domain.auth.SystemActor;
import com.streamarr.server.services.library.LibraryOperation;
import com.streamarr.server.services.library.LibraryOperationKind;
import com.streamarr.server.services.metadata.ImageRefreshMode;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;

/** Stored library operations that the System actor requested for an idle library. */
public final class LibraryOperationFixture {

  private static final Instant CREATED_ON = Instant.parse("2026-10-15T10:00:00Z");

  private LibraryOperationFixture() {}

  /** A refresh of the library that keeps stored images. */
  public static LibraryOperation.LibraryOperationBuilder refreshBuilder(@NonNull UUID libraryId) {
    return LibraryOperation.builder()
        .id(UUID.randomUUID())
        .libraryId(libraryId)
        .kind(LibraryOperationKind.REFRESH)
        .imageRefreshMode(Optional.of(ImageRefreshMode.PRESERVE))
        .statusBeforeOperation(LibraryStatus.HEALTHY)
        .createdBy(SystemActor.ID)
        .createdOn(CREATED_ON);
  }

  /** A scan of the library. */
  public static LibraryOperation.LibraryOperationBuilder scanBuilder(@NonNull UUID libraryId) {
    return refreshBuilder(libraryId)
        .kind(LibraryOperationKind.SCAN)
        .imageRefreshMode(Optional.empty());
  }
}
