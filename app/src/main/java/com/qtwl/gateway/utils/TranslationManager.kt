package com.qtwl.gateway.utils

import android.content.Context
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.qtwl.gateway.GatewayApplication
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    private val _currentLanguage = MutableStateFlow(AppLanguage.ZH_CN)
    val currentLanguageFlow: StateFlow<AppLanguage> = _currentLanguage.asStateFlow()

    @Volatile
    var currentLanguage: AppLanguage = AppLanguage.ZH_CN
        private set

    private val _autoDetect = MutableStateFlow(true)
    val autoDetectFlow: StateFlow<Boolean> = _autoDetect.asStateFlow()

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
        _autoDetect.value = autoDetect
        val code = prefs.getString(KEY_LANGUAGE, "") ?: ""
        currentLanguage = if (autoDetect) {
            AppLanguage.detectFromSystem()
        } else if (code.isNotBlank()) {
            AppLanguage.fromCode(code)
        } else {
            AppLanguage.detectFromSystem()
        }
        _currentLanguage.value = currentLanguage
        // 读取自定义标题
        customAppTitle = prefs.getString(KEY_CUSTOM_TITLE, null)?.takeIf { it.isNotBlank() }
        initialized = true
    }

    /** 设置语言 */
    fun setLanguage(lang: AppLanguage, context: Context) {
        currentLanguage = lang
        _currentLanguage.value = lang
        autoDetect = false
        _autoDetect.value = false
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_LANGUAGE, lang.code).putBoolean(KEY_AUTO_DETECT, false).apply()
        applyLocale(context, lang)
    }

    /** 开启/关闭自动跟随系统 */
    fun setAutoDetect(enabled: Boolean, context: Context) {
        autoDetect = enabled
        _autoDetect.value = enabled
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_AUTO_DETECT, enabled).apply()
        if (enabled) {
            currentLanguage = AppLanguage.detectFromSystem()
            _currentLanguage.value = currentLanguage
            applyLocale(context, currentLanguage)
        }
    }

    /** 应用语言到 Activity（使用 AppCompatDelegate 方式，系统自动 recreate） */
    fun applyLocale(context: Context, lang: AppLanguage) {
        val locale = lang.locale
        Locale.setDefault(locale)
        if (lang.code == "system") {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
        } else {
            val langTag = lang.locale.toLanguageTag()
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(langTag))
        }
    }

    /** 设置自定义标题 */
    fun setCustomAppTitle(title: String?, context: Context) {
        customAppTitle = title?.takeIf { it.isNotBlank() }
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_CUSTOM_TITLE, customAppTitle).apply()
    }

    /** 获取当前显示的 APP 标题（优先自定义，其次多语言） */
    fun getAppTitle(): String = customAppTitle ?: get("app_name")

    /** 获取翻译文本（fallback链：当前语言 → 中文 → 返回key） */
    operator fun get(key: String): String {
        val translationsForKey = translations[key] ?: return key
        return translationsForKey[currentLanguage] ?: translationsForKey[AppLanguage.ZH_CN] ?: key
    }

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

        // ===== 服务商页面 =====
        put("add_provider", mapOf(
            AppLanguage.ZH_CN to "添加服务商", AppLanguage.EN to "Add Provider",
            AppLanguage.JA to "プロバイダ追加", AppLanguage.KO to "제공업체 추가",
            AppLanguage.ZH_TW to "新增服務商", AppLanguage.ES to "Agregar proveedor",
            AppLanguage.FR to "Ajouter fournisseur", AppLanguage.DE to "Anbieter hinzufügen",
            AppLanguage.RU to "Добавить провайдера", AppLanguage.PT to "Adicionar provedor",
            AppLanguage.VI to "Thêm nhà cung cấp", AppLanguage.TH to "เพิ่มผู้ให้บริการ",
            AppLanguage.AR to "إضافة مزود", AppLanguage.HI to "प्रदाता जोड़ें", AppLanguage.ID to "Tambah penyedia"
        ))
        put("edit_provider", mapOf(
            AppLanguage.ZH_CN to "编辑服务商", AppLanguage.EN to "Edit Provider",
            AppLanguage.JA to "プロバイダ編集", AppLanguage.KO to "제공업체 편집",
            AppLanguage.ZH_TW to "編輯服務商", AppLanguage.ES to "Editar proveedor",
            AppLanguage.FR to "Modifier fournisseur", AppLanguage.DE to "Anbieter bearbeiten",
            AppLanguage.RU to "Редактировать провайдера", AppLanguage.PT to "Editar provedor",
            AppLanguage.VI to "Sửa nhà cung cấp", AppLanguage.TH to "แก้ไขผู้ให้บริการ",
            AppLanguage.AR to "تعديل المزود", AppLanguage.HI to "प्रदाता संपादित करें", AppLanguage.ID to "Edit penyedia"
        ))
        put("provider_type_label", mapOf(
            AppLanguage.ZH_CN to "服务商名称", AppLanguage.EN to "Provider Name",
            AppLanguage.JA to "プロバイダ名", AppLanguage.KO to "제공업체 이름",
            AppLanguage.ZH_TW to "服務商名稱", AppLanguage.ES to "Nombre del proveedor",
            AppLanguage.FR to "Nom du fournisseur", AppLanguage.DE to "Anbietername",
            AppLanguage.RU to "Имя провайдера", AppLanguage.PT to "Nome do provedor",
            AppLanguage.VI to "Tên nhà cung cấp", AppLanguage.TH to "ชื่อผู้ให้บริการ",
            AppLanguage.AR to "اسم المزود", AppLanguage.HI to "प्रदाता का नाम", AppLanguage.ID to "Nama penyedia"
        ))
        put("provider_type_hint", mapOf(
            AppLanguage.ZH_CN to "例如: OpenAI, Claude, 本地Ollama", AppLanguage.EN to "e.g. OpenAI, Claude, local Ollama",
            AppLanguage.JA to "例：OpenAI、Claude、ローカルOllama", AppLanguage.KO to "예: OpenAI, Claude, 로컬 Ollama",
            AppLanguage.ZH_TW to "例如：OpenAI、Claude、本地Ollama", AppLanguage.ES to "ej. OpenAI, Claude, Ollama local",
            AppLanguage.FR to "ex. OpenAI, Claude, Ollama local", AppLanguage.DE to "z.B. OpenAI, Claude, lokales Ollama",
            AppLanguage.RU to "напр. OpenAI, Claude, локальный Ollama", AppLanguage.PT to "ex. OpenAI, Claude, Ollama local",
            AppLanguage.VI to "vd: OpenAI, Claude, Ollama cục bộ", AppLanguage.TH to "เช่น OpenAI, Claude, Ollama ในเครื่อง",
            AppLanguage.AR to "مثل: OpenAI، Claude، Ollama المحلي", AppLanguage.HI to "उदा: OpenAI, Claude, स्थानीय Ollama", AppLanguage.ID to "mis. OpenAI, Claude, Ollama lokal"
        ))
        put("model_type", mapOf(
            AppLanguage.ZH_CN to "大模型类型", AppLanguage.EN to "Model Type",
            AppLanguage.JA to "モデルタイプ", AppLanguage.KO to "모델 유형",
            AppLanguage.ZH_TW to "大模型類型", AppLanguage.ES to "Tipo de modelo",
            AppLanguage.FR to "Type de modèle", AppLanguage.DE to "Modelltyp",
            AppLanguage.RU to "Тип модели", AppLanguage.PT to "Tipo de modelo",
            AppLanguage.VI to "Loại mô hình", AppLanguage.TH to "ประเภทโมเดล",
            AppLanguage.AR to "نوع النموذج", AppLanguage.HI to "मॉडल प्रकार", AppLanguage.ID to "Tipe model"
        ))
        put("provider_type", mapOf(
            AppLanguage.ZH_CN to "类型标识", AppLanguage.EN to "Type",
            AppLanguage.JA to "タイプ識別子", AppLanguage.KO to "유형 식별자",
            AppLanguage.ZH_TW to "類型標識", AppLanguage.ES to "Tipo",
            AppLanguage.FR to "Type", AppLanguage.DE to "Typ",
            AppLanguage.RU to "Тип", AppLanguage.PT to "Tipo",
            AppLanguage.VI to "Loại", AppLanguage.TH to "ประเภท",
            AppLanguage.AR to "النوع", AppLanguage.HI to "प्रकार", AppLanguage.ID to "Tipe"
        ))
        put("type_options", mapOf(
            AppLanguage.ZH_CN to "OpenAI Compatible / Anthropic / Custom",
            AppLanguage.EN to "OpenAI Compatible / Anthropic / Custom",
            AppLanguage.JA to "OpenAI互換 / Anthropic / カスタム",
            AppLanguage.KO to "OpenAI 호환 / Anthropic / 사용자 정의",
            AppLanguage.ZH_TW to "OpenAI Compatible / Anthropic / Custom",
            AppLanguage.ES to "OpenAI Compatible / Anthropic / Custom",
            AppLanguage.FR to "OpenAI Compatible / Anthropic / Custom",
            AppLanguage.DE to "OpenAI-kompatibel / Anthropic / Benutzerdefiniert",
            AppLanguage.RU to "OpenAI-совместимый / Anthropic / Пользовательский",
            AppLanguage.PT to "OpenAI Compatível / Anthropic / Personalizado",
            AppLanguage.VI to "Tương thích OpenAI / Anthropic / Tùy chỉnh",
            AppLanguage.TH to "เข้ากับ OpenAI / Anthropic / กำหนดเอง",
            AppLanguage.AR to "متوافق مع OpenAI / Anthropic / مخصص",
            AppLanguage.HI to "OpenAI संगत / Anthropic / कस्टम",
            AppLanguage.ID to "Kompatibel OpenAI / Anthropic / Kustom"
        ))
        put("final_url", mapOf(
            AppLanguage.ZH_CN to "最终URL", AppLanguage.EN to "Final URL",
            AppLanguage.JA to "最終URL", AppLanguage.KO to "최종 URL",
            AppLanguage.ZH_TW to "最終URL", AppLanguage.ES to "URL final",
            AppLanguage.FR to "URL finale", AppLanguage.DE to "Endgültige URL",
            AppLanguage.RU to "Итоговый URL", AppLanguage.PT to "URL final",
            AppLanguage.VI to "URL cuối cùng", AppLanguage.TH to "URL สุดท้าย",
            AppLanguage.AR to "الرابط النهائي", AppLanguage.HI to "अंतिम URL", AppLanguage.ID to "URL akhir"
        ))
        put("url_hint", mapOf(
            AppLanguage.ZH_CN to "提示: 输入 http://... 开头地址会自动拼接 /v1/chat/completions",
            AppLanguage.EN to "Tip: Entering http://... base will auto-append /v1/chat/completions",
            AppLanguage.JA to "ヒント：http://... を入力すると自動的に /v1/chat/completions が追加されます",
            AppLanguage.KO to "팁: http://...을 입력하면 /v1/chat/completions이 자동으로 추가됩니다",
            AppLanguage.ZH_TW to "提示：輸入 http://... 開頭地址會自動拼接 /v1/chat/completions",
            AppLanguage.ES to "Ingrese http://... para auto-agregar /v1/chat/completions",
            AppLanguage.FR to "Astuce: saisir http://... ajoutera automatiquement /v1/chat/completions",
            AppLanguage.DE to "Tipp: http://... automatisch mit /v1/chat/completions ergänzen",
            AppLanguage.RU to "Подсказка: ввод http://... автоматически добавит /v1/chat/completions",
            AppLanguage.PT to "Diga: digitar http://... adiciona automaticamente /v1/chat/completions",
            AppLanguage.VI to "Mẹo: nhập http://... sẽ tự động thêm /v1/chat/completions",
            AppLanguage.TH to "เคล็ดลับ: ป้อน http://... จะเติม /v1/chat/completions อัตโนมัติ",
            AppLanguage.AR to "أدخل http://... لإضافة /v1/chat/completions تلقائياً",
            AppLanguage.HI to "टिप: http://... दर्ज करने से /v1/chat/completions स्वचालित रूप से जुड़ जाएगा",
            AppLanguage.ID to "Tip: masukkan http://... untuk menambahkan /v1/chat/completions secara otomatis"
        ))
        put("api_url", mapOf(
            AppLanguage.ZH_CN to "API 地址", AppLanguage.EN to "API Address",
            AppLanguage.JA to "APIアドレス", AppLanguage.KO to "API 주소",
            AppLanguage.ZH_TW to "API 地址", AppLanguage.ES to "Dirección API",
            AppLanguage.FR to "Adresse API", AppLanguage.DE to "API-Adresse",
            AppLanguage.RU to "API-адрес", AppLanguage.PT to "Endereço API",
            AppLanguage.VI to "Địa chỉ API", AppLanguage.TH to "ที่อยู่ API",
            AppLanguage.AR to "عنوان API", AppLanguage.HI to "एपीआई पता", AppLanguage.ID to "Alamat API"
        ))
        put("api_url_hint", mapOf(
            AppLanguage.ZH_CN to "https://api.openai.com 或 http://localhost:11434",
            AppLanguage.EN to "https://api.openai.com or http://localhost:11434",
            AppLanguage.JA to "https://api.openai.com または http://localhost:11434",
            AppLanguage.KO to "https://api.openai.com 또는 http://localhost:11434",
            AppLanguage.ZH_TW to "https://api.openai.com 或 http://localhost:11434",
            AppLanguage.ES to "https://api.openai.com o http://localhost:11434",
            AppLanguage.FR to "https://api.openai.com ou http://localhost:11434",
            AppLanguage.DE to "https://api.openai.com oder http://localhost:11434",
            AppLanguage.RU to "https://api.openai.com или http://localhost:11434",
            AppLanguage.PT to "https://api.openai.com ou http://localhost:11434",
            AppLanguage.VI to "https://api.openai.com hoặc http://localhost:11434",
            AppLanguage.TH to "https://api.openai.com หรือ http://localhost:11434",
            AppLanguage.AR to "https://api.openai.com أو http://localhost:11434",
            AppLanguage.HI to "https://api.openai.com या http://localhost:11434",
            AppLanguage.ID to "https://api.openai.com atau http://localhost:11434"
        ))
        put("port", mapOf(
            AppLanguage.ZH_CN to "端口", AppLanguage.EN to "Port",
            AppLanguage.JA to "ポート", AppLanguage.KO to "포트",
            AppLanguage.ZH_TW to "連接埠", AppLanguage.ES to "Puerto",
            AppLanguage.FR to "Port", AppLanguage.DE to "Port",
            AppLanguage.RU to "Порт", AppLanguage.PT to "Porta",
            AppLanguage.VI to "Cổng", AppLanguage.TH to "พอร์ต",
            AppLanguage.AR to "المنفذ", AppLanguage.HI to "पोर्ट", AppLanguage.ID to "Port"
        ))
        put("port_hint", mapOf(
            AppLanguage.ZH_CN to "如 443, 11434, 8080", AppLanguage.EN to "e.g. 443, 11434, 8080",
            AppLanguage.JA to "例：443、11434、8080", AppLanguage.KO to "예: 443, 11434, 8080",
            AppLanguage.ZH_TW to "如 443, 11434, 8080", AppLanguage.ES to "p.ej. 443, 11434, 8080",
            AppLanguage.FR to "ex. 443, 11434, 8080", AppLanguage.DE to "z.B. 443, 11434, 8080",
            AppLanguage.RU to "напр. 443, 11434, 8080", AppLanguage.PT to "ex. 443, 11434, 8080",
            AppLanguage.VI to "vd. 443, 11434, 8080", AppLanguage.TH to "เช่น 443, 11434, 8080",
            AppLanguage.AR to "مثل 443، 11434، 8080", AppLanguage.HI to "उदा. 443, 11434, 8080",
            AppLanguage.ID to "mis. 443, 11434, 8080"
        ))
