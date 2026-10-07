// SPDX-License-Identifier: Apache-2.0
package org.bikesched.job;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.bikesched.AbstractBikeschedTest;
import org.bikesched.Bikesched;
import org.bikesched.BikeschedLogContext;
import org.junit.jupiter.api.Test;

/** Durable {@link Job#step(String, Class, StepHandler) steps} of a job. */
class StepTest extends AbstractBikeschedTest {

  @Test
  void completedStepsAreNotExecutedAgainWhenJobIsRetried() throws Exception {
    try (Bikesched bikesched = testNode("node-steps").build()) {
      var chargeCalls = new java.util.concurrent.atomic.AtomicInteger();
      var mailCalls = new java.util.concurrent.atomic.AtomicInteger();
      var mailedPayments = new java.util.concurrent.CopyOnWriteArrayList<String>();
      UUID jobId =
          bikesched
              .newJob("order")
              .handler(
                  job -> {
                    String payment =
                        job.step(
                            "charge",
                            String.class,
                            step -> {
                              chargeCalls.incrementAndGet();
                              return "{\"payment\": " + chargeCalls.get() + "}";
                            });
                    job.step(
                        "mail",
                        String.class,
                        step -> {
                          if (mailCalls.incrementAndGet() == 1) {
                            throw new java.io.IOException("mail server down");
                          }
                          mailedPayments.add(payment);
                          return null;
                        });
                  })
              .schedule();

      awaitState(jobId, "SUCCEEDED");

      assertThat(count("id = '" + jobId + "' and attempts = 2"))
          .as("job retried once")
          .isEqualTo(1);
      assertThat(chargeCalls).as("charged only once").hasValue(1);
      assertThat(mailCalls).hasValue(2);
      assertThat(mailedPayments)
          .as("stored result reused on retry")
          .containsExactly("{\"payment\": 1}");
      assertThat(
              countRows(
                  "bikesched_steps",
                  "job_id = '"
                      + jobId
                      + "' and name = 'charge' and state = 'SUCCEEDED' "
                      + "and attempts = 1 and result = '{\"payment\": 1}'::jsonb"))
          .isEqualTo(1);
      assertThat(
              countRows(
                  "bikesched_steps",
                  "job_id = '"
                      + jobId
                      + "' and name = 'mail' and state = 'SUCCEEDED' "
                      + "and attempts = 2 and result is null and last_error = 'java.io.IOException: mail server down'"))
          .isEqualTo(1);
    }
  }

  @Test
  void stepReceivesStepContextAndLogContext() throws Exception {
    try (Bikesched bikesched = testNode("node-step-context").build()) {
      var seen = new java.util.concurrent.CompletableFuture<String>();
      UUID jobId =
          bikesched
              .newJob("step-context")
              .handler(
                  job -> {
                    String inStep =
                        job.step(
                            "context",
                            String.class,
                            step ->
                                "\""
                                    + step.jobId()
                                    + " "
                                    + step.name()
                                    + " "
                                    + step.attempts()
                                    + " "
                                    + org.slf4j.MDC.get(BikeschedLogContext.STEP_NAME)
                                    + "\"");
                    seen.complete(
                        inStep + " / " + org.slf4j.MDC.get(BikeschedLogContext.STEP_NAME));
                  })
              .schedule();

      assertThat(seen.get(10, java.util.concurrent.TimeUnit.SECONDS))
          .isEqualTo("\"" + jobId + " context 1 context\" / null");
    }
  }

