package fm.rizx.player.ui.settings

import android.app.LocaleManager
import android.content.Context
import android.os.LocaleList

/**
 * The app's language options and the per-app locale plumbing behind the Settings language selector.
 *
 * On minSdk 34 the **OS owns** the per-app language (it also appears under Settings › Apps › Rizx ›
 * Language). Setting an empty locale list means "follow the device": Android then resolves every string to
 * the best-matching `values-xx/` folder and falls back to `values/` (English) for any device language we
 * don't ship — so **auto-detect + English fallback are the platform default**, and this selector only
 * *overrides* it. Changing the locale recreates the activity, so the whole UI re-reads in the new language.
 */
enum class AppLanguage(val tag: String?, val endonym: String) {
    /** Follow the device language (auto-detect, English fallback). Its label is a localized string. */
    SYSTEM(null, ""),
    ENGLISH("en", "English"),
    SPANISH("es", "Español"),
    PORTUGUESE("pt", "Português"),
    FRENCH("fr", "Français"),
}

private fun localeManager(context: Context): LocaleManager =
    context.getSystemService(LocaleManager::class.java)

/** The currently-applied language, or [AppLanguage.SYSTEM] when the app is following the device. */
fun currentAppLanguage(context: Context): AppLanguage {
    val locales = localeManager(context).applicationLocales
    if (locales.isEmpty) return AppLanguage.SYSTEM
    val tag = locales.get(0)?.language
    return AppLanguage.entries.firstOrNull { it.tag == tag } ?: AppLanguage.SYSTEM
}

/** Applies [language]; [AppLanguage.SYSTEM] clears the override so the app follows the device again. */
fun setAppLanguage(context: Context, language: AppLanguage) {
    localeManager(context).applicationLocales =
        language.tag?.let { LocaleList.forLanguageTags(it) } ?: LocaleList.getEmptyLocaleList()
}
