package com.tneff.cyppieagents

// No ambient environment on this target — use the local dev default (entry points may override).
actual fun defaultShellConfig(): ShellConfig = ShellConfig.dev()
