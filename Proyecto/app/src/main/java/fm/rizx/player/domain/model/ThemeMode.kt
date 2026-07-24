package fm.rizx.player.domain.model

/**
 * How the app decides light vs dark.
 *
 * [SYSTEM] (the default) **follows the device's** dark/light setting — resolved at render time via
 * `isSystemInDarkTheme()`, so the app flips the moment the device does. [LIGHT] and [DARK] force it.
 */
enum class ThemeMode { SYSTEM, LIGHT, DARK }
