package com.hermes.mobile.data.fake

import com.hermes.mobile.data.model.Room
import com.hermes.mobile.data.model.RoomAction
import com.hermes.mobile.data.model.RoomEvent
import com.hermes.mobile.data.model.RoomMember
import com.hermes.mobile.data.model.RoomStatus
import com.hermes.mobile.data.remote.RoomApi
import com.hermes.mobile.data.remote.RoomLog

/** 演示用的群聊：一个「周末出游」群，小助手和码农各说了几句。 */
class FakeRoomApi : RoomApi {
    private val now = System.currentTimeMillis()
    private val rooms = mutableListOf(
        Room(
            "r-trip", "周末出游",
            listOf(RoomMember("default", "default", "hermes", "小助手"), RoomMember("coder", "coder", "coder", "码农")),
            latestSeq = 6, updatedAt = now - 300_000,
        ),
    )
    private val events = mutableMapOf(
        "r-trip" to mutableListOf(
            event(1, "message.user", "user", "desktop", null, "周六去杭州，帮我排个一日行程，@coder 顺便写个查天气的小脚本", now - 900_000),
            event(2, "message.member", "member", "default", "default", "上午灵隐寺（人少），中午知味观，下午西湖骑行，傍晚去湖滨看日落。要我订餐厅吗？", now - 840_000),
            event(3, "message.member", "member", "coder", "coder", "脚本写好了：`~/scripts/weather.py 杭州`，会输出未来三天的天气和降水概率。\n\n```bash\npython3 ~/scripts/weather.py 杭州\n```", now - 780_000),
            event(4, "message.member", "member", "default", "default", "看了下 @coder 脚本的结果：周六多云 22°C，适合骑行。", now - 720_000),
            event(5, "room.activity", "gateway", "gw", null, "", now - 700_000, status = "settled"),
            event(6, "message.user", "user", "desktop", null, "好，那就这么定了", now - 300_000),
        ),
    )

    private fun event(seq: Long, kind: String, actorKind: String, actorId: String, profile: String?, text: String, at: Long, status: String? = null) =
        RoomEvent(seq, kind, actorKind, actorId, profile, null, text, "t1", null, status, null, 0, at)

    override suspend fun rooms(): List<Room> = rooms.toList()

    override suspend fun create(name: String, members: List<RoomMember>): Room =
        Room("r-${rooms.size + 1}", name, members, 0, System.currentTimeMillis()).also { rooms.add(0, it); events[it.id] = mutableListOf() }

    override suspend fun status(roomId: String): RoomStatus =
        RoomStatus(rooms.first { it.id == roomId }, driverRunning = true, working = false, actions = emptyList())

    override suspend fun log(roomId: String, sinceSeq: Long): RoomLog {
        val list = events[roomId].orEmpty().filter { it.seq > sinceSeq }
        return RoomLog(list, events[roomId].orEmpty().maxOfOrNull { it.seq } ?: 0, false)
    }

    override suspend fun send(roomId: String, text: String) {
        val list = events.getOrPut(roomId) { mutableListOf() }
        list.add(event((list.maxOfOrNull { it.seq } ?: 0) + 1, "message.user", "user", "desktop", null, text, System.currentTimeMillis()))
    }

    override suspend fun stop(roomId: String): Int = 0
    override suspend fun rename(roomId: String, name: String) {
        rooms.replaceAll { if (it.id == roomId) it.copy(name = name) else it }
    }
    override suspend fun disband(roomId: String) {
        rooms.removeAll { it.id == roomId }
    }
    override suspend fun approve(roomId: String, action: RoomAction, allow: Boolean) = Unit
    override suspend fun retry(roomId: String, action: RoomAction) = Unit
    override suspend fun discard(roomId: String, action: RoomAction) = Unit
}
