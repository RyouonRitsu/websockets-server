package com.ryouonritsu.plugins

import com.ryouonritsu.Connection
import com.ryouonritsu.Group
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
        val users = Collections.synchronizedMap<Int, Connection?>(LinkedHashMap())
        val connections = Collections.synchronizedSet<Connection?>(LinkedHashSet())
        val groups = Collections.synchronizedSet<Group?>(LinkedHashSet())
        webSocket("/") {
            println("Adding user!")
            val thisConnection = Connection(this)
            send("${thisConnection.id}")
            users[thisConnection.id] = thisConnection
        }
        webSocket("/chat") {
            val thisConnection = users[getUserId()]!!
            thisConnection.session = this
            connections += thisConnection
            try {
                pre(thisConnection, connections)
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
            val thisConnection = users[getUserId()]!!
            thisConnection.session = this
            val customizedConnections = mutableSetOf(thisConnection)
            connections += thisConnection
            try {
                pre(thisConnection, connections)
                send(
                    "- Now type the ID of the user or a part of his/her nick you want to whisper to. -\n" +
                            "- Enter '.complete' to end adding users. -\n" +
                            "- Or Enter '.id[Group ID]' to join a group. -"
                )
                var group: Group? = null
                for (frame in incoming) {
                    when (frame) {
                        is Frame.Text -> {
                            val receivedText = frame.readText()
                            if (receivedText == ".complete") {
                                group = Group(customizedConnections)
                                groups += group
                                send("- Ok, now everything is set up. Type the message you want to send. -")
                                break
                            } else if (receivedText.startsWith(".id")) {
                                val groupId = receivedText.substringAfter(".id").trim()
                                group = groups.firstOrNull { it.id == groupId.toIntOrNull() }
                                if (group == null) {
                                    send("- Group with ID '$groupId' does not exist. Please try again! -")
                                    continue
                                } else {
                                    group.members += thisConnection
                                    send("- You have joined group 'g${group.id}'! -")
                                    break
                                }
                            } else {
                                val targetConnection = connections.firstOrNull {
                                    it.id == receivedText.toIntOrNull() || it.nick?.contains(receivedText) == true
                                }
                                when (targetConnection) {
                                    null -> send("- User not found. Please try again! -")
                                    in customizedConnections -> send("- This user is already in this conversation! -")
                                    else -> {
                                        customizedConnections += targetConnection
                                        send("- User ${targetConnection.nick ?: targetConnection.name} added! -")
                                    }
                                }
                            }
                        }
                        else -> send("- Only text frames are accepted! Please try again! -")
                    }
                }
                broadcast(
                    thisConnection,
                    group!!.members,
                    whisper = true,
                    basedConnections = connections,
                    groupId = group.id
                )
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

suspend fun DefaultWebSocketServerSession.getUserId(): Int {
    var userId = -1
    for (frame in incoming) {
        frame as? Frame.Text ?: continue
        userId = frame.readText().toIntOrNull() ?: continue
        break
    }
    return userId
}

suspend fun DefaultWebSocketServerSession.pre(thisConnection: Connection, connections: MutableSet<Connection>) {
    send(
        "- You are connected! There are ${connections.count()} users here. -\n" +
                "- Please remember your ID is ${thisConnection.id}, others can use this to find you. -\n" +
                "- Using 'r[User ID or a part of his/her nick]:[message]' to reply a user. -\n" +
                "- Using '.re[User ID or a part of his/her nick] [...]' to open reply mode. -"
    )
    setNick(thisConnection, connections)
    connections.filterNot { it == thisConnection }.forEach {
        it.session.send("[system]: new user '${thisConnection.nick ?: thisConnection.id}' connected!")
    }
}

suspend fun DefaultWebSocketServerSession.setNick(thisConnection: Connection, connections: MutableSet<Connection>) {
    send("- Do you need to set your nick? (y/n) -")
    var flag = true
    for (frame in incoming) {
        when (frame) {
            is Frame.Text -> {
                if (flag) {
                    val confirm = frame.readText()
                    if (confirm in listOf("y", "Y", "yes", "Yes", "YES")) {
                        flag = false
                        send("- What is your nick? -")
                        continue
                    } else break
                } else {
                    val nick = frame.readText()
                    if (connections.none { it.nick == nick }) thisConnection.nick = frame.readText()
                    else {
                        send("- Nickname already taken! Please try another one. -")
                        continue
                    }
                    send("- Everything is ready! Welcome, ${thisConnection.nick}! -")
                    break
                }
            }
            else -> {
                send("- Only text frames are accepted!${if (flag) "" else " What is your nick?"} -")
            }
        }
    }
    if (flag) send("- Ok! Welcome, ${thisConnection.nick ?: thisConnection.id}! -")
}

suspend fun DefaultWebSocketServerSession.broadcast(
    thisConnection: Connection,
    connections: Set<Connection>,
    whisper: Boolean = false,
    groupId: Int? = null,
    basedConnections: Set<Connection> = connections
) {
    var replyMode = false
    val replyTo = mutableSetOf(thisConnection)
    for (frame in incoming) {
        frame as? Frame.Text ?: continue
        val receivedText = frame.readText()
        if (receivedText.matches(Regex("\\.re.+"))) {
            var failed = false
            receivedText.substringAfter(".re").trim().split(" ").forEach { target ->
                val targetConnection = basedConnections.firstOrNull {
                    it.id == target.toIntOrNull() || it.nick?.contains(target) == true
                }
                when (targetConnection) {
                    null -> {
                        send("- '$target' not found. Please try again! -")
                        failed = true
                        return@forEach
                    }
                    else -> {
                        replyTo += targetConnection
                    }
                }
            }
            if (!failed) {
                replyMode = true
                send(
                    "- Now every your message will be sent to ${
                        replyTo.filterNot { it == thisConnection }.joinToString(", ") { it.nick ?: it.name }
                    } -\n- Enter '.over' to exit reply mode. -"
                )
            } else replyTo.clear()
            continue
        }
        if (receivedText == ".over") {
            replyMode = false
            replyTo.clear()
            send("- Reply mode is over. -")
            continue
        }
        if (receivedText.matches(Regex("r.+:[\\s\\S]*"))) {
            val (target, message) = receivedText.substringAfter("r").split(":")
            val targetConnection = basedConnections.firstOrNull {
                it.id == target.toIntOrNull() || it.nick?.contains(target) == true
            }
            when (targetConnection) {
                null -> send("- User not found. Please try again! -")
                else -> listOf(targetConnection, thisConnection).forEach {
                    it.session.send("*Reply* [${thisConnection.nick ?: thisConnection.name}]: ${message.trim()}")
                }
            }
        } else if (replyMode) {
            replyTo.forEach {
                it.session.send("*Reply* [${thisConnection.nick ?: thisConnection.name}]: ${receivedText.trim()}")
            }
        } else {
            val textWithUsername =
                "${if (whisper) "*g$groupId* " else ""}[${thisConnection.nick ?: thisConnection.name}]: ${receivedText.trim()}"
            connections.intersect(basedConnections).forEach {
                it.session.send(textWithUsername)
            }
        }
    }
}