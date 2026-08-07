package io.github.yulbax.frkn.vpn

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.koin.core.annotation.Single

sealed interface Command {
    data object Start : Command
    data object Reload : Command
    data object Recover : Command
    data object CheckByeDpi : Command
    data object TestProxies : Command
    data object Stop : Command
}

@Single
class VpnCommandBus {
    private val mutableCommands = MutableSharedFlow<Command>(
        replay = 0,
        extraBufferCapacity = BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val commands: Flow<Command> = mutableCommands.asSharedFlow()

    fun start() { mutableCommands.tryEmit(Command.Start) }
    fun reload() { mutableCommands.tryEmit(Command.Reload) }
    fun recover() { mutableCommands.tryEmit(Command.Recover) }
    fun checkByeDpi() { mutableCommands.tryEmit(Command.CheckByeDpi) }
    fun testProxies() { mutableCommands.tryEmit(Command.TestProxies) }
    fun stop() { mutableCommands.tryEmit(Command.Stop) }

    private companion object {
        const val BUFFER_CAPACITY = 16
    }
}
