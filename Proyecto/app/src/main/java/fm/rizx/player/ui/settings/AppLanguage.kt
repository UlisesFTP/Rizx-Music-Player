package fm.rizx.player.ui.settings

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import androidx.annotation.RequiresApi
import java.util.Locale

/**
 * The app's language options and the per-app locale plumbing behind the Settings language selector.
 *
 * On API 33+ the **OS owns** the per-app language via [LocaleManager] (it also appears under
 * Settings › Apps › Rizx › Language). Below 33 that service doesn't exist, so the chosen tag is
 * persisted in a plain SharedPreferences file — read synchronously by [withAppLocale], which
 * MainActivity applies in `attachBaseContext` — and changing it recreates the activity by hand.
 *
 * In both worlds an empty selection means "follow the device": Android resolves every string to the
 * best-matching `values-xx/` folder and falls back to `values/` (English) for any device language we
 * don't ship — so **auto-detect + English fallback are the platform default**, and this selector only
 * *overrides* it. Changing the locale recreates the activity, so the whole UI re-reads its strings.
 */
enum class AppLanguage(val tag: String?, val endonym: String) {
    /** Follow the device language (auto-detect, English fallback). Its label is a localized string. */
    SYSTEM(null, ""),
    ENGLISH("en", "English"),
    SPANISH("es", "Español"),
    PORTUGUESE("pt", "Português"),
    FRENCH("fr", "Français"),
}

private const val LANGUAGE_PREFS = "app_language"
private const val KEY_TAG = "tag"

@RequiresApi(33)
private fun localeManager(context: Context): LocaleManager =
    context.getSystemService(LocaleManager::class.java)

private fun prefs(context: Context) =
    context.getSharedPreferences(LANGUAGE_PREFS, Context.MODE_PRIVATE)

/** The currently-applied language, or [AppLanguage.SYSTEM] when the app is following the device. */
fun currentAppLanguage(context: Context): AppLanguage {
    val tag = if (Build.VERSION.SDK_INT >= 33) {
        val locales = localeManager(context).applicationLocales
        if (locales.isEmpty) null else locales.get(0)?.language
    } else {
        prefs(context).getString(KEY_TAG, null)
    }
    return AppLanguage.entries.firstOrNull { it.tag == tag } ?: AppLanguage.SYSTEM
}

/** Applies [language]; [AppLanguage.SYSTEM] clears the override so the app follows the device again. */
fun setAppLanguage(context: Context, language: AppLanguage) {
    if (Build.VERSION.SDK_INT >= 33) {
        // The OS persists the choice and recreates the activity itself.
        localeManager(context).applicationLocales =
            language.tag?.let { LocaleList.forLanguageTags(it) } ?: LocaleList.getEmptyLocaleList()
        return
    }
    prefs(context).edit().apply {
        if (language.tag == null) remove(KEY_TAG) else putString(KEY_TAG, language.tag)
    }.apply()
    // Keep process-wide defaults (date/number formatting) in step with what resources will show.
    if (language.tag != null) {
        val locale = Locale.forLanguageTag(language.tag)
        Locale.setDefault(locale)
        LocaleList.setDefault(LocaleList(locale))
    } else {
        val system = Resources.getSystem().configuration.locales
        if (!system.isEmpty) {
            Locale.setDefault(system.get(0))
            LocaleList.setDefault(system)
        }
    }
    context.findActivity()?.recreate()
}

/**
 * Below API 33, wraps [this] so its resources resolve in the persisted app language. No-op when no
 * override is stored, and on 33+ where [LocaleManager] already localized the base context. Copying the
 * **base** [Configuration] is load-bearing: it keeps `smallestScreenWidthDp` (orientation policy) and
 * `uiMode` (system dark theme) exactly as the platform reported them.
 */
fun Context.withAppLocale(): Context {
    if (Build.VERSION.SDK_INT >= 33) return this
    val tag = prefs(this).getString(KEY_TAG, null) ?: return this
    val locale = Locale.forLanguageTag(tag)
    Locale.setDefault(locale)
    LocaleList.setDefault(LocaleList(locale))
    val config = Configuration(resources.configuration).apply { setLocale(locale) }
    return createConfigurationContext(config)
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
