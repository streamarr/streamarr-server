package com.streamarr.server.graphql.subscriptions;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.Library;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.support.ActiveSubscriptions;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.GraphQlTransportWsTestClient;
import com.streamarr.server.support.PostgresLockTestSupport;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Parks a subscription delivery's database read behind a table lock held on another connection, so
 * the subscriber's operation can end, or the read can fail, while the read is in flight. Each test
 * gets a ServerAdmin and a library. The sockets it opens close, and their operations end, before
 * both are deleted.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class AbstractSubscriptionDeliveryLockIntegrationTest extends AbstractIntegrationTest {

  @LocalServerPort private int port;

  @Autowired AuthTestSupport authTestSupport;
  @Autowired MeterRegistry meterRegistry;
  @Autowired DataSource dataSource;
  @Autowired private LibraryRepository libraryRepository;

  private final List<GraphQlTransportWsTestClient> sockets = new ArrayList<>();
  ActiveSubscriptions activeSubscriptions;
  AuthTestSupport.TestIdentity admin;
  Library library;

  @BeforeEach
  void createServerAdminAndLibrary() {
    activeSubscriptions = new ActiveSubscriptions(meterRegistry);
    admin = authTestSupport.createAdminIdentity();
    library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
  }

  @AfterEach
  void releaseResources() {
    sockets.forEach(GraphQlTransportWsTestClient::close);
    activeSubscriptions.awaitNoneActive();
    libraryRepository.deleteById(library.getId());
    authTestSupport.deleteIdentity(admin);
  }

  GraphQlTransportWsTestClient connect() {
    var socket =
        GraphQlTransportWsTestClient.connectWithBearer(port, authTestSupport.profileBearer(admin));
    sockets.add(socket);
    return socket;
  }

  // Neither the library's findById nor a read decision's authority read takes a row lock, so only
  // a table lock parks them.
  HeldTableLock lockTable(String table) throws SQLException {
    var connection = dataSource.getConnection();
    connection.setAutoCommit(false);
    try (var statement = connection.createStatement()) {
      statement.execute("LOCK TABLE " + table + " IN ACCESS EXCLUSIVE MODE");
    } catch (SQLException failure) {
      connection.close();
      throw failure;
    }

    return new HeldTableLock(connection, PostgresLockTestSupport.backendPid(connection));
  }

  record HeldTableLock(Connection connection, int backendPid) implements AutoCloseable {

    @Override
    public void close() throws SQLException {
      try {
        connection.rollback();
      } finally {
        connection.close();
      }
    }
  }
}
