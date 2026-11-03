package com.github.ssquadteam.kscaler.internal

import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import org.bukkit.entity.Player
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

internal class PingChannel private constructor(
    private val handler: String,
    private val handle: MethodHandle,
    private val listener: MethodHandle,
    private val connection: MethodHandle,
    private val channel: MethodHandle,
    private val send: MethodHandle,
    private val ping: MethodHandle,
    private val pong: Class<*>,
    private val pongId: MethodHandle,
) {
    fun listenerOf(player: Player): Any = listener.invoke(handle.invoke(player))

    fun channelOf(listener: Any): Channel = channel.invoke(connection.invoke(listener)) as Channel

    fun ping(
        listener: Any,
        id: Int,
    ) {
        send.invoke(listener, ping.invoke(id))
    }

    fun tap(channel: Channel): Tap {
        val tap = Tap()
        channel.eventLoop().execute {
            if (channel.pipeline().get(handler) == null && channel.pipeline().get(PACKET_HANDLER) != null) {
                channel.pipeline().addBefore(PACKET_HANDLER, handler, tap)
            }
        }
        return tap
    }

    inner class Tap : ChannelInboundHandlerAdapter() {
        @Volatile var block: Block? = null

        override fun channelRead(
            ctx: ChannelHandlerContext,
            msg: Any,
        ) {
            if (pong.isInstance(msg)) block?.arrive(pongId.invoke(msg) as Int, System.nanoTime())
            ctx.fireChannelRead(msg)
        }

        override fun isSharable(): Boolean = false
    }

    companion object {
        private const val PACKET_HANDLER = "packet_handler"

        fun create(handler: String): PingChannel? =
            runCatching {
                val lookup = MethodHandles.lookup()
                val serverPlayer = Class.forName("net.minecraft.server.level.ServerPlayer")
                val game = Class.forName("net.minecraft.server.network.ServerGamePacketListenerImpl")
                val common = Class.forName("net.minecraft.server.network.ServerCommonPacketListenerImpl")
                val connection = Class.forName("net.minecraft.network.Connection")
                val packet = Class.forName("net.minecraft.network.protocol.Packet")
                val ping = Class.forName("net.minecraft.network.protocol.common.ClientboundPingPacket")
                val pong = Class.forName("net.minecraft.network.protocol.common.ServerboundPongPacket")
                val craft = Class.forName("org.bukkit.craftbukkit.entity.CraftPlayer")
                PingChannel(
                    handler = handler,
                    handle = lookup.findVirtual(craft, "getHandle", MethodType.methodType(serverPlayer)),
                    listener = lookup.unreflectGetter(field(serverPlayer, game)),
                    connection = lookup.unreflectGetter(field(common, connection)),
                    channel = lookup.unreflectGetter(field(connection, Channel::class.java)),
                    send = lookup.findVirtual(common, "send", MethodType.methodType(Void.TYPE, packet)),
                    ping = lookup.findConstructor(ping, MethodType.methodType(Void.TYPE, Int::class.javaPrimitiveType)),
                    pong = pong,
                    pongId = lookup.unreflect(intGetter(pong)),
                )
            }.getOrNull()

        private fun field(
            owner: Class<*>,
            type: Class<*>,
        ): Field =
            generateSequence(owner) { it.superclass }
                .flatMap { it.declaredFields.asSequence() }
                .first { !Modifier.isStatic(it.modifiers) && type.isAssignableFrom(it.type) }
                .apply { isAccessible = true }

        private fun intGetter(owner: Class<*>): Method =
            owner.methods.firstOrNull {
                it.name == "getId" &&
                    it.parameterCount == 0 &&
                    it.returnType == Int::class.javaPrimitiveType
            }
                ?: owner.declaredMethods
                    .first {
                        it.parameterCount == 0 &&
                            it.returnType == Int::class.javaPrimitiveType &&
                            !Modifier.isStatic(it.modifiers)
                    }.apply { isAccessible = true }
    }
}
