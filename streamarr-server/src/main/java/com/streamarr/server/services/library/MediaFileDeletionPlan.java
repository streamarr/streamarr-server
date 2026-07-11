package com.streamarr.server.services.library;

import com.streamarr.server.repositories.media.MediaFileDeletionTarget;
import java.util.List;
import java.util.UUID;
import lombok.Builder;

@Builder
record MediaFileDeletionPlan(List<MediaFileDeletionTarget> targets, List<UUID> streamSessionIds) {}
