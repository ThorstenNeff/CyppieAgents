package com.tneff.cyppieagents

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform