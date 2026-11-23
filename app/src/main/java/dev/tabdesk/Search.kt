package dev.tabdesk

import java.math.BigDecimal
import java.math.MathContext
import java.util.Locale
import kotlin.math.abs

/** Tiny calculator for Start search: + − × ÷ ^ %, brackets and decimals. */
object Calc {
    /** The formatted answer, or null when [input] isn't a sum (a lone number, an app name, a half-typed sum). */
    fun answer(input: String): String? {
        var s = input.replace(" ", "").replace(",", "")
            .replace('×', '*').replace('÷', '/').replace('−', '-')
        s = s.replace(Regex("(?<=[\\d.)])[xX](?=[\\d.(])"), "*")
        if (s.isEmpty() || s.none { it.isDigit() } || s.any { it !in "0123456789.+-*/^%()" }) return null
        if (!Regex("[+*/^%()]|[\\d.)]-").containsMatchIn(s)) return null
        val p = Parser(s)
        val v = try { p.expr() } catch (e: Exception) { return null }
        if (p.i != s.length || v.isNaN() || v.isInfinite()) return null
        return format(v)
    }

    private fun format(v: Double): String {
        if (v == 0.0) return "0"
        if (abs(v) >= 1e15 || abs(v) < 1e-6) return String.format(Locale.US, "%.6e", v)
        return BigDecimal(v).round(MathContext(12)).stripTrailingZeros().toPlainString()
    }

    /** expr → term (± term)*; term → unary (×÷ unary)*; unary → −unary | power; power → postfix (^ unary)?; postfix → primary %* */
    private class Parser(val s: String) {
        var i = 0
        private fun eat(c: Char): Boolean = if (i < s.length && s[i] == c) { i++; true } else false

        fun expr(): Double {
            var v = term()
            while (true) v = when { eat('+') -> v + term(); eat('-') -> v - term(); else -> return v }
        }

        private fun term(): Double {
            var v = unary()
            while (true) v = when { eat('*') -> v * unary(); eat('/') -> v / unary(); else -> return v }
        }

        private fun unary(): Double = when { eat('-') -> -unary(); eat('+') -> unary(); else -> power() }

        private fun power(): Double { val b = postfix(); return if (eat('^')) Math.pow(b, unary()) else b }

        private fun postfix(): Double { var v = primary(); while (eat('%')) v /= 100; return v }

        private fun primary(): Double {
            if (eat('(')) { val v = expr(); require(eat(')')); return v }
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            require(i > start)
            return s.substring(start, i).toDouble()
        }
    }
}

/** "web:" in Start search: open a site in the default browser, or search the web for anything else. */
object WebAddress {
    const val PREFIX = "web:"
    private val host = Regex("^(https?://)?([a-z0-9-]+\\.)+[a-z][a-z0-9-]*(:\\d+)?([/?#].*)?$", RegexOption.IGNORE_CASE)
    private val ip = Regex("^(https?://)?\\d{1,3}(\\.\\d{1,3}){3}(:\\d+)?([/?#].*)?$", RegexOption.IGNORE_CASE)

    /** The text after "web:", or null if [raw] doesn't start with it. */
    fun query(raw: String): String? = if (raw.startsWith(PREFIX, ignoreCase = true)) raw.substring(PREFIX.length).trim() else null

    fun looksLike(t: String) = ' ' !in t && (host.matches(t) || ip.matches(t))

    fun normalize(t: String) = if (t.startsWith("http://", true) || t.startsWith("https://", true)) t else "https://$t"
}
