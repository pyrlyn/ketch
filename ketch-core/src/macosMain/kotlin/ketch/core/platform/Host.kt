package ketch.core.platform

import ketch.core.platform.macos.MacOsPlatform

actual fun host(): Platform = MacOsPlatform()
