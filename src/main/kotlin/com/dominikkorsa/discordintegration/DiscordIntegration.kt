package com.dominikkorsa.discordintegration

import co.aikar.commands.PaperCommandManager
import com.dominikkorsa.discordintegration.command.DiscordIntegrationCommand
import com.dominikkorsa.discordintegration.config.ConfigManager
import com.dominikkorsa.discordintegration.config.MessageManager
import com.dominikkorsa.discordintegration.formatter.DiscordFormatter
import com.dominikkorsa.discordintegration.formatter.EmojiFormatter
import com.dominikkorsa.discordintegration.formatter.MinecraftFormatter
import com.dominikkorsa.discordintegration.listener.ChatListener
import com.dominikkorsa.discordintegration.listener.DeathListener
import com.dominikkorsa.discordintegration.listener.PlayerCountListener
import com.github.shynixn.mccoroutine.launchAsync
import com.github.shynixn.mccoroutine.registerSuspendingEvents
import discord4j.core.`object`.entity.Message
import discord4j.core.`object`.entity.channel.GuildMessageChannel
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.time.delay
import kotlinx.coroutines.time.withTimeout
import net.md_5.bungee.api.ChatMessageType
import org.bukkit.Bukkit
import org.bukkit.event.Listener
import org.bukkit.plugin.java.JavaPlugin
import java.time.Duration

class DiscordIntegration: JavaPlugin() {
    val client = Client(this)
    val discordFormatter = DiscordFormatter(this)
    val minecraftFormatter = MinecraftFormatter(this)
    val emojiFormatter = EmojiFormatter(this)
    val avatarService = AvatarService(this)
    private val lockFileService = LockFileService(this)
    lateinit var configManager: ConfigManager
    lateinit var messages: MessageManager
    private var activityJob: Job? = null
    private val connectionLock = Mutex()

    override fun onEnable() {
        super.onEnable()
        configManager = ConfigManager(this)
        messages = MessageManager(this)
        initCommands()
        registerEvents()
        this.launchAsync {
            connect(true)
            lockFileService.start()
        }
    }

    override fun onDisable() {
        super.onDisable()
        lockFileService.stop()
        runBlocking {
            withTimeout(Duration.ofSeconds(5)) {
                disconnect(true)
            }
        }
    }

    private suspend fun connect(withLock: Boolean) {
        if (withLock) return connectionLock.withLock { connect(false) }

        try {
            client.connect()
            this@DiscordIntegration.launchAsync {
                client.initListeners()
            }
            activityJob = this@DiscordIntegration.launchAsync {
                while (isActive) {
                    client.updateActivity()
                    delay(Duration.ofSeconds(configManager.activityUpdateInterval.toLong()))
                }
            }
            Bukkit.broadcastMessage(messages.connected)
        } catch (error: Exception) {
            Bukkit.broadcastMessage(messages.connectionFailed)
            error.printStackTrace()
        }
    }

    private suspend fun disconnect(withLock: Boolean) {
        if (withLock) return connectionLock.withLock { disconnect(false) }

        activityJob?.cancel()
        activityJob = null
        client.disconnect()
    }

    suspend fun reload() {
        configManager.reload()
        messages.reload()
        connectionLock.withLock {
            disconnect(false)
            connect(false)
        }
    }

    private fun initCommands() {
        val manager = PaperCommandManager(this)
        manager.registerCommand(DiscordIntegrationCommand(this))
    }

    private fun registerEvents() {
        registerSuspendingEvents(PlayerCountListener(this@DiscordIntegration))
        registerSuspendingEvents(ChatListener(this@DiscordIntegration))
        registerSuspendingEvents(DeathListener(this@DiscordIntegration))
    }

    suspend fun broadcastDiscordMessage(message: Message) {
        val channel = message.channel.awaitFirstOrNull()
        if (channel == null || channel !is GuildMessageChannel) return
        val parts = minecraftFormatter.formatDiscordMessage(
            message,
            channel,
        ).toTypedArray()
        server.onlinePlayers.forEach {
            it.spigot().sendMessage(ChatMessageType.CHAT, *parts)
        }
        Bukkit.getConsoleSender().spigot().sendMessage(*parts)
    }

    private fun registerSuspendingEvents(listener: Listener) {
        server.pluginManager.registerSuspendingEvents(listener, this)
    }
}
