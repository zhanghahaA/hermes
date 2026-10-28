package com.hermes.mobile.data.local

import kotlinx.coroutines.CompletableDeferred
import java.util.UUID

/**
 * 命令回调的进程内登记。只读检查丢失回包时可以重试；安装等修改操作必须等实际回包。
 * 每次使用独立标识，超时检查的迟到结果不得完成或释放下一条命令。
 */
class TermuxCommandTracker {
    class Call internal constructor(val id: String, internal val readOnly: Boolean) {
        internal val result = CompletableDeferred<TermuxCommandResult>()
        internal var dispose: (() -> Unit)? = null
    }
    private val lock = Any()
    private var active: Call? = null

    fun begin(readOnly: Boolean): Call = synchronized(lock) {
        if (active != null) throw RuntimeCommandException(RuntimeIssue.CommandPending)
        Call(UUID.randomUUID().toString(), readOnly).also { active = it }
    }

    fun registerCallback(call: Call, dispose: () -> Unit) {
        val expired = synchronized(lock) {
            if (active === call) { call.dispose = dispose; false } else true
        }
        if (expired) runCatching(dispose)
    }

    fun finish(id: String, result: TermuxCommandResult) {
        val call = synchronized(lock) {
            active?.takeIf { it.id == id }?.also { active = null }
        } ?: return
        call.dispose?.let { runCatching(it) }
        call.result.complete(result)
    }

    suspend fun await(call: Call): TermuxCommandResult = try {
        call.result.await()
    } finally {
        // 取消等待不等于 Termux 停止了任务。仅可重复的检查允许释放占位。
        if (call.readOnly) {
            val expired = synchronized(lock) {
                if (active === call) { active = null; true } else false
            }
            if (expired) {
                call.dispose?.let { runCatching(it) }
                call.result.cancel()
            }
        }
    }
}
