// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.eel

import org.jetbrains.annotations.ApiStatus
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Thrown when an EEL cannot be accessed or initialized.
 *
 * This exception indicates that the target execution environment (such as a remote machine,
 * Docker container, or WSL instance) is temporarily or permanently unavailable.
 *
 * Common scenarios include:
 * - Docker daemon connection failures
 * - Container not found or stopped
 * - Remote SSH connection issues
 * - Environment-specific setup errors
 *
 * Any method of an Eel API may throw this exception. On a bug in Eel, a method can also throw another exception.
 *
 * The exception should contain a localized user-facing message explaining the specific
 * reason for unavailability, and optionally wrap the underlying cause.
 *
 * The direct subclasses are the kinds of the error: [CommunicationFailure], [Conclusive] and its subclass [IntendedExit].
 * Use an exhaustive `when` over the kinds. Any module can add a subclass of a kind.
 *
 * The constructor takes only the cause, and each kind holds the message.
 * So the constructor does not conflict with the factory function [EelUnavailableException].
 *
 * @param cause Optional underlying exception that caused the unavailability
 */
@ApiStatus.Experimental
sealed class EelUnavailableException(
  override val message: String,
  cause: Throwable?,
) : IOException(message, cause) {

  /**
   * The communication with the environment broke, and a more exact cause can come later.
   * Examples are a lost network connection and a closed channel.
   *
   * The Eel implementation treats it as a symptom. It waits a short time for a [Conclusive] cause, and uses this error only if no such cause comes.
   * A new attempt can succeed, so a retry is reasonable.
   */
  open class CommunicationFailure @ApiStatus.Internal @JvmOverloads constructor(
    message: String,
    cause: Throwable? = null,
  ) : EelUnavailableException(message, cause) {
    @ApiStatus.Internal
    override fun copyForCaller(): EelUnavailableException = CommunicationFailure(message, this)
  }

  /**
   * The cause of the error is known and final.
   * Examples are an exit code of the remote agent and a failed SSH authentication.
   *
   * The Eel implementation treats it as the root cause, and it wins over a [CommunicationFailure] at once.
   * A new attempt can succeed, so a retry is reasonable, unless the error is an [IntendedExit].
   */
  abstract class Conclusive @ApiStatus.Internal constructor(
    message: String,
    cause: Throwable?,
  ) : EelUnavailableException(message, cause)

  /**
   * The environment ended on purpose.
   * Examples are a close by the IDE and an SSH authentication that the user cancelled.
   *
   * This exception means that the user does not need the connection.
   * Automatic reconnection logic should stop trying to recreate the [EelApi] on receiving this exception.
   */
  open class IntendedExit @ApiStatus.Internal constructor(
    message: String,
    cause: Throwable?,
  ) : Conclusive(message, cause) {
    @ApiStatus.Internal
    override fun copyForCaller(): EelUnavailableException = IntendedExit(message, this)
  }

  /**
   * Creates a new exception of the same type and with the same message, and this exception becomes its cause.
   *
   * The Eel implementation gives each caller its own copy of a shared error, so the stack trace shows the caller.
   * A subclass of an open kind that does not override it gets the copy of its kind.
   */
  @ApiStatus.Internal
  abstract fun copyForCaller(): EelUnavailableException

  companion object {
    // TODO Not the best place for these functions.
    @ApiStatus.Internal
    @JvmStatic
    inline fun <T> unwrapFromCancellationExceptions(body: () -> T): T =
      try {
        body()
      }
      catch (initialError: Throwable) {
        throw unwrapFromCancellationExceptions(initialError) ?: initialError
      }

    @ApiStatus.Internal
    @JvmStatic
    fun unwrapFromCancellationExceptions(initialError: Throwable): EelUnavailableException? {
      var err: Throwable? = initialError
      while (true) {
        when (err) {
          is CancellationException -> err = err.cause

          is EelUnavailableException -> return err

          else -> break
        }
      }
      return null
    }
  }
}