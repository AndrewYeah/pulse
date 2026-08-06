package com.andrew.proxyapp.manager

import com.andrew.proxyapp.data.RuntimeErrorCategory
import java.util.Locale

object ErrorTranslator {
    fun userMessage(category: RuntimeErrorCategory?, raw: String?, locale: Locale = Locale.getDefault()): String {
        val text = raw.orEmpty().lowercase()
        val kind = when {
            "permission" in text || "vpn" in text || "protect" in text -> ErrorKind.PERMISSION
            "config" in text || "invalid" in text -> ErrorKind.CONFIGURATION
            "timeout" in text || "network" in text || "interface" in text -> ErrorKind.NETWORK
            "binder" in text || "deadobject" in text || "closed" in text -> ErrorKind.DISCONNECTED
            category == RuntimeErrorCategory.CORE -> ErrorKind.CORE
            else -> ErrorKind.START
        }
        return messages[locale.language]?.get(kind) ?: messages.getValue("en").getValue(kind)
    }

    private enum class ErrorKind { PERMISSION, CONFIGURATION, NETWORK, DISCONNECTED, CORE, START }

    private val messages = mapOf(
        "en" to mapOf(
            ErrorKind.PERMISSION to "VPN permission or socket protection failed",
            ErrorKind.CONFIGURATION to "The proxy configuration is invalid",
            ErrorKind.NETWORK to "The network is unavailable or the node timed out",
            ErrorKind.DISCONNECTED to "The proxy core disconnected and will be restarted",
            ErrorKind.CORE to "The proxy core reported an error",
            ErrorKind.START to "The proxy could not start"
        ),
        "zh" to mapOf(
            ErrorKind.PERMISSION to "VPN 权限或网络保护失败",
            ErrorKind.CONFIGURATION to "代理配置无效",
            ErrorKind.NETWORK to "网络不可用或节点连接超时",
            ErrorKind.DISCONNECTED to "代理内核已断开，正在尝试恢复",
            ErrorKind.CORE to "代理内核报告错误",
            ErrorKind.START to "代理启动失败"
        ),
        "ru" to mapOf(
            ErrorKind.PERMISSION to "Не удалось получить разрешение VPN или защитить сокет",
            ErrorKind.CONFIGURATION to "Недопустимая конфигурация прокси",
            ErrorKind.NETWORK to "Сеть недоступна или истекло время ожидания узла",
            ErrorKind.DISCONNECTED to "Ядро прокси отключилось и будет перезапущено",
            ErrorKind.CORE to "Ядро прокси сообщило об ошибке",
            ErrorKind.START to "Не удалось запустить прокси"
        ),
        "fa" to mapOf(
            ErrorKind.PERMISSION to "مجوز VPN یا محافظت سوکت ناموفق بود",
            ErrorKind.CONFIGURATION to "پیکربندی پراکسی نامعتبر است",
            ErrorKind.NETWORK to "شبکه در دسترس نیست یا زمان اتصال گره تمام شد",
            ErrorKind.DISCONNECTED to "هسته پراکسی قطع شد و دوباره راه‌اندازی می‌شود",
            ErrorKind.CORE to "هسته پراکسی خطا گزارش کرد",
            ErrorKind.START to "راه‌اندازی پراکسی ناموفق بود"
        ),
        "az" to mapOf(
            ErrorKind.PERMISSION to "VPN icazəsi və ya soket qorunması uğursuz oldu",
            ErrorKind.CONFIGURATION to "Proksi konfiqurasiyası yanlışdır",
            ErrorKind.NETWORK to "Şəbəkə əlçatan deyil və ya qovşaq vaxt aşımına uğradı",
            ErrorKind.DISCONNECTED to "Proksi nüvəsi ayrıldı və yenidən başladılacaq",
            ErrorKind.CORE to "Proksi nüvəsi xəta bildirdi",
            ErrorKind.START to "Proksini başlatmaq mümkün olmadı"
        ),
        "ar" to mapOf(
            ErrorKind.PERMISSION to "فشل إذن VPN أو حماية المقبس",
            ErrorKind.CONFIGURATION to "إعداد الوكيل غير صالح",
            ErrorKind.NETWORK to "الشبكة غير متاحة أو انتهت مهلة العقدة",
            ErrorKind.DISCONNECTED to "انقطع اتصال نواة الوكيل وستتم إعادة تشغيلها",
            ErrorKind.CORE to "أبلغت نواة الوكيل عن خطأ",
            ErrorKind.START to "تعذر تشغيل الوكيل"
        )
    )
}
