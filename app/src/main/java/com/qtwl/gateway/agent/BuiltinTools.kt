package com.qtwl.gateway.agent

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * 内置工具集合
 *
 * 全部为无副作用的纯计算/查询类工具，
 * 不需要用户确认即可由模型自动调用。
 */
object BuiltinTools {

    fun all(): List<AgentTool> = listOf(
        currentTimeTool(),
        calculatorTool(),
        textTransformTool(),
        randomNumberTool(),
        unitConvertTool()
    )

    // ---------------- 1. 当前时间 ----------------
    private fun currentTimeTool() = AgentTool(
        name = "get_current_time",
        description = "获取当前日期和时间。当用户询问现在几点、今天日期、星期几时使用。",
        parameters = listOf(
            ToolParam(
                name = "timezone",
                type = "string",
                description = "时区，如 Asia/Shanghai、UTC。默认跟随设备时区",
                required = false
            )
        )
    ) { args ->
        try {
            val tz = args["timezone"]?.takeIf { it.isNotBlank() }
            val zone = if (tz != null) java.util.TimeZone.getTimeZone(tz) else java.util.TimeZone.getDefault()
            val now = Date()
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss EEEE", Locale.CHINA)
            sdf.timeZone = zone
            val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).apply { timeZone = zone }
            ToolResult.ok(
                "当前时间：${sdf.format(now)}\n" +
                "ISO 8601：${iso.format(now)}\n" +
                "时区：${zone.getDisplayName(false, java.util.TimeZone.SHORT)}\n" +
                "Unix 时间戳：${now.time / 1000}"
            )
        } catch (e: Exception) {
            ToolResult.fail("获取时间失败：${e.message}")
        }
    }

    // ---------------- 2. 计算器 ----------------
    private fun calculatorTool() = AgentTool(
        name = "calculate",
        description = "计算数学表达式。支持 + - * / ^ ( )、百分号、sqrt/sin/cos/tan/log/ln/abs 等函数。" +
            "当需要进行精确数值计算时使用，不要自己心算。",
        parameters = listOf(
            ToolParam(
                name = "expression",
                type = "string",
                description = "数学表达式，例如：(3+5)*2、sqrt(16)、sin(3.14/2)、2^10"
            )
        )
    ) { args ->
        val expr = args["expression"].orEmpty().trim()
        if (expr.isEmpty()) return@AgentTool ToolResult.fail("表达式为空")
        try {
            val value = MathEvaluator.eval(expr)
            if (value.isNaN()) {
                ToolResult.fail("计算结果为 NaN，请检查表达式：$expr")
            } else if (value.isInfinite()) {
                ToolResult.fail("计算结果为无穷大，请检查是否除以 0：$expr")
            } else {
                // 整数结果不显示小数点
                val text = if (abs(value - Math.round(value)) < 1e-9 && abs(value) < 1e15) {
                    Math.round(value).toString()
                } else {
                    "%.10f".format(value).trimEnd('0').trimEnd('.')
                }
                ToolResult.ok("$expr = $text")
            }
        } catch (e: Exception) {
            ToolResult.fail("表达式解析失败：${e.message}\n表达式：$expr")
        }
    }

    // ---------------- 3. 文本处理 ----------------
    private fun textTransformTool() = AgentTool(
        name = "transform_text",
        description = "对文本做变换或统计。可用于统计字数、转大小写、反转、Base64 编解码、提取关键词等。",
        parameters = listOf(
            ToolParam(
                name = "text",
                type = "string",
                description = "要处理的文本"
            ),
            ToolParam(
                name = "operation",
                type = "string",
                description = "操作类型",
                enumValues = listOf(
                    "count", "upper", "lower", "reverse", "trim",
                    "base64_encode", "base64_decode", "word_count"
                )
            )
        )
    ) { args ->
        val text = args["text"].orEmpty()
        when (args["operation"].orEmpty()) {
            "count" -> ToolResult.ok("字符数：${text.length}\n不含空格：${text.replace(" ", "").length}")
            "upper" -> ToolResult.ok(text.uppercase())
            "lower" -> ToolResult.ok(text.lowercase())
            "reverse" -> ToolResult.ok(text.reversed())
            "trim" -> ToolResult.ok(text.trim())
            "word_count" -> {
                val words = text.split(Regex("[\\s,，。.!！?？;；:：]+")).filter { it.isNotBlank() }
                ToolResult.ok("词数：${words.size}\n去重后：${words.distinct().size}")
            }
            "base64_encode" -> ToolResult.ok(
                android.util.Base64.encodeToString(text.toByteArray(), android.util.Base64.NO_WRAP)
            )
            "base64_decode" -> try {
                ToolResult.ok(String(android.util.Base64.decode(text, android.util.Base64.DEFAULT)))
            } catch (e: Exception) {
                ToolResult.fail("Base64 解码失败：${e.message}")
            }
            else -> ToolResult.fail("未知操作：${args["operation"]}")
        }
    }

    // ---------------- 4. 随机数 ----------------
    private fun randomNumberTool() = AgentTool(
        name = "random_number",
        description = "生成随机数。用于抽签、掷骰子、随机选择等场景。",
        parameters = listOf(
            ToolParam("min", "integer", "最小值（含），默认 1", required = false),
            ToolParam("max", "integer", "最大值（含），默认 100", required = false)
        )
    ) { args ->
        val min = args["min"]?.toIntOrNull() ?: 1
        val max = args["max"]?.toIntOrNull() ?: 100
        if (min > max) return@AgentTool ToolResult.fail("最小值不能大于最大值")
        val value = (min..max).random()
        ToolResult.ok("随机结果：$value（范围 $min..$max）")
    }

    // ---------------- 5. 单位换算 ----------------
    private fun unitConvertTool() = AgentTool(
        name = "convert_unit",
        description = "单位换算。支持长度、重量、温度、数据存储、时间等常见单位。",
        parameters = listOf(
            ToolParam("value", "number", "数值"),
            ToolParam("from", "string", "源单位，如 m/km/cm/mm/kg/g/lb/celsius/fahrenheit/kb/mb/gb/hour/minute"),
            ToolParam("to", "string", "目标单位")
        )
    ) { args ->
        val value = args["value"]?.toDoubleOrNull()
            ?: return@AgentTool ToolResult.fail("数值无效：${args["value"]}")
        val from = args["from"].orEmpty().lowercase()
        val to = args["to"].orEmpty().lowercase()

        // 温度需要特殊处理（非线性）
        val tempResult = convertTemperature(value, from, to)
        if (tempResult != null) {
            return@AgentTool ToolResult.ok("${fmt(value)} $from = ${fmt(tempResult)} $to")
        }

        val fromFactor = unitFactor(from)
        val toFactor = unitFactor(to)
        if (fromFactor == null) return@AgentTool ToolResult.fail("不支持的源单位：$from")
        if (toFactor == null) return@AgentTool ToolResult.fail("不支持的目标单位：$to")

        val result = value * fromFactor / toFactor
        ToolResult.ok("${fmt(value)} $from = ${fmt(result)} $to")
    }

    private fun fmt(v: Double): String =
        if (abs(v - Math.round(v)) < 1e-9 && abs(v) < 1e15) Math.round(v).toString()
        else "%.6f".format(v).trimEnd('0').trimEnd('.')

    private fun convertTemperature(v: Double, from: String, to: String): Double? {
        val isTemp = setOf("c", "celsius", "f", "fahrenheit", "k", "kelvin")
        if (from !in isTemp || to !in isTemp) return null
        // 先统一转为摄氏度
        val celsius = when (from) {
            "c", "celsius" -> v
            "f", "fahrenheit" -> (v - 32) * 5.0 / 9.0
            "k", "kelvin" -> v - 273.15
            else -> return null
        }
        return when (to) {
            "c", "celsius" -> celsius
            "f", "fahrenheit" -> celsius * 9.0 / 5.0 + 32
            "k", "kelvin" -> celsius + 273.15
            else -> null
        }
    }

    private fun unitFactor(u: String): Double? = when (u) {
        // 长度（基准：米）
        "m", "meter", "米" -> 1.0
        "km", "kilometer", "千米", "公里" -> 1000.0
        "cm", "厘米" -> 0.01
        "mm", "毫米" -> 0.001
        "inch", "in", "英寸" -> 0.0254
        "foot", "ft", "英尺" -> 0.3048
        "mile", "英里" -> 1609.344
        // 重量（基准：千克）
        "kg", "千克", "公斤" -> 1.0
        "g", "gram", "克" -> 0.001
        "mg", "毫克" -> 0.000001
        "lb", "pound", "磅" -> 0.45359237
        "oz", "盎司" -> 0.028349523125
        // 数据存储（基准：字节）
        "byte", "b", "字节" -> 1.0
        "kb" -> 1024.0
        "mb" -> 1024.0 * 1024
        "gb" -> 1024.0 * 1024 * 1024
        "tb" -> 1024.0 * 1024 * 1024 * 1024
        // 时间（基准：秒）
        "second", "s", "秒" -> 1.0
        "minute", "min", "分钟" -> 60.0
        "hour", "h", "小时" -> 3600.0
        "day", "天" -> 86400.0
        else -> null
    }
}

