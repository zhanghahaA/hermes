package com.hermes.mobile.ui.components

/**
 * 模型回复里常见、但标准 Markdown 不认的写法，解析前先整理成能认的样子：
 * - 数学公式（`$…$`、`\(…\)`、`$$…$$`、`\[…\]`）：换成能读的符号（a² + b² = c²、√、×、≤……），整块的放进“公式”代码块；
 * - 脚注（`[^1]` 和 `[^1]: 内容`）：标成上标，内容不丢；
 * - 标题少了空格（`###标题`）；
 * - 有序列表下面只缩进两格的子项（标准要求缩进到正文对齐）。
 * 代码块和行内代码里的内容不动。
 */
internal fun chatFixes(source: String): String {
    val lines = source.lines()
    val out = ArrayList<String>(lines.size + 4)
    var inFence = false
    var orderedContentIndent = -1
    var i = 0
    while (i < lines.size) {
        var line = lines[i]
        val trimmed = line.trimStart()
        if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
            inFence = !inFence
            out += line
            i++
            continue
        }
        if (inFence) {
            out += line
            i++
            continue
        }

        // 整块公式：$$ … $$ 或 \[ … \]（可以在同一行）
        val blockOpen = when {
            trimmed.startsWith("$$") -> "$$"
            trimmed.startsWith("\\[") -> "\\]"
            else -> null
        }
        if (blockOpen != null) {
            val opener = if (blockOpen == "$$") "$$" else "\\["
            val rest = trimmed.removePrefix(opener)
            val body = StringBuilder()
            var end = -1
            if (rest.contains(blockOpen)) {
                body.append(rest.substringBefore(blockOpen))
                end = i
            } else {
                body.append(rest)
                var j = i + 1
                while (j < lines.size) {
                    val l = lines[j]
                    if (l.contains(blockOpen)) {
                        body.append('\n').append(l.substringBefore(blockOpen))
                        end = j
                        break
                    }
                    body.append('\n').append(l)
                    j++
                }
            }
            if (end >= 0) {
                val math = latexToText(body.toString()).lines().map { it.trim() }.filter { it.isNotEmpty() }
                if (out.isNotEmpty() && out.last().isNotBlank()) out += ""
                out += "```公式"
                out += math
                out += "```"
                out += ""
                i = end + 1
                continue
            }
        }

        // 标题少了空格：##标题 → ## 标题（单个 # 可能是话题标签，不动）
        Regex("""^(\s{0,3})(#{2,6})([^#\s])""").find(line)?.let { m ->
            line = m.groupValues[1] + m.groupValues[2] + " " + line.substring(m.groupValues[1].length + m.groupValues[2].length)
        }

        // 有序列表下只缩进两格的子项：补到正文对齐的位置，才算嵌套
        val ordered = Regex("""^(\s*)(\d{1,9}[.)])\s+""").find(line)
        val indent = line.length - line.trimStart().length
        when {
            ordered != null -> orderedContentIndent = ordered.value.length
            line.isBlank() -> Unit
            indent == 0 -> orderedContentIndent = -1
            orderedContentIndent > 0 && indent < orderedContentIndent && Regex("""^\s+([-*+]|\d{1,9}[.)])\s""").containsMatchIn(line) ->
                line = " ".repeat(orderedContentIndent) + line.trimStart()
        }

        // 脚注：定义行 [^1]: 内容；正文里的 [^1]
        Regex("""^\[\^([^\]\s]+)]:\s?(.*)$""").find(line.trim())?.let { m ->
            line = "<sup>[${m.groupValues[1]}]</sup> " + m.groupValues[2]
        } ?: run {
            line = outsideInlineCode(line) { part ->
                part.replace(Regex("""\[\^([^\]\s]+)](?!:)""")) { "<sup>[${it.groupValues[1]}]</sup>" }
            }
        }

        // 行内公式
        line = outsideInlineCode(line, ::inlineMath)
        out += line
        i++
    }
    return out.joinToString("\n")
}

/** 只改行内代码（`…`）以外的部分。 */
private fun outsideInlineCode(line: String, transform: (String) -> String): String {
    if ('`' !in line) return transform(line)
    val sb = StringBuilder()
    var i = 0
    var plainStart = 0
    while (i < line.length) {
        if (line[i] == '`') {
            val ticks = line.drop(i).takeWhile { it == '`' }.length
            val close = line.indexOf("`".repeat(ticks), i + ticks)
            if (close < 0) break
            sb.append(transform(line.substring(plainStart, i)))
            sb.append(line, i, close + ticks)
            i = close + ticks
            plainStart = i
            continue
        }
        i++
    }
    sb.append(transform(line.substring(plainStart)))
    return sb.toString()
}

