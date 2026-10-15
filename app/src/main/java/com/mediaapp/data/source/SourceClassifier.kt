package com.mediaapp.data.source

/**
 * Where a media file came from. [id] is stable and is also the folder key.
 * Known sources share one id across path variants. Anything else becomes its own folder.
 */
data class ClassifiedSource(
    val id: String,
    val label: String,
    val known: Boolean,
)

data class ClassificationInput(
    val relativePath: String?,
    val bucketName: String?,
    val displayName: String,
    val mimeType: String? = null,
)

/**
 * First matching rule wins. Put specific app folders above broad ones
 * (Messenger before Facebook, WhatsApp before Downloads, filename rules before Camera).
 * Add a [SourceRule] here to recognize a new source without touching callers.
 */
data class SourceRule(
    val id: String,
    val label: String,
    val pathNeedles: List<String> = emptyList(),
    val bucketNames: List<String> = emptyList(),
    val fileNameNeedles: List<String> = emptyList(),
    val fileNameRegex: Regex? = null,
)

val KnownSourceOrder: List<String> = listOf(
    "camera",
    "screenshots",
    "screen_recordings",
    "downloads",
    "whatsapp",
    "instagram",
    "messenger",
    "facebook",
    "telegram",
    "snapchat",
    "tiktok",
    "twitter",
    "bluetooth",
    "edited",
)

val DefaultSourceRules: List<SourceRule> = listOf(
    SourceRule(
        id = "whatsapp",
        label = "WhatsApp",
        pathNeedles = listOf(
            "com.whatsapp",
            "/whatsapp/",
            "whatsapp images",
            "whatsapp video",
            "whatsapp animated",
            "whatsapp documents",
            "/.statuses/",
            "/statuses/",
        ),
        bucketNames = listOf(
            "whatsapp",
            "whatsapp images",
            "whatsapp video",
            "whatsapp animated gifs",
            "whatsapp stickers",
            "whatsapp status",
            ".statuses",
        ),
    ),
    SourceRule(
        id = "messenger",
        label = "Messenger",
        pathNeedles = listOf("/messenger/", "com.facebook.orca", "com.facebook.mlite"),
        bucketNames = listOf("messenger"),
    ),
    SourceRule(
        id = "facebook",
        label = "Facebook",
        pathNeedles = listOf("/facebook/", "com.facebook.katana", "com.facebook.lite"),
        bucketNames = listOf("facebook"),
    ),
    SourceRule(
        id = "instagram",
        label = "Instagram",
        pathNeedles = listOf("/instagram/", "com.instagram.android", "com.instagram.lite"),
        bucketNames = listOf("instagram"),
    ),
    SourceRule(
        id = "telegram",
        label = "Telegram",
        pathNeedles = listOf(
            "/telegram/",
            "org.telegram.messenger",
            "org.telegram.android",
            "org.thunderdog.challegram",
        ),
        bucketNames = listOf("telegram", "telegram images", "telegram video"),
    ),
    SourceRule(
        id = "snapchat",
        label = "Snapchat",
        pathNeedles = listOf("/snapchat/", "com.snapchat.android"),
        bucketNames = listOf("snapchat"),
    ),
    SourceRule(
        id = "tiktok",
        label = "TikTok",
        pathNeedles = listOf(
            "/tiktok/",
            "com.zhiliaoapp.musically",
            "com.ss.android.ugc.trill",
            "com.ss.android.ugc.aweme",
            "musical.ly",
        ),
        bucketNames = listOf("tiktok"),
    ),
    SourceRule(
        id = "twitter",
        label = "Twitter / X",
        pathNeedles = listOf("/twitter/", "com.twitter.android", "com.x.android"),
        bucketNames = listOf("twitter", "x"),
    ),
    SourceRule(
        id = "bluetooth",
        label = "Bluetooth",
        pathNeedles = listOf("/bluetooth/"),
        bucketNames = listOf("bluetooth"),
    ),
    SourceRule(
        id = "screenshots",
        label = "Screenshots",
        pathNeedles = listOf("/screenshots/", "/screenshot/"),
        bucketNames = listOf("screenshots", "screenshot"),
    ),
    SourceRule(
        id = "screen_recordings",
        label = "Screen recordings",
        pathNeedles = listOf(
            "screenrecorder",
            "screen recordings",
            "screen recording",
            "screen_record",
            "screen-record",
        ),
        bucketNames = listOf(
            "screen recordings",
            "screen recording",
            "screenrecorder",
            "screen recorder",
        ),
    ),
    SourceRule(
        id = "screenshots",
        label = "Screenshots",
        fileNameNeedles = listOf("screenshot"),
    ),
    SourceRule(
        id = "screen_recordings",
        label = "Screen recordings",
        fileNameNeedles = listOf(
            "screenrecord",
            "screen_record",
            "screen-record",
            "screen recording",
        ),
    ),
    SourceRule(
        id = "edited",
        label = "Edited",
        pathNeedles = listOf("/edited/", "/edits/"),
        bucketNames = listOf("edited", "edits"),
        fileNameRegex = Regex("(^|[\\s._-])edited([\\s._-]|\\.|$)"),
    ),
    SourceRule(
        id = "downloads",
        label = "Downloads",
        pathNeedles = listOf("/download/", "/downloads/"),
        bucketNames = listOf("download", "downloads"),
    ),
    SourceRule(
        id = "camera",
        label = "Camera",
        pathNeedles = listOf(
            "/dcim/camera/",
            "/dcim/camera1/",
            "/dcim/100media/",
            "/dcim/100andro/",
        ),
        bucketNames = listOf("camera", "camera1", "100media", "100andro"),
    ),
)

