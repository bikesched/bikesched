// SPDX-License-Identifier: Apache-2.0
package org.bikesched.examples.spring;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * An order to be fulfilled; marked fulfilled once its job received the fulfillment event, or failed
 * by the job's failure handler.
 */
@Entity
@Table(name = "orders")
public class Order {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  private String customer;
  private String item;
  private boolean fulfilled;
  private boolean failed;

  protected Order() {}

  public Order(String customer, String item) {
    this.customer = customer;
    this.item = item;
  }

  public UUID getId() {
    return id;
  }

  public String getCustomer() {
    return customer;
  }

  public String getItem() {
    return item;
  }

  public boolean isFulfilled() {
    return fulfilled;
  }

  public void markFulfilled() {
    this.fulfilled = true;
  }

  public boolean isFailed() {
    return failed;
  }

  public void markFailed() {
    this.failed = true;
  }
}
