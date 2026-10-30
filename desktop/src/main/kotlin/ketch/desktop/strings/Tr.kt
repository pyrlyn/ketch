// The app's words outside composition: enums, the tray menu, the store, what the core's errors say. Sits beside the
// generated Res so one import brings both.
package ketch.desktop.strings

import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.PluralStringResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString

/** [res] in the person's language. Compose's own read suspends only to reach the file, which it then keeps. */
fun tr(
    res: StringResource,
    vararg args: Any,
): String = runBlocking { getString(res, *args) }

/** The wording of [res] for [count], which is also its value. */
fun plural(
    count: Int,
    res: PluralStringResource,
): String = runBlocking { getPluralString(res, count, count) }