/**
 * 极简数学表达式求值器（递归下降）
 *
 * 支持：+ - * / ^ 、括号、一元负号、百分号后缀、常见函数
 * 不依赖任何第三方库，可安全用于用户输入。
 */
object MathEvaluator {

    fun eval(expression: String): Double {
        val parser = Parser(expression.replace(" ", ""))
        val result = parser.parseExpression()
        parser.expectEnd()
        return result
    }

    private class Parser(private val src: String) {
        private var pos = 0

        fun parseExpression(): Double {
            var left = parseTerm()
            while (pos < src.length) {
                val c = src[pos]
                if (c == '+') {
                    pos++
                    left += parseTerm()
                } else if (c == '-') {
                    pos++
                    left -= parseTerm()
                } else break
            }
            return left
        }

        private fun parseTerm(): Double {
            var left = parsePower()
            while (pos < src.length) {
                val c = src[pos]
                if (c == '*') {
                    pos++
                    left *= parsePower()
                } else if (c == '/') {
                    pos++
                    val divisor = parsePower()
                    left = if (divisor == 0.0) Double.NaN else left / divisor
                } else if (c == '%') {
                    pos++
                    left /= 100.0
                } else break
            }
            return left
        }

        private fun parsePower(): Double {
            val base = parseUnary()
            if (pos < src.length && src[pos] == '^') {
                pos++
                val exp = parsePower()   // 右结合
                return base.pow(exp)
            }
            return base
        }

