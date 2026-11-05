// checkProductOnly: the reference lab (lab/) is for development, tests and demos, and never ships
// (ADR 0024). This check reads the assembled release folder, and Kilnmark's own jars inside it,
// and fails when anything there depends on the lab, names it or bundles it. `release` depends on it.
//
// Rules, each reported with the file and the text around the hit:
//   paths      no lab/, tests/ or docs/ folder in the release, and none inside Kilnmark's jars except
//              the bundled guides and changelog (kilnmark/docs/guide/*.md, guide.list, CHANGELOG.md)
//   names      no lab source or service name (lab-db, lab-share, lab-warehouse, lab-sqlserver, ...:
//              any lab-<name>), no labadmin, no path into lab/ (lab/files, lab/sources.csv, ...)
//   ports      no lab-only port: localhost:5433, :9020 or :3307, or a 5433:5432, 9020:7070 or 3307:3306
//              mapping
//   words      the shipped compose.yaml, everything under containers/, the bundled guides and the
//              samples (samples/ and kilnmark/samples/ in the jar) and the sample data they read
//              (kilnmark/sample-data/) do not mention the lab at all (the word "lab"); the samples name
//              their sources by placeholders
//   changelog  the bundled changelog's "What's new" sections, written for customers, do not either;
//              its details may describe lab changes, and the UI bundle that carries them is let off
//              for exactly that text
//   secrets    the shipped compose.yaml gives no variable whose name holds PASSWORD, SECRET or TOKEN
//              a default (${X:-...}, ${X-...}, ${X:=...}, ${X=...}, even an empty one) and sets no
//              such key to a literal value; .env.example leaves every such variable empty; and the
//              release holds no .env file
// Kilnmark's own jars are its application jar and lib/main/io.kilnmark.*.jar, read entry by entry
// (classes, the UI bundle under META-INF/resources, the bundled docs, application.properties, whose
// %dev and %test lines may use the lab). Other files in the release are read as they are.
// Justified exceptions go in config/product-only-allow.txt.

val releaseFolder = layout.projectDirectory.dir("dist/release/kilnmark-${project.version}")
val allowFile = layout.projectDirectory.file("config/product-only-allow.txt")
val marker = layout.buildDirectory.file("product-only/checked.txt")

tasks.register("checkProductOnly") {
    group = "verification"
    description = "Fails if the release folder depends on, names or bundles the reference lab (ADR 0024)."
    dependsOn("assembleRelease")
    inputs.dir(releaseFolder)
    inputs.file(allowFile)
    outputs.file(marker)
    val root = releaseFolder.asFile
    val allow = allowFile.asFile
    val out = marker.get().asFile
    doLast {
        val problems = ProductOnly(root, allow).check()
        if (problems.isNotEmpty()) {
            problems.forEach { logger.error(it) }
            throw GradleException(
                "checkProductOnly: ${problems.size} problem(s) in the release (ADR 0024). " +
                    "Remove it, or justify it in config/product-only-allow.txt."
            )
        }
        out.parentFile.mkdirs()
        out.writeText("Product only: no lab references or default secrets in ${root.name}.\n")
        logger.lifecycle("checkProductOnly: no lab references or default secrets in ${root.name}")
    }
}

/** An exception: a hit is allowed when its file matches glob and the text around it contains snippet. */
data class Allow(val glob: Regex, val snippet: String)

class ProductOnly(private val root: java.io.File, allowFile: java.io.File) {

    /** lab-<name> as a whole word (lab-db, lab-share, lab-warehouse, ...), labadmin, paths into lab/, lab-only ports. */
    private val names = listOf(
        Regex("(?<![A-Za-z0-9_.-])lab-[a-z][a-z0-9-]*[a-z0-9]"),
        Regex("(?i)labadmin"),
        Regex("(?<![A-Za-z0-9_-])lab/[A-Za-z]"),
        Regex("(localhost|127\\.0\\.0\\.1|host\\.(docker|containers)\\.internal):(5433|9020|3307)(?![0-9])"),
        Regex("(?<![0-9])(5433:5432|9020:7070|3307:3306)(?![0-9])"),
    )
    /** Any mention of the lab, for files people read as the product: compose, containers, guides. */
    private val word = Regex("(?i)(?<![A-Za-z])lab(?![A-Za-z])")
    /** Characters either side of a hit that an allow-list entry's text must fall within. */
    private val window = 200
    /** Characters either side of a hit compared with the changelog's details. */
    private val corpusWindow = 40
    private val allowed = readAllowList(allowFile)

