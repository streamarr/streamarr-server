package com.streamarr.server.services.events.auth;

import com.streamarr.server.domain.auth.ChangedManagerInvitation;
import com.streamarr.server.domain.auth.ManagerInvitationAction;
import lombok.NonNull;

/**
 * A write changed one manager invitation. It is published inside the writer's transaction, so a
 * listener after commit hears only changes that committed, and a listener inside the transaction
 * commits or rolls back with the write. {@code action} says what the write did and which Account
 * did it.
 */
public record ProfileManagerInvitationChangedEvent(
    @NonNull ChangedManagerInvitation invitation, @NonNull ManagerInvitationAction action) {}
