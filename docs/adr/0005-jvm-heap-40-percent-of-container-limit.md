# 0005: JVM heap at 40% of the container memory limit

Date: 2026-10-06 · Status: accepted (replaces the 75% rule in CLAUDE.md)

## Context

CLAUDE.md budgeted each Spring service at 384M with `-XX:MaxRAMPercentage=75`, i.e. a 288 MiB heap cap.
Measured in compose under load (100 logins + 300 `/me`), identity-service used:

| Area | MiB |
|---|---|
| Heap, live data / committed | 48 / 78 |
| Metaspace + compressed class space | ~122 |
| Code cache | ~30 |
| Native (threads, GC, malloc, ...) | ~70 |
| **Container total** | **~300 of 384** |

Non-heap is about 220 MiB and does not shrink with the heap. With a 288 MiB heap cap, a full heap would
need about 508 MiB. The kernel would OOM-kill the container before the JVM ever had to collect hard.
75% is a good rule for containers of a gigabyte or more, not for 384M.

## Decision

- Spring services keep the **384M** limit and use **`-XX:MaxRAMPercentage=40`** (heap cap about 154 MiB,
  three times today's live heap). Set in the Dockerfile's `JAVA_TOOL_OPTIONS`.
- `-XX:+ExitOnOutOfMemoryError` stays: a heap that really is too small fails fast and gets restarted.
- At this size the JVM picks SerialGC on its own (it does so under 2 CPUs or 1792 MB). That's fine
  for a small service.

## Consequences

- Same RAM budget as before. Heap plus non-heap now fits inside the limit, so pressure becomes GC
  work instead of a kernel OOM kill.
- If live heap grows past about 100 MiB (more features, caches), raise the limit, not the percentage.
  Re-measure with `/metrics` (`jvm_memory_*`) and `docker stats`.
- The api-gateway (Phase 2) starts with the same setting and gets measured too.
