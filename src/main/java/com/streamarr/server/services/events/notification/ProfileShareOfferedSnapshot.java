package com.streamarr.server.services.events.notification;

import com.streamarr.server.domain.notification.ProfileShareAnswer;
import java.util.Optional;
import lombok.NonNull;

/**
 * What a HouseholdAdmin was told of an offer: the Profile's name when it was offered, and the
 * answer once the offer is decided, which its resolution writes.
 */
public record ProfileShareOfferedSnapshot(
    @NonNull String profileName, @NonNull Optional<ProfileShareAnswer> answer)
    implements NotificationSnapshot {}
