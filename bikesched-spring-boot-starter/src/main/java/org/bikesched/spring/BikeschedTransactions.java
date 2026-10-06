// SPDX-License-Identifier: Apache-2.0
package org.bikesched.spring;

import java.sql.Connection;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The connection of the caller's transaction, so a job scheduled on it is committed and rolled back
 * with the transaction:
 *
 * <pre>{@code
 * @Transactional
 * public void placeOrder(Order order) {
 *   orderRepository.save(order);
 *   Connection connection = BikeschedTransactions.transactionalConnection(dataSource);
 *   bikesched.newJob("send-confirmation").payload(new Confirmation(order.id()))
 *       .connection(connection).schedule();
 * }
 * }</pre>
 *
 * <p>Recurring jobs cannot be scheduled this way: their registration lives in the node's memory.
 *
 * <p>Requires {@code spring-jdbc} and {@code spring-tx} on the classpath (e.g. via {@code
 * spring-boot-starter-jdbc}).
 */
public final class BikeschedTransactions {

  private BikeschedTransactions() {}

  /**
   * Returns the connection the caller's transaction already uses for {@code dataSource}, or {@code
   * null} without one. Call it after the transaction's first database access, when the connection
   * is bound. Pass it to {@link org.bikesched.job.JobBuilder#connection(Connection)}; do not close
   * or commit it, Spring releases it with the transaction.
   *
   * @param dataSource the {@code DataSource} of the node and of the caller's transaction
   * @return the transaction's connection, or {@code null} if no transaction is active for this
   *     {@code DataSource}
   */
  public static Connection transactionalConnection(DataSource dataSource) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || !TransactionSynchronizationManager.hasResource(dataSource)) {
      return null;
    }
    return DataSourceUtils.getConnection(dataSource);
  }
}
