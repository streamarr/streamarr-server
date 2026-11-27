package com.streamarr.server.domain.auth;

import java.util.UUID;
import lombok.NonNull;

/**
 * The pending invitations to manage one Profile, as one reader reads them (ADR 0024 §Read
 * authorization). The Profile's ProfileManagers also read the invitations invalidated because of
 * what became of their invited Account, which still wait to them; a ServerAdmin reads those as
 * invalidated, so not as pending.
 */
public record ProfileManagerInvitationsFilter(
    @NonNull UUID profileId, boolean asItsManagersReadThem) {}
