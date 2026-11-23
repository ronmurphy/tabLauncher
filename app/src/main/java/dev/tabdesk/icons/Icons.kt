package dev.tabdesk.icons

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import dev.tabdesk.*
import kotlin.math.abs
import kotlin.math.max

/**
 * App icons: what each app is drawn from (in order: the user's pick, an icon pack, the app's own
 * one-colour drawing on Android 13+, a Material symbol for what the app does, or its initial), then
 * painted in a style (IconStyles) and colours (IconColours) with an effect over it (IconEffects).
 * Original keeps each app's own icon.
 */
object Icons {
    const val ORIGINAL = 0; const val THEMED = 1; const val WATERCOLOUR = 2; const val INKED = 3; const val MINIMAL = 4
    const val OUTLINE = 5; const val PAPERCUT = 6; const val LONGSHADOW = 7; const val BADGE = 8
    /** Indexes are stored, so new styles go on the end. */
    val STYLES = listOf("Original", "Themed", "Watercolour", "Inked", "Minimal", "Outline", "Paper cut", "Long shadow", "Round badge")

    /** Per-app choice (Prefs): the app's own icon, or "sym:<name>". No entry = automatic. */
    const val OWN = "own"
    const val SYM = "sym:"
    /** "pack:<pack package>/<drawable name>": one icon from an icon pack. */
    const val PACK = "pack:"

    /**
     * Everything a drawing depends on, captured on the main thread for background renders.
     * [lightWall]: the wallpaper is light (Android's hint for dark text on it).
     */
    data class Spec(val style: Int, val colours: String, val dark: Boolean, val accent: Int, val lightWall: Boolean,
                    val pack: String? = null, val effect: String = IconEffects.NONE)

    @Volatile var spec = Spec(ORIGINAL, "accent", false, 0xFF4C8DF6.toInt(), false); private set

    /** Called whenever the palette is built. True if app icons need drawing again. */
    fun update(pal: Palette, lightWall: Boolean): Boolean {
        val s = Spec(Prefs.iconStyle, Prefs.iconColours, pal.dark, pal.accent, lightWall, Prefs.iconPack, Prefs.iconEffect)
        if (s == spec) return false
        val before = spec
        spec = s
        // Original icons don't change with the theme, unless some app has a symbol picked or a pack is in use.
        return before.style != ORIGINAL || s.style != ORIGINAL || Prefs.hasIconOverrides() || before.pack != s.pack ||
            before.effect != s.effect
    }

    // ------------------------------------------------------------ Material Symbols (Apache 2.0, bundled)

    class Symbol(val cp: Int, val names: List<String>) {
        val name get() = names[0]
        val text: String = String(Character.toChars(cp))
    }

    private var symbolList: List<Symbol>? = null
    private var symbolByName: Map<String, Symbol> = emptyMap()
    private val fonts = arrayOfNulls<Typeface>(2)

    @Synchronized fun symbols(ctx: Context): List<Symbol> {
        symbolList?.let { return it }
        val list = try {
            ctx.assets.open("symbols.txt").bufferedReader().useLines { lines ->
                lines.mapNotNull { l -> val p = l.split(' '); if (p.size < 2) null else Symbol(p[0].toInt(16), p.drop(1)) }.toList()
            }
        } catch (e: Exception) { emptyList() }
        symbolByName = HashMap<String, Symbol>().apply { list.forEach { s -> s.names.forEach { put(it, s) } } }
        symbolList = list
        return list
    }

    fun symbol(ctx: Context, name: String): Symbol? { symbols(ctx); return symbolByName[name] }

    /** Material Symbols Rounded, filled or outlined (one variable font, FILL axis). */
    @Synchronized fun symbolFont(ctx: Context, fill: Boolean): Typeface? {
        val i = if (fill) 1 else 0
        fonts[i]?.let { return it }
        return try {
            Typeface.Builder(ctx.assets, "symbols.ttf").setFontVariationSettings("'FILL' $i").build()
        } catch (e: Exception) { null }?.also { fonts[i] = it }
    }

    // ------------------------------------------------------------ which symbol suits an app

    @Volatile private var roleCache: Map<String, String>? = null