class SourceClassifier(
    private val rules: List<SourceRule> = DefaultSourceRules,
) {
    fun classify(input: ClassificationInput): ClassifiedSource {
        val path = normalizePath(input.relativePath)
        val bucket = input.bucketName?.trim()?.lowercase().orEmpty()
        val name = input.displayName.trim().lowercase()
        for (rule in rules) {
            if (matches(rule, path, bucket, name)) {
                return ClassifiedSource(id = rule.id, label = rule.label, known = true)
            }
        }
        return unknown(input)
    }

    private fun matches(rule: SourceRule, path: String, bucket: String, name: String): Boolean {
        if (bucket.isNotEmpty() && rule.bucketNames.any { it == bucket }) return true
        if (path.isNotEmpty() && rule.pathNeedles.any { path.contains(it) }) return true
        if (name.isNotEmpty() && rule.fileNameNeedles.any { name.contains(it) }) return true
        if (name.isNotEmpty() && rule.fileNameRegex?.containsMatchIn(name) == true) return true
        return false
    }

    private fun unknown(input: ClassificationInput): ClassifiedSource {
        val raw = input.bucketName?.trim().takeUnless { it.isNullOrEmpty() }
            ?: input.relativePath
                ?.replace('\\', '/')
                ?.trim('/')
                ?.substringAfterLast('/')
                ?.takeIf { it.isNotEmpty() }
            ?: "Other"
        return ClassifiedSource(
            id = "bucket:" + raw.lowercase(),
            label = humanize(raw),
            known = false,
        )
    }

    private fun normalizePath(relativePath: String?): String {
        if (relativePath.isNullOrBlank()) return ""
        return "/" + relativePath.replace('\\', '/').lowercase().trim('/') + "/"
    }

    private fun humanize(raw: String): String {
        val cleaned = raw.replace('_', ' ').replace(Regex("\\s+"), " ").trim()
        if (cleaned.isEmpty()) return "Other"
        return cleaned.split(' ').joinToString(" ") { word ->
            val letters = word.filter { it.isLetter() }
            if (letters.isNotEmpty() && letters.all { it.isLowerCase() }) {
                word.replaceFirstChar { char ->
                    if (char.isLowerCase()) char.titlecase() else char.toString()
                }
            } else {
                word
            }
        }
    }
}
