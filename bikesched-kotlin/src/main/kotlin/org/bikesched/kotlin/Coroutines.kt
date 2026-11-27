// SPDX-License-Identifier: Apache-2.0
package org.bikesched.kotlin

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job as CoroutineJob
import kotlinx.coroutines.runBlocking

/** The coroutine job of the outermost handler running on this thread; `null` if none runs. */
private val handlerJob = ThreadLocal<CoroutineJob?>()

/**
 * Runs a suspending handler of the DSL (job, failure, step, poll and compensation handlers) to
 * completion on the calling thread, the job's own virtual thread.
 *
 * `runBlocking` keeps the coroutine on that thread: the MDC of the job, the check against nested
 * steps and the `Error` that ends an attempt at an event wait, sleep or poll keep working, and
 * blocking a virtual thread costs no platform thread. An interrupt of the thread (the job's
 * timeout, a stopping node) cancels the coroutine, and `runBlocking` then throws an
 * `InterruptedException`, the same outcome as an interrupted blocking handler.
 *
 * A step, poll or compensation inside a handler runs in a nested `runBlocking`, which takes the
 * interrupt (and clears the thread's interrupt flag). So that the handler's own coroutine is
 * cancelled too, even if it catches the step's exception, the nested call cancels the outermost
 * handler's coroutine job when it is interrupted. The nested coroutine is deliberately no child of
 * that job: a step failure the handler catches must not cancel the handler.
 */
internal fun <R> blocking(block: suspend () -> R): R {
  val outer = handlerJob.get()
  if (outer == null) {
    val handler = CoroutineJob()
    handlerJob.set(handler)
    try {
      return runBlocking(handler) { block() }
    } finally {
      handlerJob.remove()
      handler.cancel()
    }
  }
  try {
    return runBlocking { block() }
  } catch (e: InterruptedException) {
    outer.cancel(CancellationException("Job thread interrupted", e))
    throw e
  }
}