// $…$：开头 $ 后面不是空白，结尾 $ 前面不是空白、后面不是数字（避免把“$5 到 $10”当公式）
private val dollarMath = Regex("""(?<![\\$\w])\$(?![\s$])([^$\n]+?)(?<![\s\\])\$(?![\d$])""")
private val parenMath = Regex("""\\\((.+?)\\\)""")
private val mathish = Regex("""[A-Za-z\\^_=+<>]""")

private fun inlineMath(text: String): String {
    var t = text
    if ("\\(" in t) t = t.replace(parenMath) { formula(it.groupValues[1]) }
    if ('$' in t) t = t.replace(dollarMath) { m -> if (mathish.containsMatchIn(m.groupValues[1])) formula(m.groupValues[1]) else m.value }
    return t
}

/** 行内公式：换成能读的符号，转义掉 Markdown 的特殊字符，用斜体显示。 */
private fun formula(latex: String): String {
    val plain = latexToText(latex).replace("\n", " ").trim()
    val escaped = plain.replace(Regex("""([\\`*_\[\]<>|~#])""")) { "\\" + it.value }
    return "<i>$escaped</i>"
}

private val Greek = mapOf(
    "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ", "epsilon" to "ε", "varepsilon" to "ε", "zeta" to "ζ", "eta" to "η",
    "theta" to "θ", "vartheta" to "ϑ", "iota" to "ι", "kappa" to "κ", "lambda" to "λ", "mu" to "μ", "nu" to "ν", "xi" to "ξ", "pi" to "π",
    "rho" to "ρ", "sigma" to "σ", "tau" to "τ", "upsilon" to "υ", "phi" to "φ", "varphi" to "φ", "chi" to "χ", "psi" to "ψ", "omega" to "ω",
    "Gamma" to "Γ", "Delta" to "Δ", "Theta" to "Θ", "Lambda" to "Λ", "Xi" to "Ξ", "Pi" to "Π", "Sigma" to "Σ", "Phi" to "Φ", "Psi" to "Ψ", "Omega" to "Ω",
)

private val Symbols = mapOf(
    "times" to "×", "cdot" to "·", "div" to "÷", "pm" to "±", "mp" to "∓", "le" to "≤", "leq" to "≤", "ge" to "≥", "geq" to "≥",
    "ne" to "≠", "neq" to "≠", "approx" to "≈", "equiv" to "≡", "sim" to "∼", "propto" to "∝", "infty" to "∞", "sum" to "∑", "prod" to "∏",
    "int" to "∫", "oint" to "∮", "partial" to "∂", "nabla" to "∇", "to" to "→", "rightarrow" to "→", "leftarrow" to "←", "Rightarrow" to "⇒",
    "Leftarrow" to "⇐", "Leftrightarrow" to "⇔", "iff" to "⇔", "implies" to "⇒", "mapsto" to "↦", "in" to "∈", "notin" to "∉", "ni" to "∋",
    "subset" to "⊂", "subseteq" to "⊆", "supset" to "⊃", "supseteq" to "⊇", "cup" to "∪", "cap" to "∩", "emptyset" to "∅", "varnothing" to "∅",
    "forall" to "∀", "exists" to "∃", "neg" to "¬", "land" to "∧", "wedge" to "∧", "lor" to "∨", "vee" to "∨", "circ" to "∘", "degree" to "°",
    "ldots" to "…", "cdots" to "⋯", "dots" to "…", "vdots" to "⋮", "angle" to "∠", "perp" to "⊥", "parallel" to "∥", "triangle" to "△",
    "prime" to "′", "hbar" to "ħ", "ell" to "ℓ", "Re" to "ℜ", "Im" to "ℑ", "aleph" to "ℵ", "therefore" to "∴", "because" to "∵",
    "langle" to "⟨", "rangle" to "⟩", "lfloor" to "⌊", "rfloor" to "⌋", "lceil" to "⌈", "rceil" to "⌉", "mid" to "∣",
    "quad" to "  ", "qquad" to "    ", "," to " ", ";" to " ", ":" to " ", "!" to "", " " to " ",
    "%" to "%", "{" to "{", "}" to "}", "$" to "$", "&" to "&", "#" to "#", "_" to "_",
)

private val Functions = setOf("sin", "cos", "tan", "cot", "sec", "csc", "arcsin", "arccos", "arctan", "sinh", "cosh", "tanh",
    "log", "ln", "lg", "exp", "lim", "max", "min", "sup", "inf", "det", "gcd", "deg", "arg", "dim", "ker", "Pr")

