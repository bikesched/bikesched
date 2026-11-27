// SPDX-License-Identifier: Apache-2.0
package org.bikesched.kotlin

import kotlinx.coroutines.runBlocking

/**
 * Runs a suspending handler of the DSL (job, failure, step, poll and compensation handlers) to
 * completion on the calling thread, the job's own virtual thread.
 *
 * `runBlocking` keeps the coroutine on that thread: the MDC of the job, the check against nested
 * steps and the `Error` that ends an attempt at an event wait, sleep or poll keep working, and
 * blocking a virtual thread costs no platform thread. An interrupt of the thread (the job's
 * timeout, a stopping node) cancels the coroutine, and `runBlocking` then throws an
 * `InterruptedException`, the same outcome as an interrupted blocking handler.
 */
@PublishedApi internal fun <R> blocking(block: suspend () -> R): R = runBlocking { block() }
