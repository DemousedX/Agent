package com.demoused.phoneagent

object SafetyGuard {

    private val PROTECTED_PREFIXES = listOf(
        "/system", "/proc", "/sys", "/dev", "/etc",
        "/data/data", "/data/system", "/data/app",
        "/sdcard/Android", "/sdcard/DCIM",
        "/storage/emulated/0/Android",
        "/storage/emulated/0/DCIM",
    )

    private val PROTECTED_EXTENSIONS = listOf(
        ".apk", ".dex", ".so", ".db", ".db-wal", ".db-shm"
    )

    private val DESTRUCTIVE_ACTIONS = setOf("delete_file", "write_file")

    data class CheckResult(val allowed: Boolean, val reason: String = "")

    fun check(action: String, path: String? = null): CheckResult {
        if (path == null) return CheckResult(true)
        val normalized = path.trimEnd('/')

        for (prefix in PROTECTED_PREFIXES) {
            if (normalized.startsWith(prefix)) {
                return CheckResult(false, "Шлях $prefix захищений")
            }
        }

        if (action in DESTRUCTIVE_ACTIONS) {
            for (ext in PROTECTED_EXTENSIONS) {
                if (normalized.endsWith(ext)) {
                    return CheckResult(false, "Файли $ext не можна змінювати")
                }
            }
        }

        if (action == "delete_file" && java.io.File(normalized).isDirectory) {
            return CheckResult(false, "Видалення директорій заборонено")
        }

        if ((action == "write_file" || action == "create_file")
            && normalized.count { it == '/' } <= 1) {
            return CheckResult(false, "Запис у кореневі директорії заборонено")
        }

        return CheckResult(true)
    }

    fun safetySystemPrompt(): String = """
        SAFETY RULES (mandatory):
        - NEVER delete: /system, /data, /sdcard/DCIM, /sdcard/Android
        - NEVER delete .apk, .db, .so files
        - NEVER delete directories
        - NEVER write to root-level paths
        - If unsure about path — use list_dir first
    """.trimIndent()
}
