package com.hermes.mobile.data.local

import java.nio.file.Files
import kotlin.test.*

class DomesticPackageSourcesTest {
    @Test fun aptUsesOnlyManagedSourcesAndSeparateIndexesWithoutOverwritingGlobalFiles() {
        val folder = Files.createTempDirectory("hermes-domestic-fixture").toFile()
        try {
            val root = folder.resolve("runtime")
            val prefix = folder.resolve("prefix")
            val parts = prefix.resolve("etc/apt/sources.list.d").apply { mkdirs() }
            val main = prefix.resolve("etc/apt/sources.list").apply { writeText("fixture global source\n") }
            val custom = parts.resolve("custom.list").apply { writeText("fixture custom source\n") }
            val hermes = parts.resolve("hermes-agent.list").apply { writeText("fixture Hermes source\n") }
            val bin = folder.resolve("bin").apply { mkdir() }
            bin.resolve("apt-get").apply {
                writeText("#!/bin/sh\nprintf '%s\\n' \"\$@\" >> \"\$TEST_LOG\"\nexit \"\$FAIL_APT\"\n")
                check(setExecutable(true))
            }
            val log = folder.resolve("calls")
            fun run(includeHermes: Boolean, fail: Boolean = false): Int {
                val script = "set -eu\n" + DomesticPackageSources.setup(includeHermes) + "\n" +
                    DomesticPackageSources.Update + "\nhm_apt install -y python"
                val builder = ProcessBuilder("/bin/bash", "-c", script).redirectErrorStream(true)
                builder.environment().apply {
                    put("HERMES_MOBILE_ROOT", root.absolutePath)
                    put("PREFIX", prefix.absolutePath)
                    put("PATH", bin.absolutePath + ":/usr/bin:/bin")
                    put("TEST_LOG", log.absolutePath)
                    put("FAIL_APT", if (fail) "9" else "0")
                }
                val process = builder.start()
                assertEquals("", process.inputStream.bufferedReader().readText())
                return process.waitFor()
            }
            assertEquals(0, run(true))
            val sources = root.resolve("apt/sources.list").readLines()
            assertEquals(4, sources.size)
            assertTrue(sources.take(3).all { it.startsWith("deb https://mirrors.tuna.tsinghua.edu.cn/termux/apt/") })
            assertEquals("fixture Hermes source", sources.last())
            val args = log.readLines()
            assertEquals(2, args.count { it == "Dir::Etc::sourceparts=-" })
            assertEquals(2, args.count { it == "Dir::State::lists=" + root.resolve("apt/lists").absolutePath })
            assertFalse(args.any { it.contains("allow-unauthenticated") || it.contains("trusted=yes") })
            assertEquals("fixture global source\n", main.readText())
            assertEquals("fixture custom source\n", custom.readText())
            assertEquals("fixture Hermes source\n", hermes.readText())
            assertEquals(0, run(false))
            assertEquals(3, root.resolve("apt/sources.list").readLines().size)
            log.writeText("")
            assertEquals(9, run(false, fail = true))
            assertFalse(log.readLines().contains("install"), "国内源更新失败后不能继续安装或回退到其它源")
        } finally { folder.deleteRecursively() }
    }
}
