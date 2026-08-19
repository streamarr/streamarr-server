package com.streamarr.server.services.library;

/** Every expected reason {@code addLibrary} refuses; each maps to one schema error type. */
public sealed interface AddLibraryRejection {

  record NameRequired() implements AddLibraryRejection {}

  record PathRequired() implements AddLibraryRejection {}

  record PathNotFound() implements AddLibraryRejection {}

  record PathNotDirectory() implements AddLibraryRejection {}

  record PathNotReadable() implements AddLibraryRejection {}

  record PathAlreadyRegistered() implements AddLibraryRejection {}
}
