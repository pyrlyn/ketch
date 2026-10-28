// Changelog Markdown, behind our own composable so the renderer can be replaced alone. The core has stripped escape
// sequences and bidi overrides; the links are still the package author's, so only web links open.
package ketch.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import com.mikepenz.markdown.m3.Markdown
import java.net.URI
import java.net.URISyntaxException

@Composable
fun ChangelogText(
    markdown: String,
    modifier: Modifier = Modifier,
) {
    val system = LocalUriHandler.current
    val web = remember(system) { WebOnly(system) }
    CompositionLocalProvider(LocalUriHandler provides web) {
        Markdown(markdown, modifier = modifier)
    }
}

/** Opens `http` and `https` links and drops the rest: `file:`, `javascript:` and app schemes stay shut. */
class WebOnly(
    private val next: UriHandler,
) : UriHandler {
    override fun openUri(uri: String) {
        if (isWeb(uri)) next.openUri(uri)
    }

    companion object {
        fun isWeb(uri: String): Boolean =
            try {
                URI(uri).scheme?.lowercase() in setOf("http", "https")
            } catch (_: URISyntaxException) {
                false
            }
    }
}
