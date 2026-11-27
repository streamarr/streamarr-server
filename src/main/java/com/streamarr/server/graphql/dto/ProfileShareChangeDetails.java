package com.streamarr.server.graphql.dto;

import com.streamarr.server.services.identity.ShareChange;
import java.time.Instant;

/** A committed share change as the schema shows it; {@code share} is null once it was deleted. */
public record ProfileShareChangeDetails(String shareId, ProfileShareDetails share) {

  public static ProfileShareChangeDetails from(ShareChange change, Instant now) {
    return new ProfileShareChangeDetails(
        change.shareId().toString(),
        change.share().map(share -> ProfileShareDetails.from(share, now)).orElse(null));
  }
}
