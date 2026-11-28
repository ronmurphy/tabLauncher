package dev.tabdesk.people

// Ported from DroidACP's core/src/main/kotlin/com/droidacp/core/people/PixelPeople.kt (d675b50), with
// assets/people/sprites.txt copied from the same commit. Both apps draw the same people from the same file, so keep
// this reader in step with DroidACP's: change it there first, then copy it here. Only the loading differs (an
// Android asset here, a Java resource there).

/**
 * One agent's look, as it chose it once (or a default until it does): the option ids of
 * `sprites.txt`. [letter] goes on the shirt (A-Z, 0-9), or none; [shirt] is a chosen colour,
 * or null for the agent's own. [bottom] is pants, shorts, a skirt or a dress, in
 * [bottomColor] (a dress is the shirt's colour). [agent] isn't chosen: it's whose colours the
 * bar under the person has ([PixelPeople.agentFor], [PixelPeople.bar]), so people from
 * different agents stay apart.
 */
data class Look(
    val skin: String = "light",
    val hair: String = "short",
    val hairColor: String = "brown",
    val accessories: List<String> = emptyList(),
    val letter: Char? = null,
    val shirt: String? = null,
    val agent: String = "unknown",
    val bottom: String = "pants",
    val bottomColor: String = "navy",
)

/** A drawn person, [width] x [height] pixels of ARGB; 0 is transparent. */
class Sprite(val width: Int, val height: Int, val pixels: IntArray)

/**
 * The Pixel People: small people drawn from `sprites.txt`, one per agent, posed by what the
 * agent is doing. The file's format is described at its top; DroidACP reads the same file, so
 * keep this reader in step with DroidACP's.
 */