    private fun readAllowList(file: java.io.File): List<Allow> =
        if (!file.exists()) emptyList()
        else file.readLines().mapIndexedNotNull { i, raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@mapIndexedNotNull null
            val bar = line.indexOf(" | ")
            require(bar > 0) { "${file.name}:${i + 1}: write <path glob> | <text around the hit>" }
            Allow(glob(line.substring(0, bar).trim()), line.substring(bar + 3).trim())
        }

    /** * within a folder, ** across folders, ? one character. */
    private fun glob(g: String): Regex {
        val sb = StringBuilder()
        var i = 0
        while (i < g.length) {
            val c = g[i]
            when {
                c == '*' && i + 1 < g.length && g[i + 1] == '*' -> { sb.append(".*"); i++ }
                c == '*' -> sb.append("[^/]*")
                c == '?' -> sb.append("[^/]")
                else -> sb.append(Regex.escape(c.toString()))
            }
            i++
        }
        return Regex(sb.toString())
    }

    /** Letters and digits only, lower case, with JS string escapes taken out: text compared across formats. */
    private fun normalise(s: String): String =
        s.replace(Regex("\\\\u[0-9a-fA-F]{4}|\\\\x[0-9a-fA-F]{2}|\\\\[nrt]"), " ")
            .lowercase().filter { it in 'a'..'z' || it in '0'..'9' }

    private val problems = mutableListOf<String>()
    /** The changelog's text outside "What's new": it may describe lab changes, wherever it is bundled. */
    private var changelogDetails = ""

    fun check(): List<String> {
        if (!root.isDirectory) return listOf("$root: no release folder; run assembleRelease")
        val files = root.walkTopDown().filter { it.isFile }.sortedBy { it.path }.toList()
        val ownJars = files.filter { isOwnJar(rel(it)) }
        // Read the bundled changelog first, so the UI bundle can be let off for its details.
        ownJars.forEach { jar ->
            entries(jar) { name, text -> if (name == "kilnmark/docs/CHANGELOG.md") changelog(rel(jar) + "!/" + name, text) }
        }
        for (f in files) {
            val path = rel(f)
            checkPath(path)
            when {
                isOwnJar(path) -> entries(f) { name, text -> checkEntry("$path!/$name", name, text) }
                f.name.endsWith(".jar") -> {}   // third-party libraries and Quarkus's generated code
                path == "compose.yaml" || path.startsWith("containers/") || path.startsWith("samples/") -> {
                    val text = f.readText(Charsets.ISO_8859_1)
                    scan(path, text, names + word, corpus = false)
                    if (path == "compose.yaml") secretDefaults(path, text)
                }
                path == ".env.example" -> {
                    val text = f.readText(Charsets.ISO_8859_1)
                    scan(path, text, names + word, corpus = false)
                    secretValues(path, text)
                }
                else -> scan(path, f.readText(Charsets.ISO_8859_1), names, corpus = false)
            }
        }
        return problems
    }

    private fun rel(f: java.io.File) = f.relativeTo(root).invariantSeparatorsPath

    private fun isOwnJar(path: String) =
        Regex("server/app/[^/]+\\.jar|server/lib/main/io\\.kilnmark\\.[^/]+\\.jar").matches(path)

    /** A name that holds a secret: a password, secret or token. */
    private val secretName = "[A-Za-z0-9_]*(?:PASSWORD|SECRET|TOKEN)[A-Za-z0-9_]*"

    /** Names that match secretName but hold no secret: each a setting about secrets, named here on purpose. */
    private val notSecrets = setOf(
        "KILNMARK_TOKEN_MAX_LIFETIME", // how long API tokens may last, a duration (ADR 0037)
    )

    /** compose.yaml: no default for a secret's variable, and no secret written into the file. */
    private fun secretDefaults(path: String, text: String) {
        text.lines().forEachIndexed { i, raw ->
            val line = raw.substringBefore(" #").let { if (it.trimStart().startsWith("#")) "" else it }
            Regex("\\$\\{($secretName)(:?[-=])").findAll(line).filter { it.groupValues[1] !in notSecrets }.forEach { m ->
                problems += "$path:${i + 1}: secrets: \"${m.groupValues[1]}\" has a default (${m.groupValues[2]}); " +
                    "require it with \${${m.groupValues[1]}:?message} or let Kilnmark generate it"
            }
            Regex("^\\s*-?\\s*\"?($secretName)\"?\\s*[:=]\\s*\"?([^\\s\"]+)").find(line)?.let { m ->
                if (!m.groupValues[2].startsWith("\$") && !m.groupValues[1].endsWith("_FILE")) {
                    problems += "$path:${i + 1}: secrets: \"${m.groupValues[1]}\" is set to a literal value"
                }
            }
        }
    }