        private fun parseUnary(): Double {
            if (pos < src.length && src[pos] == '-') {
                pos++
                return -parseUnary()
            }
            if (pos < src.length && src[pos] == '+') {
                pos++
                return parseUnary()
            }
            return parseAtom()
        }

        private fun parseAtom(): Double {
            if (pos >= src.length) throw IllegalArgumentException("表达式意外结束")
            val c = src[pos]

            if (c == '(') {
                pos++
                val v = parseExpression()
                if (pos >= src.length || src[pos] != ')') {
                    throw IllegalArgumentException("括号不匹配")
                }
                pos++
                return v
            }

            // 函数调用
            if (c.isLetter()) {
                val start = pos
                while (pos < src.length && (src[pos].isLetterOrDigit() || src[pos] == '_')) pos++
                val name = src.substring(start, pos).lowercase()
                if (pos < src.length && src[pos] == '(') {
                    pos++
                    val arg = parseExpression()
                    if (pos >= src.length || src[pos] != ')') {
                        throw IllegalArgumentException("函数 $name 缺少右括号")
                    }
                    pos++
                    return applyFunction(name, arg)
                }
                // 常量
                return when (name) {
                    "pi", "π" -> Math.PI
                    "e" -> Math.E
                    else -> throw IllegalArgumentException("未知标识符：$name")
                }
            }

            // 数字
            val start = pos
            while (pos < src.length && (src[pos].isDigit() || src[pos] == '.')) pos++
            if (start == pos) throw IllegalArgumentException("无法解析字符：$c")
            return src.substring(start, pos).toDoubleOrNull()
                ?: throw IllegalArgumentException("数字格式错误：${src.substring(start, pos)}")
        }

        private fun applyFunction(name: String, arg: Double): Double = when (name) {
            "sqrt" -> sqrt(arg)
            "abs" -> abs(arg)
            "sin" -> sin(arg)
            "cos" -> cos(arg)
            "tan" -> tan(arg)
            "ln" -> ln(arg)
            "log", "log10" -> kotlin.math.log10(arg)
            "log2" -> kotlin.math.log2(arg)
            "floor" -> kotlin.math.floor(arg)
            "ceil" -> kotlin.math.ceil(arg)
            "round" -> Math.round(arg).toDouble()
            "exp" -> kotlin.math.exp(arg)
            "degrees" -> Math.toDegrees(arg)
            "radians" -> Math.toRadians(arg)
            else -> throw IllegalArgumentException("未知函数：$name")
        }

        fun expectEnd() {
            if (pos < src.length) {
                throw IllegalArgumentException("表达式末尾有多余字符：${src.substring(pos)}")
            }
        }
    }
}