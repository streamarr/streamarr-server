package com.streamarr.server.services.notification;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

/**
 * One event of a kind about a subject, to tell its recipients. The kind's recipients rule names the
 * recipients from the relationships as they stand when the event happens; the Account that acted,
 * if any, is never told.
 *
 * @param <K> the kind
 * @param <F> what the kind carries beyond its subject
 */
@Builder
public record NotificationEvent<K, F>(
    @NonNull K kind,
    @NonNull UUID subjectId,
    @NonNull F facts,
    @NonNull Set<UUID> recipients,
    @NonNull Optional<UUID> actingAccountId,
    @NonNull Instant at) {}
