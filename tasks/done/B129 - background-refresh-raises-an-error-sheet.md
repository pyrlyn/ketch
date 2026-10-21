---
id: B129
title: Background refresh raises an error sheet
status: done
created_at: 1792583854
updated_at: 1792583854
---

The update timer and the window becoming key both called `refresh`, and any error set `errorMessage`. Offline, with the real core, the modal appeared on every interval and every time the window came forward. Those callers now pass `alerting: false`. The failure is a warning in the activity log, and a refresh the user started still alerts.

Tests: `aBackgroundRefreshLogsAFailureWithoutAnAlert`, `aRefreshStillAlertsWhenAsked`.
