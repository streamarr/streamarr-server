package com.streamarr.server.services;

import com.streamarr.server.services.ArtworkResult.Failed;
import com.streamarr.server.services.ArtworkResult.Saved;
import com.streamarr.server.services.ArtworkResult.Skipped;
import com.streamarr.server.services.ArtworkResult.Unavailable;
import java.util.List;
import lombok.Builder;

/** Finished source images by result; resized variants of one source image count once. */
@Builder(toBuilder = true)
public record ArtworkCounts(int saved, int skipped, int unavailable, int failed) {

  static final ArtworkCounts NONE = ArtworkCounts.builder().build();

  ArtworkCounts plus(List<ArtworkResult> results) {
    var counts = this;
    for (var result : results) {
      counts = counts.plus(result);
    }

    return counts;
  }

  private ArtworkCounts plus(ArtworkResult result) {
    return switch (result) {
      case Saved _ -> toBuilder().saved(saved + 1).build();
      case Skipped _ -> toBuilder().skipped(skipped + 1).build();
      case Unavailable _ -> toBuilder().unavailable(unavailable + 1).build();
      case Failed _ -> toBuilder().failed(failed + 1).build();
    };
  }
}
