package com.streamarr.server.services.identity;

import com.streamarr.server.domain.auth.ProfileManagerInvitation;
import com.streamarr.server.domain.auth.ProfileManagerInvitationStatus;
import lombok.NonNull;

/** A manager invitation as one reader may read it, with the status that reader reads. */
public record ManagerInvitationReading(
    @NonNull ProfileManagerInvitation invitation, @NonNull ProfileManagerInvitationStatus status) {}
