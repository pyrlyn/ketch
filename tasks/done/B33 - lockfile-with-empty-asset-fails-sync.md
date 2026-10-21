---
id: B33
title: Lockfile with empty `asset` fails `sync`
status: done
created_at: 1792583218
updated_at: 1792583218
---

`LockedPackage::validate` checks name, tag, source and hash but not `asset`, so `asset = ""` is a valid file; `choose_asset` then fails during `sync` with an opaque "release has no asset named ``" error.

`Lockfile::validate` now refuses a blank `asset` the same way it refuses a blank `tag`, so `Lockfile::load` (and therefore `sync`) fails early with a clear message. Unit test `a_blank_asset_is_refused` covers it.
