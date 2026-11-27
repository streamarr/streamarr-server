package com.streamarr.server.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@Tag("IntegrationTest")
@DisplayName("Statement Count Integration Tests")
class StatementCountIT extends AbstractIntegrationTest {

  @Autowired private StatementCount statementCount;
  @Autowired private DSLContext dsl;
  @Autowired private UserAccountRepository userAccountRepository;
  @Autowired private ProfileRepository profileRepository;

  @Test
  @DisplayName("Should count a jOOQ statement when it runs inside the measured request")
  void shouldCountAJooqStatementWhenItRunsInsideTheMeasuredRequest() throws Exception {
    assertThat(statementCount.statementsWhile(() -> dsl.selectOne().fetch())).isEqualTo(1);
  }

  @Test
  @DisplayName("Should count a Hibernate statement when it runs inside the measured request")
  void shouldCountAHibernateStatementWhenItRunsInsideTheMeasuredRequest() throws Exception {
    assertThat(statementCount.statementsWhile(() -> userAccountRepository.count())).isEqualTo(1);
  }

  @Test
  @DisplayName("Should count a query once when jOOQ builds it and Hibernate runs it")
  void shouldCountAQueryOnceWhenJooqBuildsItAndHibernateRunsIt() throws Exception {
    assertThat(
            statementCount.statementsWhile(
                () -> profileRepository.findAvailableInHousehold(UUID.randomUUID())))
        .isEqualTo(1);
  }
}
