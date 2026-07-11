package com.streamarr.server.repositories;

public interface CompanyRepositoryCustom {

  boolean insertIfAbsent(String sourceId, String name);
}
