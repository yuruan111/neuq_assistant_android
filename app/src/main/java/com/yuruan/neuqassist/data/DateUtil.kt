package com.yuruan.neuqassist.data

import java.text.Collator
import java.util.Locale

/**
 * 日期工具 —— 移植自 HarmonyOS 端 common/DateUtil.ets（Rust src/fetcher.rs）
 *
 * 教务系统查询使用北京时间（UTC+8），通过 UTC 侧函数计算，避免设备本地时区干扰。
 */
object DateUtil {

    private val WEEKDAYS = arrayOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")

    private val BEIJING_ZONE: java.time.ZoneId = java.time.ZoneId.of("Asia/Shanghai")

    private fun pad2(n: Int) = if (n < 10) "0$n" else "$n"

    /** 获取指定偏移天数的北京日期字符串，格式 YYYY-MM-DD */
    fun beijingDate(dayOffset: Int): String {
        val d = java.time.LocalDate.now(BEIJING_ZONE).plusDays(dayOffset.toLong())
        return "${d.year}-${pad2(d.monthValue)}-${pad2(d.dayOfMonth)}"
    }

    /** 日期偏移量的展示标签：今天/明天/后天/MM-DD 周X */
    fun dayLabel(dayOffset: Int): String {
        val d = java.time.LocalDate.now(BEIJING_ZONE).plusDays(dayOffset.toLong())
        val prefix = when (dayOffset) {
            0 -> "今天"
            1 -> "明天"
            2 -> "后天"
            else -> ""
        }
        val dow = WEEKDAYS[d.dayOfWeek.value % 7]
        val label = "${pad2(d.monthValue)}-${pad2(d.dayOfMonth)} $dow"
        return if (prefix.isEmpty()) label else "$prefix $label"
    }

    /** YYYY-MM-DD → MM-DD 周X（用于结果标题） */
    fun dateToCn(dateStr: String): String {
        val parts = dateStr.split("-")
        if (parts.size != 3) return dateStr
        val d = java.time.LocalDate.of(parts[0].toInt(), parts[1].toInt(), parts[2].toInt())
        val dow = WEEKDAYS[d.dayOfWeek.value % 7]
        return "${parts[1]}-${parts[2]} $dow"
    }

    /** 生成当前时间戳文本，格式 yyyy/MM/dd HH:mm */
    fun stampNow(): String {
        val now = java.time.LocalDateTime.now()
        return "${now.year}/${pad2(now.monthValue)}/${pad2(now.dayOfMonth)} " +
            "${pad2(now.hour)}:${pad2(now.minute)}"
    }
}

/** 中文排序比较器（对应 ArkTS localeCompare('zh-Hans-CN')） */
val ZH_COLLATOR: Collator = Collator.getInstance(Locale.CHINA)

/** 数值感知的中文排序（对应 ArkTS localeCompare(b, 'zh-Hans-CN', {numeric:true})） */
fun compareZhNumeric(a: String, b: String): Int {
    if (a == b) return 0
    var i = 0
    var j = 0
    while (i < a.length && j < b.length) {
        val ca = a[i]
        val cb = b[j]
        if (ca.isDigit() && cb.isDigit()) {
            // 提取连续数字段按数值比较
            var si = i
            while (si < a.length && a[si].isDigit()) si++
            var sj = j
            while (sj < b.length && b[sj].isDigit()) sj++
            val na = a.substring(i, si).trimStart('0').ifEmpty { "0" }
            val nb = b.substring(j, sj).trimStart('0').ifEmpty { "0" }
            val lenCmp = na.length.compareTo(nb.length)
            val cmp = if (lenCmp != 0) lenCmp else na.compareTo(nb)
            if (cmp != 0) return cmp
            i = si
            j = sj
        } else {
            val cmp = ca.compareTo(cb)
            if (cmp != 0) return cmp
            i++
            j++
        }
    }
    return (a.length - i).compareTo(b.length - j)
}