class PixelPeople private constructor(
    private val palette: Map<Char, Int>,
    private val options: Map<String, Map<String, Int>>,
    private val shades: Map<String, Map<String, Int>>,
    private val agentColors: Map<String, List<Int>>,
    private val face: Section,
    private val hairs: Map<String, Section>,
    private val accessoryDefs: Map<String, Section>,
    private val bottomDefs: Map<String, Section>,
    private val parts: Map<String, Grid>,
    private val poseDefs: Map<String, Section>,
    private val signs: Map<String, Grid>,
    private val glyphs: Map<Char, List<String>>,
) {
    val poses: Set<String> get() = poseDefs.keys
    val skins: List<String> get() = options["skin"].orEmpty().keys.toList()
    val hairStyles: List<String> get() = hairs.keys.toList()
    val hairColors: List<String> get() = options["haircolor"].orEmpty().keys.toList()
    val accessories: List<String> get() = accessoryDefs.keys.toList()
    val shirts: List<String> get() = options["shirt"].orEmpty().keys.toList()
    val bottoms: List<String> get() = bottomDefs.keys.toList()
    val bottomColors: List<String> get() = options["bottomcolor"].orEmpty().keys.toList()
    val agents: List<String> get() = agentColors.keys.toList()
    val letters: Set<Char> get() = glyphs.keys

    /** [pose] drawn for [look]; an unknown pose is drawn as [IDLE_POSE], unknown options as their first. */
    fun render(pose: String, look: Look): Sprite {
        val px = IntArray(SIZE * SIZE)
        val p = poseDefs[pose] ?: poseDefs.getValue(IDLE_POSE)
        val skin = options["skin"]?.get(look.skin) ?: options.getValue("skin").values.first()
        val hairColor = options["haircolor"]?.get(look.hairColor) ?: options.getValue("haircolor").values.first()
        val shirt = look.shirt?.let { options["shirt"]?.get(it) }?.let(::listOf) ?: colorsOf(look.agent)
        // Pants, shorts, a skirt or a dress: its grids stand in for the parts they're named
        // after, in its colour (J j), or the shirt's for a dress.
        val bottom = bottomDefs[look.bottom] ?: bottomDefs.values.firstOrNull()
        val inShirt = bottom?.word("color") == "shirt"
        val bottomColors = options["bottomcolor"].orEmpty()
        val bottomKey = look.bottomColor.takeIf { it in bottomColors } ?: bottomColors.keys.firstOrNull()
        val legs = bottomKey?.let(bottomColors::get) ?: palette.getValue('P')
        val legsShade = bottomKey?.let { shades["bottomcolor"]?.get(it) } ?: if (bottomKey == null) palette.getValue('p') else shade(legs, 0.8)
        val colors = HashMap(palette).apply {
            put('S', skin)
            put('s', shade(skin, 0.85))
            put('r', mix(skin, 0xE0707A, 0.35))
            put('H', hairColor)
            put('h', shade(hairColor, 0.75))
            put('L', if (shirt.map(::luminance).average() > 150) getValue('k') else getValue('W'))
        }

        fun color(ch: Char, y: Int, extra: Map<Char, Int>): Int? = extra[ch] ?: when (ch) {
            'T' -> shirt[y % shirt.size]
            't' -> shade(shirt[y % shirt.size], 0.8)
            'J' -> if (inShirt) shirt[y % shirt.size] else legs
            'j' -> if (inShirt) shade(shirt[y % shirt.size], 0.8) else legsShade
            else -> colors[ch]
        }

        fun stamp(rows: List<String>, ox: Int, oy: Int, extra: Map<Char, Int> = emptyMap()) {
            for ((j, row) in rows.withIndex()) {
                for ((i, ch) in row.withIndex()) {
                    if (ch == '.') continue
                    val x = ox + i
                    val y = oy + j
                    if (x !in 0 until SIZE || y !in 0 until SIZE) continue
                    val c = color(ch, y, extra) ?: continue
                    px[y * SIZE + x] = (0xFF shl 24) or c
                }
            }
        }

        val head = p.pair("head")
        val eyes = p.word("eyes") ?: "open"
        val hair = hairs[look.hair] ?: hairs.values.first()
        if (head != null) {
            hair.grids.filter { it.label == "back" }.forEach { stamp(it.rows, head.first + it.x, head.second + it.y) }
        }
        for (line in p.lines) {
            when (line.first()) {
                "use" -> (bottom?.grids?.firstOrNull { it.label == line[1] } ?: parts[line[1]])?.let {
                    stamp(it.rows, line[2].toInt() + it.x, line[3].toInt() + it.y)
                }
                "grid" -> p.gridsByLine[line]?.let { stamp(it.rows, it.x, it.y) }
                "@head" -> if (head != null) {
                    val (hx, hy) = head
                    face.grids.forEach { stamp(it.rows, hx + it.x, hy + it.y) }
                    face.lines.firstOrNull { it.first() == "eyes" && it.getOrNull(1) == eyes }?.drop(2)?.forEach { xy ->
                        val (ex, ey) = xy.split(',').map(String::toInt)
                        val x = hx + ex
                        val y = hy + ey
                        if (x in 0 until SIZE && y in 0 until SIZE) px[y * SIZE + x] = (0xFF shl 24) or colors.getValue('E')
                    }
                    hair.grids.filter { it.label == "front" }.forEach { stamp(it.rows, hx + it.x, hy + it.y) }
                    for (name in look.accessories.distinct().take(MAX_ACCESSORIES)) {
                        val acc = accessoryDefs[name] ?: continue
                        val extra = acc.lines.filter { it.first() == "A" || it.first() == "a" }
                            .associate { it.first().single() to it[1].toInt(16) }
                        acc.grids.forEach { stamp(it.rows, hx + it.x, hy + it.y, extra) }
                    }
                }
                "letter" -> look.letter?.uppercaseChar()?.let(glyphs::get)?.let { g ->
                    stamp(g.map { it.replace('#', 'L') }, line[1].toInt(), line[2].toInt())
                }
                "effect" -> signs[line[1]]?.let { stamp(it.rows, line[2].toInt(), line[3].toInt()) }
            }
        }
        return Sprite(SIZE, SIZE, px)
    }

    /** The colours of an agent (its `agentFor` id), for its bar and its shirt until it chooses one. */
    private fun colorsOf(agent: String): List<Int> = agentColors[agent] ?: agentColors.getValue("unknown")

    /** Whose colours an agent wears, from its ACP `agentInfo` name (or title). */
    fun agentFor(agentName: String?): String {
        val n = agentName?.lowercase() ?: return "unknown"
        val id = AGENT_NAMES.firstOrNull { (key, _) -> key in n }?.second ?: return "unknown"
        return if (id in agentColors) id else "unknown"
    }

    /**
     * The bar under a person: [agent]'s colours side by side, [BAR_WIDTH] x [BAR_HEIGHT],
     * outlined like the people (corners left out; in grey when the colours are as dark as
     * the outline, so it shows on a dark screen) and its lower row shaded.
     */
    fun bar(agent: String): Sprite {
        val colors = colorsOf(agent)
        val outline = palette.getValue(if (colors.map(::luminance).average() < DARK) 'G' else 'k')
        val inner = BAR_WIDTH - 2
        val px = IntArray(BAR_WIDTH * BAR_HEIGHT)
        for (y in 0 until BAR_HEIGHT) {
            for (x in 0 until BAR_WIDTH) {
                val edgeX = x == 0 || x == BAR_WIDTH - 1
                val edgeY = y == 0 || y == BAR_HEIGHT - 1
                if (edgeX && edgeY) continue
                val c = if (edgeX || edgeY) {
                    outline
                } else {
                    val base = colors[(x - 1) * colors.size / inner]
                    if (y == BAR_HEIGHT - 2) shade(base, 0.8) else base
                }
                px[y * BAR_WIDTH + x] = (0xFF shl 24) or c
            }
        }
        return Sprite(BAR_WIDTH, BAR_HEIGHT, px)
    }

    /**
     * A look for an agent that hasn't chosen its own yet: the same every time for [seed] (its
     * project folder, say), so its person doesn't change between restarts or devices.
     */
    fun defaultLook(seed: String, agent: String, letter: Char?): Look {
        var h = 17
        for (c in seed) h = h * 31 + c.code
        val n = h and 0x7FFFFFFF
        val styles = hairStyles.filter { it != "bald" }
        return Look(
            skin = skins[n % skins.size],
            hair = styles[(n / 7) % styles.size],
            hairColor = NATURAL_HAIR.filter { it in hairColors }.let { it[(n / 53) % it.size] },
            letter = letter?.uppercaseChar()?.takeIf { it in glyphs },
            agent = agent,
        )
    }

    /**
     * The look an agent chose ([skin], [hair], ... as option ids), over [default]: a part it
     * didn't choose, or chose with an id this file doesn't have, stays the default's (for the
     * shirt, the agent's own colours). The bar is always the agent's.
     */
    fun chosen(
        default: Look,
        skin: String?,
        hair: String?,
        hairColor: String?,
        accessories: List<String>,
        letter: Char?,
        shirt: String? = null,
        bottom: String? = null,
        bottomColor: String? = null,
    ): Look =
        default.copy(
            skin = skin?.takeIf { it in skins } ?: default.skin,
            hair = hair?.takeIf { it in hairs } ?: default.hair,
            hairColor = hairColor?.takeIf { it in hairColors } ?: default.hairColor,
            accessories = accessories.filter { it in accessoryDefs }.distinct().take(MAX_ACCESSORIES),
            letter = letter?.uppercaseChar()?.takeIf { it in glyphs },
            shirt = shirt?.takeIf { it in shirts },
            bottom = bottom?.takeIf { it in bottomDefs } ?: default.bottom,
            bottomColor = bottomColor?.takeIf { it in bottomColors } ?: default.bottomColor,
        )

    private class Grid(val label: String, val x: Int, val y: Int, val rows: MutableList<String> = mutableListOf())

    private class Section(val kind: String, val name: String, val args: List<String>) {
        val lines = mutableListOf<List<String>>()
        val grids = mutableListOf<Grid>()
        val gridsByLine = HashMap<List<String>, Grid>()

        fun pair(key: String): Pair<Int, Int>? {
            val l = lines.firstOrNull { it.first() == key } ?: return null
            if (l.getOrNull(1) == "none") return null
            return l[1].toInt() to l[2].toInt()
        }

        fun word(key: String): String? = lines.firstOrNull { it.first() == key }?.getOrNull(1)
    }

    companion object {
        const val SIZE = 32
        const val IDLE_POSE = "sleep"
        const val MAX_ACCESSORIES = 2
        const val BAR_WIDTH = 22
        const val BAR_HEIGHT = 4
        private const val DARK = 60.0

        private val KINDS = setOf("palette", "option", "agent", "face", "hair", "accessory", "bottom", "part", "pose", "sign", "glyph")

        // Agent names (as ACP adapters report them) to their colours; the first that matches wins.
        private val AGENT_NAMES = listOf(
            "claude" to "claude",
            "gemini" to "gemini",
            "codex" to "codex",
            "openai" to "codex",
            "copilot" to "copilot",
            "qwen" to "qwen",
            "mistral" to "mistral",
            "vibe" to "mistral",
            "deepseek" to "deepseek",
            "opencode" to "opencode",
            "goose" to "goose",
            "ollama" to "local",
            "llama" to "local",
            "lmstudio" to "local",
        )

        private val NATURAL_HAIR = listOf("black", "dark-brown", "brown", "auburn", "ginger", "blond", "platinum", "grey")

        @Volatile private var loaded: PixelPeople? = null

        /** The people in TabDesk's `assets/people/sprites.txt`, read once. */
        fun get(ctx: android.content.Context): PixelPeople = loaded ?: synchronized(this) {
            loaded ?: parse(ctx.assets.open("people/sprites.txt").bufferedReader().use { it.readText() }).also { loaded = it }
        }

        fun parse(text: String): PixelPeople {
            val sections = mutableListOf<Section>()
            var cur: Section? = null
            var grid: Grid? = null
            for (raw in text.lines()) {
                if (grid != null) {
                    if (raw.isBlank()) grid = null else grid.rows += raw.trim()
                    continue
                }
                val s = raw.trim()
                if (s.isEmpty() || s.startsWith("#")) continue
                val w = s.split(Regex("\\s+"))
                when {
                    w[0] in KINDS -> {
                        cur = Section(w[0], w.getOrElse(1) { "" }, w.drop(2)).also(sections::add)
                        if (w[0] == "glyph") grid = Grid("glyph", 0, 0).also { cur.grids += it }
                    }
                    w[0] == "grid" -> {
                        val section = cur ?: error("a grid outside a section")
                        grid = Grid(w[1], w[2].toInt(), w[3].toInt())
                        section.grids += grid
                        section.lines += w
                        section.gridsByLine[w] = grid
                    }
                    else -> (cur ?: error("\"$s\" outside a section")).lines += w
                }
            }
            val palette = HashMap<Char, Int>()
            val options = LinkedHashMap<String, LinkedHashMap<String, Int>>()
            val shades = HashMap<String, HashMap<String, Int>>()
            val agents = LinkedHashMap<String, List<Int>>()
            val hairs = LinkedHashMap<String, Section>()
            val accessories = LinkedHashMap<String, Section>()
            val bottoms = LinkedHashMap<String, Section>()
            val parts = HashMap<String, Grid>()
            val poses = LinkedHashMap<String, Section>()
            val signs = HashMap<String, Grid>()
            val glyphs = HashMap<Char, List<String>>()
            var face: Section? = null
            for (sec in sections) {
                when (sec.kind) {
                    "palette" -> sec.lines.forEach { palette[it[0].single()] = it[1].toInt(16) }
                    "option" -> sec.lines.forEach {
                        options.getOrPut(sec.name, ::LinkedHashMap)[it[0]] = it[1].toInt(16)
                        // A colour may say its own shade, else it's worked out.
                        it.getOrNull(2)?.let { shade -> shades.getOrPut(sec.name, ::HashMap)[it[0]] = shade.toInt(16) }
                    }
                    "agent" -> agents[sec.name] = sec.args.map { it.toInt(16) }
                    "face" -> face = sec
                    "hair" -> hairs[sec.name] = sec
                    "accessory" -> accessories[sec.name] = sec
                    "bottom" -> bottoms[sec.name] = sec
                    "part" -> parts[sec.name] = sec.grids.single()
                    "pose" -> poses[sec.name] = sec
                    "sign" -> signs[sec.name] = sec.grids.single()
                    "glyph" -> glyphs[sec.name.single()] = sec.grids.single().rows
                }
            }
            return PixelPeople(
                palette, options, shades, agents, face ?: error("no face in sprites.txt"), hairs, accessories, bottoms, parts, poses,
                signs, glyphs,
            )
        }

        private fun shade(c: Int, f: Double): Int =
            (((c shr 16 and 0xFF) * f).toInt() shl 16) or (((c shr 8 and 0xFF) * f).toInt() shl 8) or ((c and 0xFF) * f).toInt()

        private fun mix(a: Int, b: Int, t: Double): Int {
            fun ch(shift: Int) = ((a shr shift and 0xFF) * (1 - t) + (b shr shift and 0xFF) * t).toInt()
            return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }

        private fun luminance(c: Int): Double = 0.3 * (c shr 16 and 0xFF) + 0.59 * (c shr 8 and 0xFF) + 0.11 * (c and 0xFF)
    }
}
