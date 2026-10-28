package ketch.core.stats

import db.DbDriver
import db.native.NativeDbDriver

internal actual val sqlite: DbDriver = NativeDbDriver
