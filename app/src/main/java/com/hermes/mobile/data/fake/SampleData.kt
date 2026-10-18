package com.hermes.mobile.data.fake

import com.hermes.mobile.data.model.ChatItem
import com.hermes.mobile.data.model.ConnectionProfile
import com.hermes.mobile.data.model.ConnectionType
import com.hermes.mobile.data.model.CronJob
import com.hermes.mobile.data.model.ImageRef
import com.hermes.mobile.data.model.JobRunResult
import com.hermes.mobile.data.model.ModelOption
import com.hermes.mobile.data.model.Role
import com.hermes.mobile.data.model.Session
import com.hermes.mobile.data.model.SessionSource
import com.hermes.mobile.data.model.Skill
import com.hermes.mobile.data.model.Toolset

/** 骨架阶段的演示数据。接入真实 API 后删除。 */
object SampleData {
    private const val MIN = 60_000L
    private const val HOUR = 60 * MIN
    private const val DAY = 24 * HOUR

    val connections = listOf(
        ConnectionProfile("c-home", "家里 NAS", ConnectionType.Direct, "100.101.22.8"),
        ConnectionProfile("c-ssh", "工作站（SSH）", ConnectionType.Ssh, "nas.local", sshUser = "hermes"),
        ConnectionProfile("c-local", "手机本机", ConnectionType.Local, "127.0.0.1"),
    )

    val models = listOf(
        ModelOption("anthropic/claude-sonnet", "Claude Sonnet", "OpenRouter", "默认"),
        ModelOption("anthropic/claude-opus", "Claude Opus", "OpenRouter"),
        ModelOption("google/gemini-pro", "Gemini Pro", "OpenRouter"),
        ModelOption("nous/hermes-4-405b", "Hermes 4 405B", "Nous Portal", "推荐"),
        ModelOption("nous/hermes-4-70b", "Hermes 4 70B", "Nous Portal"),
        ModelOption("local/qwen3-32b", "Qwen3 32B", "本地 vLLM"),
    )

    val skills = listOf(
        Skill("github-pr-review", "审阅 GitHub Pull Request 并给出修改建议", "开发"),
        Skill("daily-briefing", "汇总日历、邮件和新闻，生成每日简报", "效率"),
        Skill("home-assistant", "控制家里的灯、空调和传感器", "智能家居"),
        Skill("arxiv-digest", "检索并摘要最新论文", "研究"),
        Skill("server-health", "检查服务器磁盘、内存和服务状态", "运维"),
        Skill("translate-doc", "保留排版地翻译整篇文档", "写作"),
    )

    val toolsets = listOf(
        Toolset("terminal", "在服务器上执行命令", 3, true),
        Toolset("file", "读写文件、搜索代码", 6, true),
        Toolset("web", "搜索网页、抓取页面", 2, true),
        Toolset("browser", "无头浏览器自动化", 8, false),
        Toolset("vision", "理解图片内容", 1, true),
        Toolset("cronjob", "创建和管理定时任务", 4, true),
        Toolset("delegate", "派生子代理并行处理", 1, false),
    )

    fun jobs() = listOf(
        CronJob(
            "j-brief", "每日简报", "整理今天的日程、天气和我关注的新闻，用三段话发给我",
            "每天 08:00", "0 8 * * *", "明天 08:00", JobRunResult.Success, "今天 08:00", true, "App 通知",
        ),
        CronJob(
            "j-backup", "NAS 备份检查", "检查昨晚的备份任务是否成功，失败时告诉我原因",
            "每天 09:30", "30 9 * * *", "明天 09:30", JobRunResult.Failed, "今天 09:30", true, "Telegram",
        ),
        CronJob(
            "j-papers", "论文周报", "汇总本周 arXiv 上关于 agent 的新论文",
            "每周一 10:00", "0 10 * * 1", "周一 10:00", JobRunResult.Never, null, false, "仅记录",
        ),
    )

