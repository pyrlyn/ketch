// What every screen shares: its title row with its own buttons, the empty state, and a package as a list row.
package ketch.desktop.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import ketch.desktop.strings.*
import ketch.desktop.ui.LocalSpacing
import org.jetbrains.compose.resources.stringResource

@Composable
fun Page(
    title: String,
    tag: String,
    buttons: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val space = LocalSpacing.current
    Column(Modifier.fillMaxSize().padding(horizontal = space.xxl, vertical = space.xl).testTag("page-$tag")) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = space.lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(space.sm),
        ) {
            Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f).semantics { heading() })
            buttons()
        }
        content()
    }
}

@Composable
fun Empty(
    title: String,
    detail: String? = null,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

/** One package in a list: its name and a line under it, what it is now on the right, then its buttons. */
@Composable
fun PackageRow(
    name: String,
    summary: String?,
    tag: String,
    onOpen: (() -> Unit)?,
    trailing: @Composable RowScope.() -> Unit,
) {
    val space = LocalSpacing.current
    val open = onOpen?.let { Modifier.clickable(onClickLabel = stringResource(Res.string.action_open_named, name), onClick = it) } ?: Modifier
    Row(
        Modifier
            .fillMaxWidth()
            .then(open)
            .padding(vertical = space.sm, horizontal = space.xs)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(space.md),
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleSmall)
            summary?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2) }
        }
        trailing()
    }
    HorizontalDivider()
}

/** A short label in a tinted pill: `Update to 1.2`, `Held`, `Installed`. */
@Composable
fun Chip(
    text: String,
    ink: Color,
    background: Color,
) {
    Surface(color = background, shape = MaterialTheme.shapes.extraSmall) {
        Text(text, color = ink, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = LocalSpacing.current.sm, vertical = LocalSpacing.current.xxs))
    }
}

/** `owner/repo` from a `github:owner/repo` source; null for another forge or a local file. */
fun githubRepo(source: String): String? = source.removePrefix("github:").takeIf { source.startsWith("github:") && it.count { c -> c == '/' } == 1 }
