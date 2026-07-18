package com.tneff.cyppieagents.firstrun

/** jvm: live-by-default with an env kill-switch — ON unless `CYP_FIRST_RUN` is explicitly `false`. */
actual fun firstRunGateEnabled(): Boolean =
    System.getenv("CYP_FIRST_RUN")?.equals("false", ignoreCase = true) != true
