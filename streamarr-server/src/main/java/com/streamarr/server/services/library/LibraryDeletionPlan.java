package com.streamarr.server.services.library;

import com.streamarr.server.repositories.media.LibraryDeletionTarget;
import java.util.List;
import java.util.UUID;
import lombok.Builder;

@Builder
record LibraryDeletionPlan(LibraryDeletionTarget target, List<UUID> streamSessionIds) {}
