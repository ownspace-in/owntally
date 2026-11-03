package owntally.app

import owntally.ui.groups.DebugTools

/** Release builds have no debug tools, so none of their code or controls exist in the shipped app. */
internal object DebugToolsFactory {
    fun create(container: AppContainer): DebugTools? = null
}
