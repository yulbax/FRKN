package io.github.yulbax.frkn.vpn.core

interface TunPlatform {
    fun openTun(config: TunConfig): Int

    fun protectFd(fd: Int): Boolean

    fun findConnectionOwner(
        ipProtocol: Int,
        sourceAddress: String,
        sourcePort: Int,
        destinationAddress: String,
        destinationPort: Int
    ): ConnectionOwnerInfo
}