    /** .env.example: secrets are listed, never filled in. */
    private fun secretValues(path: String, text: String) {
        text.lines().forEachIndexed { i, line ->
            Regex("^\\s*($secretName)\\s*=\\s*(\\S.*)$").find(line)?.let { m ->
                problems += "$path:${i + 1}: secrets: \"${m.groupValues[1]}\" has a value; leave it empty"
            }
        }
    }

    private fun checkPath(path: String) {
        if (path.substringAfterLast('/') == ".env") problems += "$path: secrets: a .env file in the release"
        val parts = path.split('/')
        if (parts.dropLast(1).any { it == "lab" || it == "tests" || it == "docs" }) {
            problems += "$path: a lab, tests or docs folder in the release"
        }
    }

    private fun entries(jar: java.io.File, each: (String, String) -> Unit) {
        java.util.zip.ZipFile(jar).use { zip ->
            for (e in zip.entries()) {
                if (e.isDirectory) continue
                val text = zip.getInputStream(e).use { String(it.readBytes(), Charsets.ISO_8859_1) }
                each(e.name, text)
            }
        }
    }

    private fun checkEntry(where: String, name: String, text: String) {
        val top = name.substringBefore('/')
        if (top == "lab" || top == "tests" || top == "docs" ||
            (name.startsWith("kilnmark/docs/") &&
                !Regex("kilnmark/docs/(guide/[^/]+\\.md|guide\\.list|CHANGELOG\\.md)").matches(name))) {
            problems += "$where: only the user guides and the changelog are bundled as documentation"
        }
        when {
            name.startsWith("kilnmark/docs/guide/") || name.startsWith("kilnmark/samples/")
                || name.startsWith("kilnmark/sample-data/") ->
                scan(where, text, names + word, corpus = false)
            name == "kilnmark/docs/CHANGELOG.md" -> {}   // checked in changelog()
            name == "application.properties" ->
                // %dev and %test may use the lab; production defaults (no profile, or one naming prod) may not.
                scan(where, text.lines().joinToString("\n") { l ->
                    val p = Regex("^\\s*%([^.]+)\\.").find(l)?.groupValues?.get(1)
                    if (p != null && "prod" !in p.split(',')) "" else l
                }, names, corpus = false)
            else -> scan(where, text, names, corpus = true)
        }
    }

    /** "What's new" is for customers: no lab at all. The rest may describe lab changes. */
    private fun changelog(where: String, text: String) {
        val details = StringBuilder()
        var whatsNew = false
        for (line in text.split('\n')) {
            if (line.startsWith("#")) whatsNew = line.trimStart('#').trim().equals("What's new", ignoreCase = true)
            if (whatsNew) {
                scan(where, line, names + word, corpus = false, section = "What's new ")
            } else {
                details.append(line).append('\n')
            }
        }
        changelogDetails = normalise(details.toString())
    }

    private fun scan(where: String, text: String, rules: List<Regex>, corpus: Boolean, section: String = "") {
        val named = mutableListOf<IntRange>()
        for (r in rules) {
            for (m in r.findAll(text)) {
                // "lab" inside a name already reported is the same finding.
                if (r === word && named.any { m.range.first in it }) continue
                if (r !== word) named += m.range
                if (corpus && fromChangelogDetails(text, m.range)) continue
                report(where, section + (if (r === word) "mentions the lab" else "lab name, path or port"), text, m.range, m.value)
            }
        }
    }

    private fun fromChangelogDetails(text: String, range: IntRange): Boolean {
        if (changelogDetails.isEmpty()) return false
        val around = normalise(text.substring(maxOf(0, range.first - corpusWindow), minOf(text.length, range.last + 1 + corpusWindow)))
        return around.length > 20 && changelogDetails.contains(around)
    }

    private fun report(where: String, rule: String, text: String, range: IntRange, value: String) {
        val around = text.substring(maxOf(0, range.first - window), minOf(text.length, range.last + 1 + window))
        if (allowed.any { it.glob.matches(where) && it.snippet.contains(value) && around.contains(it.snippet) }) return
        val context = text.substring(maxOf(0, range.first - 50), minOf(text.length, range.last + 1 + 50))
            .replace(Regex("\\s+"), " ")
        problems += "$where: $rule: \"$value\" in ...$context..."
    }
}
