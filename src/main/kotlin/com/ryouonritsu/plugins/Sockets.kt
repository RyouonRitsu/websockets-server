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
                send(
                    "You are connected! There are ${connections.count()} users here.\n" +
                            "Please remember your ID is ${thisConnection.id}, others can use this to find you."
                )
                setNick(thisConnection, connections)
                broadcast(thisConnection, connections)
            } catch (e: Exception) {
                println(e.localizedMessage)
            } finally {
                println("Removing $thisConnection!")
                connections -= thisConnection
            }
        }
        webSocket("/whisper") {
            println("Adding user!")
            val thisConnection = Connection(this)
            val customizedConnections = Collections.synchronizedSet<Connection?>(LinkedHashSet())
            connections += thisConnection
            customizedConnections += thisConnection
            try {
                send(
                    "You are connected! There are ${connections.count()} users here.\n" +
                            "Please remember your ID is ${thisConnection.id}, others can use this to find you."
                )
                setNick(thisConnection, connections)
                send(
                    "Now type the ID of the user or a part of his/her nick you want to whisper to." +
                            "Enter '.complete' to end adding users."
                )
                for (frame in incoming) {
                    when (frame) {
                        is Frame.Text -> {
                            val receivedText = frame.readText()
                            if (receivedText == ".complete") {
                                send("Ok, now everything is set up. Type the message you want to send.")
                                break
                            } else {
                                val targetConnection = connections.firstOrNull {
                                    it.id == receivedText.toIntOrNull() || it.nick?.contains(receivedText) == true
                                }
                                when (targetConnection) {
                                    null -> send("User not found. Please try again!")
                                    in customizedConnections -> send("This user is already in this conversation!")
                                    else -> {
                                        customizedConnections += targetConnection
                                        send("User ${targetConnection.nick ?: targetConnection.name} added!")
                                    }
                                }
                            }
                        }
                        else -> send("Only text frames are accepted! Please try again!")
                    }
                }
                broadcast(thisConnection, customizedConnections, whisper = true)
            } catch (e: Exception) {
                println(e.localizedMessage)
            } finally {
                println("Removing $thisConnection!")
                connections -= thisConnection
            }
        }
    }
}

suspend fun DefaultWebSocketServerSession.setNick(thisConnection: Connection, connections: MutableSet<Connection>) {
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
                    val nick = frame.readText()
                    if (connections.none { it.nick == nick }) thisConnection.nick = frame.readText()
                    else {
                        send("Nickname already taken! Please try another one.")
                        continue
                    }
                    send("Everything is ready! Welcome, ${thisConnection.nick}!")
                    break
                }
            }
            else -> {
                send("Only text frames are accepted!${if (flag) "" else " What is your nick?"}")
            }
        }
    }
    if (flag) send("Ok! Welcome!")
}

suspend fun DefaultWebSocketServerSession.broadcast(
    thisConnection: Connection,
    connections: MutableSet<Connection>,
    whisper: Boolean = false
) {
    for (frame in incoming) {
        frame as? Frame.Text ?: continue
        val receivedText = frame.readText()
        val textWithUsername =
            "${if (whisper) "*Whisper* " else ""}[${thisConnection.nick ?: thisConnection.name}]: $receivedText"
        connections.forEach {
            it.session.send(textWithUsername)
        }
    }
}