    /** Packages that do a standard job (take photos, calculate…) → the symbol for it. Asked of Android, so it works for any brand. */
    fun roles(ctx: Context, refresh: Boolean = false): Map<String, String> {
        if (!refresh) roleCache?.let { return it }
        val pm = ctx.packageManager
        fun main(cat: String) = Intent(Intent.ACTION_MAIN).addCategory(cat)
        val jobs = listOf(
            main(Intent.CATEGORY_APP_CALCULATOR) to "calculate",
            main(Intent.CATEGORY_APP_CALENDAR) to "calendar_month",
            Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA) to "photo_camera",
            Intent(AlarmClock.ACTION_SHOW_ALARMS) to "schedule",
            Intent(Intent.ACTION_DIAL) to "call",
            main(Intent.CATEGORY_APP_CONTACTS) to "contacts",
            main(Intent.CATEGORY_APP_EMAIL) to "mail",
            main(Intent.CATEGORY_APP_MESSAGING) to "chat",
            main(Intent.CATEGORY_APP_MAPS) to "map",
            main(Intent.CATEGORY_APP_MUSIC) to "music_note",
            main(Intent.CATEGORY_APP_GALLERY) to "photo_library",
            main(Intent.CATEGORY_APP_FILES) to "folder",
            main(Intent.CATEGORY_APP_WEATHER) to "partly_cloudy_day",
            main(Intent.CATEGORY_APP_FITNESS) to "fitness_center",
            Intent(MediaStore.Audio.Media.RECORD_SOUND_ACTION) to "mic",
            main(Intent.CATEGORY_APP_MARKET) to "storefront",
            Intent(Settings.ACTION_SETTINGS) to "settings",
            main(Intent.CATEGORY_APP_BROWSER) to "language",
        )
        val out = HashMap<String, String>()
        for ((intent, sym) in jobs) {
            val found = try { pm.queryIntentActivities(intent, 0) } catch (e: Exception) { emptyList() }
            found.forEach { out.putIfAbsent(it.activityInfo.packageName, sym) }
        }
        roleCache = out
        return out
    }

    /** Words in an app's name or package → symbol. The name is checked before the package. */
    private val KEYWORDS: List<Pair<Set<String>, String>> = listOf(
        setOf("calculator", "calc") to "calculate",
        setOf("calendar") to "calendar_month",
        setOf("camera") to "photo_camera",
        setOf("clock", "alarm", "timer") to "schedule",
        setOf("gallery", "photos", "album") to "photo_library",
        setOf("files", "file", "explorer") to "folder",
        setOf("downloads", "download") to "download",
        setOf("settings") to "settings",
        setOf("mail", "gmail", "outlook", "email") to "mail",
        setOf("messages", "messaging", "sms", "chat", "whatsapp", "telegram", "signal", "messenger", "discord") to "chat",
        setOf("phone", "dialer") to "call",
        setOf("contacts", "people") to "contacts",
        setOf("maps", "map", "waze", "navigation", "gps") to "map",
        setOf("weather") to "partly_cloudy_day",
        setOf("music", "spotify", "deezer", "tidal", "soundcloud") to "music_note",
        setOf("podcast", "podcasts") to "podcasts",
        setOf("radio", "fm") to "radio",
        setOf("youtube", "video", "videos", "netflix", "plex", "vlc", "player", "tv") to "smart_display",
        setOf("browser", "chrome", "firefox", "opera", "brave", "edge", "duckduckgo", "internet", "vivaldi") to "language",
        setOf("notes", "note", "keep", "notepad", "memo", "obsidian", "notion") to "sticky_note_2",
        setOf("books", "book", "reader", "kindle", "kobo", "epub", "comic", "comics", "manga", "neko", "mihon",
            "tachiyomi", "libby", "moon", "readera", "koreader") to "menu_book",
        setOf("pdf", "docs", "document", "documents", "word") to "description",
        setOf("sheets", "excel", "spreadsheet") to "table",
        setOf("slides", "powerpoint") to "slideshow",
        setOf("drive", "dropbox", "onedrive", "cloud") to "cloud",
        setOf("recorder", "voice", "dictaphone") to "mic",
        setOf("translate", "translator", "dictionary") to "translate",
        setOf("claude", "gemini", "assistant", "chatgpt", "copilot", "perplexity") to "auto_awesome",
        setOf("store", "market", "appstore", "shop") to "storefront",
        setOf("games", "game") to "sports_esports",
        setOf("news", "newspaper") to "newspaper",
        setOf("bank", "banking", "wallet", "pay") to "account_balance_wallet",
        setOf("shopping", "amazon", "ebay", "cart") to "shopping_cart",
        setOf("health", "fitness", "fit") to "favorite",
        setOf("flashlight", "torch") to "flashlight_on",
        setOf("compass") to "explore",
        setOf("scanner", "scan") to "document_scanner",
        setOf("keyboard", "gboard") to "keyboard",
        setOf("vpn") to "vpn_key",
        setOf("password", "passwords", "authenticator", "bitwarden", "keepass") to "password",
        setOf("security", "antivirus") to "security",
        setOf("cleaner", "booster", "optimizer") to "cleaning_services",
        setOf("backup") to "backup",
        setOf("theme", "themes", "wallpaper", "wallpapers") to "palette",
        setOf("help", "support", "tips", "manual", "feedback") to "help",
        setOf("update", "updater", "updates") to "system_update",
        setOf("remote", "cast", "mirror") to "cast",
        setOf("terminal", "termux", "shell", "console") to "terminal",
        setOf("code", "ide") to "code",
        setOf("tasks", "task", "todo", "reminders", "reminder") to "checklist",
        setOf("school", "learn", "learning", "duolingo", "classroom") to "school",
        setOf("recipes", "recipe", "food", "cook") to "restaurant",
        setOf("travel", "flight", "flights", "airline") to "flight",
        setOf("taxi", "uber", "lyft") to "local_taxi",
        setOf("meet", "zoom", "teams", "skype") to "videocam",
        setOf("home") to "home",
        setOf("bluetooth") to "bluetooth",
        setOf("editor", "snapseed", "lightroom") to "photo_filter",
        setOf("social", "facebook", "instagram", "twitter", "reddit", "mastodon", "threads", "bluesky") to "forum",
    )

    /** Symbols to show first in Change icon: the ones apps usually get, plus a few general favourites. */
    val COMMON: List<String> by lazy {
        (KEYWORDS.map { it.second } + listOf(
            "star", "bolt", "lightbulb", "rocket_launch", "eco", "pets", "local_cafe", "brush", "piano", "sports_soccer",
            "cake", "diamond", "spa", "landscape", "headphones", "mic_external_on", "image", "movie", "public", "work",
        )).distinct()
    }

    private fun words(s: String) = s.lowercase().split(Regex("[^a-z0-9+]+")).filter { it.isNotEmpty() }.toSet()

    /** The symbol TabDesk would pick for an app, or null to use its initial. */
    fun suggest(pkg: String, label: String, ai: ApplicationInfo?, roles: Map<String, String>): String? {
        roles[pkg]?.let { return it }
        for (w in listOf(words(label), words(pkg)))
            KEYWORDS.firstOrNull { (keys, _) -> keys.any { it in w } }?.let { return it.second }
        return when (ai?.category) {
            ApplicationInfo.CATEGORY_GAME -> "sports_esports"
            ApplicationInfo.CATEGORY_AUDIO -> "music_note"
            ApplicationInfo.CATEGORY_VIDEO -> "smart_display"
            ApplicationInfo.CATEGORY_IMAGE -> "photo_library"
            ApplicationInfo.CATEGORY_SOCIAL -> "forum"
            ApplicationInfo.CATEGORY_NEWS -> "newspaper"
            ApplicationInfo.CATEGORY_MAPS -> "map"
            ApplicationInfo.CATEGORY_ACCESSIBILITY -> "accessibility_new"
            else -> null
        }
    }

    // ------------------------------------------------------------ making an icon

    /**
     * An app's icon in [spec]'s style and colours. [own] is the app's own icon already drawn at [size];
     * [d] is the same icon as a drawable (for its monochrome layer). [onLight]: what's behind it is light.
     */
    fun make(ctx: Context, d: Drawable, own: Bitmap, component: ComponentName, label: String, ai: ApplicationInfo?, choice: String?,
             roles: Map<String, String>, size: Int, spec: Spec, onLight: Boolean): Bitmap {
        val pkg = component.packageName
        if (choice == OWN) return own
        val picked = choice?.takeIf { it.startsWith(SYM) }?.removePrefix(SYM)
        val pickedPack = choice?.takeIf { it.startsWith(PACK) }?.removePrefix(PACK)
        val original = spec.style == ORIGINAL && picked == null
        // An icon from a pack: one picked for this app, else the pack in use's icon for it.
        val t = size * 2
        val packSize = if (original) size else t
        val packIcon = when {
            pickedPack != null -> IconPacks.named(ctx, pickedPack.substringBefore('/'), pickedPack.substringAfter('/'), packSize)
            picked == null && spec.pack != null -> IconPacks.iconFor(ctx, spec.pack, component, packSize)
            else -> null
        }
        if (original) {
            if (packIcon != null) return contrasting(packIcon, onLight)
            // Apps the pack doesn't have get its background / mask / overlay, if it has them.
            spec.pack?.let { p -> IconPacks.themeOther(ctx, p, own, pkg.hashCode(), size)?.let { return it } }
            return own
        }
        // A symbol picked for one app while the rest keep their own icons is drawn Themed.
        val style = if (spec.style == ORIGINAL) THEMED else spec.style
        return try {
            // Watercolour and Inked draw symbols in outline, like a pen would; the rest filled.
            val fill = style != WATERCOLOUR && style != INKED
            val frac = when (style) { MINIMAL -> 0.72f; OUTLINE -> 0.66f; THEMED -> 0.54f; BADGE -> 0.46f; PAPERCUT -> 0.48f; else -> 0.5f }
            // A one-colour pack icon (line art, like Arcticons) becomes the drawing. A full-colour one
            // only if it was picked for this app, and then as it is.
            val packMask = packIcon?.let { if (oneColour(it)) normalise(it, size, frac, solidOk = false) else null }
            if (packIcon != null && packMask == null && pickedPack != null) return Bitmap.createScaledBitmap(packIcon, size, size, true)
            val mask = picked?.let { symbolMask(ctx, it, fill, t, size, frac) }
                ?: packMask
                ?: monochrome(d, t)?.let { normalise(it, size, frac, solidOk = false) }
                ?: suggest(pkg, label, ai, roles)?.let { symbolMask(ctx, it, fill, t, size, frac) }
                ?: letterMask(label, t, size, frac * 0.8f)
                ?: return own
            val seed = pkg.hashCode()
            val k = IconColours.resolve(spec, onLight, own)
            IconStyles.paint(style, mask, k, seed, size, plain = spec.effect in IconEffects.TILE_AS_BACKGROUND)
        } catch (e: Exception) { own }
    }

    /**
     * True if what's drawn in [b] is all about one colour (a line-art or glyph icon), so its shape can
     * be redrawn in a style; false for a full-colour icon.
     */
    fun oneColour(b: Bitmap): Boolean = oneColourOf(b) != null

    /** The colour [b] is drawn in if it's all about one colour, else null. */
    private fun oneColourOf(b: Bitmap): Int? {
        val w = b.width; val h = b.height
        val px = IntArray(w * h); b.getPixels(px, 0, w, 0, 0, w, h)
        var n = 0; var r = 0L; var g = 0L; var bl = 0L
        for (c in px) if (c ushr 24 > 160) { n++; r += (c shr 16) and 255; g += (c shr 8) and 255; bl += c and 255 }
        if (n < 20) return null
        val mr = (r / n).toInt(); val mg = (g / n).toInt(); val mb = (bl / n).toInt()
        var near = 0
        for (c in px) if (c ushr 24 > 160) {
            val d = abs(((c shr 16) and 255) - mr) + abs(((c shr 8) and 255) - mg) + abs((c and 255) - mb)
            if (d < 90) near++
        }
        return if (near > n * 0.88f) Color.rgb(mr, mg, mb) else null
    }

    /**
     * A one-colour icon (most line-art packs are white) redrawn dark on a light background, or light on
     * a dark one, if it would otherwise barely show. Anything else is returned as it is.
     */
    fun contrasting(b: Bitmap, onLight: Boolean): Bitmap {
        val c = oneColourOf(b) ?: return b
        val lum = (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)) / 255f
        val ink = when {
            onLight && lum > 0.72f -> 0xFF1B1C20.toInt()
            !onLight && lum < 0.28f -> 0xFFF2F2F2.toInt()
            else -> return b
        }
        val out = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(b, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = PorterDuffColorFilter(ink, PorterDuff.Mode.SRC_IN) })
        return out
    }

    /** The app's own one-colour drawing (Android 13+), white on transparent. */
    private fun monochrome(d: Drawable, t: Int): Bitmap? {
        if (Build.VERSION.SDK_INT < 33 || d !is AdaptiveIconDrawable) return null
        val m = d.monochrome?.mutate() ?: return null
        val b = Bitmap.createBitmap(t, t, Bitmap.Config.ARGB_8888)
        m.setBounds(0, 0, t, t)
        m.colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
        m.draw(Canvas(b))
        return b
    }

    private fun symbolMask(ctx: Context, name: String, fill: Boolean, t: Int, size: Int, frac: Float): Bitmap? {
        val s = symbol(ctx, name) ?: return null
        val tf = symbolFont(ctx, fill) ?: return null
        return normalise(text(s.text, tf, t), size, frac, solidOk = true)
    }

    private fun letterMask(label: String, t: Int, size: Int, frac: Float): Bitmap? {
        val ch = label.trim().firstOrNull { it.isLetterOrDigit() } ?: return null
        return normalise(text(ch.uppercase(), Fonts.medium, t), size, frac, solidOk = true)
    }

    private fun text(s: String, tf: Typeface, t: Int): Bitmap {
        val b = Bitmap.createBitmap(t, t, Bitmap.Config.ARGB_8888)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = tf; textSize = t * 0.7f; color = Color.WHITE }
        val r = Rect(); p.getTextBounds(s, 0, s.length, r)
        Canvas(b).drawText(s, (t - r.width()) / 2f - r.left, (t - r.height()) / 2f - r.top, p)
        return b
    }

    /**
     * Crop [src] to what's drawn and scale it so its longer side is [frac] of [size], centred, as an
     * alpha mask, so every app's drawing ends up the same size. Null if there's nothing usable.
     */
    private fun normalise(src: Bitmap, size: Int, frac: Float, solidOk: Boolean): Bitmap? {
        val w = src.width; val h = src.height
        val px = IntArray(w * h); src.getPixels(px, 0, w, 0, 0, w, h)
        var l = w; var t = h; var r = -1; var b = -1; var solid = 0
        for (y in 0 until h) for (x in 0 until w) {
            val a = px[y * w + x] ushr 24
            if (a <= 20) continue
            if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > b) b = y
            if (a > 200) solid++
        }
        if (r < l) return null
        val bw = r - l + 1; val bh = b - t + 1
        // A layer that's just a filled shape covering everything is a background, not a drawing.
        if (!solidOk && bw > w * 0.9f && bh > h * 0.9f && solid > bw * bh * 0.7f) return null
        val s = frac * size / max(bw, bh)
        val dw = bw * s; val dh = bh * s
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ALPHA_8)
        Canvas(out).drawBitmap(src, Rect(l, t, r + 1, b + 1),
            RectF((size - dw) / 2f, (size - dh) / 2f, (size + dw) / 2f, (size + dh) / 2f),
            Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return out
    }

    /** True if this icon is drawn differently on a light or a dark background (so the desktop needs its own copy). */
    fun dependsOnBackground(spec: Spec, choice: String?): Boolean {
        if (choice == OWN) return false
        val picked = choice?.startsWith(SYM) == true
        // Pack icons may be one-colour line art, redrawn dark or light to suit what's behind them.
        if (spec.style == ORIGINAL && !picked) return spec.pack != null || choice?.startsWith(PACK) == true
        val style = if (spec.style == ORIGINAL) THEMED else spec.style
        return spec.colours == IconColours.AUTO || ((style == MINIMAL || style == OUTLINE) && spec.colours == IconColours.ACCENT)
    }
}