    fun sessions(now: Long) = listOf(
        Session("s-1", "家庭网络排查", "已经把路由器的 DNS 改回 223.5.5.5，重启后…", now - 4 * MIN, pinned = true, model = "Claude Sonnet"),
        Session("s-2", "周末去杭州的行程", "第二天上午建议先去灵隐寺，人少一些。", now - 2 * HOUR, unread = 2),
        Session("s-3", "整理照片脚本", "脚本写好了，放在 ~/scripts/sort_photos.py", now - 5 * HOUR),
        Session("s-4", "hermes 配置迁移", "(在电脑终端里进行的会话)", now - DAY - 3 * HOUR, source = SessionSource.Cli, readOnly = true),
        Session("s-5", "论文阅读：长上下文检索", "核心思路是把检索当作一种工具调用…", now - 2 * DAY),
        Session("s-6", "Telegram 群里的提醒", "好的，周五下午 3 点提醒大家开会。", now - 3 * DAY, source = SessionSource.Telegram, readOnly = true),
        Session("s-7", "给爸妈的手机设置", "已经列好了步骤，一共 6 步。", now - 6 * DAY),
    )

    fun chat(sessionId: String, now: Long): List<ChatItem> = when (sessionId) {
        "s-1" -> listOf(
            ChatItem.Message("m1", now - DAY - 2 * HOUR, Role.User, "家里网络从昨晚开始时不时断一下，帮我看看是什么原因"),
            ChatItem.Message(
                "m2", now - DAY - 2 * HOUR + MIN, Role.Assistant,
                "我检查了路由器日志，发现两个问题：\n\n" +
                    "1. **DNS 超时**：上游 DNS `114.114.114.114` 在 23:10 之后多次超时\n" +
                    "2. **DHCP 租约冲突**：NAS 和打印机抢同一个地址 `192.168.1.20`\n\n" +
                    "建议先把 DNS 换成 `223.5.5.5`，再给 NAS 设一个静态地址。要我来改吗？",
            ),
            ChatItem.Message("m3", now - 12 * MIN, Role.User, "好，先改 DNS，然后把路由器重启一下"),
            ChatItem.Approval(
                "a1", now - 11 * MIN,
                "修改路由器 DNS 并重启",
                "uci set network.wan.dns='223.5.5.5'\nuci commit network && reboot",
                resolvedChoice = "允许一次",
            ),
            ChatItem.Message(
                "m4", now - 6 * MIN, Role.Assistant,
                "已经把路由器的 DNS 改回 `223.5.5.5`，重启后网络恢复正常，最近 5 分钟没有再出现超时。\n\n" +
                    "要继续给 NAS 设静态地址吗？我打算用下面这个配置：\n\n" +
                    "```\nhost: nas\nmac: 3c:7c:3f:aa:12:9e\nip: 192.168.1.50\n```",
            ),
            ChatItem.Message("m5", now - 5 * MIN, Role.User, "先看看这张截图里的报错是什么意思", images = listOf(ImageRef("img1", 210f, 1.6f))),
            ChatItem.Message(
                "m6", now - 4 * MIN, Role.Assistant,
                "截图里是 NAS 的 SMB 服务报错 *\"access denied\"*，原因是共享目录的权限只给了 `admin` 组。把你的账号加进这个组就行。",
            ),
            ChatItem.Clarify(
                "c1", now - 4 * MIN + 10_000,
                "要把哪个账号加到 admin 组？",
                listOf("me", "family", "guest"),
            ),
        )

        "s-2" -> listOf(
            ChatItem.Message("m1", now - 3 * HOUR, Role.User, "周末想去杭州玩两天，帮我排个行程，不想太累"),
            ChatItem.Message(
                "m2", now - 2 * HOUR, Role.Assistant,
                "给你排了一个轻松版的两日行程：\n\n" +
                    "**第一天**\n- 上午：西湖断桥 → 白堤，慢慢走\n- 下午：浙江省博物馆（孤山馆区）\n- 晚上：河坊街吃小吃\n\n" +
                    "**第二天**\n- 上午：灵隐寺 + 飞来峰\n- 下午：龙井村喝茶\n\n" +
                    "第二天上午建议先去灵隐寺，人少一些。",
            ),
        )

        else -> listOf(
            ChatItem.Message("m1", now - 6 * HOUR, Role.User, "帮我写个脚本，把相册按拍摄日期分文件夹"),
            ChatItem.Message(
                "m2", now - 5 * HOUR, Role.Assistant,
                "脚本写好了，放在 `~/scripts/sort_photos.py`。它会读取照片的 EXIF 拍摄时间，按 `年/月` 建文件夹。\n\n" +
                    "```python\nfor photo in photos:\n    taken = read_exif_date(photo)\n    move(photo, root / f\"{taken:%Y/%m}\")\n```\n\n" +
                    "先用 `--dry-run` 跑一遍看看效果。",
            ),
        )
    }
}
