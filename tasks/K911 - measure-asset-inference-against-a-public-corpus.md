---
id: K911
title: Measure asset inference against a public corpus
status: open
created_at: 1792603863
updated_at: 1792603928
priority: 1
blocked_by:
- K910
---

"Any repository that ships releases" is the headline claim (README, site), and nothing measures it. A pass rate turns the slogan into a number and finds asset-scoring bugs before users do.

Done when a scheduled workflow (network allowed) runs `ketch why --target` for a few hundred repositories from the aqua list across five targets, publishes a pass-rate JSON and a failure list as an artifact, and the first batch of failures has become scoring fixes or registry entries.
