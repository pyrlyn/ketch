package ketch.desktop

import ketch.desktop.store.UpdateNotifier
import java.io.IOException
import java.util.Base64
import kotlin.concurrent.thread

/**
 * A Windows notice with an Update button, which the tray's balloon cannot have. Windows only reports a click to a process
 * that is still there, so a small PowerShell child posts the notice and waits for it; a packaged app's COM activator
 * would need an identity an MSI does not give. Anything that goes wrong falls back to the tray's notice.
 */
internal class WindowsToast(
    private val tray: UpdateNotifier,
    private val onClick: (update: Boolean) -> Unit,
) : UpdateNotifier {
    override suspend fun requestAuthorization() = true

    override fun post(
        title: String,
        body: String,
    ) {
        val process =
            try {
                ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-EncodedCommand", encoded(SCRIPT))
                    .apply {
                        environment()[TOAST_VARIABLE] = toastXml(title, body)
                        redirectError(ProcessBuilder.Redirect.DISCARD)
                    }.start()
            } catch (_: IOException) {
                tray.post(title, body)
                return
            }
        thread(isDaemon = true, name = "toast") {
            val click = process.inputReader().use { it.readLine() }
            if (process.waitFor() != 0) tray.post(title, body) else click?.let { onClick(it.trim() == UPDATE) }
        }
    }
}

/** A toast with the title, two lines of [body] and one button. Names in it come from a registry: they are escaped. */
internal fun toastXml(
    title: String,
    body: String,
): String {
    val lines = (listOf(title) + body.lines().filter(String::isNotBlank).take(BODY_LINES)).joinToString("") { "<text>${it.escaped()}</text>" }
    return "<toast launch=\"$OPEN\" activationType=\"foreground\"><visual><binding template=\"ToastGeneric\">$lines</binding></visual>" +
        "<actions><action content=\"Update\" arguments=\"$UPDATE\" activationType=\"foreground\"/></actions></toast>"
}

private fun String.escaped() =
    replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

/** PowerShell's -EncodedCommand reads UTF-16LE: no shell quoting to get wrong. */
private fun encoded(script: String) = Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))

private const val BODY_LINES = 2
private const val TOAST_VARIABLE = "KETCH_TOAST"
private const val OPEN = "open"
private const val UPDATE = "update"

// An unpackaged app has no identity until this per-user key names it: the notice would not show.
// Windows PowerShell cannot subscribe to a WinRT event, and a script block would run on a thread without a runspace,
// so the click lands in a table through a plain .NET delegate.
private val SCRIPT =
    """
    ${'$'}ErrorActionPreference = 'Stop'
    ${'$'}id = 'io.github.pyrlyn.ketch'
    ${'$'}key = 'HKCU:\Software\Classes\AppUserModelId\' + ${'$'}id
    New-Item -Path ${'$'}key -Force | Out-Null
    Set-ItemProperty -Path ${'$'}key -Name DisplayName -Value 'ketch'
    [void][Windows.UI.Notifications.ToastNotificationManager, Windows.UI.Notifications, ContentType = WindowsRuntime]
    [void][Windows.Data.Xml.Dom.XmlDocument, Windows.Data.Xml.Dom, ContentType = WindowsRuntime]
    ${'$'}xml = New-Object Windows.Data.Xml.Dom.XmlDocument
    ${'$'}xml.LoadXml(${'$'}env:$TOAST_VARIABLE)
    ${'$'}toast = New-Object Windows.UI.Notifications.ToastNotification ${'$'}xml
    ${'$'}clicks = [hashtable]::Synchronized(@{})
    ${'$'}type = [Windows.UI.Notifications.ToastNotification].GetEvent('Activated').EventHandlerType
    [void]${'$'}toast.add_Activated([Delegate]::CreateDelegate(${'$'}type, ${'$'}clicks, ${'$'}clicks.GetType().GetMethod('Add')))
    [Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier(${'$'}id).Show(${'$'}toast)
    ${'$'}end = (Get-Date).AddMinutes(10)
    while (${'$'}clicks.Count -eq 0 -and (Get-Date) -lt ${'$'}end) { Start-Sleep -Milliseconds 200 }
    foreach (${'$'}click in ${'$'}clicks.Values) {
        [Console]::Out.WriteLine(([Windows.UI.Notifications.ToastActivatedEventArgs]${'$'}click).Arguments)
    }
    """.trimIndent()
