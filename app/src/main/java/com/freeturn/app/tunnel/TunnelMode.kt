package com.freeturn.app.tunnel

enum class TunnelMode {
    LOCAL_PROXY,
    FULL_TUNNEL;

    companion object {
        fun fromStorage(value: String?): TunnelMode =
            entries.firstOrNull { it.name == value } ?: LOCAL_PROXY
    }
}
