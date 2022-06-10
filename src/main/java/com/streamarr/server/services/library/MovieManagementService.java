package com.streamarr.server.services.library;

// MovieManagementService?
// MovieLibraryManagementService?
public class MovieManagementService {

    // Multiple Methods:
    //     matchMovieFromFileInfo() -> name / year
    //     refreshMovieById()

    // TODO: matchMovieFromFileInfo(FileInfo info)
    // We've determined this is a new file, filename has been parsed. Going to attempt to create a new movie.

    // Searching TMDB, or another service -> [MATCHING]

    // Make a match -> [MATCHED / MATCH_FAILED]
    //     If [MATCHED] -> continue
    //     If [MATCH_FAILED] -> Persist new entity with basic file info, user can manually match

    // On successful match - check DB for duplicates -> [EXISTING / NEW]
    //     If [EXISTING] -> Updated entity and attach file (release)
    //     IF [NEW] -> Persist new entity

    // Retryable Errors - backoff? wait for chron to re-execute?
    //    [TIMEOUT / LIMIT_EXCEEDED]
}
