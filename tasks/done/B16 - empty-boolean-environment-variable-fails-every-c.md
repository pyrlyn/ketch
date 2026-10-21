---
id: B16
title: Empty boolean environment variable fails every command
status: done
created_at: 1792583854
updated_at: 1792583854
---

`KETCH_LINK_APPS=`, `KETCH_PRERELEASE=`, `KETCH_ALLOW_EMULATION=`, `KETCH_STRIP_QUARANTINE=` and `KETCH_REQUIRE_CHECKSUMS=` each exited with "must be a boolean, not ``".

`env_bool` now filters empty and whitespace-only values with `.filter(|v| !v.trim().is_empty())`, matching `parsed`, `KETCH_REGISTRY`, and the other settings. Unit test `an_empty_boolean_environment_variable_is_treated_as_unset` covers all five keys with empty, space, and tab values.
