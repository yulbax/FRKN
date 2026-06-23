package io.github.yulbax.frkn.vpn

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
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
    private val channel = Channel<Command>(Channel.UNLIMITED)
    val commands: Flow<Command> = channel.receiveAsFlow()

    fun start() { channel.trySend(Command.Start) }
    fun reload() { channel.trySend(Command.Reload) }
    fun recover() { channel.trySend(Command.Recover) }
    fun checkByeDpi() { channel.trySend(Command.CheckByeDpi) }
    fun testProxies() { channel.trySend(Command.TestProxies) }
    fun stop() { channel.trySend(Command.Stop) }
}
