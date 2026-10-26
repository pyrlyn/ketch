package ketch.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import ketch.wire.PROTOCOL

fun main() =
    application {
        Window(onCloseRequest = ::exitApplication, title = "Ketch") {
            MaterialTheme { Text("ketch protocol $PROTOCOL") }
        }
    }
