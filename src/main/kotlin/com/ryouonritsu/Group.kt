package com.ryouonritsu

import java.util.concurrent.atomic.AtomicInteger

class Group(val members: MutableSet<Connection>) {
    companion object {
        var lastId = AtomicInteger(0)
    }

    val id = lastId.getAndIncrement()
    val name = "user$id"
}