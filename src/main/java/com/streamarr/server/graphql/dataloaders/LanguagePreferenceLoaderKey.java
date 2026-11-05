package com.streamarr.server.graphql.dataloaders;

import java.util.UUID;

/** A movie, series, season or episode, seen by one Profile. */
public record LanguagePreferenceLoaderKey(UUID profileId, UUID titleId) {}
