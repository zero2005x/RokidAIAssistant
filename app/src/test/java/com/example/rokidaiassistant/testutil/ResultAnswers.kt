package com.example.rokidaiassistant.testutil

import io.mockk.MockKStubScope

/**
 * Stubbing helpers for suspend functions that return [Result].
 *
 * MockK 1.13.16 boxes the answer of a function whose return type is a value class,
 * and [Result] is one. Writing `coEvery { f() } returns Result.success(x)` therefore
 * hands the caller `Result(Result(x))`: `onSuccess` yields a `Result` where a `T` is
 * expected, the implicit cast fails, and the production error path runs instead of
 * the branch under test — with no MockK failure pointing at it.
 *
 * These helpers pass MockK the *underlying* value, so its own boxing produces exactly
 * the single `Result` the caller expects.
 */
private val resultValue = Result::class.java.getDeclaredField("value").apply { isAccessible = true }

/** Stub a suspend function returning `Result<T>` with a successful [value]. */
@Suppress("UNCHECKED_CAST")
infix fun <T, R, B> MockKStubScope<R, B>.returnsSuccess(value: T) {
    answers { value as R }
}

/** Stub a suspend function returning `Result<*>` with a failed result carrying [error]. */
@Suppress("UNCHECKED_CAST")
infix fun <R, B> MockKStubScope<R, B>.returnsFailure(error: Throwable) {
    // Assigning to Any boxes the value class; its wrapped value is the Failure marker.
    val boxed: Any = Result.failure<Any>(error)
    val failure = resultValue.get(boxed)
    answers { failure as R }
}
