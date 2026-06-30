package com.qtwl.gateway.utils

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import com.qtwl.gateway.GatewayApplication
import java.util.Locale

/**
 * 多语言管理器 —— 支持自动跟随系统 + 手动切换
 */
enum class AppLanguage(
    val code: String,
    val displayName: String,
    val locale: Locale
) {
    ZH_CN("zh", "简体中文", Locale.SIMPLIFIED_CHINESE),
    ZH_TW("zh-tw", "繁體中文", Locale.TRADITIONAL_CHINESE),
    EN("en", "English", Locale.ENGLISH),
    JA("ja", "日本語", Locale.JAPANESE),
    KO("ko", "한국어", Locale.KOREAN),
    ES("es", "Español", Locale("es")),
    FR("fr", "Français", Locale("fr")),
    DE("de", "Deutsch", Locale("de")),
    RU("ru", "Русский", Locale("ru")),
    PT("pt", "Português", Locale("pt")),
    VI("vi", "Tiếng Việt", Locale("vi")),
    TH("th", "ภาษาไทย", Locale("th")),
    AR("ar", "العربية", Locale("ar")),
    HI("hi", "हिन्दी", Locale("hi")),
    ID("id", "Bahasa Indonesia", Locale("id"));

    companion object {
        fun fromCode(code: String): AppLanguage =
            entries.find { it.code == code } ?: EN
        
        /** 根据系统语言自动检测 */
        fun detectFromSystem(): AppLanguage {
            val sysLang = Locale.getDefault().language
            val sysCountry = Locale.getDefault().country.lowercase()
            return when (sysLang) {
                "zh" -> if (sysCountry == "tw" || sysCountry == "hk") ZH_TW else ZH_CN
                "en" -> EN
                "ja" -> JA
                "ko" -> KO
                "es" -> ES
                "fr" -> FR
                "de" -> DE
                "ru" -> RU
                "pt" -> PT
                "vi" -> VI
                "th" -> TH
                "ar" -> AR
                "hi" -> HI
                "id" -> ID
                else -> EN
            }
        }
    }
}

/**
 * 翻译管理器 —— 单例，存储当前语言和所有翻译文本
 */
object TranslationManager {
    private const val PREF_NAME = "language_prefs"
    private const val KEY_LANGUAGE = "app_language"
    private const val KEY_AUTO_DETECT = "auto_detect_language"

    @Volatile
    var currentLanguage: AppLanguage = AppLanguage.ZH_CN
        private set

    @Volatile
    var autoDetect: Boolean = true
        private set

    private var initialized = false

    private const val KEY_CUSTOM_TITLE = "custom_app_title"

    @Volatile
    var customAppTitle: String? = null
        private set

    /** 初始化：从 SharedPreferences 读取设置 */
    fun init(context: Context) {
        if (initialized) return
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        autoDetect = prefs.getBoolean(KEY_AUTO_DETECT, true)
        val code = prefs.getString(KEY_LANGUAGE, "") ?: ""
        currentLanguage = if (autoDetect) {
            AppLanguage.detectFromSystem()
        } else if (code.isNotBlank()) {
            AppLanguage.fromCode(code)
        } else {
            AppLanguage.detectFromSystem()
        }
        // 读取自定义标题
        customAppTitle = prefs.getString(KEY_CUSTOM_TITLE, null)?.takeIf { it.isNotBlank() }
        initialized = true
    }

