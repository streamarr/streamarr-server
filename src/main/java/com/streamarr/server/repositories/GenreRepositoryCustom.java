package com.streamarr.server.repositories;

public interface GenreRepositoryCustom {

  boolean insertIfAbsent(String sourceId, String name);
}
