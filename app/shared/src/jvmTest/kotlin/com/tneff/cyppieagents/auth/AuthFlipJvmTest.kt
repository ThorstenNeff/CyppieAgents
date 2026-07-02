package com.tneff.cyppieagents.auth

import kotlin.test.Test
import kotlin.test.assertIs

/**
 * CYP-182 flip — proves the LIVE branch with the DEFAULT factory actually produces an [HttpAuthRepository]
 * (the reviewer's "flag+URLs → HttpAuthRepository" end of the selection). Kept in jvmTest because the default
 * factory builds a real HttpClient (default engine = CIO on the JVM). The stub/misconfigured branches +
 * resolveAuthMode are covered in the common `AuthFlipTest`.
 */
class AuthFlipJvmTest {

    @Test
    fun liveMode_defaultFactory_buildsHttpAuthRepository() {
        val repo = authRepositoryFor(
            AuthMode.Live("http://127.0.0.1:8787", "http://127.0.0.1:8088/.ory/kratos/public"),
        )
        assertIs<HttpAuthRepository>(repo)
    }
}
