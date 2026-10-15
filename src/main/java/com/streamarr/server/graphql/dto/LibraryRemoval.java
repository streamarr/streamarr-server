package com.streamarr.server.graphql.dto;

import java.util.UUID;

/** The receipt for a removed library: its id, never the deleted entity (ADR 0042). */
public record LibraryRemoval(UUID libraryId) {}
