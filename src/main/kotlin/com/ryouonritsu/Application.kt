package com.ryouonritsu

import com.ryouonritsu.plugins.configureRouting
import com.ryouonritsu.plugins.configureSecurity
import com.ryouonritsu.plugins.configureSockets
import io.ktor.server.application.*

fun main(args: Array<String>): Unit =
    io.ktor.server.netty.EngineMain.main(args)

@Suppress("unused") // application.conf references the main function. This annotation prevents the IDE from marking it as unused.
fun Application.module() {
    configureRouting()
    configureSecurity()
    configureSockets()
}
