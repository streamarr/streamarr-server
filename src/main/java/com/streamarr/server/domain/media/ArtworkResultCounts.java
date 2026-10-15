package com.streamarr.server.domain.media;

/**
 * How many artwork results of one target type ended in each outcome; a succeeded result counts as
 * saved.
 */
public record ArtworkResultCounts(long saved, long unavailable, long failed) {}
