package com.streamarr.server.config;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import javax.sql.DataSource;
import lombok.NonNull;
import org.springframework.jdbc.datasource.ConnectionProxy;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;

/** Keeps native scheduler completion inside the surrounding Spring transaction. */
final class SchedulerTransactionDataSource extends TransactionAwareDataSourceProxy {

  SchedulerTransactionDataSource(@NonNull DataSource targetDataSource) {
    super(targetDataSource);
  }

  @Override
  protected Connection getTransactionAwareConnectionProxy(DataSource targetDataSource) {
    var connection = (ConnectionProxy) super.getTransactionAwareConnectionProxy(targetDataSource);
    return (Connection)
        Proxy.newProxyInstance(
            ConnectionProxy.class.getClassLoader(),
            new Class<?>[] {ConnectionProxy.class},
            (proxy, method, arguments) -> {
              if (method.getName().equals("equals")) {
                return proxy == arguments[0];
              }

              if (method.getName().equals("hashCode")) {
                return System.identityHashCode(proxy);
              }

              // db-scheduler's runtime commits even when auto-commit is disabled. Spring owns
              // commit and rollback only while this connection is enlisted in its transaction.
              if (method.getParameterCount() == 0
                  && (method.getName().equals("commit") || method.getName().equals("rollback"))
                  && DataSourceUtils.isConnectionTransactional(
                      connection.getTargetConnection(), targetDataSource)) {
                return null;
              }

              try {
                return method.invoke(connection, arguments);
              } catch (InvocationTargetException exception) {
                throw exception.getCause();
              }
            });
  }
}
