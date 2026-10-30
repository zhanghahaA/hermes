package com.hermes.mobile.data.remote

import com.hermes.mobile.data.model.Room
import com.hermes.mobile.data.model.RoomAction
import com.hermes.mobile.data.model.RoomEvent
import com.hermes.mobile.data.model.RoomMember
import com.hermes.mobile.data.model.RoomStatus
import com.hermes.mobile.data.ssh.RemoteStream
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.util.UUID

/** 群聊要用的操作。真实的走 SSH 上的 [RoomClient]，演示数据有自己的实现。 */
interface RoomApi {
    suspend fun rooms(): List<Room>
    /** 建群：[members] 是 (Profile 名, @ 用的名字, 显示名)。 */
    suspend fun create(name: String, members: List<RoomMember>): Room
    suspend fun status(roomId: String): RoomStatus
    /** [sinceSeq] 之后的记录（一次最多 500 条，[RoomLog.hasMore] 为 true 时接着读）。 */
    suspend fun log(roomId: String, sinceSeq: Long): RoomLog
    suspend fun send(roomId: String, text: String)
    /** 让正在回复的 bot 停下；返回停了几个。 */
    suspend fun stop(roomId: String): Int
    suspend fun rename(roomId: String, name: String)
    suspend fun disband(roomId: String)
    /** 批准（[allow]）或拒绝一个操作。 */
    suspend fun approve(roomId: String, action: RoomAction, allow: Boolean)
    suspend fun retry(roomId: String, action: RoomAction)
    suspend fun discard(roomId: String, action: RoomAction)
}

data class RoomLog(val events: List<RoomEvent>, val latestSeq: Long, val hasMore: Boolean)

/**
 * 经 SSH 连 Hermes gateway 的群聊（hosted rooms）。群聊的每一轮都在 gateway 里跑，
 * 这里只是把 `groups.*` JSON-RPC 请求一行一行写给服务器上的小桥（[HermesAdmin.GroupBridgeScript]），
 * 桥用本机控制 socket 换票据连上 gateway 的 `/api/ws`，和电脑版、TUI 用的是同一个接口。
 * 互动卡片同步（[SessionControlFeed]）也走这条通道。
 * 一条 SSH 通道一直开着；断了下次调用时重新开。
 */
class RoomClient(open: suspend () -> RemoteStream) : RoomApi, java.io.Closeable {
    private val channel = GatewayChannel(open)

    internal suspend fun call(method: String, params: JsonObject = JsonObject(emptyMap())): JsonObject {
        val withProfile = JsonObject(params + ("profile" to (params["profile"] ?: JsonPrimitive("default"))))
        val result = try {
            channel.call(method, withProfile)
        } catch (e: GatewayException) {
            if (e.disconnected) throw AdminException(e.message ?: "和 Hermes gateway 的连接断了")
            throw AdminException(errorText(buildJsonObject {
                put("reason", e.reason)
                e.data?.let { put("data", it) }
            }))
        }
        return result as? JsonObject ?: JsonObject(emptyMap())
    }

    override suspend fun rooms(): List<Room> {
        val out = mutableListOf<Room>()
        var offset = 0L
        while (true) {
            val r = call("groups.list", buildJsonObject { put("limit", 200); put("offset", offset) })
            (r["rooms"] as? JsonArray).orEmpty().mapNotNullTo(out) { (it as? JsonObject)?.let(::parseRoom) }
            offset = (r["next_offset"] as? JsonPrimitive)?.longOrNull ?: break
        }
        return out.sortedByDescending { it.updatedAt }
    }

    override suspend fun create(name: String, members: List<RoomMember>): Room {
        val r = call("groups.create", buildJsonObject {
            put("room_id", UUID.randomUUID().toString())
            put("name", name.trim())
            put("members", buildJsonArray {
                members.forEach { m ->
                    add(buildJsonObject {
                        put("member_id", m.memberId)
                        put("profile", m.profile)
                        put("handle", m.handle)
                        m.displayName?.takeIf { it.isNotBlank() }?.let { put("display_name", it) }
                        put("target", buildJsonObject { put("kind", "local"); put("profile", m.profile) })
                    })
                }
            })
        })
        return (r["room"] as? JsonObject)?.let(::parseRoom) ?: throw AdminException("建群失败")
    }

    override suspend fun status(roomId: String): RoomStatus {
        val r = call("groups.state", buildJsonObject { put("room_id", roomId) })
        val room = (r["room"] as? JsonObject)?.let(::parseRoom) ?: throw AdminException("找不到这个群聊")
        val driver = r["driver_status"] as? JsonObject
        val actions = (driver?.get("pending_actions") as? JsonArray).orEmpty().mapNotNull { e ->
            val a = e as? JsonObject ?: return@mapNotNull null
            val approval = a["approval"] as? JsonObject
            RoomAction(
                kind = a.str("kind") ?: return@mapNotNull null,
                memberId = a.str("member_id").orEmpty(),
                taskId = a.str("task_id") ?: return@mapNotNull null,
                generation = (a["execution_generation"] as? JsonPrimitive)?.longOrNull ?: 1,
                requestId = a.str("request_id") ?: approval?.str("request_id"),
                description = approval?.let { it.str("description") ?: it.str("command") },
            )
        }
        return RoomStatus(
            room = room,
            driverRunning = driver != null && (driver["running"] as? JsonPrimitive)?.booleanOrNull != false,
            working = (driver?.get("working") as? JsonPrimitive)?.booleanOrNull == true,
            actions = actions,
        )
    }

