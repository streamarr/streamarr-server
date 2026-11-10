package com.streamarr.server.services.identity;

import com.streamarr.server.domain.auth.Profile;
import lombok.NonNull;

/** The Profile on a share, and whether it is someone's Personal Profile. */
public record SharedProfile(@NonNull Profile profile, boolean personalProfile) {}
