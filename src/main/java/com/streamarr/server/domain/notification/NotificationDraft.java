package com.streamarr.server.domain.notification;

import java.time.Instant;
import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

/**
 * One event of a kind about a subject, as one recipient Account is to be told it.
 *
 * @param <K> the kind
 * @param <F> what the kind carries beyond its subject
 */
@Builder
public record NotificationDraft<K, F>(
    @NonNull UUID recipientAccountId,
    @NonNull K kind,
    @NonNull UUID subjectId,
    @NonNull F facts,
    @NonNull Instant at) {}