    override suspend fun log(roomId: String, sinceSeq: Long): RoomLog {
        val r = call("groups.log", buildJsonObject { put("room_id", roomId); put("since_seq", sinceSeq); put("limit", 500) })
        val events = (r["events"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::parseEvent) }
        return RoomLog(
            events = events,
            latestSeq = (r["latest_seq"] as? JsonPrimitive)?.longOrNull ?: events.maxOfOrNull { it.seq } ?: sinceSeq,
            hasMore = (r["has_more"] as? JsonPrimitive)?.booleanOrNull == true,
        )
    }

    override suspend fun send(roomId: String, text: String) {
        // 和电脑版一样：每条消息自己开一个话题（thread_id = event_id）
        val eventId = UUID.randomUUID().toString()
        call("groups.send", buildJsonObject {
            put("room_id", roomId)
            put("event_id", eventId)
            put("payload", buildJsonObject { put("text", text); put("thread_id", eventId) })
        })
    }

    override suspend fun stop(roomId: String): Int {
        val r = call("groups.stop", buildJsonObject { put("room_id", roomId); put("cancel_id", UUID.randomUUID().toString()) })
        return (r["cancelled"] as? JsonPrimitive)?.longOrNull?.toInt() ?: 0
    }

    override suspend fun rename(roomId: String, name: String) {
        call("groups.rename", buildJsonObject { put("room_id", roomId); put("event_id", UUID.randomUUID().toString()); put("name", name.trim()) })
    }

    override suspend fun disband(roomId: String) {
        call("groups.disband", buildJsonObject { put("room_id", roomId); put("cancel_id", UUID.randomUUID().toString()) })
    }

    private fun actionParams(roomId: String, action: RoomAction) = buildJsonObject {
        put("room_id", roomId)
        put("member_id", action.memberId)
        put("task_id", action.taskId)
        put("execution_generation", action.generation)
    }

    override suspend fun approve(roomId: String, action: RoomAction, allow: Boolean) {
        call("groups.approve", JsonObject(actionParams(roomId, action) + mapOf(
            "choice" to JsonPrimitive(if (allow) "once" else "deny"),
            "request_id" to JsonPrimitive(action.requestId.orEmpty()),
        )))
    }

    override suspend fun retry(roomId: String, action: RoomAction) {
        call("groups.retry", actionParams(roomId, action))
    }

    override suspend fun discard(roomId: String, action: RoomAction) {
        call("groups.discard", actionParams(roomId, action))
    }

    override fun close() = channel.close()

    companion object {
        private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        private fun seconds(e: JsonElement?) = ((e as? JsonPrimitive)?.doubleOrNull ?: 0.0).times(1000).toLong()

        internal fun parseRoom(o: JsonObject): Room? = Room(
            id = o.str("room_id") ?: return null,
            name = o.str("name").orEmpty(),
            members = (o["members"] as? JsonArray).orEmpty().mapNotNull { e ->
                val m = e as? JsonObject ?: return@mapNotNull null
                val profile = m.str("profile") ?: return@mapNotNull null
                RoomMember(m.str("member_id") ?: profile, profile, m.str("handle") ?: profile, m.str("display_name"))
            },
            latestSeq = (o["latest_seq"] as? JsonPrimitive)?.longOrNull ?: 0,
            updatedAt = seconds(o["updated_at"]),
        )

        internal fun parseEvent(o: JsonObject): RoomEvent? {
            val actor = o["actor"] as? JsonObject
            val payload = o["payload"] as? JsonObject ?: JsonObject(emptyMap())
            return RoomEvent(
                seq = (o["seq"] as? JsonPrimitive)?.longOrNull ?: return null,
                kind = o.str("kind") ?: return null,
                actorKind = actor?.str("kind").orEmpty(),
                actorId = actor?.str("id").orEmpty(),
                actorProfile = actor?.str("profile"),
                actorName = actor?.str("display_name"),
                text = payload.str("text") ?: payload.str("content").orEmpty(),
                threadId = payload.str("thread_id"),
                memberId = payload.str("member_id"),
                status = payload.str("status"),
                reason = payload.str("error") ?: payload.str("reason") ?: payload.str("reason_code"),
                attachments = (payload["attachments"] as? JsonArray)?.size ?: 0,
                createdAt = seconds(o["created_at"]),
            )
        }

        /** Hermes 的错误原因 → 看得懂的话。 */
        internal fun errorText(error: JsonObject): String {
            val reason = error.str("reason") ?: error.str("message") ?: "出错了"
            val detail = (error["data"] as? JsonObject)?.str("detail")
            val text = when (reason) {
                "runtime_coordination_required" -> "Hermes gateway 里的群聊服务没在运行，重启一下 gateway 试试"
                "permission_denied" -> "没有权限" + (detail?.let { "：$it" } ?: "（这个群聊可能是在别的设备上、用别的身份建的）")
                "invalid_params" -> "参数不对" + (detail?.let { "：$it" } ?: "")
                "profile_mismatch" -> "这个群聊不在默认 bot 下面"
                "session_busy" -> "它还在忙，等这一轮结束再试"
                else -> reason + (detail?.let { "：$it" } ?: "")
            }
            return text
        }
    }
}
