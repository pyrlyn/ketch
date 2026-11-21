// AppKit lays out a taller title bar, and moves the real window buttons in with it, only for a window with a toolbar, and
// JDK 21 has no property that asks for one. An empty toolbar leaves the placing of the buttons to the system.
package ketch.desktop

import androidx.compose.ui.awt.ComposeWindow
import com.sun.jna.Callback
import com.sun.jna.Function
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer

private const val SEPARATOR_NONE = 1L

private val send by lazy { Function.getFunction("objc", "objc_msgSend") }

/** Held for as long as the app runs: AppKit calls it later, and a collected callback would crash the call. */
private val pending = mutableListOf<Callback>()

private fun selector(name: String) = Function.getFunction("objc", "sel_registerName").invokePointer(arrayOf(name))

private fun Pointer.send(
    method: String,
    vararg arguments: Any?,
): Pointer? = send.invokePointer(arrayOf<Any?>(this, selector(method), *arguments))

private fun objcClass(name: String) = Function.getFunction("objc", "objc_getClass").invokePointer(arrayOf(name))

/** Everything that touches an NSWindow belongs on AppKit's thread, which is not AWT's. */
private fun onAppKitThread(work: () -> Unit) {
    val callback =
        object : Callback {
            @Suppress("unused", "UnusedParameter")
            fun callback(context: Pointer?) {
                runCatching(work).onFailure { System.err.println("ketch: could not set the window's title bar: ${it.message}") }
            }
        }
    pending += callback
    val system = NativeLibrary.getInstance("System")
    system.getFunction("dispatch_async_f").invokeVoid(arrayOf(system.getGlobalVariableAddress("_dispatch_main_q"), null, callback))
}

/** A title bar of [style], an `NSWindowToolbarStyle`, on the window [window]. */
fun tallTitleBar(
    window: ComposeWindow,
    style: Long,
) {
    val nsWindow = Pointer(window.windowHandle)
    onAppKitThread {
        val toolbar = objcClass("NSToolbar").send("new")
        nsWindow.send("setToolbar:", toolbar)
        nsWindow.send("setToolbarStyle:", style)
        nsWindow.send("setTitlebarSeparatorStyle:", SEPARATOR_NONE)
    }
    probe(nsWindow, window.height)
}

private fun probe(
    nsWindow: Pointer,
    height: Int,
) {
    Thread {
        Thread.sleep(4000)
        onAppKitThread {
            val root = nsWindow.send("contentView")!!.send("superview")!!
            for (x in listOf(290.0, 1000.0)) {
                for (top in 0..70 step 4) {
                    val view = root.send("hitTest:", x, (height - top).toDouble())
                    println("PROBE x=$x top=$top ${view?.send("className")?.send("UTF8String")?.getString(0)}")
                }
            }
        }
    }.start()
}
