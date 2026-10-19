package com.hermes.mobile.data.store

import com.hermes.mobile.data.model.ChatItem
import com.hermes.mobile.data.model.Session
import com.hermes.mobile.data.model.SessionGrouping
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/**
 * 会话列表和聊天记录在手机上的副本：打开 App 先显示它（不用等网络），没网时也能翻看。
 * 服务器才是准的，每次读到新的就整份覆盖。按连接分目录：
 * `<dir>/<连接>/sessions.json`、`<dir>/<连接>/m/<会话 id 的哈希>.json`。
 */
class ChatCache(private val dir: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val sessionList = ListSerializer(Session.serializer())
    private val messageList = ListSerializer(ChatItem.Message.serializer())

    private fun connectionDir(connectionId: String) = File(dir, hash(connectionId))
    private fun messagesFile(connectionId: String, sessionId: String) = File(connectionDir(connectionId), "m/${hash(sessionId)}.json")

    fun sessions(connectionId: String): List<Session> =
        read(File(connectionDir(connectionId), "sessions.json")) { json.decodeFromString(sessionList, it) }
            .orEmpty()
            .map { it.copy(running = false) }

    /** 存会话列表，顺便删掉已经不在列表里的会话的聊天记录。 */
    fun saveSessions(connectionId: String, sessions: List<Session>) {
        write(File(connectionDir(connectionId), "sessions.json"), json.encodeToString(sessionList, sessions.take(MaxSessions)))
        val keep = sessions.take(MaxSessions).map { "${hash(it.id)}.json" }.toSet()
        File(connectionDir(connectionId), "m").listFiles()?.filter { it.name !in keep }?.forEach { it.delete() }
    }

    fun messages(connectionId: String, sessionId: String): List<ChatItem.Message>? =
        read(messagesFile(connectionId, sessionId)) { json.decodeFromString(messageList, it) }

    fun saveMessages(connectionId: String, sessionId: String, messages: List<ChatItem.Message>) {
        write(messagesFile(connectionId, sessionId), json.encodeToString(messageList, messages.takeLast(MaxMessages)))
    }

    fun groups(connectionId: String): SessionGrouping? =
        read(File(connectionDir(connectionId), "groups.json")) { json.decodeFromString(SessionGrouping.serializer(), it) }

    fun saveGroups(connectionId: String, grouping: SessionGrouping) {
        write(File(connectionDir(connectionId), "groups.json"), json.encodeToString(SessionGrouping.serializer(), grouping))
    }

    /** App 里归档的会话（Hermes 的列表不再返回它们，靠这里还能找到、取消归档）。 */
    fun archived(connectionId: String): List<Session> =
        read(File(connectionDir(connectionId), "archived.json")) { json.decodeFromString(sessionList, it) }.orEmpty()

    fun saveArchived(connectionId: String, sessions: List<Session>) {
        write(File(connectionDir(connectionId), "archived.json"), json.encodeToString(sessionList, sessions.take(500)))
    }

    fun removeSession(connectionId: String, sessionId: String) {
        messagesFile(connectionId, sessionId).delete()
    }

    fun removeConnection(connectionId: String) {
        connectionDir(connectionId).deleteRecursively()
    }

    private fun <T> read(file: File, decode: (String) -> T): T? = synchronized(this) {
        if (!file.exists()) return null
        // 格式变了或者文件坏了：当没有缓存
        runCatching { decode(file.readText()) }.getOrNull()
    }

    private fun write(file: File, text: String) = synchronized(this) {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
    }

    private fun hash(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)

    companion object {
        /** Hermes 一次返回最近 100 个会话；每个会话留最近 300 条消息。 */
        const val MaxSessions = 100
        const val MaxMessages = 300
    }
}
