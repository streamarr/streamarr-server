package com.streamarr.server.repositories;

public interface CompanyRepositoryCustom {

  boolean insertOnConflictDoNothing(String sourceId, String name);
}
