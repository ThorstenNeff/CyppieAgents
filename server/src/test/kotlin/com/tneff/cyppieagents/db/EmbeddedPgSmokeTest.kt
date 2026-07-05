package com.tneff.cyppieagents.db

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import kotlin.test.Test
import kotlin.test.assertEquals

/** CYP-220 Phase 3 — de-risk the embedded-Postgres test infra (a real PG binary in-process, no Docker). */
class EmbeddedPgSmokeTest {
    @Test fun embeddedPostgres_startsAndSelects1() {
        EmbeddedPostgres.start().use { pg ->
            pg.postgresDatabase.connection.use { c ->
                c.createStatement().use { st ->
                    st.executeQuery("SELECT 1").use { rs ->
                        rs.next()
                        assertEquals(1, rs.getInt(1))
                    }
                }
            }
        }
    }
}
