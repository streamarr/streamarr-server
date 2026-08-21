package com.streamarr.server.support;

import static com.streamarr.server.support.PostgresLockTestSupport.awaitBlockedBackendPid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("PostgreSQL Lock Test Support Tests")
class PostgresLockTestSupportTest {

  @Test
  @DisplayName("Should return backend PID observed by successful poll when wait ends immediately")
  void shouldReturnBackendPidObservedBySuccessfulPollWhenWaitEndsImmediately() throws SQLException {
    var observer = mock(Connection.class);
    var pollStatement = mock(PreparedStatement.class);
    var followUpStatement = mock(PreparedStatement.class);
    var blockedBackend = mock(ResultSet.class);
    var unblockedBackend = mock(ResultSet.class);
    when(observer.prepareStatement(anyString())).thenReturn(pollStatement, followUpStatement);
    when(pollStatement.executeQuery()).thenReturn(blockedBackend);
    when(blockedBackend.next()).thenReturn(true);
    when(blockedBackend.getInt(1)).thenReturn(42);
    when(followUpStatement.executeQuery()).thenReturn(unblockedBackend);
    when(unblockedBackend.next()).thenReturn(false);

    assertThat(awaitBlockedBackendPid(observer, 7, null)).isEqualTo(42);
  }
}
