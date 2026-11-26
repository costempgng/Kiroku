package ani.dantotsu.settings.extensions

import ani.dantotsu.extension.ExtensionCategory
import ani.dantotsu.settings.saving.PrefManager

/**
 * Persists the user's preferred extension order per category.
 *
 * Extension ordering is a Kiroku preference, not runtime state: the runtime does not know which
 * extensions the user pinned first, so it lives in Kiroku's own preference storage.
 */
class ExtensionOrderStore {

    fun order(category: ExtensionCategory): List<String> {
        val stored = PrefManager.getNullableCustomVal(key(category), "", String::class.java)
        return stored?.split(SEPARATOR)?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
    }

    fun save(category: ExtensionCategory, orderedIds: List<String>) {
        PrefManager.setCustomVal(key(category), orderedIds.joinToString(SEPARATOR.toString()))
    }

    /** Applies the stored order, appending extensions the user has not seen yet. */
    fun apply(category: ExtensionCategory, extensions: List<String>): List<String> {
        val stored = order(category)
        if (stored.isEmpty()) return extensions
        val known = stored.filter { it in extensions }
        val unknown = extensions.filterNot { it in known }
        return known + unknown
    }

    private fun key(category: ExtensionCategory) =
        "extensions_order_v1_${category.name.lowercase()}"

    private companion object {
        const val SEPARATOR = '\n'
    }
}
