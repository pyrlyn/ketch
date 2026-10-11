# Copyright (c) 2026 Ivan Tugay
# SPDX-License-Identifier: GPL-3.0-or-later
# Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

# The Linux app (Vala, GTK 4, libadwaita). `ketch-app-core` is everything
# below the screens: the core contract, the fake core over the contract
# scenarios and the store. It needs GLib and json-glib only, so its tests run
# without GTK or a display. `just linux-app-test`; the ketch-linux-app CI job.
project('ketch-linux', 'vala', 'c', meson_version: '>= 1.0')

# The C is valac's output, not ours: its unused temporaries are not worth a warning.
add_project_arguments('-w', language: 'c')

core_deps = [
  dependency('glib-2.0'),
  dependency('gobject-2.0'),
  dependency('gio-2.0'),
  dependency('json-glib-1.0'),
]

app_core = static_library(
  'ketch-app-core',
  'src/core.vala',
  'src/scenario.vala',
  'src/fake-core.vala',
  'src/models.vala',
  'src/store.vala',
  dependencies: core_deps,
)
app_core_dep = declare_dependency(link_with: app_core, dependencies: core_deps)

scenarios = meson.current_source_dir() / '..' / 'contract' / 'scenarios'

foreach name : ['fake-core', 'store']
  test(
    name,
    executable(name + '-test', 'tests' / name + '-test.vala', dependencies: app_core_dep),
    env: {'KETCH_SCENARIOS': scenarios},
    protocol: 'tap',
  )
endforeach
