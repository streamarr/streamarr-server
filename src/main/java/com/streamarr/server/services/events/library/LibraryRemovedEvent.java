package com.streamarr.server.services.events.library;

import java.util.Set;
import java.util.UUID;

public record LibraryRemovedEvent(UUID libraryId, String filepathUri, Set<UUID> mediaFileIds) {}
