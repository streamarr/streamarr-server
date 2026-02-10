package com.streamarr.server.repositories;

public interface PersonRepositoryCustom {

  boolean insertOnConflictDoNothing(String sourceId, String name);
}
