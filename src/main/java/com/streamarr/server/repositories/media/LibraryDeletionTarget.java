package com.streamarr.server.repositories.media;

import com.streamarr.server.domain.LibraryStatus;
import java.util.List;
import java.util.UUID;
import lombok.Builder;

@Builder
public record LibraryDeletionTarget(
    UUID libraryId, String filepathUri, LibraryStatus status, List<UUID> mediaFileIds) {}
