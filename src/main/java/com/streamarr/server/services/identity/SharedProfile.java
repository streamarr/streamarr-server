package com.streamarr.server.services.identity;

import com.streamarr.server.domain.auth.Profile;
import lombok.Builder;
import lombok.NonNull;

/**
 * The Profile on a share, whether it is someone's Personal Profile, and whether it is a visitor
 * there: a Profile that belongs to another Household than the one the share is into.
 */
@Builder
public record SharedProfile(@NonNull Profile profile, boolean personalProfile, boolean visitor) {}
