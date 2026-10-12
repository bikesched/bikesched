// SPDX-License-Identifier: Apache-2.0
package org.bikesched.examples.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.bikesched.Bikesched;
import org.bikesched.examples.approval.ApprovalExample.Expense;
import org.bikesched.examples.approval.SimulatedInbox.Message;
import org.bikesched.examples.util.Database;
import org.bikesched.jackson.JacksonJsonMapper;
import org.bikesched.job.JobState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Runs the approval example's job against a real PostgreSQL. */
class ApprovalExampleTest {

  private final ByteArrayOutputStream output = new ByteArrayOutputStream();
  private Bikesched bikesched;
  private ApprovalExample example;
  private SimulatedInbox inbox;

  @BeforeEach
  void startNode() {
    bikesched =
        Bikesched.builder()
            .dataSource(Database.dataSource())
            .nodeName("approval-" + UUID.randomUUID())
            .pollInterval(Duration.ofMillis(50))
            .jsonMapper(new JacksonJsonMapper())
            .build();
    example = new ApprovalExample(bikesched, new PrintStream(output, true, StandardCharsets.UTF_8));
    inbox = example.inbox();
  }

  @AfterEach
  void closeNode() {
    bikesched.close();
  }

  @Test
  void theManagerDecidesInTime() {
    Expense expense = ApprovalExample.expense("alice", "maria", new BigDecimal("42.00"));
    UUID jobId = example.submit(expense);
    await().until(() -> inbox.received("maria", "approval request", expense));

    example.decide(expense, "maria", true);

    awaitSuccess(jobId);
    assertThat(inbox.messages(expense))
        .containsExactly(message("maria", "approval request", expense));
    assertThat(output()).contains(expense.expenseId() + " paid out 42.00, approved by maria");
  }

  @Test
  void theManagerDecidesAfterTheReminder() {
    Expense expense = ApprovalExample.expense("bob", "maria", new BigDecimal("380.00"));
    UUID jobId = example.submit(expense);
    await().until(() -> inbox.received("maria", "reminder", expense));

    example.decide(expense, "maria", false);

    awaitSuccess(jobId);
    assertThat(inbox.messages(expense))
        .containsExactly(
            message("maria", "approval request", expense), message("maria", "reminder", expense));
    assertThat(output()).contains(expense.expenseId() + " not paid, rejected by maria");
  }

  @Test
  void theFinanceLeadDecidesAfterTheEscalation() {
    Expense expense = ApprovalExample.expense("carol", "tom", new BigDecimal("1899.00"));
    UUID jobId = example.submit(expense);
    await().until(() -> inbox.received(ApprovalExample.FINANCE_LEAD, "approval request", expense));
    // The request is sent before the job suspends at its next wait.
    await()
        .untilAsserted(
            () ->
                assertThat(bikesched.findJob(jobId).orElseThrow().state())
                    .isEqualTo(JobState.WAITING));

    example.decide(expense, ApprovalExample.FINANCE_LEAD, true);

    awaitSuccess(jobId);
    assertThat(inbox.messages(expense))
        .containsExactly(
            message("tom", "approval request", expense),
            message("tom", "reminder", expense),
            message(ApprovalExample.FINANCE_LEAD, "approval request", expense));
    assertThat(output())
        .contains(expense.expenseId() + " paid out 1899.00, approved by finance-lead");
  }

  private void awaitSuccess(UUID jobId) {
    await().until(() -> bikesched.findJob(jobId).orElseThrow().state(), JobState::isEnded);
    assertThat(bikesched.findJob(jobId).orElseThrow().state()).isEqualTo(JobState.SUCCEEDED);
  }

  private static Message message(String to, String kind, Expense expense) {
    return new Message(to, kind, expense.expenseId());
  }

  private String output() {
    return output.toString(StandardCharsets.UTF_8);
  }
}
