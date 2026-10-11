# Copyright (c) 2026 Ivan Tugay
# SPDX-License-Identifier: GPL-3.0-or-later
# Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

# The screens: Blueprint compiled to GtkBuilder XML, bundled as a GResource.
# The version is exact so a distribution's blueprint-compiler, whose output
# can differ between releases, never stands in for the pinned subproject.
blueprint_compiler = find_program('blueprint-compiler', version: '==0.22.2')

blueprints = custom_target(
  'blueprints',
  input: files('ui/window.blp', 'ui/package-page.blp'),
  output: ['window.ui', 'package-page.ui'],
  command: [blueprint_compiler, 'batch-compile', '@OUTDIR@', meson.current_source_dir() / 'ui', '@INPUT@'],
  # 0.22's wrapper, as a subproject, is written into the build directory away
  # from its Python package and cannot import it; an installed one ignores this.
  env: {'PYTHONPATH': meson.project_source_root() / 'subprojects' / 'blueprint-compiler'},
)

# A copy, not a second source: desktop/design/build.mjs owns the tokens.
tokens_css = fs.copyfile('..' / '..' / 'design' / 'generated' / 'ketch-tokens.css', 'tokens.css')

resources = gnome.compile_resources(
  'ketch-resources',
  'ketch.gresource.xml',
  source_dir: [meson.current_build_dir(), meson.current_source_dir()],
  dependencies: [blueprints, tokens_css],
  c_name: 'ketch',
)
