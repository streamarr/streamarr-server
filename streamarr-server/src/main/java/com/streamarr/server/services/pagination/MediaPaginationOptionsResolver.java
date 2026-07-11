package com.streamarr.server.services.pagination;

import java.util.function.BiConsumer;
import java.util.function.Function;

public final class MediaPaginationOptionsResolver {

  private MediaPaginationOptionsResolver() {}

  public static MediaPaginationOptions resolve(
      PaginationOptions paginationOptions,
      MediaFilter filter,
      Function<PaginationOptions, MediaPaginationOptions> decoder,
      BiConsumer<MediaPaginationOptions, MediaFilter> validator) {

    if (paginationOptions.getCursor().isEmpty()) {
      return MediaPaginationOptions.builder()
          .paginationOptions(paginationOptions)
          .mediaFilter(filter)
          .build();
    }

    var decoded = decoder.apply(paginationOptions);
    validator.accept(decoded, filter);
    return decoded;
  }
}
