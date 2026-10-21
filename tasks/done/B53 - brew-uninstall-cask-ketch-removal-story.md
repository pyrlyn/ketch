---
id: B53
title: "`brew uninstall --cask ketch` removal story"
status: done
created_at: 1792583854
updated_at: 1792583854
---

`brew uninstall --cask ketch` removes only Homebrew's bootstrap binary and leaves `~/.ketch` and every installed package in place. README now says so in the removal section.
