package org.bikesched.kotlin

import java.util.UUID
import kotlin.time.Duration
import kotlin.time.toJavaDuration
import org.bikesched.RetryBackoff
import org.bikesched.job.Job
import org.bikesched.job.JobBuilder
import org.bikesched.job.JobFailure
import org.bikesched.job.JobSettings
import org.bikesched.job.RecurringJobBuilder
import org.bikesched.job.Schedule
import org.bikesched.job.TransactionalJobBuilder

// kotlin.time.Duration overloads of the bikesched methods taking a java.time.Duration.

/** Schedules the job to run once [delay] has passed, see `JobBuilder.schedule(Duration)`. */
public fun JobBuilder.schedule(delay: Duration): UUID = schedule(delay.toJavaDuration())

/**
 * Schedules the job in the caller's transaction to run once [delay] has passed, see
 * `TransactionalJobBuilder.schedule(Duration)`.
 */
public fun TransactionalJobBuilder.schedule(delay: Duration): UUID =
  schedule(delay.toJavaDuration())

/**
 * Registers the recurring job with its first run once [delay] has passed, see
 * `RecurringJobBuilder.schedule(Duration)`.
 */
public fun RecurringJobBuilder.schedule(delay: Duration): UUID = schedule(delay.toJavaDuration())

/** Sets the maximum duration of an attempt, see `JobSettings.timeout(Duration)`. */
public fun <B : JobSettings<B>> B.timeout(timeout: Duration): B = timeout(timeout.toJavaDuration())

/** Delays the next attempt, see `JobFailure.retryAfter(Duration)`. */
public fun JobFailure.retryAfter(delay: Duration): JobFailure = retryAfter(delay.toJavaDuration())

/** Moves the deadline of the running attempt, see `Job.updateTimeout(Duration)`. */
public fun Job.updateTimeout(timeout: Duration): Unit = updateTimeout(timeout.toJavaDuration())

/** A fixed-rate schedule running every [interval], see `Schedule.every(Duration)`. */
public fun every(interval: Duration): Schedule = Schedule.every(interval.toJavaDuration())

/** Retries after a fixed [delay], see `RetryBackoff.fixed(Duration)`. */
public fun fixedBackoff(delay: Duration): RetryBackoff = RetryBackoff.fixed(delay.toJavaDuration())

/**
 * Retries after [initialDelay], doubling the delay with every failed attempt up to [maxDelay], see
 * `RetryBackoff.exponential(Duration, Duration)`.
 */
public fun exponentialBackoff(initialDelay: Duration, maxDelay: Duration): RetryBackoff =
  RetryBackoff.exponential(initialDelay.toJavaDuration(), maxDelay.toJavaDuration())
