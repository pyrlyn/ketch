---
id: B110
title: Changelog links open any URL scheme
status: done
created_at: 1792583854
updated_at: 1792583854
---

Client release notes are parsed as inline Markdown into `Text`. Every `.link` run was clickable through the default `openURL` action, including `zoommtg:` and `file:`. `KetchStore.render` now drops a link unless its scheme is `https`. The words stay.

Test: `changelogLinksStayOnlyWhenTheyAreHTTPS` (with the existing https link test).
