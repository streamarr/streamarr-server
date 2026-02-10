package com.streamarr.server.repositories;

public interface PersonRepositoryCustom {

  boolean insertIfAbsent(String sourceId, String name);
}
