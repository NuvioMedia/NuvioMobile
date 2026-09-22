package com.nuvio.app.features.updater

enum class UpdateChannel(val storedValue: String) {
    STABLE("stable"),
    BETA("beta");

    companion object {
        fun fromStoredValue(value: String?): UpdateChannel? = entries.firstOrNull {
            it.storedValue.equals(value, ignoreCase = true)
        }

        fun defaultForVersion(versionName: String): UpdateChannel =
            if (VersionUtils.isPrerelease(versionName)) BETA else STABLE
    }
}
