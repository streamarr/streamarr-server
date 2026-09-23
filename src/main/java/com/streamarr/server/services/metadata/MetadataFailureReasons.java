package com.streamarr.server.services.metadata;

import com.streamarr.server.domain.media.ItemFailureReason;
import com.streamarr.server.exceptions.MissingMetadataProviderException;
import com.streamarr.server.services.metadata.tmdb.TmdbApiException;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class MetadataFailureReasons {

  private static final int UNAUTHORIZED = 401;
  private static final int FORBIDDEN = 403;

  static ItemFailureReason of(Throwable cause) {
    if (cause instanceof MissingMetadataProviderException) {
      return ItemFailureReason.MISCONFIGURED;
    }

    if (cause instanceof TmdbApiException apiException && rejectsCredentials(apiException)) {
      return ItemFailureReason.MISCONFIGURED;
    }

    return ItemFailureReason.TEMPORARY;
  }

  private static boolean rejectsCredentials(TmdbApiException apiException) {
    return apiException.getStatusCode() == UNAUTHORIZED
        || apiException.getStatusCode() == FORBIDDEN;
  }
}
