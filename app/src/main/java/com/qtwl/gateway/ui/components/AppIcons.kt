package com.qtwl.gateway.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 矢量图标注册表
 *
 * 全项目统一通过字符串名称拿 ImageVector，避免在各 UI 文件里散落硬编码图标引用，
 * 也方便后续整体换图标主题（只需改这一处）。
 *
 * 命名约定与 Material Icons 官方名一致，便于对照查找。
 */
object AppIcons {

    /** 根据名称取图标，找不到时回退到 [Icons.Default.Circle] */
    fun get(name: String?): ImageVector = when (name) {
        // ---------- 导航 / 主结构 ----------
        "home", "nav_home" -> Icons.Outlined.Home
        "home_filled" -> Icons.Filled.Home
        "router" -> Icons.Outlined.Router
        "dns" -> Icons.Outlined.Dns
        "smart_toy", "bot", "robot" -> Icons.Outlined.SmartToy
        "chat", "forum" -> Icons.Outlined.Forum
        "settings", "gear" -> Icons.Outlined.Settings
        "settings_applications", "tune" -> Icons.Outlined.Tune
        "info", "about" -> Icons.Outlined.Info
        "analytics", "insights", "stats" -> Icons.Outlined.Analytics
        "list_alt", "list" -> Icons.Outlined.ListAlt
        "layers", "instances" -> Icons.Outlined.Layers
        "extension", "plugin" -> Icons.Outlined.Extension
        "menu_book", "skill" -> Icons.Outlined.MenuBook
        "terminal" -> Icons.Outlined.Terminal
        "code" -> Icons.Outlined.Code
        "api" -> Icons.Outlined.Api
        "cloud" -> Icons.Outlined.Cloud
        "cloud_done" -> Icons.Outlined.CloudDone
        "storage", "database" -> Icons.Outlined.Storage
        "dashboard" -> Icons.Outlined.Dashboard
        "widgets" -> Icons.Outlined.Widgets

        // ---------- 动作 ----------
        "play_arrow", "play", "start" -> Icons.Outlined.PlayArrow
        "stop" -> Icons.Outlined.Stop
        "pause" -> Icons.Outlined.Pause
        "add", "plus" -> Icons.Outlined.Add
        "remove" -> Icons.Outlined.Remove
        "close", "clear" -> Icons.Outlined.Close
        "check", "done" -> Icons.Outlined.Check
        "check_circle" -> Icons.Outlined.CheckCircle
        "edit", "pencil" -> Icons.Outlined.Edit
        "delete", "trash" -> Icons.Outlined.Delete
        "delete_outline" -> Icons.Outlined.DeleteOutline
        "refresh", "sync" -> Icons.Outlined.Refresh
        "sync_alt" -> Icons.Outlined.SyncAlt
        "save" -> Icons.Outlined.Save
        "search" -> Icons.Outlined.Search
        "filter" -> Icons.Outlined.FilterList
        "sort" -> Icons.Outlined.Sort
        "copy", "content_copy" -> Icons.Outlined.ContentCopy
        "paste" -> Icons.Outlined.ContentPaste
        "share" -> Icons.Outlined.Share
        "download" -> Icons.Outlined.Download
        "upload", "upload_file" -> Icons.Outlined.UploadFile
        "folder", "folder_open" -> Icons.Outlined.FolderOpen
        "visibility", "eye" -> Icons.Outlined.Visibility
        "visibility_off" -> Icons.Outlined.VisibilityOff
        "more_vert", "menu" -> Icons.Outlined.MoreVert
        "more_horiz" -> Icons.Outlined.MoreHoriz
        "expand_more", "arrow_drop_down" -> Icons.Outlined.ExpandMore
        "expand_less", "arrow_drop_up" -> Icons.Outlined.ExpandLess
        "chevron_left", "arrow_back" -> Icons.Outlined.ArrowBack
        "chevron_right", "arrow_forward" -> Icons.Outlined.ArrowForward
        "arrow_upward" -> Icons.Outlined.ArrowUpward
        "arrow_downward" -> Icons.Outlined.ArrowDownward
        "undo" -> Icons.Outlined.Undo
        "redo" -> Icons.Outlined.Redo
        "send" -> Icons.Outlined.Send
        "open_in_new", "link" -> Icons.Outlined.OpenInNew
        "build", "construction", "wrench" -> Icons.Outlined.Build
        "backup", "archive" -> Icons.Outlined.Archive
        "restore", "history" -> Icons.Outlined.History
        "science", "experiment" -> Icons.Outlined.Science
        "rocket", "launch" -> Icons.Outlined.RocketLaunch
        "bolt", "flash", "speed" -> Icons.Outlined.Bolt
        "timer", "schedule" -> Icons.Outlined.Timer
        "schedule_clock" -> Icons.Outlined.Schedule
        "straighten", "ruler", "measure" -> Icons.Outlined.Straighten
        "center_focus_strong", "target", "gps" -> Icons.Outlined.MyLocation
        "swap_horiz", "swap" -> Icons.Outlined.SwapHoriz
        "swap_vert" -> Icons.Outlined.SwapVert
        "link_off" -> Icons.Outlined.LinkOff
        "vpn_key", "key" -> Icons.Outlined.VpnKey
        "lock" -> Icons.Outlined.Lock
        "lock_open" -> Icons.Outlined.LockOpen
        "shield", "security" -> Icons.Outlined.Shield
        "verified" -> Icons.Outlined.Verified
        "power", "power_settings" -> Icons.Outlined.PowerSettingsNew
        "refresh_cycle", "autorenew" -> Icons.Outlined.Autorenew
        "rule", "route" -> Icons.Outlined.Rule
        "filter_alt" -> Icons.Outlined.FilterAlt

        // ---------- 状态 / 反馈 ----------
        "check_circle_outline", "success", "ok" -> Icons.Outlined.CheckCircleOutline
        "error", "error_outline", "fail" -> Icons.Outlined.ErrorOutline
        "warning", "warn" -> Icons.Outlined.WarningAmber
        "pending", "hourglass", "waiting" -> Icons.Outlined.HourglassEmpty
        "schedule_send", "sending" -> Icons.Outlined.ScheduleSend
        "mark_email_read", "inbox" -> Icons.Outlined.MarkEmailRead
        "inbox_outline" -> Icons.Outlined.Inbox
        "notifications", "bell" -> Icons.Outlined.Notifications
        "lightbulb", "idea", "hint" -> Icons.Outlined.Lightbulb
        "psychology", "brain", "thinking" -> Icons.Outlined.Psychology
        "auto_awesome", "ai", "sparkle" -> Icons.Outlined.AutoAwesome
        "stars" -> Icons.Outlined.AutoAwesome
        "cancel" -> Icons.Outlined.Cancel
        "block" -> Icons.Outlined.Block
        "help", "help_outline", "question" -> Icons.Outlined.HelpOutline

        // ---------- 内容 / 语义 ----------
        "image", "photo" -> Icons.Outlined.Image
        "movie", "video" -> Icons.Outlined.Movie
        "mic", "audio_in" -> Icons.Outlined.Mic
        "volume_up", "audio_out" -> Icons.Outlined.VolumeUp
        "graphic_eq" -> Icons.Outlined.GraphicEq
        "description", "file", "doc" -> Icons.Outlined.Description
        "article" -> Icons.Outlined.Article
        "attachment", "attach" -> Icons.Outlined.AttachFile
        "translate", "language", "globe" -> Icons.Outlined.Translate
        "public", "world" -> Icons.Outlined.Public
        "group", "people" -> Icons.Outlined.Group
        "person", "user" -> Icons.Outlined.Person
        "person_outline" -> Icons.Outlined.PersonOutline
        "account_circle" -> Icons.Outlined.AccountCircle
        "hub", "embedding", "vector" -> Icons.Outlined.Hub
        "category", "grid" -> Icons.Outlined.Category
        "label", "tag" -> Icons.Outlined.Label
        "bookmark" -> Icons.Outlined.Bookmark
        "star" -> Icons.Outlined.Star
        "star_border" -> Icons.Outlined.StarBorder
        "trending_up", "chart" -> Icons.Outlined.TrendingUp
        "trending_down" -> Icons.Outlined.TrendingDown
        "bar_chart" -> Icons.Outlined.BarChart
        "pie_chart" -> Icons.Outlined.PieChart
        "show_chart" -> Icons.Outlined.ShowChart
        "memory" -> Icons.Outlined.Memory
        "developer_board" -> Icons.Outlined.DeveloperBoard
        "table_chart", "table" -> Icons.Outlined.TableChart
        "calendar", "date" -> Icons.Outlined.CalendarToday
        "clock", "time" -> Icons.Outlined.Schedule
        "money", "payment", "billing" -> Icons.Outlined.Payments
        "qr_code", "scan" -> Icons.Outlined.QrCode2
        "bug", "debug" -> Icons.Outlined.BugReport
        "biotech" -> Icons.Outlined.Biotech
        "filter_vintage", "gradient" -> Icons.Outlined.FilterVintage
        "blur_on", "glass" -> Icons.Outlined.BlurOn
        "waves" -> Icons.Outlined.Waves
        "bubble_chart" -> Icons.Outlined.BubbleChart
        "donut_large" -> Icons.Outlined.DonutLarge
        "scatter_plot" -> Icons.Outlined.ScatterPlot
        "candlestick_chart" -> Icons.Outlined.CandlestickChart
        "auto_graph" -> Icons.Outlined.AutoGraph
        "monitor_heart", "health" -> Icons.Outlined.MonitorHeart
        "health_and_safety" -> Icons.Outlined.HealthAndSafety
        "battery", "battery_full" -> Icons.Outlined.Battery5Bar
        "wifi" -> Icons.Outlined.Wifi
        "network", "lan" -> Icons.Outlined.Lan
        "cell_tower" -> Icons.Outlined.CellTower
        "usb" -> Icons.Outlined.Usb
        "device", "phone" -> Icons.Outlined.PhoneAndroid
        "laptop" -> Icons.Outlined.Laptop
        "cast", "screen_share" -> Icons.Outlined.Cast
        "visibility_outline" -> Icons.Outlined.RemoveRedEye
        "dark_mode", "moon" -> Icons.Outlined.DarkMode
        "light_mode", "sun" -> Icons.Outlined.LightMode
        "palette", "theme", "color" -> Icons.Outlined.Palette
        "format_size", "font" -> Icons.Outlined.FormatSize
        "privacy_tip" -> Icons.Outlined.PrivacyTip
        "policy" -> Icons.Outlined.Policy
        "gavel", "license" -> Icons.Outlined.Gavel
        "new_releases", "update" -> Icons.Outlined.NewReleases
        "system_update" -> Icons.Outlined.SystemUpdate
        "celebration", "done_all" -> Icons.Outlined.DoneAll
        "military_tech", "badge", "award" -> Icons.Outlined.MilitaryTech
        "workspace_premium" -> Icons.Outlined.WorkspacePremium
        "flag", "report" -> Icons.Outlined.Flag
        "outlined_flag" -> Icons.Outlined.OutlinedFlag
        "compass", "explore" -> Icons.Outlined.Explore
        "map" -> Icons.Outlined.Map
        "place", "location" -> Icons.Outlined.Place
        "shopping", "cart" -> Icons.Outlined.ShoppingCart
        "business", "company", "vendor" -> Icons.Outlined.Business
        "corporate_fare" -> Icons.Outlined.CorporateFare
        "handshake" -> Icons.Outlined.Handshake
        "support_agent", "support" -> Icons.Outlined.SupportAgent
        "mail" -> Icons.Outlined.MailOutline
        "message", "sms" -> Icons.Outlined.Message
        "sms_failed" -> Icons.Outlined.SmsFailed
        "call" -> Icons.Outlined.Call
        "ring_volume" -> Icons.Outlined.RingVolume
        "flash_on" -> Icons.Outlined.FlashOn
        "flash_off" -> Icons.Outlined.FlashOff
        "flashlight" -> Icons.Outlined.FlashlightOn
        "screenshot" -> Icons.Outlined.Screenshot
        "photo_camera", "camera" -> Icons.Outlined.PhotoCamera
        "camera_alt" -> Icons.Outlined.CameraAlt
        "crop" -> Icons.Outlined.Crop
        "texture", "pattern" -> Icons.Outlined.Texture
        "gradient_icon" -> Icons.Outlined.Gradient
        "animation" -> Icons.Outlined.Animation
        "motion_photos" -> Icons.Outlined.MotionPhotosAuto
        "speed_half" -> Icons.Outlined.Speed
        "timer_10" -> Icons.Outlined.Timer10
        "hourglass_bottom" -> Icons.Outlined.HourglassBottom
        "hourglass_full" -> Icons.Outlined.HourglassFull
        "watch_later" -> Icons.Outlined.WatchLater
        "event_busy" -> Icons.Outlined.EventBusy
        "event_available" -> Icons.Outlined.EventAvailable
        "task_alt" -> Icons.Outlined.TaskAlt
        "fact_check" -> Icons.Outlined.FactCheck
        "checklist" -> Icons.Outlined.Checklist
        "format_list", "bullets" -> Icons.Outlined.FormatListBulleted
        "numbers" -> Icons.Outlined.FormatListNumbered
        "title" -> Icons.Outlined.Title
        "notes" -> Icons.Outlined.Notes
        "sticky_note", "note" -> Icons.Outlined.StickyNote2
        "edit_note" -> Icons.Outlined.EditNote
        "draw" -> Icons.Outlined.Draw
        "brush" -> Icons.Outlined.Brush
        "ink" -> Icons.Outlined.Brush
        "layers_clear" -> Icons.Outlined.LayersClear
        "filter_drama", "glass_fill" -> Icons.Outlined.FilterDrama
        "opacity", "transparency" -> Icons.Outlined.Opacity
        "invert_colors" -> Icons.Outlined.InvertColors
        "tonality" -> Icons.Outlined.Tonality
        "brightness", "brightness_high" -> Icons.Outlined.BrightnessHigh
        "exposure" -> Icons.Outlined.Exposure
        "wb_twilight", "contrast" -> Icons.Outlined.WbTwilight

        // ---------- 兜底 ----------
        else -> Icons.Outlined.Circle
    }

    /** 能力标签专用图标（与 ModelCapabilities.toTags() 的 iconName 对齐） */
    fun capability(name: String?): ImageVector = get(name)
}
