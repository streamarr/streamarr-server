package com.streamarr.server.domain.media;

/** How many artwork results of one item type ended in each outcome. */
public record ArtworkOutcomeTally(long succeeded, long unavailable, long failed) {}
