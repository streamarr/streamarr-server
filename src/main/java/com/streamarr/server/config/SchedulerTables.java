package com.streamarr.server.config;

import com.github.kagkarlsson.scheduler.SchedulerName;
import com.github.kagkarlsson.scheduler.TaskRepository;
import com.github.kagkarlsson.scheduler.TaskResolver;
import com.github.kagkarlsson.scheduler.event.SchedulerListeners;
import com.github.kagkarlsson.scheduler.jdbc.AutodetectJdbcCustomization;
import com.github.kagkarlsson.scheduler.jdbc.JdbcTaskRepository;
import com.github.kagkarlsson.scheduler.serializer.Serializer;
import java.time.Clock;
import java.util.List;
import javax.sql.DataSource;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.NonNull;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class SchedulerTables {

  /**
   * Reads and writes the executions of one scheduler's table in the scheduler's own transaction,
   * outside the scheduler that runs them.
   */
  @Builder(builderMethodName = "taskRepository")
  static TaskRepository create(
      @NonNull DataSource dataSource,
      @NonNull String tableName,
      @NonNull String name,
      @NonNull Serializer serializer,
      @NonNull Clock clock) {
    return new JdbcTaskRepository(
        new SchedulerTransactionDataSource(dataSource),
        false,
        new AutodetectJdbcCustomization(dataSource),
        tableName,
        new TaskResolver(SchedulerListeners.NOOP, clock::instant, List.of()),
        new SchedulerName.Fixed(name),
        serializer,
        false,
        clock::instant);
  }
}
