---
id: B19
title: "`local:` symlink to archive or `.app` never installs"
status: done
created_at: 1792583218
updated_at: 1792583218
---

`classify` returned `Symlink` for any symlink, so `install --path link.tar.gz` forged a `bin` entry named after the link and then failed.

`classify` now canonicalizes a non-dangling symlink and classifies by the resolved target (archive, `.app`, binary, or plain-directory error) instead of returning `LocalKind::Symlink`. Unit tests `classifies_a_symlink_to_an_archive`, `classifies_a_symlink_to_an_app_bundle`, and `classifies_a_symlink_to_a_binary` verify the fix.
