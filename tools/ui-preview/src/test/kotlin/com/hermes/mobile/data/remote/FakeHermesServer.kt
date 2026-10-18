package com.hermes.mobile.data.remote

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest

/**
 * 模拟不同版本的 Hermes API Server。响应结构照 hermes-agent 源码里的 api_server.py 写，
 * 并故意加了一些未知字段、思考内容、工具消息，用来验证 App 的容错和过滤。
 */
class FakeHermesServer(val version: Version, val key: String = "test-key-0123456789abcdef") {

    enum class Version {
        /** v0.22 左右：有 /v1/capabilities（含 endpoints 表）、完整会话接口。 */
        Modern,
        /** v2026.6.19 左右：有会话接口，但没有 pinned/hidden 等字段，时间是字符串。 */
        Mid,
        /** v2026.4 左右：没有 /v1/capabilities、没有会话接口。 */
        Old,
        /** 不是 Hermes：随便一个返回网页的服务器。 */
        NotHermes,
    }

    val server = MockWebServer()
    val requests = mutableListOf<RecordedRequest>()
    var failPatch = false

    val baseUrl: String get() = server.url("/").toString().trimEnd('/')

    fun start(): FakeHermesServer {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return handle(request)
            }
        }
        server.start()
        return this
    }

    fun shutdown() = server.shutdown()

    private fun json(body: String, code: Int = 200) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private fun error(code: Int, message: String, errCode: String? = null) =
        json("""{"error":{"message":"$message","type":"invalid_request_error","code":${errCode?.let { "\"$it\"" } ?: "null"}}}""", code)

    private fun handle(request: RecordedRequest): MockResponse {
        if (version == Version.NotHermes) {
            return MockResponse().setHeader("Content-Type", "text/html").setBody("<html><body>Welcome to nginx!</body></html>")
        }
        val path = request.path!!.substringBefore('?').removePrefix("/p/work")
        if (path == "/health") {
            val v = when (version) {
                Version.Modern -> "0.22.2"
                Version.Mid -> "2026.6.19"
                else -> "2026.4.16"
            }
            return json("""{"status":"ok","platform":"hermes-agent","version":"$v","brand_new_field":{"x":1}}""")
        }
        if (request.getHeader("Authorization") != "Bearer $key") return error(401, "Invalid API key", "invalid_api_key")

        return when {
            path == "/v1/models" -> json("""{"object":"list","data":[{"id":"hermes-agent","object":"model"}]}""")
            path == "/v1/capabilities" -> when (version) {
                Version.Old -> MockResponse().setResponseCode(404).setBody("404: Not Found")
                Version.Mid -> json(
                    """{"object":"hermes.api_server.capabilities","platform":"hermes-agent","features":{"session_chat_streaming":true,"approval_events":true},
                       "endpoints":{"health":{"method":"GET","path":"/health"},"sessions":{"method":"GET","path":"/api/sessions"},
                       "session_messages":{"method":"GET","path":"/api/sessions/{session_id}/messages"},
                       "session_chat_stream":{"method":"POST","path":"/api/sessions/{session_id}/chat/stream"}}}""",
                )
                else -> json(
                    """{"object":"hermes.api_server.capabilities","platform":"hermes-agent","model":"hermes-agent",
                       "auth":{"type":"bearer","required":true},"runtime":{"mode":"server_agent"},
                       "features":{"session_chat_streaming":true,"approval_events":true,"model_options":true,"jobs_admin":false,
                         "session_continuity_header":"X-Hermes-Session-Id","browser_extension_control":{"enabled":false}},
                       "endpoints":{"health":{"method":"GET","path":"/health"},"sessions":{"method":"GET","path":"/api/sessions"},
                         "session":{"method":"GET","path":"/api/sessions/{session_id}"},
                         "session_update":{"method":"PATCH","path":"/api/sessions/{session_id}"},
                         "session_delete":{"method":"DELETE","path":"/api/sessions/{session_id}"},
                         "session_messages":{"method":"GET","path":"/api/sessions/{session_id}/messages"},
                         "session_chat_stream":{"method":"POST","path":"/api/sessions/{session_id}/chat/stream"},
                         "run_approval":{"method":"POST","path":"/v1/runs/{run_id}/approval"}}}""",
                )
            }
            path == "/api/sessions" && version == Version.Old -> MockResponse().setResponseCode(404).setBody("404: Not Found")
            path == "/api/sessions" && version == Version.Mid -> json(
                """{"object":"list","data":[
                   {"id":"m1","source":"cli","title":"旧版本会话","started_at":"2026-06-20T08:00:00Z","last_active":"1781942400.5","message_count":3,"preview":"hello"}
                ],"limit":100,"offset":0}""",
            )
            path == "/api/sessions" -> json(
                """{"object":"list","data":[
                   {"id":"s1","source":"api_server","title":"家庭网络","started_at":1760000000.0,"last_active":1760003600.25,"message_count":6,"preview":"DNS 改好了","pinned":false,"archived":false,"hidden":false,"has_system_prompt":false,"is_internal_child":false,"future_field":[1,2]},
                   {"id":"s2","source":"telegram","title":null,"started_at":1760000000,"last_active":1760001000,"preview":"周五开会提醒\n第二行","pinned":true},
                   {"id":"s3","source":"api_server","title":"被隐藏的","hidden":true},
                   {"id":"s4","source":"api_server","title":"已归档","archived":true},
                   {"id":"s5","source":"delegate","title":"子代理","is_internal_child":true},
                   {"id":"s6","source":"kanban","title":"看板来的"}
                ],"limit":100,"offset":0,"has_more":false}""",
            )
            path == "/api/sessions/s1/messages" -> json(
                """{"object":"list","session_id":"s1","data":[
                   {"id":1,"role":"system","content":"You are Hermes"},
                   {"id":2,"role":"user","content":"网络老断","timestamp":1760000100.0},
                   {"id":3,"role":"assistant","content":"","reasoning":"SECRET-THINKING-1","tool_calls":[{"id":"t1","function":{"name":"terminal"}}],"timestamp":1760000110.0},
                   {"id":4,"role":"tool","content":"ping output...","tool_call_id":"t1","tool_name":"terminal"},
                   {"id":5,"role":"assistant","content":"DNS 改好了。","reasoning_content":"SECRET-THINKING-2","finish_reason":"stop","timestamp":1760000200.0},
                   {"id":6,"role":"assistant","content":"","display_kind":"hidden"},
                   {"id":7,"role":"user","content":[{"type":"text","text":"看这张图"},{"type":"image_url","image_url":{"url":"data:image/png;base64,AAAA"}}],"timestamp":1760000300},
                   {"id":8,"role":"assistant","content":[{"type":"output_text","text":"图里是路由器后台。"}],"timestamp":1760000400,"some_new_field":"x"}
                ],"pagination":{"limit":500,"offset":0,"order":"latest","returned":8}}""",
            )
            path.startsWith("/api/sessions/") && request.method == "PATCH" ->
                if (failPatch) error(500, "database is locked") else json("""{"object":"hermes.session","session":{"id":"s1","title":"新标题"}}""")
            path.startsWith("/api/sessions/") && request.method == "DELETE" -> json("""{"object":"hermes.session.deleted","id":"s1","deleted":true}""")
            else -> MockResponse().setResponseCode(404).setBody("404: Not Found")
        }
    }
}
