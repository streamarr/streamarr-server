package com.streamarr.server.services.identity;

import com.streamarr.server.domain.auth.ProfileManagerSource;
import com.streamarr.server.domain.auth.UserAccount;
import lombok.NonNull;

/** An Account that manages a Profile, where its authority comes from, and whether it is reading. */
public record ManagingAccountDetails(
    @NonNull UserAccount account, @NonNull ProfileManagerSource source, boolean reader) {}
