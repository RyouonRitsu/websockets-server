package com.ryouonritsu

import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.collections.LinkedHashSet

class Group {
    companion object {
        var lastId = AtomicInteger(0)
    }

    val id = lastId.getAndIncrement()
    val name = "user$id"
    val members = Collections.synchronizedSet<Connection?>(LinkedHashSet())
}