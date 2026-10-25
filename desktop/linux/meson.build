# Copyright (c) 2026 Ivan Tugay
# SPDX-License-Identifier: GPL-3.0-or-later
# Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

# The Linux app (Vala, GTK 4, libadwaita). `ketch-app-core` is everything
# below the screens: the core contract, the fake core over the contract
# scenarios and the store. It needs GLib and json-glib only, so its tests run
# without GTK or a display. `just linux-app-test`; the ketch-linux-app CI job.
project('ketch-linux', 'vala', 'c', meson_version: '>= 1.1')

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
  'src/a11y.vala',
  'src/store.vala',
  dependencies: core_deps,
  # a11y.vala uses _(), which gi18n-lib.h refuses to compile without a domain.
  # The screens pass the same define.
  c_args: ['-DGETTEXT_PACKAGE="ketch"'],
)
app_core_dep = declare_dependency(link_with: app_core, dependencies: core_deps)

scenarios = meson.current_source_dir() / '..' / 'contract' / 'scenarios'

foreach name : ['fake-core', 'store', 'a11y']
  test(
    name,
    executable(name + '-test', 'tests' / name + '-test.vala', dependencies: app_core_dep),
    env: {'KETCH_SCENARIOS': scenarios},
  )
endforeach

gtk_dep = dependency('gtk4', version: '>= 4.20', required: get_option('gui'))
adw_dep = dependency('libadwaita-1', version: '>= 1.8', required: get_option('gui'))
if gtk_dep.found() and adw_dep.found()
  gnome = import('gnome')
  fs = import('fs')
  subdir('data')
  executable(
    'ketch-app',
    'src/application.vala',
    'src/window.vala',
    'src/preferences.vala',
    'src/package-page.vala',
    resources,
    dependencies: [app_core_dep, gtk_dep, adw_dep],
    c_args: ['-DKETCH_SCENARIOS_DIR="@0@"'.format(scenarios), '-DGETTEXT_PACKAGE="ketch"'],
    # Blueprint writes the .ui files into the build tree; valac looks beside
    # the gresource xml, which stays in the source tree.
    vala_args: ['--gresourcesdir=' + meson.current_build_dir() / 'data'],
    install: true,
  )
  # The blueprint-compiler subproject brings its own test suite, which tests
  # blueprint against this machine's GTK, not ketch; a plain `meson test` skips it.
  add_test_setup('ketch', exclude_suites: ['blueprint-compiler'], is_default: true)
  # `meson devenv` then runs the app on the scenarios in the source tree.
  devenv = environment()
  devenv.set('KETCH_SCENARIOS', scenarios)
  meson.add_devenv(devenv)
endif
