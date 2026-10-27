# Roadmap

Approved work not yet in `plan/`. Every phase of the port is already a task in `plan/`.

- Concurrent `ketch install` batches (`--jobs`): give kotlin-kit thread-safe file-name and parent
  helpers, use them instead of kotlinx-io's `Path.name` and `Path.parent` on native, then let
  `install.batch` prepare packages side by side again, as Rust does.
