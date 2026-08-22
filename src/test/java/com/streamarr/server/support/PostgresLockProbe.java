package com.streamarr.server.support;

import jakarta.persistence.EntityManager;
import org.springframework.jdbc.core.JdbcTemplate;

public record PostgresLockProbe(EntityManager entityManager, JdbcTemplate jdbcTemplate) {

  public int currentBackendPid() {
    return ((Number) entityManager.createNativeQuery("SELECT pg_backend_pid()").getSingleResult())
        .intValue();
  }

  public boolean isUserAccountUpdateWaiting(int backendPid) {
    var waiting =
        jdbcTemplate.queryForObject(
            """
            SELECT EXISTS (
              SELECT 1
              FROM pg_stat_activity
              WHERE pid = ?
                AND wait_event_type = 'Lock'
                AND query ILIKE '%update%user_account%'
            )
            """,
            Boolean.class, backendPid);
    return Boolean.TRUE.equals(waiting);
  }
}
