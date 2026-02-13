package com.example.rokidphone.testutil

import mockwebserver3.MockWebServer
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * JUnit Rule that manages MockWebServer startup and shutdown.
 * Each test method gets a fresh server instance.
 */
class MockWebServerRule : TestRule {

    val server = MockWebServer()

    /** Base URL of the MockWebServer (with trailing slash). */
    val baseUrl: String get() = server.url("/").toString()

    /** Base URL without trailing slash, convenient for path concatenation. */
    val baseUrlNoSlash: String get() = baseUrl.trimEnd('/')

    override fun apply(base: Statement, description: Description): Statement {
        return object : Statement() {
            override fun evaluate() {
                server.start()
                try {
                    base.evaluate()
                } finally {
                    server.shutdown()
                }
            }
        }
    }
}
