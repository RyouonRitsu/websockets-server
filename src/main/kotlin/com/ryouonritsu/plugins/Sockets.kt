package com.ryouonritsu.plugins

import com.ryouonritsu.Connection
import io.ktor.serialization.kotlinx.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import java.time.Duration
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.request.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import java.util.Collections

fun Application.configureSockets() {
    install(WebSockets) {
        pingPeriod = Duration.ofSeconds(15)
        timeout = Duration.ofSeconds(15)
        maxFrameSize = Long.MAX_VALUE
        masking = false
        contentConverter = KotlinxWebsocketSerializationConverter(Json)
    }
    routing {
        val connections = Collections.synchronizedSet<Connection?>(LinkedHashSet())
        webSocket("/chat") {
            println("Adding user!")
            val thisConnection = Connection(this)
            connections += thisConnection
            try {
                send("You are connected! There are ${connections.count()} users here.")
                send("Do you need to set your nick? (y/n)")
                var flag = true
                for (frame in incoming) {
                    when (frame) {
                        is Frame.Text -> {
                            if (flag) {
                                val confirm = frame.readText()
                                if (confirm in listOf("y", "Y", "yes", "Yes", "YES")) {
                                    flag = false
                                    send("What is your nick?")
                                    continue
                                } else break
                            } else {
                                thisConnection.nick = frame.readText()
                                send("Everything is ready! welcome, ${thisConnection.nick}!")
                                break
                            }
                        }
                        else -> {
                            send("Only text frames are accepted!${if (flag) "" else " What is your nick?"}")
                        }
                    }
                }
                if (flag) send("Ok! Welcome!")
                for (frame in incoming) {
                    frame as? Frame.Text ?: continue
                    val receivedText = frame.readText()
                    val textWithUsername = "[${thisConnection.nick ?: thisConnection.name}]: $receivedText"
                    connections.forEach {
                        it.session.send(textWithUsername)
                    }
                }
            } catch (e: Exception) {
                println(e.localizedMessage)
            } finally {
                println("Removing $thisConnection!")
                connections -= thisConnection
            }
        }
    }
}
