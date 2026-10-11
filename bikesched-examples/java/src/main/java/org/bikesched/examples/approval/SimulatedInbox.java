// SPDX-License-Identifier: Apache-2.0
package org.bikesched.examples.approval;

import java.io.PrintStream;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.bikesched.examples.approval.ApprovalExample.Expense;

/**
 * The approvers' inbox, simulated in memory: prints each message and keeps it, so the example can
 * wait for a request or reminder before an approver decides.
 */
final class SimulatedInbox {

  /**
   * A message sent to an approver.
   *
   * @param to the approver
   * @param kind {@code approval request} or {@code reminder}
   * @param expenseId the expense
   */
  record Message(String to, String kind, String expenseId) {}

  private final PrintStream out;
  private final List<Message> messages = new CopyOnWriteArrayList<>();

  SimulatedInbox(PrintStream out) {
    this.out = out;
  }

  /**
   * Asks an approver to decide on an expense.
   *
   * @param to the approver
   * @param expense the expense
   */
  void requestApproval(String to, Expense expense) {
    send(to, "approval request", expense);
  }

  /**
   * Reminds an approver of an expense.
   *
   * @param to the approver
   * @param expense the expense
   */
  void remind(String to, Expense expense) {
    send(to, "reminder", expense);
  }

  private void send(String to, String kind, Expense expense) {
    messages.add(new Message(to, kind, expense.expenseId()));
    out.printf(
        "%s [inbox]    %s to %s: %s of %s, %s%n",
        ApprovalExample.timestamp(),
        kind,
        to,
        expense.expenseId(),
        expense.employee(),
        expense.amount());
  }

  /**
   * Whether an approver received a message of a kind for an expense.
   *
   * @param to the approver
   * @param kind {@code approval request} or {@code reminder}
   * @param expense the expense
   * @return whether the message was sent
   */
  boolean received(String to, String kind, Expense expense) {
    return messages.contains(new Message(to, kind, expense.expenseId()));
  }

  /**
   * The messages sent for an expense, in order.
   *
   * @param expense the expense
   * @return the messages
   */
  List<Message> messages(Expense expense) {
    return messages.stream()
        .filter(message -> message.expenseId().equals(expense.expenseId()))
        .toList();
  }
}
