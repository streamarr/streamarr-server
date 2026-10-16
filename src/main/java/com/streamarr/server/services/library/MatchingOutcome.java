package com.streamarr.server.services.library;

import com.streamarr.server.domain.media.MatchingFailure;
import lombok.NonNull;

/** How the matching of one media file during a scan or file discovery ended. */
public sealed interface MatchingOutcome {

  /** The file now belongs to a movie or an episode. */
  record Matched() implements MatchingOutcome {}

  /** Matching stopped short of a match, and the file's status shows the failure. */
  record NotMatched(@NonNull MatchingFailure failure) implements MatchingOutcome {}

  /** Matching did not run, so nothing was processed. */
  sealed interface Skipped extends MatchingOutcome {}

  /** The file was already matched, so nothing was matched again. */
  record SkippedAsMatched() implements Skipped {}

  /** Another library holds the file, so this library leaves the file to that library. */
  record SkippedAsAnotherLibrarysFile() implements Skipped {}

  /** Matching was interrupted, and the file keeps its earlier status. */
  record Interrupted() implements MatchingOutcome {}
}