  @Test
  void stepsAndEventWaitsCannotBeNestedInAStep() throws Exception {
    try (Bikesched bikesched = testNode("node-step-nested").build()) {
      var rejected = new java.util.concurrent.CopyOnWriteArrayList<String>();
      UUID jobId =
          bikesched
              .newJob("step-nested")
              .maxAttempts(1)
              .handler(
                  job -> {
                    var builtOutside = job.newStep("built-outside", String.class);
                    var waitOutside = job.waitForEvent("wait-outside", "nested", "key-1");
                    List<java.util.concurrent.Callable<?>> nested =
                        List.of(
                            () -> job.step("inner", String.class, _ -> "\"inner\""),
                            () -> job.newStep("inner-builder", String.class),
                            () -> builtOutside.run(_ -> "\"inner\""),
                            () -> job.waitForEvent("inner-wait", "nested", "key-1"),
                            () -> waitOutside.receive(String.class),
                            () ->
                                java.util.concurrent.CompletableFuture.supplyAsync(
                                        () -> {
                                          try {
                                            return job.step(
                                                "other-thread", String.class, _ -> "\"inner\"");
                                          } catch (Exception e) {
                                            throw new java.util.concurrent.CompletionException(e);
                                          }
                                        })
                                    .join());
                    job.step(
                        "outer",
                        String.class,
                        _ -> {
                          for (var call : nested) {
                            try {
                              call.call();
                              rejected.add("not rejected");
                            } catch (Exception e) {
                              Throwable cause =
                                  e instanceof java.util.concurrent.CompletionException
                                      ? e.getCause()
                                      : e;
                              rejected.add(
                                  cause instanceof IllegalStateException
                                      ? cause.getMessage()
                                      : cause.toString());
                            }
                          }
                          return "\"outer\"";
                        });
                    job.step("after", String.class, _ -> "\"after\"");
                  })
              .schedule();

      awaitState(jobId, "SUCCEEDED");

      String job = "Job " + jobId + " cannot ";
      String suffix = " while step 'outer' runs: steps and event waits cannot be nested";
      assertThat(rejected)
          .containsExactly(
              job + "run step 'inner'" + suffix,
              job + "create step 'inner-builder'" + suffix,
              job + "run step 'built-outside'" + suffix,
              job + "wait for an event as step 'inner-wait'" + suffix,
              job + "wait for an event as step 'wait-outside'" + suffix,
              job + "run step 'other-thread'" + suffix);
      assertThat(countRows("bikesched_steps", "job_id = '" + jobId + "'"))
          .as("only the outer step and the one after it are stored")
          .isEqualTo(2);
    }
  }

  @Test
  void nestedStepFailsTheOuterStep() throws Exception {
    try (Bikesched bikesched = testNode("node-step-nested-fails").build()) {
      UUID jobId =
          bikesched
              .newJob("step-nested-fails")
              .maxAttempts(1)
              .handler(
                  job ->
                      job.step(
                          "outer",
                          String.class,
                          _ -> job.step("inner", String.class, _ -> "\"inner\"")))
              .schedule();

      awaitState(jobId, "FAILED");

      assertThat(
              countRows(
                  "bikesched_steps",
                  "job_id = '"
                      + jobId
                      + "' and name = 'outer' and state = 'FAILED' and last_error = "
                      + "'java.lang.IllegalStateException: Job "
                      + jobId
                      + " cannot run step ''inner'' while step ''outer'' runs: "
                      + "steps and event waits cannot be nested'"))
          .isOne();
      assertThat(countRows("bikesched_steps", "job_id = '" + jobId + "' and name = 'inner'"))
          .isZero();
    }
  }

  @Test
  void stepWithInvalidJsonResultFailsTheJobAttempt() throws Exception {
    try (Bikesched bikesched = testNode("node-step-invalid").build()) {
      UUID jobId =
          bikesched
              .newJob("step-invalid")
              .maxAttempts(1)
              .handler(job -> job.step("broken", String.class, step -> "not json"))
              .schedule();

      awaitState(jobId, "FAILED");

      assertThat(
              countRows(
                  "bikesched_steps",
                  "job_id = '"
                      + jobId
                      + "' and name = 'broken' and state = 'FAILED' "
                      + "and last_error like 'org.bikesched.BikeschedException: Storing the result of step%'"))
          .isEqualTo(1);
      assertThat(
              count(
                  "id = '"
                      + jobId
                      + "' and last_error like '%Storing the result of step ''broken''%'"))
          .isEqualTo(1);
    }
  }

  @Test
  void stepNameMustNotBeBlank() throws Exception {
    try (Bikesched bikesched = testNode("node-step-blank").build()) {
      var failure = new java.util.concurrent.CompletableFuture<Throwable>();
      bikesched
          .newJob("step-blank")
          .maxAttempts(1)
          .handler(
              job -> {
                try {
                  job.step(" ", String.class, step -> null);
                } catch (IllegalArgumentException e) {
                  failure.complete(e);
                }
              })
          .schedule();

      assertThat(failure.get(10, java.util.concurrent.TimeUnit.SECONDS))
          .hasMessage("step name must not be blank");
    }
  }

  @Test
  void stepResultThatCannotBeMappedFailsTheStep() throws Exception {
    try (Bikesched bikesched = testNode("node-step-unmappable").build()) {
      UUID jobId =
          bikesched
              .newJob("step-unmappable")
              .maxAttempts(1)
              .handler(job -> job.step("unmappable", Integer.class, step -> 42))
              .schedule();

      awaitState(jobId, "FAILED");

      assertThat(
              countRows(
                  "bikesched_steps",
                  "job_id = '"
                      + jobId
                      + "' and name = 'unmappable' "
                      + "and state = 'FAILED' and last_error like 'java.lang.IllegalArgumentException: Without a configured JsonMapper%'"))
          .isEqualTo(1);
    }
  }
}
