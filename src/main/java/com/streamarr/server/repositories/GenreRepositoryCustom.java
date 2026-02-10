package com.streamarr.server.repositories;

public interface GenreRepositoryCustom {

  boolean insertOnConflictDoNothing(String sourceId, String name);
}
