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
      String suffix = " while step 'outer' runs: steps, event waits and sleeps cannot be nested";
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
                      + "steps, event waits and sleeps cannot be nested'"))
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
  void stepWithRunnableHasNoResultAndIsNotExecutedAgainWhenJobIsRetried() throws Exception {
    try (Bikesched bikesched = testNode("node-step-runnable").build()) {
      var mailCalls = new java.util.concurrent.atomic.AtomicInteger();
      UUID jobId =
          bikesched
              .newJob("runnable-step")
              .handler(
                  job ->
                      job.step(
                          "mail",
                          () -> {
                            if (mailCalls.incrementAndGet() == 1) {
                              throw new RuntimeException("mail server down");
                            }
                          }))
              .schedule();

      awaitState(jobId, "SUCCEEDED");

      assertThat(mailCalls).hasValue(2);
      assertThat(
              countRows(
                  "bikesched_steps",
                  "job_id = '"
                      + jobId
                      + "' and name = 'mail' and state = 'SUCCEEDED' "
                      + "and attempts = 2 and result is null"))
          .isEqualTo(1);
    }
  }

  @Test
  void stepWithRunnableRejectsNestedSteps() throws Exception {
    try (Bikesched bikesched = testNode("node-step-runnable-nested").build()) {
      var rejected = new java.util.concurrent.CompletableFuture<Throwable>();
      UUID jobId =
          bikesched
              .newJob("runnable-step-nested")
              .maxAttempts(1)
              .handler(
                  job ->
                      job.step(
                          "outer",
                          () -> {
                            try {
                              job.step("inner", String.class, _ -> "\"inner\"");
                            } catch (Exception e) {
                              rejected.complete(e);
                            }
                          }))
              .schedule();

      assertThat(rejected.get(10, java.util.concurrent.TimeUnit.SECONDS))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage(
              "Job "
                  + jobId
                  + " cannot run step 'inner' while step 'outer' runs: "
                  + "steps, event waits and sleeps cannot be nested");
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

  @Test
  void failureHandlerRunsOnlyAfterAllAttemptsAreUsedUp() throws Exception {
    try (Bikesched bikesched = testNode("node-step-failure-handler").build()) {
      var handled = new java.util.concurrent.CopyOnWriteArrayList<String>();
      var states = new java.util.concurrent.CopyOnWriteArrayList<String>();
      UUID jobId =
          bikesched
              .newJob("step-failure-handler")
              .maxAttempts(2)
              .handler(
                  job ->
                      job.newStep("charge", String.class)
                          .onFailure(
                              failing -> {
                                states.add(stepState(job.id(), "charge"));
                                handled.add(
                                    failing.jobId()
                                        + " "
                                        + failing.name()
                                        + ": "
                                        + failing.error());
                              })
                          .run(
                              step -> {
                                throw new IllegalStateException("declined");
                              }))
              .schedule();

      awaitState(jobId, "FAILED");

      assertThat(handled)
          .as("the stored error, not the exception")
          .containsExactly(jobId + " charge: java.lang.IllegalStateException: declined");
      assertThat(states).as("FAILING while the handler runs").containsExactly("FAILING");
      assertThat(stepState(jobId, "charge")).isEqualTo("FAILED");
      assertThat(
              count(
                  "id = '"
                      + jobId
                      + "' and last_error = 'java.lang.IllegalStateException: declined'"))
          .as("the job fails with the step's exception")
          .isEqualTo(1);
    }
  }

  @Test
  void failureHandlerThatThrowsRunsAgainWithTheStoredError() throws Exception {
    try (Bikesched bikesched = testNode("node-step-failure-handler-retry").build()) {
      var errors = new java.util.concurrent.CopyOnWriteArrayList<String>();
      UUID jobId =
          bikesched
              .newJob("step-failure-handler-retry")
              .maxAttempts(1)
              .handler(
                  job ->
                      job.newStep("charge", String.class)
                          .onFailure(
                              failing -> {
                                errors.add(failing.error());
                                if (errors.size() == 1) {
                                  throw new java.io.IOException("database down");
                                }
                              })
                          .run(
                              step -> {
                                throw new IllegalStateException("declined");
                              }))
              .schedule();

      awaitState(jobId, "FAILED");

      assertThat(errors)
          .as("handler ran again in an extra attempt, with the same error")
          .containsExactly(
              "java.lang.IllegalStateException: declined",
              "java.lang.IllegalStateException: declined");
      assertThat(stepState(jobId, "charge")).isEqualTo("FAILED");
      assertThat(count("id = '" + jobId + "' and attempts = 2")).isEqualTo(1);
    }
  }

  @Test
  void failureHandlerIsGivenUpAfterFailureHandlerAttempts() throws Exception {
    try (Bikesched bikesched =
        testNode("node-step-failure-handler-give-up").failureHandlerAttempts(2).build()) {
      var calls = new java.util.concurrent.atomic.AtomicInteger();
      UUID jobId =
          bikesched
              .newJob("step-failure-handler-give-up")
              .maxAttempts(1)
              .handler(
                  job ->
                      job.newStep("charge", String.class)
                          .onFailure(
                              failing -> {
                                calls.incrementAndGet();
                                throw new java.io.IOException("database down");
                              })
                          .run(
                              step -> {
                                throw new IllegalStateException("declined");
                              }))
              .schedule();

      awaitState(jobId, "FAILED");

      assertThat(calls).as("two runs, then given up").hasValue(2);
      assertThat(stepState(jobId, "charge")).isEqualTo("FAILED");
      assertThat(count("id = '" + jobId + "' and attempts = 2"))
          .as("one extra attempt for the second run")
          .isEqualTo(1);
    }
  }

  @Test
  void failureHandlerResolvesTheStepAndTheJobContinues() throws Exception {
    try (Bikesched bikesched = testNode("node-step-failure-handler-resolve").build()) {
      var rates = new java.util.concurrent.CopyOnWriteArrayList<String>();
      UUID jobId =
          bikesched
              .newJob("step-failure-handler-resolve")
              .maxAttempts(1)
              .handler(
                  job -> {
                    String rate =
                        job.newStep("fetch-rate", String.class)
                            .onFailure(failing -> failing.resolve("\"1.0\""))
                            .run(
                                step -> {
                                  throw new java.io.IOException("rates down");
                                });
                    rates.add(rate);
                    if (rates.size() == 1) {
                      job.sleep("replay", java.time.Duration.ofMillis(10));
                    }
                  })
              .schedule();

      awaitState(jobId, "SUCCEEDED");

      assertThat(rates)
          .as("the fallback, also on the replay after the sleep")
          .containsExactly("\"1.0\"", "\"1.0\"");
      assertThat(stepState(jobId, "fetch-rate")).isEqualTo("SUCCEEDED");
      assertThat(
              countRows(
                  "bikesched_steps",
                  "job_id = '"
                      + jobId
                      + "' and name = 'fetch-rate' and result = '\"1.0\"'::jsonb"
                      + " and not compensable"
                      + " and last_error = 'java.io.IOException: rates down'"))
          .isEqualTo(1);
    }
  }

  @Test
  void resolvingWinsOverAnExceptionThrownAfterwards() throws Exception {
    try (Bikesched bikesched = testNode("node-step-failure-handler-resolve-throw").build()) {
      UUID jobId =
          bikesched
              .newJob("step-failure-handler-resolve-throw")
              .maxAttempts(1)
              .handler(
                  job ->
                      job.newStep("fetch-rate", String.class)
                          .onFailure(
                              failing -> {
                                failing.resolve("\"1.0\"");
                                throw new IllegalStateException("after resolving");
                              })
                          .run(
                              step -> {
                                throw new java.io.IOException("rates down");
                              }))
              .schedule();

      awaitState(jobId, "SUCCEEDED");
      assertThat(stepState(jobId, "fetch-rate")).isEqualTo("SUCCEEDED");
    }
  }

  @Test
  void aResolvedStepIsNotCompensated() throws Exception {
    try (Bikesched bikesched = testNode("node-step-failure-handler-no-compensation").build()) {
      var compensated = new java.util.concurrent.CopyOnWriteArrayList<String>();
      UUID jobId =
          bikesched
              .newJob("step-failure-handler-no-compensation")
              .maxAttempts(1)
              .handler(
                  job -> {
                    job.newStep("reserve", String.class)
                        .compensateWith((step, r) -> compensated.add(step.name()))
                        .run(step -> "\"reserved\"");
                    job.newStep("fetch-rate", String.class)
                        .compensateWith((step, r) -> compensated.add(step.name()))
                        .onFailure(failing -> failing.resolve("\"1.0\""))
                        .run(
                            step -> {
                              throw new java.io.IOException("rates down");
                            });
                    job.fail("then failed");
                  })
              .schedule();

      awaitState(jobId, "FAILED");
      assertThat(compensated)
          .as("only the step that ran its side effect")
          .containsExactly("reserve");
    }
  }

  private String stepState(UUID jobId, String name) throws Exception {
    for (String state : List.of("RUNNING", "FAILING", "FAILED", "SUCCEEDED")) {
      if (countRows(
              "bikesched_steps",
              "job_id = '" + jobId + "' and name = '" + name + "' and state = '" + state + "'")
          == 1) {
        return state;
      }
    }
    return null;
  }
}
