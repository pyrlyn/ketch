---
id: B50
title: Batch install documented as writing in asked order
status: done
created_at: 1792583218
updated_at: 1792583218
---

Batch install was documented as writing packages into the store in request order; concurrent batches place them one at a time in completion order instead. README now says store writes follow whichever download finishes first, while exit status and printed results stay in request order.
