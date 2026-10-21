---
id: B25
title: Exact version older than newest 30 releases not found
status: done
created_at: 1792583218
updated_at: 1792583218
---

The `v`-prefix retry covers the everyday spelling, but the fallback still lists one page: a tag spelled another way on a busy repository ends in `no release found`.

Exact tag lookup still tries `/releases/tags/{tag}` and the `v`-prefixed spelling. When both miss, `resolve_from_listing` walks GitHub `/releases` with `per_page` and `page` until a page is short, `pick` matches, or `MAX_RELEASE_PAGES`. `list_releases` still returns page 1 only. Regression: `an_exact_tag_not_on_the_first_list_page_is_still_resolved`.
