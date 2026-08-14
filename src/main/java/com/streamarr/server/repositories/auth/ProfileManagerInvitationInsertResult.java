package com.streamarr.server.repositories.auth;

import com.streamarr.server.domain.auth.ProfileManagerInvitation;
import lombok.NonNull;

public record ProfileManagerInvitationInsertResult(
    @NonNull ProfileManagerInvitation invitation, boolean inserted) {}
