package com.krishnakb.karooclassified

/**
 * The two ratios a Classified Powershift hub can be in, plus the state before
 * the hub has been heard from.
 *
 * "Direct" is the 1:1 through-drive. "Classified" is the 0.686 reduction.
 */
enum class RatioState {
    /** 1:1 through-drive. Displayed as "OFF". */
    DIRECT,

    /** 0.686 reduction engaged. Displayed as "ON". */
    CLASSIFIED,

    /** No data from the hub yet, or the connection dropped. */
    UNKNOWN,
    ;

    /** Field text shown on the Karoo data field. */
    val label: String
        get() = when (this) {
            DIRECT -> "OFF"
            CLASSIFIED -> "ON"
            UNKNOWN -> "--"
        }

    /**
     * Numeric encoding emitted on the data stream so the value is usable by
     * other data fields and by FIT recording. Null when there is nothing to report.
     */
    val numericValue: Double?
        get() = when (this) {
            DIRECT -> 0.0
            CLASSIFIED -> 1.0
            UNKNOWN -> null
        }
}
