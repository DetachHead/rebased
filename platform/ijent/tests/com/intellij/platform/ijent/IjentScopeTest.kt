// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.ijent

import com.intellij.platform.eel.EelUnavailableException
import com.intellij.platform.eel.EelUnavailableException.CommunicationFailure
import com.intellij.platform.eel.EelUnavailableException.IntendedExit
import com.intellij.platform.eel.SafeDeferred
import com.intellij.platform.eel.testFramework.bodyLimitedCoroutineScope
import com.intellij.platform.eel.testFramework.executeAndCollectLoggedErrors
import com.intellij.platform.eel.testFramework.executeAndReturnLoggedError
import com.intellij.platform.ijent.spi.IjentThreadPool
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.util.DebugAttachDetectorArgs
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.inspectors.forAll
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveAtLeastSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.kotest.matchers.types.shouldNotBeSameInstanceAs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class IjentScopeTest {
  private fun differentDispatchersTest(body: suspend CoroutineScope.() -> Unit): List<DynamicNode> {
    val timeout =
      if (DebugAttachDetectorArgs.isAttached() && System.getenv("TEAMCITY_VERSION") != null) Duration.INFINITE
      else 20.seconds
    return buildList {
      add(DynamicTest.dynamicTest("blocking dispatcher") {
        timeoutRunBlocking(timeout, "blocking dispatcher") {
          body()
        }
      })

      add(DynamicTest.dynamicTest("single-threaded dispatcher") {
        timeoutRunBlocking(timeout, "single-threaded dispatcher", Dispatchers.IO.limitedParallelism(1)) {
          body()
        }
      })

      add(DynamicTest.dynamicTest("multi-threaded dispatcher") {
        timeoutRunBlocking(timeout, "multi-threaded dispatcher", Dispatchers.IO) {
          body()
        }
      })
    }
  }

  @TestFactory
  fun `the dispatcher is always replaced to IjentThreadPool`() = differentDispatchersTest {
    bodyLimitedCoroutineScope {
      ParentOfIjentScopes(this).createIjentScope("IjentScopeTest").s
        .launch {
          repeat(123) {
            val err = executeAndReturnLoggedError(collectMessagesWithoutExceptions = true) {
              IjentThreadPool.checkCurrentThreadIsInPool()
            }
            if (err != null) {
              throw err
            }
            yield()
          }
        }
        .join()
    }
  }

  @TestFactory
  fun `any failure in any coroutine of ijent terminates the whole session`() = differentDispatchersTest {
    val loggedErrors = mutableListOf<Throwable>()
    lateinit var ijentScope: IjentScope
    val caughtError: IllegalStateException
    executeAndCollectLoggedErrors(loggedErrors, collectMessagesWithoutExceptions = true) {
      caughtError = shouldThrow<IllegalStateException> {
        coroutineScope {
          ijentScope = ParentOfIjentScopes(this).createIjentScope("IjentScopeTest")
          ijentScope.s.launch {
            delay(100.milliseconds)
            error("oops")
          }
        }
      }
    }
    caughtError.message shouldBe "oops"

    // The raw failure is a bug in the session, and a caller gets it inside a copy of the exit reason.
    val rethrownErr = shouldThrow<SafeDeferred.FailedDeferred> {
      ijentScope.toSafeDeferred(ijentScope.s.async { delay(1.seconds) }).await()
    }.cause.shouldBeInstanceOf<CommunicationFailure>().cause
    rethrownErr.shouldBeInstanceOf<IllegalStateException>().message shouldBe "oops"

    // In this test we don't care if this particular error is actually logged or not.
    loggedErrors.removeAll {
      it.javaClass == rethrownErr.javaClass && it.message == rethrownErr.message
    }
    withClue("No unexpected errors logged") {
      loggedErrors.shouldBeEmpty()
    }
  }

  @TestFactory
  fun `a completed child does not hide the exit reason`() = differentDispatchersTest {
    bodyLimitedCoroutineScope {
      val ijentScope = ParentOfIjentScopes(this).createIjentScope("IjentScopeTest")
      val firstChildCanFinish = CompletableDeferred<Unit>()
      // The children run in `IjentThreadPool`. The failing child can cancel them before they start,
      // and then a child with `CoroutineStart.DEFAULT` never runs its body.
      val firstChild = ijentScope.s.launch(start = CoroutineStart.ATOMIC) {
        withContext(NonCancellable) {
          firstChildCanFinish.await()
        }
      }
      val expected = IntendedExit("The session closed", null)
      ijentScope.s.launch(start = CoroutineStart.ATOMIC) {
        try {
          awaitCancellation()
        }
        finally {
          withContext(NonCancellable) {
            firstChild.join()
            delay(50.milliseconds)
            ijentScope.destroy(expected)
          }
        }
      }

      // `resolveExitReason` waits only in a scope that shuts down.
      ijentScope.s.launch {
        error("A failure without destroy")
      }.join()

      val resolved = async(start = CoroutineStart.UNDISPATCHED) {
        ijentScope.resolveExitReason(timeout = 1.seconds)
      }
      firstChildCanFinish.complete(Unit)
      resolved.await().shouldBeInstanceOf<IntendedExit>().cause shouldBeSameInstanceAs expected
    }
  }

  @TestFactory
  fun `resolveExitReason returns null immediately in an alive scope`() = differentDispatchersTest {
    bodyLimitedCoroutineScope {
      val ijentScope = ParentOfIjentScopes(this).createIjentScope("IjentScopeTest")
      val child = ijentScope.s.launch { awaitCancellation() }

      val resolved = withTimeout(1.seconds) {
        ijentScope.resolveExitReason(Duration.INFINITE)
      }
      resolved.shouldBeNull()

      val safeDeferred = ijentScope.toSafeDeferred(CompletableDeferred<Unit>().apply {
        completeExceptionally(IllegalStateException("An ordinary API failure"))
      })
      val thrown = withTimeout(1.seconds) {
        shouldThrow<SafeDeferred.FailedDeferred> { safeDeferred.await() }
      }
      thrown.cause.shouldBeInstanceOf<IllegalStateException>().message shouldBe "An ordinary API failure"

      child.isActive shouldBe true
      ijentScope.destroy(IntendedExit("The test is over", null))
    }
  }

  @TestFactory
  fun `wrapApiSurfaceErrors inside an alive scope rethrows an ordinary failure immediately`() = differentDispatchersTest {
    bodyLimitedCoroutineScope {
      val ijentScope = ParentOfIjentScopes(this).createIjentScope("IjentScopeTest")
      val child = ijentScope.s.launch { awaitCancellation() }

      // Production code calls `wrapApiSurfaceErrors` also in coroutines of the IJent scope, for example in the process readers.
      // The error must not leave the coroutine, because a failed child destroys the whole session.
      val caught = withTimeout(1.seconds) {
        ijentScope.s.async {
          runCatching {
            ijentScope.wrapApiSurfaceErrors<Unit> { throw IllegalStateException("An ordinary API failure") }
          }.exceptionOrNull()
        }.await()
      }
      caught.shouldBeInstanceOf<IllegalStateException>().message shouldBe "An ordinary API failure"

      child.isActive shouldBe true
      ijentScope.destroy(IntendedExit("The test is over", null))
    }
  }

  @TestFactory
  fun `wrapApiSurfaceErrors turns a rogue cancellation into a bug`() = differentDispatchersTest {
    bodyLimitedCoroutineScope {
      val ijentScope = ParentOfIjentScopes(this).createIjentScope("IjentScopeTest")
      val rawDeferred = CompletableDeferred<Unit>().apply { cancel(CancellationException("A raw deferred was cancelled")) }

      val caught = withTimeout(1.seconds) {
        runCatching { ijentScope.wrapApiSurfaceErrors { rawDeferred.await() } }.exceptionOrNull()
      }
      caught.shouldBeInstanceOf<RuntimeException>().message shouldBe "Rogue cancellation exception"
      caught.cause.shouldBeInstanceOf<CancellationException>().message shouldBe "A raw deferred was cancelled"

      ijentScope.destroy(IntendedExit("The test is over", null))
    }
  }

  @TestFactory
  fun `wrapApiSurfaceErrors rethrows a validation error at once while the session is destroying`() = differentDispatchersTest {
    bodyLimitedCoroutineScope {
      val ijentScope = ParentOfIjentScopes(this).createIjentScope("IjentScopeTest")
      // A child that finishes its work without cancellation keeps the resolution waiting, so the state stays `Destroying`.
      val release = ijentScope.holdTheSession()
      ijentScope.destroy(CommunicationFailure("A symptom", null))
      ijentScope.isResolvingExitReason shouldBe true

      val validationError = IllegalArgumentException("must be positive")
      val caught = withTimeout(1.seconds) {
        runCatching { ijentScope.wrapApiSurfaceErrors<Unit> { throw validationError } }.exceptionOrNull()
      }
      caught shouldBeSameInstanceAs validationError

      ijentScope.destroy(IntendedExit("The test is over", null))
      release.complete(Unit)
    }
  }

  @TestFactory
  fun `wrapApiSurfaceErrors rethrows a validation error unchanged in a destroyed session`() = differentDispatchersTest {
    bodyLimitedCoroutineScope {
      val ijentScope = ParentOfIjentScopes(this).createIjentScope("IjentScopeTest")
      ijentScope.destroy(IntendedExit("The session closed", null))

      val validationError = IllegalArgumentException("must be positive")
      val caught = withTimeout(1.seconds) {
        runCatching { ijentScope.wrapApiSurfaceErrors<Unit> { throw validationError } }.exceptionOrNull()
      }
      caught shouldBeSameInstanceAs validationError
    }
  }

  @TestFactory
  fun `resolveExitReason waits for the root cause after destroy by a symptom`() = differentDispatchersTest {
    bodyLimitedCoroutineScope {
      val ijentScope = ParentOfIjentScopes(this).createIjentScope("IjentScopeTest")
      val rootCause = IntendedExit("The root cause", null)
      // The symptom cancels the scope at once. A root cause after it comes from work that ends without cancellation,
      // for example the wait for the exit code of IJent.
      ijentScope.s.launch(start = CoroutineStart.UNDISPATCHED) {
        try {
          awaitCancellation()
        }
        finally {
          withContext(NonCancellable) {
            delay(100.milliseconds)
            ijentScope.destroy(rootCause)
          }
        }
      }

      // In production, gRPC reports UNAVAILABLE before the process watcher reports the exit code of IJent.
      ijentScope.destroy(CommunicationFailure("A symptom", null))

      val resolved = withTimeout(1.seconds) {
        ijentScope.resolveExitReason(Duration.INFINITE)
      }
      resolved.shouldBeInstanceOf<IntendedExit>().cause shouldBeSameInstanceAs rootCause
    }
  }

  @TestFactory
  fun `resolveExitReason gives each caller its own copy of the exit reason`() = differentDispatchersTest {
    bodyLimitedCoroutineScope {
      withContext(CoroutineExceptionHandler { _, _ -> }) {
        supervisorScope {
          val ijentScope = ParentOfIjentScopes(this).createIjentScope("IjentScopeTest")
          val exitReason = CommunicationFailure("The process suddenly exited with the code 9", null)
          ijentScope.destroy(exitReason)

          val first = ijentScope.resolveExitReason().shouldBeInstanceOf<CommunicationFailure>()
          val second = ijentScope.resolveExitReason().shouldBeInstanceOf<CommunicationFailure>()

          // A shared instance would show the stack trace of another caller, and callers could change it concurrently.
          (first === second) shouldBe false
          (first === exitReason) shouldBe false
          first.message shouldBe exitReason.message
          first.cause shouldBeSameInstanceAs exitReason
          second.cause shouldBeSameInstanceAs exitReason
          ijentScope.exitReasonOrNull shouldBeSameInstanceAs exitReason
        }
      }
    }
  }

  @TestFactory
  fun `a bug in the session reaches a caller as CommunicationFailure and the watcher reports it`() = differentDispatchersTest {
    val loggedErrors = Collections.synchronizedList(mutableListOf<Throwable>())
    val uncaught = Collections.synchronizedList(mutableListOf<Throwable>())
    executeAndCollectLoggedErrors(loggedErrors) {
      bodyLimitedCoroutineScope {
        withContext(CoroutineExceptionHandler { _, err -> uncaught += err }) {
          supervisorScope {
            val ijentScope = ParentOfIjentScopes(this).createIjentScope("IjentScopeTest")
            val bug = IllegalStateException("A bug in the session")
            ijentScope.destroy(bug)

            val resolved = ijentScope.resolveExitReason().shouldBeInstanceOf<CommunicationFailure>()
            resolved.cause shouldBeSameInstanceAs bug
            ijentScope.exitReasonOrNull shouldBeSameInstanceAs bug
          }
        }
      }
    }
    // The watcher reports the raw bug one time. The copies of the callers are not reported.
    loggedErrors.single().shouldBeInstanceOf<IllegalStateException>().message shouldBe "A bug in the session"
    uncaught.single().shouldBeInstanceOf<IllegalStateException>().message shouldBe "A bug in the session"
  }

  @TestFactory
  fun `an environment failure as the exit reason is not reported`() = differentDispatchersTest {
    val loggedErrors = Collections.synchronizedList(mutableListOf<Throwable>())
    val uncaught = Collections.synchronizedList(mutableListOf<Throwable>())
    executeAndCollectLoggedErrors(loggedErrors) {
      bodyLimitedCoroutineScope {
        withContext(CoroutineExceptionHandler { _, err -> uncaught += err }) {
          supervisorScope {
            val ijentScope = ParentOfIjentScopes(this).createIjentScope("IjentScopeTest")
            ijentScope.destroy(CommunicationFailure("The container was destroyed", null))
            ijentScope.s.coroutineContext.job.join()
          }
        }
      }
    }
    loggedErrors.shouldBeEmpty()
    uncaught.shouldBeEmpty()
  }

  @TestFactory
  fun `wrapSystemErrors inside an alive scope rethrows the root reason`() = differentDispatchersTest {
    val err = bodyLimitedCoroutineScope {
      withContext(CoroutineExceptionHandler { _, _ -> }) {
        supervisorScope {
          val ijentScope = ParentOfIjentScopes(this).createIjentScope("IjentScopeTest")

          // All timeouts were taken as a wild guess.
          val errorResolutionTimeout = 5.seconds
          val rootCauseDelay = errorResolutionTimeout / 5
          val awaitTimeout = rootCauseDelay + 1.seconds

          ijentScope.s.launch(start = CoroutineStart.ATOMIC) {
            try {
              delay(rootCauseDelay)
            }
            finally {
              ijentScope.destroy(IntendedExit("Good failure", null))
            }
          }

          // The coroutine dies together with the destroyed scope, so its own deferred cannot deliver the result.
          val caught = CompletableDeferred<Throwable?>()
          ijentScope.s.launch {
            try {
              withTimeout(awaitTimeout) {
                ijentScope.wrapSystemErrors(errorResolutionTimeout) {
                  throw IllegalStateException("Unexpected failure")
                }
              }
              caught.complete(null)
            }
            catch (err: Throwable) {
              caught.complete(err)
              throw err
            }
          }
          caught.await()
        }
      }
    }

    err.shouldBeInstanceOf<IntendedExit>().message shouldBe "Good failure"
  }

  @TestFactory
  fun `a later root cause is suppressed in the first root cause`() = differentDispatchersTest {
    val first = IntendedExit("The first root cause", null)
    val second = TestConclusive("The second root cause", null)
    lateinit var ijentScope: IjentScope

    shouldNotThrowAny {
      coroutineScope {
        ijentScope = ParentOfIjentScopes(this).createIjentScope("IjentScopeTest")
        ijentScope.destroy(first)
        ijentScope.destroy(second)
      }
    }

    ijentScope.resolveExitReason() shouldBe first
    first.suppressed.toList() shouldBe listOf(second)
  }

  @TestFactory
  fun `IjentScope rethrows the root cause`() = differentDispatchersTest {
    val rightErrorMessage = "This is the right error (${Random.nextInt()})"

    lateinit var functionThatWorksLikeAnyEelApiMethod: suspend () -> Unit

    shouldNotThrowAny {
      coroutineScope {
        val ijentScope = ParentOfIjentScopes(this).createIjentScope("IjentProcessUtilTest")

        val deferred = ijentScope.s.async {
          delay(10.seconds)
        }

        functionThatWorksLikeAnyEelApiMethod = {
          ijentScope.toSafeDeferred(deferred).await()
        }

        ijentScope.s.launch {
          launch {  // Just a nested coroutine to
            delay(10.milliseconds)
            throw CommunicationFailure("This error should not propagate", null)
          }
        }

        ijentScope.s.launch {
          delay(10.milliseconds)
          throw IllegalStateException("This error should not propagate either", null)
        }

        ijentScope.s.launch(start = CoroutineStart.UNDISPATCHED) {
          try {
            delay(200.milliseconds)
          }
          catch (ex: Throwable) {
            throw ex
          }
          finally {
            val err = IntendedExit(rightErrorMessage, null)
            ijentScope.destroy(err)
            throw CommunicationFailure("And even this error should not propagate", null)
          }
        }
      }
    }

    val errorFromExternalCall = shouldThrow<SafeDeferred.FailedDeferred> {
      functionThatWorksLikeAnyEelApiMethod()
    }.cause
    errorFromExternalCall.shouldBeInstanceOf<IntendedExit>().message shouldBe rightErrorMessage
  }

  @TestFactory
  fun `IjentScope resolveExitReason does not wait longer than its children lifetime`() = differentDispatchersTest {
    val theChildFinished = AtomicBoolean(false)
    val loggedErrors = mutableListOf<Throwable>()

    val ijentScope: IjentScope
    // TestUncaughtExceptionHandler would fail tests without the empty CoroutineExceptionHandler
    withContext(CoroutineExceptionHandler { _, _ -> }) {
      executeAndCollectLoggedErrors(loggedErrors) {
        supervisorScope {
          ijentScope = ParentOfIjentScopes(this).createIjentScope("IjentProcessUtilTest")
          ijentScope.s.launch(start = CoroutineStart.ATOMIC) {
            try {
              error("oops")
            }
            finally {
              theChildFinished.set(true)
            }
          }
        }
      }
    }

    // 1 second timeout should be enough for any glitches. No race with `resolveExitReason` because of the infinite timeout there.
    val resolvedError = withTimeout(1.seconds) {
      ijentScope.resolveExitReason(Duration.INFINITE)
    }
    withClue("A raw failure works like destroy() with a bug, and no child is left to bring a root cause") {
      resolvedError.shouldBeInstanceOf<CommunicationFailure>()
        .cause.shouldBeInstanceOf<IllegalStateException>().message shouldBe "oops"
    }

    loggedErrors.forAll {
      it.message shouldBe "oops"
    }
    // For some reason, the error is logged twice. It's a minor issue.
    loggedErrors shouldHaveAtLeastSize 1
  }

  @TestFactory
  fun `cancellation of IjentScope is prohibited`() = differentDispatchersTest {
    lateinit var ijentScope: IjentScope
    val loggedError = executeAndReturnLoggedError {
      supervisorScope {
        ijentScope = ParentOfIjentScopes(this).createIjentScope("test")
        ijentScope.s.cancel("oops")
      }
    }

    loggedError?.message shouldBe "Cancelling IjentScope is prohibited, use IjentScope.destroy() instead"

    ijentScope.resolveExitReason().shouldBeInstanceOf<IntendedExit>()
  }

  @TestFactory
  fun `cancellation of the parent is not reported as a prohibited cancellation`() = differentDispatchersTest {
    lateinit var ijentScope: IjentScope
    val loggedError = executeAndReturnLoggedError {
      val parentJob = Job()
      ijentScope = ParentOfIjentScopes(CoroutineScope(parentJob)).createIjentScope("test")
      ijentScope.s.launch { awaitCancellation() }
      parentJob.cancelAndJoin()
    }

    loggedError.shouldBeNull()

    ijentScope.resolveExitReason().shouldBeInstanceOf<IntendedExit>()
  }

  @TestFactory
  fun `destroy after the scope completed keeps the exit reason`() = differentDispatchersTest {
    val uncaught = Collections.synchronizedList(mutableListOf<Throwable>())
    val loggedErrors = mutableListOf<Throwable>()
    lateinit var ijentScope: IjentScope
    executeAndCollectLoggedErrors(loggedErrors) {
      val parentJob = SupervisorJob()
      val parent = CoroutineScope(parentJob + CoroutineExceptionHandler { _, err -> uncaught += err })
      ijentScope = ParentOfIjentScopes(parent).createIjentScope("IjentScopeTest")
      ijentScope.s.launch { awaitCancellation() }
      parentJob.cancelAndJoin()

      // In production, gRPC reports UNAVAILABLE after the application has dropped the session.
      ijentScope.destroy(CommunicationFailure("A late failure", null))
    }

    val reason = ijentScope.resolveExitReason()
    reason.shouldBeInstanceOf<IntendedExit>()
    reason.suppressed.toList().shouldBeEmpty()
    loggedErrors.shouldBeEmpty()
    uncaught.shouldBeEmpty()
  }

  @TestFactory
  fun `the copy for a caller keeps the subtype of the exit reason`() = differentDispatchersTest {
    val ijentScope = ParentOfIjentScopes(CoroutineScope(SupervisorJob() + CoroutineExceptionHandler { _, _ -> }))
      .createIjentScope("IjentScopeTest")
    val exitReason = TestConclusive("A known cause", null)
    ijentScope.destroy(exitReason)

    val copy = ijentScope.resolveExitReason().shouldBeInstanceOf<TestConclusive>()
    copy shouldNotBeSameInstanceAs exitReason
    copy.cause shouldBeSameInstanceAs exitReason
    copy.message shouldBe exitReason.message
  }

  @TestFactory
  fun `a subtype without its own copy gets the copy of its kind`() = differentDispatchersTest {
    val ijentScope = ParentOfIjentScopes(CoroutineScope(SupervisorJob() + CoroutineExceptionHandler { _, _ -> }))
      .createIjentScope("IjentScopeTest")
    val exitReason = TestCommunicationFailure("A lost connection")
    ijentScope.destroy(exitReason)

    val copy = ijentScope.resolveExitReason().shouldBeInstanceOf<CommunicationFailure>()
    copy.javaClass shouldBe CommunicationFailure::class.java
    copy.cause shouldBeSameInstanceAs exitReason
    copy.message shouldBe exitReason.message
  }

  @TestFactory
  fun `a Conclusive error after a symptom becomes the exit reason`() = differentDispatchersTest {
    bodyLimitedCoroutineScope {
      val ijentScope = ParentOfIjentScopes(this).createIjentScope("IjentScopeTest")
      // A child that finishes its work without cancellation keeps the resolution waiting.
      val release = ijentScope.holdTheSession()
      val symptom = CommunicationFailure("A lost connection", null)
      val rootCause = TestConclusive("IJent exited", null)

      ijentScope.destroy(symptom)
      ijentScope.destroy(rootCause)

      val resolved = withTimeout(1.seconds) {
        ijentScope.resolveExitReason(Duration.INFINITE)
      }
      resolved.shouldBeInstanceOf<TestConclusive>().cause shouldBeSameInstanceAs rootCause
      release.complete(Unit)
    }
  }

  @TestFactory
  fun `an IntendedExit becomes the exit reason at once`() = differentDispatchersTest {
    bodyLimitedCoroutineScope {
      val ijentScope = ParentOfIjentScopes(this).createIjentScope("IjentScopeTest")
      ijentScope.s.launch { awaitCancellation() }
      val exit = IntendedExit("The session closed", null)

      ijentScope.destroy(exit)

      ijentScope.exitReasonOrNull shouldBeSameInstanceAs exit
    }
  }

  @TestFactory
  fun `a symptom without a root cause becomes the exit reason after the wait and is not reported`() = differentDispatchersTest {
    val loggedErrors = Collections.synchronizedList(mutableListOf<Throwable>())
    val uncaught = Collections.synchronizedList(mutableListOf<Throwable>())
    val symptom = CommunicationFailure("A lost connection", null)
    executeAndCollectLoggedErrors(loggedErrors) {
      bodyLimitedCoroutineScope {
        withContext(CoroutineExceptionHandler { _, err -> uncaught += err }) {
          supervisorScope {
            val ijentScope = ParentOfIjentScopes(this).createIjentScope("IjentScopeTest")
            val release = ijentScope.holdTheSession()

            ijentScope.destroy(symptom)
            ijentScope.exitReasonOrNull.shouldBeNull()

            val resolved = withTimeout(IjentScope.DEAD_SESSION_RESOLVE_TIMEOUT + 2.seconds) {
              ijentScope.resolveExitReason(Duration.INFINITE)
            }
            resolved.shouldBeInstanceOf<CommunicationFailure>().cause shouldBeSameInstanceAs symptom
            release.complete(Unit)
            ijentScope.s.coroutineContext.job.join()
          }
        }
      }
    }
    loggedErrors.shouldBeEmpty()
    uncaught.shouldBeEmpty()
  }

  @TestFactory
  fun `a symptom cancels the session at once and the exit reason waits`() = differentDispatchersTest {
    bodyLimitedCoroutineScope {
      val ijentScope = ParentOfIjentScopes(this).createIjentScope("IjentScopeTest")
      val release = ijentScope.holdTheSession()
      val longLivedChild = ijentScope.s.launch { awaitCancellation() }

      ijentScope.destroy(CommunicationFailure("A lost connection", null))

      withTimeout(1.seconds) {
        longLivedChild.join()
      }
      longLivedChild.isCancelled shouldBe true
      ijentScope.s.coroutineContext.job.isActive shouldBe false
      val bodyStarted = AtomicBoolean(false)
      ijentScope.s.launch { bodyStarted.set(true) }.join()
      bodyStarted.get() shouldBe false
      ijentScope.isResolvingExitReason shouldBe true

      val rootCause = TestConclusive("IJent exited", null)
      ijentScope.destroy(rootCause)
      ijentScope.exitReasonOrNull shouldBeSameInstanceAs rootCause
      release.complete(Unit)
    }
  }

  @TestFactory
  fun `a raw failure makes the session wait for a root cause`() = differentDispatchersTest {
    val loggedErrors = Collections.synchronizedList(mutableListOf<Throwable>())
    val rootCause = TestConclusive("IJent exited", null)
    executeAndCollectLoggedErrors(loggedErrors) {
      bodyLimitedCoroutineScope {
        val ijentScope = ParentOfIjentScopes(this).createIjentScope("IjentScopeTest")
        val release = ijentScope.holdTheSession()

        ijentScope.s.launch { error("A bug in the session") }.join()
        ijentScope.isResolvingExitReason shouldBe true

        ijentScope.destroy(rootCause)
        release.complete(Unit)
        withTimeout(1.seconds) {
          ijentScope.resolveExitReason(Duration.INFINITE)
        }.shouldBeInstanceOf<TestConclusive>().cause shouldBeSameInstanceAs rootCause
      }
    }
    // The root cause won, so the bug is not the exit reason, and the watcher does not report it.
    loggedErrors.shouldBeEmpty()
  }

  /**
   * Starts a child that finishes its work without cancellation, until the returned deferred completes.
   * It works like the wait for the exit code of IJent, so the resolution of the exit reason waits for it.
   */
  private fun IjentScope.holdTheSession(): CompletableDeferred<Unit> {
    val release = CompletableDeferred<Unit>()
    s.launch(start = CoroutineStart.UNDISPATCHED) {
      withContext(NonCancellable) {
        release.await()
      }
    }
    return release
  }

  private class TestConclusive(message: String, cause: Throwable?) : EelUnavailableException.Conclusive(message, cause) {
    override fun copyForCaller(): EelUnavailableException = TestConclusive(message, this)
  }

  private class TestCommunicationFailure(message: String) : CommunicationFailure(message, null)
}
