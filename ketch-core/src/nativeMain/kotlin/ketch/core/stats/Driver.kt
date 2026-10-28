package ketch.core.stats

import db.DbDriver

/** The SQLite driver for this host: its own build where androidx.sqlite has one, SQLite's C API on Windows. */
internal expect val sqlite: DbDriver
