package com.streamarr.server.services.library;

@FunctionalInterface
public interface LibraryWatchTrigger {

  void triggerAsyncWatch(String filepathUri);
}
