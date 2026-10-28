// The records and the JSON-lines protocol between the native `ketch` and the desktop app (the port of
// crates/ketch-ffi's surface). Built for jvm too, because the Compose app runs on the JVM.
plugins { id("ketch.kmp") }

kotlin {
    mingwX64()
    jvm()
    jvmToolchain(21)
}
