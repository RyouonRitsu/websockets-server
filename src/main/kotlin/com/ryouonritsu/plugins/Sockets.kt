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
                connections.filterNot { it == thisConnection }.forEach {
                    it.session.send("[system]: new user '${thisConnection.nick ?: thisConnection.id}' connected!")
                }
                broadcast(thisConnection, connections)
            } catch (e: Exception) {
                println(e.localizedMessage)
            } finally {
                println("Removing $thisConnection!")
                connections -= thisConnection
                connections.forEach {
                    it.session.send("[system]: user '${thisConnection.nick ?: thisConnection.id}' disconnected!")
                }
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
                connections.filterNot { it == thisConnection }.forEach {
                    it.session.send("[system]: new user '${thisConnection.nick ?: thisConnection.id}' connected!")
                }
                send(
                    "Now type the ID of the user or a part of his/her nick you want to whisper to. " +
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
                broadcast(thisConnection, customizedConnections, whisper = true, basedConnections = connections)
            } catch (e: Exception) {
                println(e.localizedMessage)
            } finally {
                println("Removing $thisConnection!")
                connections -= thisConnection
                connections.forEach {
                    it.session.send("[system]: user '${thisConnection.nick ?: thisConnection.id}' disconnected!")
                }
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
    connections: Set<Connection>,
    whisper: Boolean = false,
    basedConnections: Set<Connection> = connections
) {
    var replyMode = false
    var replyTo: Connection? = null
    for (frame in incoming) {
        frame as? Frame.Text ?: continue
        val receivedText = frame.readText()
        if (receivedText.matches(Regex("\\.re.+"))) {
            replyMode = true
            val target = receivedText.substringAfter(".re").trim()
            val targetConnection = basedConnections.firstOrNull {
                it.id == target.toIntOrNull() || it.nick?.contains(target) == true
            }
            when (targetConnection) {
                null -> send("User not found. Please try again!")
                else -> {
                    send(
                        "Now every your message will be sent to ${targetConnection.nick ?: targetConnection.name}, " +
                                "enter '.over' to exit reply mode."
                    )
                    replyTo = targetConnection
                }
            }
        }
        if (receivedText == ".over") replyMode = false
        if (receivedText.matches(Regex("r.+:[\\s\\S]*"))) {
            val (target, message) = receivedText.substringAfter("r").split(":")
            val targetConnection = basedConnections.firstOrNull {
                it.id == target.toIntOrNull() || it.nick?.contains(target) == true
            }
            when (targetConnection) {
                null -> send("User not found. Please try again!")
                else -> targetConnection.session.send("*Whisper* [${thisConnection.nick ?: thisConnection.name}]: $message")
            }
        } else if (replyMode) {
            replyTo?.session?.send("*Whisper* [${thisConnection.nick ?: thisConnection.name}]: $receivedText")
        } else {
            val textWithUsername =
                "${if (whisper) "*Whisper* " else ""}[${thisConnection.nick ?: thisConnection.name}]: $receivedText"
            connections.intersect(basedConnections).forEach {
                it.session.send(textWithUsername)
            }
        }
    }
}