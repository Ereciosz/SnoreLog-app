package app.snorelog

/** Newest first. Only the last 5 are shown. */
object Changelog {
    val items = listOf(
        "0.49" to "New sleep score based on the share of the night spent snoring; tap the score ring to see how it is made.",
        "0.48" to "Cleanup: removed leftover code and an unused CSV column.",
        "0.47" to "Backups skip rejected clips; new clips are about 1/3 smaller.",
        "0.46" to "Backup and restore to a ZIP file; labels no longer cut off.",
        "0.45" to "One setting for what unplugging does.",
        "0.44" to "One simpler \"Disarm when I get up\" setting.",
        "0.43" to "Fixes a crash introduced in 0.42.",
        "0.42" to "Health Connect watch data removed.",
        "0.41" to "Optional auto-disarm after your Clock alarm; tidier Settings.",
        "0.40" to "Credits and this changelog.",
        "0.39" to "Asks about background activity on first launch and when arming.",
        "0.38" to "Hidden easter egg: tap the score ring 7 times.",
        "0.37" to "AI classifier is always on; its switch is gone.",
        "0.36" to "Button to play the loudest clip of the night."
    )

    fun latest(): String = items.first().let { "Updated to v${it.first}: ${it.second}" }

    fun text(): String = items.take(5).joinToString("\n\n") { "v${it.first}\n${it.second}" }
}