    /** 设置语言 */
    fun setLanguage(lang: AppLanguage, context: Context) {
        currentLanguage = lang
        autoDetect = false
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_LANGUAGE, lang.code).putBoolean(KEY_AUTO_DETECT, false).apply()
        applyLocale(context, lang)
    }

    /** 开启/关闭自动跟随系统 */
    fun setAutoDetect(enabled: Boolean, context: Context) {
        autoDetect = enabled
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_AUTO_DETECT, enabled).apply()
        if (enabled) {
            currentLanguage = AppLanguage.detectFromSystem()
            applyLocale(context, currentLanguage)
        }
    }

    /** 应用语言到 Activity */
    fun applyLocale(context: Context, lang: AppLanguage) {
        val locale = lang.locale
        Locale.setDefault(locale)
        val config = Configuration()
        config.setLocale(locale)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.createConfigurationContext(config)
        }
        context.resources.updateConfiguration(config, context.resources.displayMetrics)
    }

    /** 设置自定义标题 */
    fun setCustomAppTitle(title: String?, context: Context) {
        customAppTitle = title?.takeIf { it.isNotBlank() }
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_CUSTOM_TITLE, customAppTitle).apply()
    }

    /** 获取当前显示的 APP 标题（优先自定义，其次多语言） */
    fun getAppTitle(): String = customAppTitle ?: get("app_name")

    /** 获取翻译文本 */
    operator fun get(key: String): String = translations[key]?.get(currentLanguage) ?: key

    /** 所有翻译键值表 */
    private val translations: Map<String, Map<AppLanguage, String>> = buildMap {

        // ===== 通用 =====
        put("app_name", mapOf(
            AppLanguage.ZH_CN to "綦桐AI网关", AppLanguage.ZH_TW to "綦桐AI網關", AppLanguage.EN to "QiTong AI Gateway",
            AppLanguage.JA to "綦桐AIゲートウェイ", AppLanguage.KO to "치퉁AI 게이트웨이", AppLanguage.ES to "Puerta de Enlace AI QiTong",
            AppLanguage.FR to "Passerelle AI QiTong", AppLanguage.DE to "QiTong AI Gateway", AppLanguage.RU to "QiTong AI Шлюз",
            AppLanguage.PT to "Gateway AI QiTong", AppLanguage.VI to "Cổng AI QiTong", AppLanguage.TH to "เกตเวย์ AI QiTong",
            AppLanguage.AR to "بوابة QiTong AI", AppLanguage.HI to "QiTong AI गेटवे", AppLanguage.ID to "Gerbang AI QiTong"
        ))

        // ===== 底部导航 =====
        put("nav_home", mapOf(
            AppLanguage.ZH_CN to "首页", AppLanguage.ZH_TW to "首頁", AppLanguage.EN to "Home",
            AppLanguage.JA to "ホーム", AppLanguage.KO to "홈", AppLanguage.ES to "Inicio",
            AppLanguage.FR to "Accueil", AppLanguage.DE to "Start", AppLanguage.RU to "Главная",
            AppLanguage.PT to "Início", AppLanguage.VI to "Trang chủ", AppLanguage.TH to "หน้าแรก",
            AppLanguage.AR to "الرئيسية", AppLanguage.HI to "होम", AppLanguage.ID to "Beranda"
        ))
        put("nav_providers", mapOf(
            AppLanguage.ZH_CN to "服务商", AppLanguage.ZH_TW to "服務商", AppLanguage.EN to "Providers",
            AppLanguage.JA to "プロバイダ", AppLanguage.KO to "제공업체", AppLanguage.ES to "Proveedores",
            AppLanguage.FR to "Fournisseurs", AppLanguage.DE to "Anbieter", AppLanguage.RU to "Провайдеры",
            AppLanguage.PT to "Provedores", AppLanguage.VI to "Nhà cung cấp", AppLanguage.TH to "ผู้ให้บริการ",
            AppLanguage.AR to "المزودون", AppLanguage.HI to "प्रदाता", AppLanguage.ID to "Penyedia"
        ))
        put("nav_models", mapOf(
            AppLanguage.ZH_CN to "模型", AppLanguage.ZH_TW to "模型", AppLanguage.EN to "Models",
            AppLanguage.JA to "モデル", AppLanguage.KO to "모델", AppLanguage.ES to "Modelos",
            AppLanguage.FR to "Modèles", AppLanguage.DE to "Modelle", AppLanguage.RU to "Модели",
            AppLanguage.PT to "Modelos", AppLanguage.VI to "Mô hình", AppLanguage.TH to "โมเดล",
            AppLanguage.AR to "النماذج", AppLanguage.HI to "मॉडल", AppLanguage.ID to "Model"
        ))
        put("nav_chat", mapOf(
            AppLanguage.ZH_CN to "聊天", AppLanguage.ZH_TW to "聊天", AppLanguage.EN to "Chat",
            AppLanguage.JA to "チャット", AppLanguage.KO to "채팅", AppLanguage.ES to "Chat",
            AppLanguage.FR to "Discussion", AppLanguage.DE to "Chat", AppLanguage.RU to "Чат",
            AppLanguage.PT to "Chat", AppLanguage.VI to "Trò chuyện", AppLanguage.TH to "แชท",
            AppLanguage.AR to "الدردشة", AppLanguage.HI to "चैट", AppLanguage.ID to "Obrolan"
        ))
        put("nav_stats", mapOf(
            AppLanguage.ZH_CN to "统计", AppLanguage.ZH_TW to "統計", AppLanguage.EN to "Stats",
            AppLanguage.JA to "統計", AppLanguage.KO to "통계", AppLanguage.ES to "Estadísticas",
            AppLanguage.FR to "Statistiques", AppLanguage.DE to "Statistiken", AppLanguage.RU to "Статистика",
            AppLanguage.PT to "Estatísticas", AppLanguage.VI to "Thống kê", AppLanguage.TH to "สถิติ",
            AppLanguage.AR to "الإحصائيات", AppLanguage.HI to "आँकड़े", AppLanguage.ID to "Statistik"
        ))
        put("nav_manage", mapOf(
            AppLanguage.ZH_CN to "管理", AppLanguage.ZH_TW to "管理", AppLanguage.EN to "Manage",
            AppLanguage.JA to "管理", AppLanguage.KO to "관리", AppLanguage.ES to "Administrar",
            AppLanguage.FR to "Gérer", AppLanguage.DE to "Verwalten", AppLanguage.RU to "Управление",
            AppLanguage.PT to "Gerenciar", AppLanguage.VI to "Quản lý", AppLanguage.TH to "จัดการ",
            AppLanguage.AR to "الإدارة", AppLanguage.HI to "प्रबंधन", AppLanguage.ID to "Kelola"
        ))
        put("nav_about", mapOf(
            AppLanguage.ZH_CN to "关于", AppLanguage.ZH_TW to "關於", AppLanguage.EN to "About",
            AppLanguage.JA to "について", AppLanguage.KO to "정보", AppLanguage.ES to "Acerca de",
            AppLanguage.FR to "À propos", AppLanguage.DE to "Über", AppLanguage.RU to "О программе",
            AppLanguage.PT to "Sobre", AppLanguage.VI to "Giới thiệu", AppLanguage.TH to "เกี่ยวกับ",
            AppLanguage.AR to "حول", AppLanguage.HI to "के बारे में", AppLanguage.ID to "Tentang"
        ))

        // ===== 首页 =====
        put("gateway_running", mapOf(
            AppLanguage.ZH_CN to "🟢 网关运行中", AppLanguage.ZH_TW to "🟢 網關運行中", AppLanguage.EN to "🟢 Gateway Running",
            AppLanguage.JA to "🟢 ゲートウェイ実行中", AppLanguage.KO to "🟢 게이트웨이 실행 중", AppLanguage.ES to "🟢 Gateway Ejecutándose",
            AppLanguage.FR to "🟢 Passerelle en cours", AppLanguage.DE to "🟢 Gateway läuft", AppLanguage.RU to "🟢 Шлюз работает",
            AppLanguage.PT to "🟢 Gateway em execução", AppLanguage.VI to "🟢 Cổng đang chạy", AppLanguage.TH to "🟢 เกตเวย์กำลังทำงาน",
            AppLanguage.AR to "🟢 البوابة تعمل", AppLanguage.HI to "🟢 गेटवे चल रहा है", AppLanguage.ID to "🟢 Gateway berjalan"
        ))
        put("gateway_stopped", mapOf(
            AppLanguage.ZH_CN to "🔴 网关已停止", AppLanguage.ZH_TW to "🔴 網關已停止", AppLanguage.EN to "🔴 Gateway Stopped",
            AppLanguage.JA to "🔴 ゲートウェイ停止", AppLanguage.KO to "🔴 게이트웨이 중지", AppLanguage.ES to "🔴 Gateway Detenido",
            AppLanguage.FR to "🔴 Passerelle arrêtée", AppLanguage.DE to "🔴 Gateway gestoppt", AppLanguage.RU to "🔴 Шлюз остановлен",
            AppLanguage.PT to "🔴 Gateway parado", AppLanguage.VI to "🔴 Cổng đã dừng", AppLanguage.TH to "🔴 เกตเวย์หยุดแล้ว",
            AppLanguage.AR to "🔴 البوابة متوقفة", AppLanguage.HI to "🔴 गेटवे बंद है", AppLanguage.ID to "🔴 Gateway berhenti"
        ))
        put("port_label", mapOf(
            AppLanguage.ZH_CN to "网关监听端口", AppLanguage.ZH_TW to "網關監聽端口", AppLanguage.EN to "Gateway Port",
            AppLanguage.JA to "ゲートウェイポート", AppLanguage.KO to "게이트웨이 포트", AppLanguage.ES to "Puerto del Gateway",
            AppLanguage.FR to "Port de la passerelle", AppLanguage.DE to "Gateway-Port", AppLanguage.RU to "Порт шлюза",
            AppLanguage.PT to "Porta do gateway", AppLanguage.VI to "Cổng gateway", AppLanguage.TH to "พอร์ตเกตเวย์",
            AppLanguage.AR to "منفذ البوابة", AppLanguage.HI to "गेटवे पोर्ट", AppLanguage.ID to "Port gateway"
        ))
        put("start_gateway", mapOf(
            AppLanguage.ZH_CN to "启动网关服务", AppLanguage.ZH_TW to "啟動網關服務", AppLanguage.EN to "Start Gateway",
            AppLanguage.JA to "ゲートウェイ起動", AppLanguage.KO to "게이트웨이 시작", AppLanguage.ES to "Iniciar Gateway",
            AppLanguage.FR to "Démarrer la passerelle", AppLanguage.DE to "Gateway starten", AppLanguage.RU to "Запустить шлюз",
            AppLanguage.PT to "Iniciar gateway", AppLanguage.VI to "Khởi động cổng", AppLanguage.TH to "เริ่มเกตเวย์",
            AppLanguage.AR to "تشغيل البوابة", AppLanguage.HI to "गेटवे शुरू करें", AppLanguage.ID to "Mulai gateway"
        ))
        put("stop_gateway", mapOf(
            AppLanguage.ZH_CN to "停止网关服务", AppLanguage.ZH_TW to "停止網關服務", AppLanguage.EN to "Stop Gateway",
            AppLanguage.JA to "ゲートウェイ停止", AppLanguage.KO to "게이트웨이 중지", AppLanguage.ES to "Detener Gateway",
            AppLanguage.FR to "Arrêter la passerelle", AppLanguage.DE to "Gateway stoppen", AppLanguage.RU to "Остановить шлюз",
            AppLanguage.PT to "Parar gateway", AppLanguage.VI to "Dừng cổng", AppLanguage.TH to "หยุดเกตเวย์",
            AppLanguage.AR to "إيقاف البوابة", AppLanguage.HI to "गेटवे बंद करें", AppLanguage.ID to "Hentikan gateway"
        ))
        put("local_addr", mapOf(
            AppLanguage.ZH_CN to "本地地址", AppLanguage.ZH_TW to "本地地址", AppLanguage.EN to "Local Address",
            AppLanguage.JA to "ローカルアドレス", AppLanguage.KO to "로컬 주소", AppLanguage.ES to "Dirección local",
            AppLanguage.FR to "Adresse locale", AppLanguage.DE to "Lokale Adresse", AppLanguage.RU to "Локальный адрес",
            AppLanguage.PT to "Endereço local", AppLanguage.VI to "Địa chỉ local", AppLanguage.TH to "ที่อยู่ภายใน",
            AppLanguage.AR to "العنوان المحلي", AppLanguage.HI to "स्थानीय पता", AppLanguage.ID to "Alamat lokal"
        ))
        put("lan_addr", mapOf(
            AppLanguage.ZH_CN to "局域网地址", AppLanguage.ZH_TW to "局域網地址", AppLanguage.EN to "LAN Address",
            AppLanguage.JA to "LANアドレス", AppLanguage.KO to "LAN 주소", AppLanguage.ES to "Dirección LAN",
            AppLanguage.FR to "Adresse LAN", AppLanguage.DE to "LAN-Adresse", AppLanguage.RU to "Локальный адрес сети",
            AppLanguage.PT to "Endereço LAN", AppLanguage.VI to "Địa chỉ LAN", AppLanguage.TH to "ที่อยู่ LAN",
            AppLanguage.AR to "عنوان الشبكة المحلية", AppLanguage.HI to "LAN पता", AppLanguage.ID to "Alamat LAN"
        ))

        // ===== 自动故障转移 =====
        put("auto_failover", mapOf(
            AppLanguage.ZH_CN to "🔄 自动故障转移", AppLanguage.ZH_TW to "🔄 自動故障轉移", AppLanguage.EN to "🔄 Auto Failover",
            AppLanguage.JA to "🔄 自動フェイルオーバー", AppLanguage.KO to "🔄 자동 장애 조치", AppLanguage.ES to "🔄 Failover Automático",
            AppLanguage.FR to "🔄 Basculement automatique", AppLanguage.DE to "🔄 Automatische Umschaltung", AppLanguage.RU to "🔄 Автоматическое переключение",
            AppLanguage.PT to "🔄 Failover Automático", AppLanguage.VI to "🔄 Chuyển đổi tự động", AppLanguage.TH to "🔄 การสลับอัตโนมัติ",
            AppLanguage.AR to "🔄 التبديل التلقائي", AppLanguage.HI to "🔄 स्वत: विफलता स्थानांतरण", AppLanguage.ID to "🔄 Failover Otomatis"
        ))
        put("failover_on", mapOf(
            AppLanguage.ZH_CN to "开启：请求失败自动切换其他可用模型", AppLanguage.ZH_TW to "開啟：請求失敗自動切換其他可用模型", AppLanguage.EN to "On: Auto switch model on failure",
            AppLanguage.JA to "ON：失敗時に自動で別のモデルに切り替え", AppLanguage.KO to "켜짐: 실패 시 자동으로 다른 모델로 전환",
            AppLanguage.ES to "Encendido: Cambiar modelo automáticamente al fallar",
            AppLanguage.FR to "Activé : basculer automatiquement en cas d'échec",
            AppLanguage.DE to "Ein: Automatischer Wechsel bei Fehler",
            AppLanguage.RU to "Вкл: автоматическое переключение при сбое",
            AppLanguage.PT to "Ligado: trocar automaticamente em caso de falha",
            AppLanguage.VI to "Bật: tự động chuyển model khi lỗi",
            AppLanguage.TH to "เปิด: สลับโมเดลอัตโนมัติเมื่อล้มเหลว",
            AppLanguage.AR to "تشغيل: التبديل التلقائي عند الفشل",
            AppLanguage.HI to "चालू: विफलता पर स्वचालित रूप से मॉडल बदलें",
            AppLanguage.ID to "Nyala: ganti model otomatis saat gagal"
        ))
        put("failover_off", mapOf(
            AppLanguage.ZH_CN to "关闭：只使用指定模型", AppLanguage.ZH_TW to "關閉：只使用指定模型", AppLanguage.EN to "Off: Use only specified model",
            AppLanguage.JA to "OFF：指定モデルのみ使用", AppLanguage.KO to "꺼짐: 지정된 모델만 사용",
            AppLanguage.ES to "Apagado: Usar solo el modelo especificado",
            AppLanguage.FR to "Désactivé : utiliser uniquement le modèle spécifié",
            AppLanguage.DE to "Aus: Nur angegebenes Modell verwenden",
            AppLanguage.RU to "Выкл: использовать только указанную модель",
            AppLanguage.PT to "Desligado: usar apenas o modelo especificado",
            AppLanguage.VI to "Tắt: chỉ dùng model đã chỉ định",
            AppLanguage.TH to "ปิด: ใช้เฉพาะโมเดลที่ระบุ",
            AppLanguage.AR to "إيقاف: استخدام النموذج المحدد فقط",
            AppLanguage.HI to "बंद: केवल निर्दिष्ट मॉडल का उपयोग करें",
            AppLanguage.ID to "Mati: hanya gunakan model yang ditentukan"
        ))

        // ===== 管理页面 =====
        put("data_management", mapOf(
            AppLanguage.ZH_CN to "📋 数据管理", AppLanguage.ZH_TW to "📋 數據管理", AppLanguage.EN to "📋 Data Management",
            AppLanguage.JA to "📋 データ管理", AppLanguage.KO to "📋 데이터 관리", AppLanguage.ES to "📋 Gestión de Datos",
            AppLanguage.FR to "📋 Gestion des données", AppLanguage.DE to "📋 Datenverwaltung", AppLanguage.RU to "📋 Управление данными",
            AppLanguage.PT to "📋 Gerenciamento de Dados", AppLanguage.VI to "📋 Quản lý dữ liệu", AppLanguage.TH to "📋 การจัดการข้อมูล",
            AppLanguage.AR to "📋 إدارة البيانات", AppLanguage.HI to "📋 डेटा प्रबंधन", AppLanguage.ID to "📋 Manajemen Data"
        ))
        put("language_settings", mapOf(
            AppLanguage.ZH_CN to "🌐 语言设置", AppLanguage.ZH_TW to "🌐 語言設置", AppLanguage.EN to "🌐 Language Settings",
            AppLanguage.JA to "🌐 言語設定", AppLanguage.KO to "🌐 언어 설정", AppLanguage.ES to "🌐 Configuración de Idioma",
            AppLanguage.FR to "🌐 Paramètres de langue", AppLanguage.DE to "🌐 Spracheinstellungen", AppLanguage.RU to "🌐 Настройки языка",
            AppLanguage.PT to "🌐 Configurações de Idioma", AppLanguage.VI to "🌐 Cài đặt ngôn ngữ", AppLanguage.TH to "🌐 การตั้งค่าภาษา",
            AppLanguage.AR to "🌐 إعدادات اللغة", AppLanguage.HI to "🌐 भाषा सेटिंग्स", AppLanguage.ID to "🌐 Pengaturan Bahasa"
        ))
        put("auto_follow_system", mapOf(
            AppLanguage.ZH_CN to "跟随系统", AppLanguage.ZH_TW to "跟隨系統", AppLanguage.EN to "Follow System",
            AppLanguage.JA to "システムに従う", AppLanguage.KO to "시스템 따르기", AppLanguage.ES to "Seguir al sistema",
            AppLanguage.FR to "Suivre le système", AppLanguage.DE to "System folgen", AppLanguage.RU to "Следовать системе",
            AppLanguage.PT to "Seguir sistema", AppLanguage.VI to "Theo hệ thống", AppLanguage.TH to "ตามระบบ",
            AppLanguage.AR to "اتباع النظام", AppLanguage.HI to "सिस्टम का पालन करें", AppLanguage.ID to "Ikuti sistem"
        ))
        put("manual_select", mapOf(
            AppLanguage.ZH_CN to "手动选择", AppLanguage.ZH_TW to "手動選擇", AppLanguage.EN to "Manual Select",
            AppLanguage.JA to "手動選択", AppLanguage.KO to "수동 선택", AppLanguage.ES to "Selección manual",
            AppLanguage.FR to "Sélection manuelle", AppLanguage.DE to "Manuelle Auswahl", AppLanguage.RU to "Ручной выбор",
            AppLanguage.PT to "Seleção manual", AppLanguage.VI to "Chọn thủ công", AppLanguage.TH to "เลือกด้วยตนเอง",
            AppLanguage.AR to "اختيار يدوي", AppLanguage.HI to "मैन्युअल चयन", AppLanguage.ID to "Pilih manual"
        ))
    }
}

/** 便捷翻译函数 */
fun tr(key: String): String = TranslationManager[key]