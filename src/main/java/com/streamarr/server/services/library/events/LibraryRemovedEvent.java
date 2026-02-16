package com.streamarr.server.services.library.events;

import java.util.Set;
import java.util.UUID;

public record LibraryRemovedEvent(String filepathUri, Set<UUID> mediaFileIds) {}