put("api_key_hint", mapOf(AppLanguage.ZH_CN to "sk-... 或留空（本地服务无需Key）",
            AppLanguage.EN to "sk-... or blank (local service needs no key)",
            AppLanguage.JA to "sk-... または空白（ローカルサービスはキー不要）",
            AppLanguage.KO to "sk-... 또는 비움 (로컬 서비스는 키 불필요)",
            AppLanguage.ZH_TW to "sk-... 或留空（本地服務無需Key）",
            AppLanguage.ES to "sk-... o vacío (servicio local no necesita)",
            AppLanguage.FR to "sk-... ou vide (service local sans clé)",
            AppLanguage.DE to "sk-... oder leer (lokaler Dienst braucht keinen)",
            AppLanguage.RU to "sk-... или пусто (локальный сервис без ключа)",
            AppLanguage.PT to "sk-... ou vazio (serviço local não precisa)",
            AppLanguage.VI to "sk-... hoặc để trống (dịch vụ cục bộ không cần)",
            AppLanguage.TH to "sk-... หรือเว้นว่าง (บริการในเครื่องไม่ต้องใช้)",
            AppLanguage.HI to "sk-... या खाली (स्थानीय सेवा को कुंजी नहीं चाहिए)",
            AppLanguage.AR to "sk-... أو فارغ (الخدمة المحلية لا تحتاج مفتاح)",
            AppLanguage.ID to "sk-... atau kosong (layanan lokal tidak perlu)"
        ))
        put("sync_models", mapOf(AppLanguage.ZH_CN to "同步模型", AppLanguage.EN to "Sync Models",
            AppLanguage.JA to "モデル同期", AppLanguage.KO to "모델 동기화",
            AppLanguage.ZH_TW to "同步模型", AppLanguage.ES to "Sincronizar modelos",
            AppLanguage.FR to "Synchroniser modèles", AppLanguage.DE to "Modelle synchronisieren",
            AppLanguage.RU to "Синхронизация моделей", AppLanguage.PT to "Sincronizar modelos",
            AppLanguage.VI to "Đồng bộ mô hình", AppLanguage.TH to "ซิงค์โมเดล",
            AppLanguage.HI to "मॉडल सिंक करें", AppLanguage.AR to "مزامنة النماذج",
            AppLanguage.ID to "Sinkronkan model"
        ))
        put("search_model", mapOf(AppLanguage.ZH_CN to "搜索模型", AppLanguage.EN to "Search Model",
            AppLanguage.JA to "モデル検索", AppLanguage.KO to "모델 검색",
            AppLanguage.ZH_TW to "搜尋模型", AppLanguage.ES to "Buscar modelo",
            AppLanguage.FR to "Rechercher modèle", AppLanguage.DE to "Modell suchen",
            AppLanguage.RU to "Поиск модели", AppLanguage.PT to "Buscar modelo",
            AppLanguage.VI to "Tìm mô hình", AppLanguage.TH to "ค้นหาโมเดล",
            AppLanguage.HI to "मॉडल खोजें", AppLanguage.AR to "البحث عن نموذج",
            AppLanguage.ID to "Cari model"
        ))
        put("search_hint", mapOf(AppLanguage.ZH_CN to "输入模型名称/ID/别名...",
            AppLanguage.EN to "Enter model name/ID/alias...",
            AppLanguage.JA to "モデル名/ID/別名を入力...",
            AppLanguage.KO to "모델 이름/ID/별칭 입력...",
            AppLanguage.ZH_TW to "輸入模型名稱/ID/別名...",
            AppLanguage.ES to "Ingrese nombre/ID/alias...",
            AppLanguage.FR to "Saisir nom/ID/alias...",
            AppLanguage.DE to "Modellname/ID/Alias eingeben...",
            AppLanguage.RU to "Введите имя/ID/псевдоним...",
            AppLanguage.PT to "Insira nome/ID/alias...",
            AppLanguage.VI to "Nhập tên/ID/bí danh...",
            AppLanguage.TH to "ป้อนชื่อ/ID/นามแฝง...",
            AppLanguage.AR to "أدخل اسم/معرف/اسم مستعار...",
            AppLanguage.HI to "नाम/ID/उपनाम दर्ज करें...",
            AppLanguage.ID to "Masukkan nama/ID/alias..."
        ))
        put("test_speed", mapOf(AppLanguage.ZH_CN to "测速", AppLanguage.EN to "Test",
            AppLanguage.JA to "速度テスト", AppLanguage.KO to "속도 테스트",
            AppLanguage.ZH_TW to "測速", AppLanguage.ES to "Prueba",
            AppLanguage.FR to "Test", AppLanguage.DE to "Test",
            AppLanguage.RU to "Тест", AppLanguage.PT to "Teste",
            AppLanguage.VI to "Kiểm tra", AppLanguage.TH to "ทดสอบ",
            AppLanguage.HI to "परीक्षण", AppLanguage.AR to "اختبار",
            AppLanguage.ID to "Uji"
        ))
        put("select_brain", mapOf(AppLanguage.ZH_CN to "选择 qtai-sj 脑子",
            AppLanguage.EN to "Select qtai-sj Brain",
            AppLanguage.JA to "qtai-sj ブレインを選択",
            AppLanguage.KO to "qtai-sj 브레인 선택",
            AppLanguage.ZH_TW to "選擇 qtai-sj 腦子",
            AppLanguage.ES to "Seleccionar cerebro qtai-sj",
            AppLanguage.FR to "Sélectionner le cerveau qtai-sj",
            AppLanguage.DE to "qtai-sj-Gehirn auswählen",
            AppLanguage.RU to "Выбрать мозг qtai-sj",
            AppLanguage.PT to "Selecionar cérebro qtai-sj",
            AppLanguage.VI to "Chọn não qtai-sj",
            AppLanguage.TH to "เลือกสมอง qtai-sj",
            AppLanguage.HI to "qtai-sj दिमाग चुनें",
            AppLanguage.AR to "اختر دماغ qtai-sj",
            AppLanguage.ID to "Pilih otak qtai-sj"
        ))
        put("save", mapOf(
            AppLanguage.ZH_CN to "保存", AppLanguage.EN to "Save",
            AppLanguage.JA to "保存", AppLanguage.KO to "저장",
            AppLanguage.ZH_TW to "儲存", AppLanguage.ES to "Guardar",
            AppLanguage.FR to "Enregistrer", AppLanguage.DE to "Speichern",
            AppLanguage.RU to "Сохранить", AppLanguage.PT to "Salvar",
            AppLanguage.VI to "Lưu", AppLanguage.TH to "บันทึก",
            AppLanguage.AR to "حفظ", AppLanguage.HI to "सहेजें", AppLanguage.ID to "Simpan"
        ))
        put("cancel", mapOf(
            AppLanguage.ZH_CN to "取消", AppLanguage.EN to "Cancel",
            AppLanguage.JA to "キャンセル", AppLanguage.KO to "취소",
            AppLanguage.ZH_TW to "取消", AppLanguage.ES to "Cancelar",
            AppLanguage.FR to "Annuler", AppLanguage.DE to "Abbrechen",
            AppLanguage.RU to "Отмена", AppLanguage.PT to "Cancelar",
            AppLanguage.VI to "Hủy", AppLanguage.TH to "ยกเลิก",
            AppLanguage.AR to "إلغاء", AppLanguage.HI to "रद्द करें", AppLanguage.ID to "Batal"
        ))
        put("close", mapOf(
            AppLanguage.ZH_CN to "关闭", AppLanguage.EN to "Close",
            AppLanguage.JA to "閉じる", AppLanguage.KO to "닫기",
            AppLanguage.ZH_TW to "關閉", AppLanguage.ES to "Cerrar",
            AppLanguage.FR to "Fermer", AppLanguage.DE to "Schließen",
            AppLanguage.RU to "Закрыть", AppLanguage.PT to "Fechar",
            AppLanguage.VI to "Đóng", AppLanguage.TH to "ปิด",
            AppLanguage.AR to "إغلاق", AppLanguage.HI to "बंद करें", AppLanguage.ID to "Tutup"
        ))
        put("home_thinking_guide", mapOf(
            AppLanguage.ZH_CN to "首页思考引导", AppLanguage.EN to "Home Thinking Guide",
            AppLanguage.JA to "ホーム思考ガイド", AppLanguage.KO to "홈 사고 가이드",
            AppLanguage.ZH_TW to "首頁思考引導", AppLanguage.ES to "Guía de pensamiento en inicio",
            AppLanguage.FR to "Guide de réflexion accueil", AppLanguage.DE to "Startseiten-Denkhilfe",
            AppLanguage.RU to "Мысль на главной", AppLanguage.PT to "Guia de reflexión inicial",
            AppLanguage.VI to "Hướng dẫn suy nghĩ trang chủ", AppLanguage.TH to "คู่มือความคิดหน้าแรก",
            AppLanguage.AR to "دليل التفكير في الصفحة الرئيسية", AppLanguage.HI to "होम सोच गाइड",
            AppLanguage.ID to "Panduan pemikiran beranda"
        ))

        // ===== 首页引导 =====
        put("quick_start", mapOf(
            AppLanguage.ZH_CN to "快速上手", AppLanguage.EN to "Quick Start",
            AppLanguage.JA to "クイックスタート", AppLanguage.KO to "빠른 시작",
            AppLanguage.ZH_TW to "快速上手", AppLanguage.ES to "Inicio rápido",
            AppLanguage.FR to "Démarrage rapide", AppLanguage.DE to "Schnellstart",
            AppLanguage.RU to "Быстрый старт", AppLanguage.PT to "Início rápido",
            AppLanguage.VI to "Bắt đầu nhanh", AppLanguage.TH to "เริ่มต้นเร็ว",
            AppLanguage.AR to "بداية سريعة", AppLanguage.HI to "त्वरित शुरुआत", AppLanguage.ID to "Mulai cepat"
        ))
        put("set_base_url", mapOf(
            AppLanguage.ZH_CN to "在第三方APP设置 Base URL 为手机地址",
            AppLanguage.EN to "Set phone address as Base URL in 3rd party app",
            AppLanguage.JA to "サードパーティアプリにBase URLとして電話アドレスを設定",
            AppLanguage.KO to "타사 앱에서 전화 주소를 Base URL로 설정",
            AppLanguage.ZH_TW to "在第三方APP設定 Base URL 為手機地址",
            AppLanguage.ES to "Establecer dirección del teléfono como Base URL en app de terceros",
            AppLanguage.FR to "Définir l'adresse du téléphone comme URL de base dans l'application tierce",
            AppLanguage.DE to "Telefonadresse als Base URL in Drittanbieter-App einstellen",
            AppLanguage.RU to "Указать адрес телефона как Base URL в стороннем приложении",
            AppLanguage.PT to "Definir endereço do telefone como Base URL em app de terceiros",
            AppLanguage.VI to "Đặt địa chỉ điện thoại làm Base URL trong ứng dụng bên thứ ba",
            AppLanguage.TH to "ตั้งค่าที่อยู่โทรศัพท์เป็น Base URL ในแอปบุคคลที่สาม",
            AppLanguage.AR to "تعيين عنوان الهاتف كـ Base URL في تطبيق خارجي",
            AppLanguage.HI to "तृतीय-पक्ष ऐप में फोन पते को Base URL के रूप में सेट करें",
            AppLanguage.ID to "Atur alamat ponsel sebagai Base URL di aplikasi pihak ke-3"
        ))
        put("enable_failover", mapOf(
            AppLanguage.ZH_CN to "开启故障转移可自动切换最优模型",
            AppLanguage.EN to "Enable failover for auto optimal model switching",
            AppLanguage.JA to "フェイルオーバーを有効にすると最適なモデルに自動切り替え",
            AppLanguage.KO to "자동 장애 조치를 켜면 최적의 모델로 자동 전환",
            AppLanguage.ZH_TW to "開啟故障轉移可自動切換最優模型",
            AppLanguage.ES to "Activar failover para cambiar automáticamente al mejor modelo",
            AppLanguage.FR to "Activer le basculement automatique vers le meilleur modèle",
            AppLanguage.DE to "Failover aktivieren für automatisch besten Modellwechsel",
            AppLanguage.RU to "Включите failover для автоматического переключения на лучшую модель",
            AppLanguage.PT to "Ativar failover para troca automática pelo melhor modelo",
            AppLanguage.VI to "Bật chuyển đổi dự phòng để tự động chuyển sang mô hình tốt nhất",
            AppLanguage.TH to "เปิดใช้ failover เพื่อสลับโมเดลที่ดีที่สุดอัตโนมัติ",
            AppLanguage.AR to "تفعيل التبديل الفشل للتبديل التلقائي للنموذج الأمثل",
            AppLanguage.HI to "सर्वोत्तम मॉडल स्विचिंग के लिए फेलओवर सक्षम करें",
            AppLanguage.ID to "Aktifkan failover untuk beralih model optimal secara otomatis"
        ))
        put("no_thinking_tag", mapOf(
            AppLanguage.ZH_CN to "⚠️ 思考模式缺少结束标签", AppLanguage.EN to "⚠️ Thinking mode missing end tag",
            AppLanguage.JA to "⚠️ 思考モードの終了タグがありません",
            AppLanguage.KO to "⚠️ 사고 모드 종료 태그 누락",
            AppLanguage.ZH_TW to "⚠️ 思考模式缺少結束標籤",
            AppLanguage.ES to "⚠️ Falta etiqueta de cierre del modo de pensamiento",
            AppLanguage.FR to "⚠️ Balise de fin manquante pour le mode réflexion",
            AppLanguage.DE to "⚠️ Endezeichen des Denkmodus fehlt",
            AppLanguage.RU to "⚠️ Отсутствует закрывающий тег режима размышления",
            AppLanguage.PT to "⚠️ Faltando tag de fechamento do modo de pensamento",
            AppLanguage.VI to "⚠️ Thiếu thẻ kết thúc chế độ suy nghĩ",
            AppLanguage.TH to "⚠️ แท็กปิดโหมดคิดหายไป",
            AppLanguage.AR to "⚠️ علامة الإغلاق لوضع التفكير مفقودة",
            AppLanguage.HI to "⚠️ सोच मोड का अंत टैग गायब है",
            AppLanguage.ID to "⚠️ Tag penutup mode berpikir hilang"
        ))
        put("no_provider", mapOf(
            AppLanguage.ZH_CN to "未找到对应的AI服务商",
            AppLanguage.EN to "No corresponding AI provider found",
            AppLanguage.JA to "対応するAIプロバイダが見つかりません",
            AppLanguage.KO to "해당 AI 제공업체를 찾을 수 없음",
            AppLanguage.ZH_TW to "未找到對應的AI服務商",
            AppLanguage.ES to "No se encontró el proveedor de IA correspondiente",
            AppLanguage.FR to "Aucun fournisseur IA correspondant trouvé",
            AppLanguage.DE to "Kein entsprechender KI-Anbieter gefunden",
            AppLanguage.RU to "Соответствующий ИИ-провайдер не найден",
            AppLanguage.PT to "Nenhum provedor de IA correspondente encontrado",
            AppLanguage.VI to "Không tìm thấy nhà cung cấp AI tương ứng",
            AppLanguage.TH to "ไม่พบผู้ให้บริการ AI ที่ตรงกัน",
            AppLanguage.AR to "لم يتم العثور على مزود AI مطابق",
            AppLanguage.HI to "कोई संबंधित AI प्रदाता नहीं मिला",
            AppLanguage.ID to "Penyedia AI yang sesuai tidak ditemukan"
        ))
        put("clear_all_chats", mapOf(
            AppLanguage.ZH_CN to "清空所有聊天记录", AppLanguage.EN to "Clear All Chats",
            AppLanguage.JA to "すべてのチャットを消去", AppLanguage.KO to "모든 채팅 지우기",
            AppLanguage.ZH_TW to "清除所有聊天记录", AppLanguage.ES to "Limpiar todos los chats",
            AppLanguage.FR to "Effacer toutes les discussions", AppLanguage.DE to "Alle Chats löschen",
            AppLanguage.RU to "Очистить все чаты", AppLanguage.PT to "Limpar todos os chats",
            AppLanguage.VI to "Xóa tất cả cuộc trò chuyện", AppLanguage.TH to "ล้างแชททั้งหมด",
            AppLanguage.AR to "مسح جميع الدردشات", AppLanguage.HI to "सभी चैट साफ़ करें",
            AppLanguage.ID to "Hapus semua obrolan"
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

        // ===== 补充缺失键 =====
        put("default", mapOf(AppLanguage.ZH_CN to "默认:", AppLanguage.EN to "Default:", AppLanguage.JA to "デフォルト：", AppLanguage.KO to "기본:", AppLanguage.ZH_TW to "預設:", AppLanguage.ES to "Predeterminado:", AppLanguage.FR to "Par défaut:", AppLanguage.DE to "Standard:", AppLanguage.RU to "По умолчанию:", AppLanguage.PT to "Padrão:", AppLanguage.VI to "Mặc định:", AppLanguage.TH to "ค่าเริ่มต้น:", AppLanguage.AR to "افتراضي:", AppLanguage.HI to "डिफ़ॉल्ट:", AppLanguage.ID to "Bawaan:"))
        put("port_value", mapOf(AppLanguage.ZH_CN to "监听端口", AppLanguage.EN to "Listen Port", AppLanguage.JA to "リスンポート", AppLanguage.KO to "수신 포트", AppLanguage.ZH_TW to "監聽埠", AppLanguage.ES to "Puerto de escucha", AppLanguage.FR to "Port d'écoute", AppLanguage.DE to "Überwachungsport", AppLanguage.RU to "Порт прослушивания", AppLanguage.PT to "Porta de escuta", AppLanguage.VI to "Cổng nghe", AppLanguage.TH to "พอร์ตฟัง", AppLanguage.AR to "منفذ الاستماع", AppLanguage.HI to "सुनवाई पोर्ट", AppLanguage.ID to "Port mendengarkan"))
        put("service_status", mapOf(AppLanguage.ZH_CN to "服务状态", AppLanguage.EN to "Service Status", AppLanguage.JA to "サービス状態", AppLanguage.KO to "서비스 상태", AppLanguage.ZH_TW to "服務狀態", AppLanguage.ES to "Estado del servicio", AppLanguage.FR to "État du service", AppLanguage.DE to "Dienststatus", AppLanguage.RU to "Статус сервиса", AppLanguage.PT to "Status do serviço", AppLanguage.VI to "Trạng thái dịch vụ", AppLanguage.TH to "สถานะบริการ", AppLanguage.AR to "حالة الخدمة", AppLanguage.HI to "सेवा की स्थिति", AppLanguage.ID to "Status layanan"))
        put("running", mapOf(AppLanguage.ZH_CN to "运行中", AppLanguage.EN to "Running", AppLanguage.JA to "実行中", AppLanguage.KO to "실행 중", AppLanguage.ZH_TW to "執行中", AppLanguage.ES to "Ejecutándose", AppLanguage.FR to "En cours", AppLanguage.DE to "Läuft", AppLanguage.RU to "Работает", AppLanguage.PT to "Executando", AppLanguage.VI to "Đang chạy", AppLanguage.TH to "กำลังทำงาน", AppLanguage.AR to "يعمل", AppLanguage.HI to "चल रहा है", AppLanguage.ID to "Berjalan"))
        put("stopped", mapOf(AppLanguage.ZH_CN to "已停止", AppLanguage.EN to "Stopped", AppLanguage.JA to "停止", AppLanguage.KO to "중지됨", AppLanguage.ZH_TW to "已停止", AppLanguage.ES to "Detenido", AppLanguage.FR to "Arrêté", AppLanguage.DE to "Gestoppt", AppLanguage.RU to "Остановлен", AppLanguage.PT to "Parado", AppLanguage.VI to "Đã dừng", AppLanguage.TH to "หยุดแล้ว", AppLanguage.AR to "متوقف", AppLanguage.HI to "रुक गया", AppLanguage.ID to "Berhenti"))
        put("active_model", mapOf(AppLanguage.ZH_CN to "🧠 当前活跃模型", AppLanguage.EN to "🧠 Current Active Model", AppLanguage.JA to "🧠 現在アクティブモデル", AppLanguage.KO to "🧠 현재 활성 모델", AppLanguage.ZH_TW to "🧠 目前活躍模型", AppLanguage.ES to "🧠 Modelo activo actual", AppLanguage.FR to "🧠 Modèle actif actuel", AppLanguage.DE to "🧠 Aktives Modell", AppLanguage.RU to "🧠 Текущая модель", AppLanguage.PT to "🧠 Modelo ativo atual", AppLanguage.VI to "🧠 Mô hình đang hoạt độง", AppLanguage.TH to "🧠 โมเดลที่ใช้งานอยู่", AppLanguage.AR to "🧠 النموذج النشط الحالي", AppLanguage.HI to "🧠 वर्तमान सक्रिय मॉडल", AppLanguage.ID to "🧠 Model aktif saat ini"))
        put("sync_models", mapOf(AppLanguage.ZH_CN to "同步模型", AppLanguage.EN to "Sync Models", AppLanguage.JA to "モデル同期", AppLanguage.KO to "모델 동기화", AppLanguage.ZH_TW to "同步模型", AppLanguage.ES to "Sincronizar modelos", AppLanguage.FR to "Synchroniser modèles", AppLanguage.DE to "Modelle synchronisieren", AppLanguage.RU to "Синхронизация моделей", AppLanguage.PT to "Sincronizar modelos", AppLanguage.VI to "Đồng bộ mô hình", AppLanguage.TH to "ซิงค์โมเดล", AppLanguage.HI to "मॉडल सिंक करें", AppLanguage.AR to "مزامنة النماذج", AppLanguage.ID to "Sinkronkan model"))
        put("search_model", mapOf(AppLanguage.ZH_CN to "搜索模型", AppLanguage.EN to "Search Model", AppLanguage.JA to "モデル検索", AppLanguage.KO to "모델 검색", AppLanguage.ZH_TW to "搜尋模型", AppLanguage.ES to "Buscar modelo", AppLanguage.FR to "Rechercher modèle", AppLanguage.DE to "Modell suchen", AppLanguage.RU to "Поиск модели", AppLanguage.PT to "Buscar modelo", AppLanguage.VI to "Tìm mô hình", AppLanguage.TH to "ค้นหาโมเดล", AppLanguage.HI to "मॉडल खोजें", AppLanguage.AR to "البحث عن نموذج", AppLanguage.ID to "Cari model"))
        put("search_hint", mapOf(AppLanguage.ZH_CN to "输入模型名称/ID/别名...", AppLanguage.EN to "Enter model name/ID/alias...", AppLanguage.JA to "モデル名/ID/別名を入力...", AppLanguage.KO to "모델 이름/ID/별칭 입력...", AppLanguage.ZH_TW to "輸入模型名稱/ID/別名...", AppLanguage.ES to "Ingrese nombre/ID/alias...", AppLanguage.FR to "Saisir nom/ID/alias...", AppLanguage.DE to "Modellname/ID/Alias eingeben...", AppLanguage.RU to "Введите имя/ID/псевдоним...", AppLanguage.PT to "Insira nome/ID/alias...", AppLanguage.VI to "Nhập tên/ID/bí danh...", AppLanguage.TH to "ป้อนชื่อ/ID/นามแฝง...", AppLanguage.AR to "أدخل اسم/معرف/اسم مستعار...", AppLanguage.HI to "नाम/ID/उपनाम दर्ज करें...", AppLanguage.ID to "Masukkan nama/ID/alias..."))
        put("test_speed", mapOf(AppLanguage.ZH_CN to "测速", AppLanguage.EN to "Test", AppLanguage.JA to "速度テスト", AppLanguage.KO to "속도 테스트", AppLanguage.ZH_TW to "測速", AppLanguage.ES to "Prueba", AppLanguage.FR to "Test", AppLanguage.DE to "Test", AppLanguage.RU to "Тест", AppLanguage.PT to "Teste", AppLanguage.VI to "Kiểm tra", AppLanguage.TH to "ทดสอบ", AppLanguage.HI to "परीक्षण", AppLanguage.AR to "اختبار", AppLanguage.ID to "Uji"))
        put("select_brain", mapOf(AppLanguage.ZH_CN to "选择 qtai-sj 脑子", AppLanguage.EN to "Select qtai-sj Brain", AppLanguage.JA to "qtai-sj ブレインを選択", AppLanguage.KO to "qtai-sj 브레인 선택", AppLanguage.ZH_TW to "選擇 qtai-sj 腦子", AppLanguage.ES to "Seleccionar cerebro qtai-sj", AppLanguage.FR to "Sélectionner le cerveau qtai-sj", AppLanguage.DE to "qtai-sj-Gehirn auswählen", AppLanguage.RU to "Выбрать мозг qtai-sj", AppLanguage.PT to "Selecionar cérebro qtai-sj", AppLanguage.VI to "Chọn não qtai-sj", AppLanguage.TH to "เลือกสมอง qtai-sj", AppLanguage.HI to "qtai-sj दिमाग चुनें", AppLanguage.AR to "اختر دماغ qtai-sj", AppLanguage.ID to "Pilih otak qtai-sj"))
    }
}

/** 便捷翻译函数 */
fun tr(key: String): String = TranslationManager[key]