package com.kuthay.p2p

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

object P2pBus {
    val logs = MutableSharedFlow<String>(
        extraBufferCapacity = 512,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val status = MutableStateFlow("IDLE")
    val connected = MutableStateFlow(false)
    var session: P2pSession? = null

    fun log(msg: String) {
        logs.tryEmit(msg)
    }

    fun setStatus(s: String) {
        status.value = s
        log("== $s ==")
    }
}
