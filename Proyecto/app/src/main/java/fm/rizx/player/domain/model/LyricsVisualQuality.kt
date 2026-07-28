package fm.rizx.player.domain.model

/**
 * How much the lyrics screen is allowed to spend on looking good.
 *
 * The karaoke sweep is the one place in this app that asks for a frame every frame, so it gets an
 * explicit dial instead of a hidden heuristic — and a way out for anyone whose phone or battery would
 * rather it didn't.
 */
enum class LyricsVisualQuality {
    /** Full effects, stepping down on its own when the device is in power-save mode or is low-RAM. */
    AUTOMATIC,

    /** Everything on, whatever the device says. */
    HIGH,

    /** No glow, no scaling, no per-letter interpolation — the highlight steps word by word. */
    BATTERY_SAVER,
}