private val Superscripts = mapOf(
    '0' to '⁰', '1' to '¹', '2' to '²', '3' to '³', '4' to '⁴', '5' to '⁵', '6' to '⁶', '7' to '⁷', '8' to '⁸', '9' to '⁹',
    '+' to '⁺', '-' to '⁻', '=' to '⁼', '(' to '⁽', ')' to '⁾', 'n' to 'ⁿ', 'i' to 'ⁱ', 'x' to 'ˣ', 'k' to 'ᵏ', 'T' to 'ᵀ',
)
private val Subscripts = mapOf(
    '0' to '₀', '1' to '₁', '2' to '₂', '3' to '₃', '4' to '₄', '5' to '₅', '6' to '₆', '7' to '₇', '8' to '₈', '9' to '₉',
    '+' to '₊', '-' to '₋', '=' to '₌', '(' to '₍', ')' to '₎', 'i' to 'ᵢ', 'j' to 'ⱼ', 'n' to 'ₙ', 'k' to 'ₖ', 'x' to 'ₓ',
    'a' to 'ₐ', 'e' to 'ₑ', 'o' to 'ₒ', 'm' to 'ₘ', 't' to 'ₜ',
)

/**
 * 把常见的 LaTeX 换成普通文字：\frac{a}{b} → a/b，x^{2} → x²，\sqrt{x} → √x，希腊字母和运算符换成对应符号。
 * 不认识的命令去掉反斜杠留下名字。做不到排版级别，但不会再是一串看不懂的代码。
 */
internal fun latexToText(latex: String): String {
    val p = LatexReader(latex)
    return p.readAll().replace(Regex("""[ \t]{2,}"""), " ").trim()
}

private class LatexReader(private val s: String) {
    private var i = 0

    fun readAll(): String = readUntil(null)

    /** 读到 [stop]（右花括号）为止。 */
    private fun readUntil(stop: Char?): String {
        val sb = StringBuilder()
        while (i < s.length) {
            val c = s[i]
            when {
                c == stop -> { i++; return sb.toString() }
                c == '{' -> { i++; sb.append(readUntil('}')) }
                c == '\\' -> sb.append(command())
                c == '^' -> { i++; sb.append(script(Superscripts, "^")) }
                c == '_' -> { i++; sb.append(script(Subscripts, "_")) }
                c == '&' -> i++
                c == '~' -> { i++; sb.append(' ') }
                else -> { sb.append(c); i++ }
            }
        }
        return sb.toString()
    }

    /** 下一个“参数”：{…} 或单个字符 / 命令。 */
    private fun argument(): String {
        while (i < s.length && s[i] == ' ') i++
        if (i >= s.length) return ""
        return when (s[i]) {
            '{' -> { i++; readUntil('}') }
            '\\' -> command()
            else -> s[i++].toString()
        }
    }

    private fun script(table: Map<Char, Char>, mark: String): String {
        val arg = argument()
        if (arg == "∘" || arg == "\\circ") return "°"
        val mapped = arg.map { table[it] }
        return if (arg.isNotEmpty() && mapped.all { it != null }) mapped.joinToString("") else if (arg.length == 1) "$mark$arg" else "$mark($arg)"
    }

    private fun group(text: String) = if (text.length <= 1 || text.all { it.isLetterOrDigit() || it == '.' }) text else "($text)"

    private fun command(): String {
        i++ // 反斜杠
        if (i >= s.length) return ""
        if (!s[i].isLetter()) {
            val c = s[i++]
            if (c == '\\') return "\n"
            return Symbols[c.toString()] ?: c.toString()
        }
        val start = i
        while (i < s.length && s[i].isLetter()) i++
        val name = s.substring(start, i)
        return when (name) {
            "frac", "dfrac", "tfrac" -> {
                val a = argument()
                val b = argument()
                "${group(a)}/${group(b)}"
            }
            "sqrt" -> {
                var root = ""
                if (i < s.length && s[i] == '[') {
                    val end = s.indexOf(']', i)
                    if (end > 0) {
                        root = s.substring(i + 1, end)
                        i = end + 1
                    }
                }
                val body = argument()
                (if (root.isNotEmpty()) root.map { Superscripts[it] ?: it }.joinToString("") else "") + "√" + group(body)
            }
            "text", "mathrm", "mathbf", "mathit", "mathsf", "mathtt", "mathcal", "mathbb", "operatorname", "textbf", "textit", "boldsymbol",
            "hat", "bar", "overline", "underline", "vec", "tilde", "dot", "ddot", "widehat", "widetilde", "mbox", "displaystyle",
            -> if (name == "displaystyle") "" else argument()
            "left", "right", "big", "Big", "bigg", "Bigg", "bigl", "bigr", "Bigl", "Bigr" -> {
                // 括号大小写法：留下括号本身
                if (i < s.length && s[i] == '.') { i++; "" } else ""
            }
            "begin", "end" -> { argument(); if (name == "end") "\n" else "" }
            "binom" -> {
                val a = argument()
                val b = argument()
                "C($a, $b)"
            }
            in Greek -> Greek.getValue(name)
            in Symbols -> Symbols.getValue(name)
            in Functions -> "$name "
            else -> name
        }
    }
}
