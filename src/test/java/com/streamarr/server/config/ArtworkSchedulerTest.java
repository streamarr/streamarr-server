package com.streamarr.server.config;

import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.github.kagkarlsson.scheduler.boot.config.DbSchedulerCustomizer;
import com.github.kagkarlsson.scheduler.boot.config.DbSchedulerProperties;
import com.streamarr.server.fakes.FakeTransactionManager;
import com.streamarr.server.fixtures.ArtworkFixture;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@Tag("UnitTest")
@DisplayName("Artwork Scheduler Tests")
class ArtworkSchedulerTest {

  @ParameterizedTest
  @ValueSource(
      strings = {
        "schedulerProperties",
        "customizer",
        "dataSource",
        "transactionManager",
        "stepsRunner",
        "requests",
        "tableName",
        "taskName",
        "clock",
        "listeners"
      })
  @DisplayName("Should reject an artwork scheduler when a required collaborator is missing")
  void shouldRejectAnArtworkSchedulerWhenARequiredCollaboratorIsMissing(String missing) {
    var builder = builderWithout(missing);

    assertThatNullPointerException().isThrownBy(builder::build).withMessageContaining(missing);
  }

  @Test
  @DisplayName("Should reject artwork schedulers when the primary scheduler is missing")
  void shouldRejectArtworkSchedulersWhenThePrimarySchedulerIsMissing() {
    var withoutPrimary = ArtworkPrioritySchedulers.builder();

    assertThatNullPointerException()
        .isThrownBy(withoutPrimary::build)
        .withMessageContaining("primary");
  }

  private static ArtworkScheduler.ArtworkSchedulerBuilder builderWithout(String missing) {
    var artwork = ArtworkFixture.builder().build();
    var collaborators =
        Map.<String, Consumer<ArtworkScheduler.ArtworkSchedulerBuilder>>of(
            "schedulerProperties",
                builder -> builder.schedulerProperties(new DbSchedulerProperties()),
            "customizer", builder -> builder.customizer(new DbSchedulerCustomizer() {}),
            "dataSource", builder -> builder.dataSource(new DriverManagerDataSource()),
            "transactionManager",
                builder -> builder.transactionManager(new FakeTransactionManager()),
            "stepsRunner", builder -> builder.stepsRunner(artwork.stepsRunner()),
            "requests", builder -> builder.requests(artwork.requests()),
            "tableName", builder -> builder.tableName("primary_artwork_scheduled_tasks"),
            "taskName", builder -> builder.taskName("primary-artwork-steps"),
            "clock", builder -> builder.clock(Clock.systemUTC()),
            "listeners", builder -> builder.listeners(List.of()));
    var builder = ArtworkScheduler.builder().threads(1);
    collaborators.forEach(
        (name, setIt) -> {
          if (!name.equals(missing)) {
            setIt.accept(builder);
          }
        });
    return builder;
  }
}
