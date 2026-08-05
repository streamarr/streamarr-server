package com.streamarr.server.services.events.library;

import java.util.Set;
import java.util.UUID;

public record LibraryRemovedEvent(String filepathUri, Set<UUID> mediaFileIds) {}
