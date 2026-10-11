// SPDX-License-Identifier: Apache-2.0
package org.bikesched.examples.approval;

import static org.awaitility.Awaitility.await;

import java.io.PrintStream;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import org.bikesched.Bikesched;
import org.bikesched.event.EventType;
import org.bikesched.examples.util.BikeschedUtil;
import org.bikesched.job.JobState;
import org.bikesched.job.JobType;

/**
 * A human approval with a reminder and an escalation: an expense waits for the manager's decision;
 * without one in time, the manager gets a reminder, and if that goes unanswered too, the finance
 * lead decides. Each wait for the decision is an event wait with a timeout whose failure handler
 * resolves it with {@code null}, so the job continues with the next stage instead of failing. Runs
 * against the Postgres from {@code compose.yaml} ({@code docker compose up -d}).
 *
 * <p>Run with {@code ./gradlew :bikesched-examples:java:runApproval}; honors {@code POSTGRES_PORT}
 * like the compose file (default 5432).
 */
public final class ApprovalExample {

  /** Gets an expense approved and paid out. */
  public static final JobType<Expense> APPROVE_EXPENSE =
      JobType.of("approve-expense", Expense.class);

  /** A decision on an expense, keyed by the expense id. */
  public static final EventType<Decision> EXPENSE_DECIDED =
      EventType.of("expense-decided", Decision.class);

  /** Who decides when the manager doesn't. */
  public static final String FINANCE_LEAD = "finance-lead";

  /** How long the manager has before the reminder (days in practice). */
  static final Duration REMIND_AFTER = Duration.ofSeconds(2);

  /** How long the manager has after the reminder before the escalation. */
  static final Duration ESCALATE_AFTER = Duration.ofSeconds(2);

  /**
   * The job payload.
   *
   * @param expenseId the expense
   * @param employee who submitted it
   * @param manager who approves it
   * @param amount the amount
   */
  public record Expense(String expenseId, String employee, String manager, BigDecimal amount) {}

  /**
   * A decision, published by the approval UI.
   *
   * @param approver who decided
   * @param approved whether the expense is approved
   */
  public record Decision(String approver, boolean approved) {}

  private static final DateTimeFormatter TIMESTAMP_FORMAT =
      DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

  /**
   * The current time, formatted for the example's console output.
   *
   * @return the formatted time
   */
  static String timestamp() {
    return LocalTime.now().format(TIMESTAMP_FORMAT);
  }

  private final Bikesched bikesched;
  private final SimulatedInbox inbox;

  ApprovalExample(Bikesched bikesched, PrintStream out) {
    this.bikesched = bikesched;
    this.inbox = new SimulatedInbox(out);

    bikesched.registerJobHandler(
        APPROVE_EXPENSE,
        (job, expense) -> {
          String id = expense.expenseId();
          job.step("request-approval", () -> inbox.requestApproval(expense.manager(), expense));
          Decision decision =
              job.waitForEvent("await-decision", EXPENSE_DECIDED, id)
                  .timeout(REMIND_AFTER)
                  .onFailure(failing -> failing.resolve(null)) // no decision in time
                  .receive();

          if (decision == null) {
            job.step("send-reminder", () -> inbox.remind(expense.manager(), expense));
            decision =
                job.waitForEvent("await-decision-after-reminder", EXPENSE_DECIDED, id)
                    .timeout(ESCALATE_AFTER)
                    .onFailure(failing -> failing.resolve(null))
                    .receive();
          }

          if (decision == null) {
            job.step("escalate", () -> inbox.requestApproval(FINANCE_LEAD, expense));
            decision = job.awaitEvent("await-escalated-decision", EXPENSE_DECIDED, id);
          }

          Decision decided = decision;
          job.step(
              decided.approved() ? "pay-out" : "notify-rejection",
              () ->
                  out.printf(
                      "%s [payroll]  %s %s, %s by %s%n",
                      timestamp(),
                      id,
                      decided.approved() ? "paid out " + expense.amount() : "not paid",
                      decided.approved() ? "approved" : "rejected",
                      decided.approver()));
        });
  }

  /**
   * A new expense.
   *
   * @param employee who submits it
   * @param manager who approves it
   * @param amount the amount
   * @return the expense
   */
  static Expense expense(String employee, String manager, BigDecimal amount) {
    return new Expense("expense-" + UUID.randomUUID(), employee, manager, amount);
  }

  /**
   * Submits an expense for approval.
   *
   * @param expense the expense
   * @return the job id
   */
  public UUID submit(Expense expense) {
    return bikesched.newJob(APPROVE_EXPENSE).payload(expense).schedule();
  }

  /**
   * The approval UI: an approver decides on an expense.
   *
   * @param expense the expense
   * @param approver who decides
   * @param approved whether the expense is approved
   */
  public void decide(Expense expense, String approver, boolean approved) {
    bikesched
        .newEvent(EXPENSE_DECIDED, expense.expenseId())
        .payload(new Decision(approver, approved))
        .publish();
  }

  /** The approvers' inbox, e.g. to wait for a reminder. */
  SimulatedInbox inbox() {
    return inbox;
  }

  static void main() {
    try (Bikesched bikesched = BikeschedUtil.bikesched("approval-example")) {
      var example = new ApprovalExample(bikesched, System.out);
      SimulatedInbox inbox = example.inbox();

      // The manager approves right away.
      Expense taxi = expense("alice", "maria", new BigDecimal("42.00"));
      UUID taxiJob = example.submit(taxi);
      await().until(() -> inbox.received("maria", "approval request", taxi));
      example.decide(taxi, "maria", true);

      // The manager rejects after the reminder.
      Expense dinner = expense("bob", "maria", new BigDecimal("380.00"));
      UUID dinnerJob = example.submit(dinner);
      await()
          .atMost(Duration.ofSeconds(10))
          .until(() -> inbox.received("maria", "reminder", dinner));
      example.decide(dinner, "maria", false);

      // The manager doesn't answer: the finance lead approves after the escalation.
      Expense laptop = expense("carol", "tom", new BigDecimal("1899.00"));
      UUID laptopJob = example.submit(laptop);
      await()
          .atMost(Duration.ofSeconds(10))
          .until(() -> inbox.received(FINANCE_LEAD, "approval request", laptop));
      example.decide(laptop, FINANCE_LEAD, true);

      for (UUID jobId : List.of(taxiJob, dinnerJob, laptopJob)) {
        JobState state =
            await()
                .atMost(Duration.ofSeconds(10))
                .until(() -> bikesched.findJob(jobId).orElseThrow().state(), JobState::isEnded);
        System.out.println(timestamp() + " [app]      job " + jobId + " ended " + state);
      }
    }
  }
}
