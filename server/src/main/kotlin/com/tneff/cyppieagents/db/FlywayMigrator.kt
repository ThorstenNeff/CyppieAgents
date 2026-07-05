package com.tneff.cyppieagents.db

import org.flywaydb.core.Flyway
import javax.sql.DataSource

/**
 * CYP-220 Phase 3 — runs a store's versioned SQL migrations against a runtime-supplied [DataSource]
 * (Design §4.1). `baselineOnMigrate` lets a **non-empty BYO schema** (a user DB with unrelated tables) be
 * baselined so only our migrations apply; idempotent (re-running on an up-to-date DB is a no-op). One call
 * per newly-bound instance, at store construction.
 */
object FlywayMigrator {
    /** Migrate [dataSource] from the SQL scripts under [location] (e.g. `classpath:db/migration/projectregistry`). */
    fun migrate(dataSource: DataSource, location: String) {
        Flyway.configure()
            .dataSource(dataSource)
            .locations(location)
            .baselineOnMigrate(true)
            .load()
            .migrate()
    }
}
