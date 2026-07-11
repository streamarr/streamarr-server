package com.streamarr.server.graphql.dto;

import java.util.UUID;

public record SelectableProfile(UUID id, String name, boolean active) {}
