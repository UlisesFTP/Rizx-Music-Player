package fm.rizx.player.core.region

import android.content.Context
import android.telephony.TelephonyManager
import java.util.Locale

/**
 * Resolves the user's country (ISO 3166-1 alpha-2, lowercase) for **regional recommendations only**.
 * Chain: SIM country → network country → device locale → null (= global). None of these needs an
 * Android permission and none touches location — per the owner's decision, the "permission" is the
 * in-app consent card (`SettingsRepository.recsRegionalConsent`), which callers must check before
 * using [country] for anything regional.
 */
class RegionResolver(
    private val suppliers: List<() -> String?>,
) {
    /** First plausible 2-letter country the chain yields, lowercased — or null (= global). */
    fun country(): String? = suppliers.firstNotNullOfOrNull { supplier ->
        runCatching { supplier() }.getOrNull()
            ?.trim()
            ?.takeIf { it.length == 2 && it.all(Char::isLetter) }
            ?.lowercase(Locale.ROOT)
    }

    /** The country's name in the user's language ("mx" → "México"), for the consent card/Settings. */
    fun countryDisplayName(): String? = country()?.let { code ->
        Locale("", code.uppercase(Locale.ROOT))
            .getDisplayCountry(Locale.getDefault())
            .takeIf { it.isNotBlank() }
    }

    companion object {
        /** The production chain: SIM → network → device locale. */
        fun fromContext(context: Context): RegionResolver {
            val telephony = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            return RegionResolver(
                listOf(
                    { telephony?.simCountryIso },
                    { telephony?.networkCountryIso },
                    { Locale.getDefault().country },
                ),
            )
        }
    }
}
