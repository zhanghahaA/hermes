package com.hermes.mobile.data.ssh

import com.hermes.mobile.data.remote.HermesJson
import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.serialization.json.*

/** 替代远端 Python 桥，仍通过真实 SSH exec 通道收发 JSON 行。 */
class InteractiveGatewayCommand(private val command: String, private val requests: CopyOnWriteArrayList<JsonObject>) : Command {
    private lateinit var input: InputStream
    private lateinit var output: OutputStream
    private lateinit var callback: ExitCallback
    override fun setInputStream(value: InputStream) { input = value }
    override fun setOutputStream(value: OutputStream) { output = value }
    override fun setErrorStream(value: OutputStream) {}
    override fun setExitCallback(value: ExitCallback) { callback = value }
    override fun destroy(channel: ChannelSession) { runCatching { input.close() } }
    override fun start(channel: ChannelSession, env: Environment) {
        Thread {
            try {
                fun emit(text: String) { output.write((text + "\n").toByteArray()); output.flush() }
                if (command.contains("config path")) {
                    emit("/tmp/hermes-fixture/config.yaml")
                    return@Thread
                }
                if (!command.contains("from hermes_cli.gateway_client import GatewayClient")) {
                    emit("{}")
                    return@Thread
                }
                emit("""{"ready":true}""")
                input.bufferedReader().forEachLine { line ->
                    val req = runCatching { HermesJson.parseToJsonElement(line).jsonObject }.getOrNull() ?: return@forEachLine
                    requests += req
                    val id = req["id"]
                    val params = req["params"]!!.jsonObject
                    val result = when (req["method"]?.jsonPrimitive?.content) {
                        "session.resume" -> """{"session_id":${params["session_id"]},"subscription_id":"sub","last_sequence":0,"replay_epoch":"e","prompts":[{"kind":"clarify","prompt_id":"shared-choice","execution_generation":4,"question":"共享选项","choices":["甲","乙"]}]}"""
                        "session.events.since" -> """{"events":[],"last_sequence":0}"""
                        else -> "{}"
                    }
                    emit("""{"id":$id,"result":$result}""")
                }
            } catch (_: Exception) {
            } finally { callback.onExit(0) }
        }.apply { isDaemon = true; start() }
    }
}
