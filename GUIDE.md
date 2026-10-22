# Spring JVM Lab: The Guidebook

This guide explains what this repository is, how each part works, and the JVM concepts behind
it, especially memory and garbage collection. Read it top to bottom once. After that, use
Part III (the labs) as a workbook and Part IV as a reference.

Every number quoted here as "measured" came from this lab running on this machine, not from
documentation. Your numbers will differ a little, and comparing them is part of the exercise.

---

## Contents

- **Part I: The big picture**
  - [1. What this lab is](#1-what-this-lab-is)
  - [2. Quick start](#2-quick-start)
- **Part II: How it works**
  - [3. The JVM in one page](#3-the-jvm-in-one-page)
  - [4. JVM memory: heap, non-heap, and the container](#4-jvm-memory-heap-non-heap-and-the-container)
  - [5. Garbage collection fundamentals](#5-garbage-collection-fundamentals)
  - [6. G1, the default collector, in depth](#6-g1-the-default-collector-in-depth)
  - [7. The other collectors](#7-the-other-collectors)
  - [8. Reading GC logs](#8-reading-gc-logs)
  - [9. Leaks, OutOfMemoryError, and what happens after](#9-leaks-outofmemoryerror-and-what-happens-after)
  - [10. JFR: the flight recorder](#10-jfr-the-flight-recorder)
  - [11. Metrics: Micrometer, Prometheus, Grafana](#11-metrics-micrometer-prometheus-grafana)
  - [12. Logs: structured JSON, Alloy, Loki](#12-logs-structured-json-alloy-loki)
  - [13. Traces: spans, propagation, Tempo](#13-traces-spans-propagation-tempo)
  - [14. Load testing with k6](#14-load-testing-with-k6)
  - [15. Alerting: Prometheus rules and Alertmanager](#15-alerting-prometheus-rules-and-alertmanager)
  - [16. Docker details that matter for the JVM](#16-docker-details-that-matter-for-the-jvm)
- **Part III: Labs**
  - [Lab 1: Young-generation churn](#lab-1-young-generation-churn)
  - [Lab 2: Humongous objects](#lab-2-humongous-objects)
  - [Lab 3: A slow leak, all the way to OOM](#lab-3-a-slow-leak-all-the-way-to-oom)
  - [Lab 4: Compare garbage collectors](#lab-4-compare-garbage-collectors)
  - [Lab 5: Follow a trace into its logs](#lab-5-follow-a-trace-into-its-logs)
  - [Lab 6: Record and read a JFR file](#lab-6-record-and-read-a-jfr-file)
  - [Lab 7: Make every alert fire](#lab-7-make-every-alert-fire)
- **Part IV: Reference**
  - [File map](#file-map)
  - [Endpoints](#endpoints)
  - [Troubleshooting](#troubleshooting)
  - [Glossary](#glossary)

---

# Part I: The big picture

## 1. What this lab is

A small Spring Boot app whose only job is to **produce interesting JVM behavior** (allocation,
garbage collection, memory leaks, slow requests), wrapped in the same observability stack you
would find around a production service. You trigger behavior on purpose, then observe it from
every angle: metrics, logs, traces, GC logs, and flight recordings.

```
                         ┌──────────────────────────────────────────────┐
   you / k6  ── HTTP ──▶ │ app  (Spring Boot, JVM 25, 512 MB / 1 CPU)   │
                         │   /hello  /lab/allocate  /lab/leak/*  /lab/chain
                         └──┬──────────────┬───────────────┬────────────┘
                            │              │               │
          scrape every 5s   │   stdout     │  OTLP spans   │  files on a bind mount
     /actuator/prometheus   │   (JSON)     │               │  gc.log · *.jfr · *.hprof
                            ▼              ▼               ▼
                     ┌────────────┐  ┌──────────┐   ┌──────────┐    ./docker/app/
                     │ Prometheus │  │  Alloy   │   │  Tempo   │
                     │  metrics   │  │ (reads   │   │  traces  │
                     │  + rules   │  │  docker  │   └────┬─────┘
                     └──┬──────┬──┘  │  logs)   │        │
              alerts    │      │     └────┬─────┘        │
                        ▼      │          ▼              │
               ┌──────────────┐│     ┌──────────┐        │
               │ Alertmanager ││     │   Loki   │        │
               └──────────────┘│     │   logs   │        │
                               ▼     └────┬─────┘        │
                        ┌─────────────────┴──────────────┴──┐
                        │  Grafana: dashboards, Explore,    │
                        │  logs ⇄ traces links              │
                        └───────────────────────────────────┘
```

| Service | What it does | URL from your Mac |
|---|---|---|
| `app` | The Spring Boot app under study | http://localhost:8080 |
| `prometheus` | Scrapes and stores metrics, evaluates alert rules | http://localhost:9090 |
| `alertmanager` | Receives firing alerts, groups and routes them | http://localhost:9093 |
| `grafana` | Dashboards and Explore (login `admin` / `admin`) | http://localhost:3000 |
| `loki` | Stores logs | http://localhost:3100 (API only) |
| `alloy` | Ships container logs to Loki | http://localhost:12345 (pipeline UI) |
| `tempo` | Stores traces | http://localhost:3200 (API only) |
| `k6` | Load generator, only runs when you ask | n/a |

The three kinds of telemetry answer different questions:

- **Metrics** answer *how much / how often*: numbers over time, cheap to store, good for
  dashboards and alerts. "Heap is 80% full", "p95 latency is 40 ms".
- **Logs** answer *what happened*: individual events with detail. "chain finished after 87 ms".
- **Traces** answer *where did the time go*: one request followed across every hop it makes.

The JVM adds two more sources that are specific to Java:

- **GC logs**: one line per collection, written by the JVM itself.
- **JFR recordings**: a detailed, low-overhead event stream from inside the JVM.

## 2. Quick start

```bash
cp .env.example .env                 # JVM flags live here; compose refuses to start without it
docker compose up -d --build         # build the app image and start everything
docker compose ps                    # everything should be "Up"
curl localhost:8080/hello            # Hello JVM Lab
```

Then open Grafana → Dashboards → **JVM Overview**.

Stop everything with `docker compose down`. Nothing is persisted except the files under
`./docker/app/` (GC logs, JFR recordings, heap dumps), so every `up` starts with empty
Prometheus, Loki and Tempo.

---

# Part II: How it works

## 3. The JVM in one page

When you run `java -jar app.jar`, here is what happens:

1. **Class loading.** Your code was compiled by `javac` (via Maven) into **bytecode**: `.class`
   files packed into the jar. Bytecode is a portable instruction set for an imaginary machine,
   not real CPU instructions. Class loaders read classes on demand, the first time each one is
   used. Their metadata goes into **Metaspace** (more in section 4).
2. **Interpretation.** At first the JVM *interprets* bytecode one instruction at a time. This is
   slow, but it starts immediately.
3. **JIT compilation.** The JVM counts how often each method runs. Hot methods are compiled to
   native machine code by the **JIT (just-in-time) compiler**: first quickly by C1, then, if they
   stay hot, aggressively by C2, which uses the profile it gathered (which branches are taken,
   which types actually show up). The compiled code lives in the **CodeHeap**. This is why a JVM
   app is slower for its first few seconds or minutes ("warm-up") and why benchmarks discard
   their first iterations.
4. **Memory management.** Your code allocates objects with `new` and never frees them. The
   **garbage collector** finds objects that can no longer be reached and reclaims their memory.
   This is the main subject of this guide.

**JDK vs JRE.** The JDK is the JRE plus development and diagnostic tools (`javac`, `jcmd`,
`jmap`, `jstack`, …). The `Dockerfile` builds with a JDK+Maven image and runs on a JRE-only
image, because production doesn't need a compiler. The trade-off: the runtime image only has
`java`, `jfr`, `keytool` and a few others (`docker run --rm --entrypoint sh spring-jvm-lab-app -c
'ls $JAVA_HOME/bin'`). There's no `jcmd`; section 10 shows how to get it back temporarily.

## 4. JVM memory: heap, non-heap, and the container

A JVM process uses much more memory than its heap. This is the most common reason containers get
killed unexpectedly, so it's worth getting this picture right:

```
 Container memory limit (mem_limit: 512m)  ── the kernel kills the process above this
 ┌───────────────────────────────────────────────────────────────────────────┐
 │ JVM process (resident memory, RSS)                                         │
 │ ┌─────────────────────────────────────┐ ┌───────────────────────────────┐ │
 │ │ HEAP  (capped by -Xmx256m)          │ │ NON-HEAP / NATIVE              │ │
 │ │  your objects live here             │ │  Metaspace     class metadata  │ │
 │ │  ┌───────────┬─────────────────────┐│ │  CodeHeap      JIT-compiled    │ │
 │ │  │ young gen │ old gen             ││ │  Thread stacks one per thread  │ │
 │ │  │ Eden, Surv│ long-lived objects  ││ │  GC structures remembered sets,│ │
 │ │  └───────────┴─────────────────────┘│ │                card tables     │ │
 │ │  managed by the garbage collector   │ │  Direct buffers NIO, Netty     │ │
 │ └─────────────────────────────────────┘ │  JFR buffers, malloc overhead  │ │
 │                                         └───────────────────────────────┘ │
 └───────────────────────────────────────────────────────────────────────────┘
```

**Measured in this lab** (the leak test, just before OOM): heap used ≈ 206 MB, non-heap pools
(Metaspace + CodeHeap + Compressed Class Space) ≈ 55 MB, but `docker stats` showed the container
at **456 MB**. The JVM's own metrics only account for about 261 MB. The remaining ~195 MB is
native memory that no `jvm_memory_*` metric reports: thread stacks, G1's bookkeeping
structures, malloc arenas, and similar.

That gap is why this lab sets `-Xmx256m` inside a `512m` container rather than `-Xmx512m`.

**Two very different "out of memory" failures:**

| | `java.lang.OutOfMemoryError` | Container OOM kill |
|---|---|---|
| Who decides | The JVM: the heap (or Metaspace, …) can't satisfy an allocation even after GC | The Linux kernel: the whole process exceeded `mem_limit` |
| What you see | An exception, often a stack trace; the process may keep running in a broken state | Process killed with `SIGKILL`, exit code **137**, `docker inspect` shows `OOMKilled: true`, no Java stack trace at all |
| Fix direction | Find what's using the heap (heap dump), or raise `-Xmx` | Leave more headroom between `-Xmx` and `mem_limit`, or reduce native memory |

**The JVM is container-aware.** Since JDK 10 it reads the container's cgroup limits rather than
the host's RAM and CPU count. Without `-Xmx`, the default maximum heap is
`MaxRAMPercentage=25` of the container limit, which is only 128 MB here. Many teams use
`-XX:MaxRAMPercentage=50` (or up to 75) instead of a fixed `-Xmx`, so the heap scales with the
container limit.

**Watch out: with this container's limits the JVM wouldn't pick G1 by itself.** The JVM only
considers a machine "server class", and defaults to G1, when it sees at least 2 CPUs and about 2
GB of memory. With `cpus: 1.0` and `512m` it falls back to **Serial GC**. That's why `.env` sets
`-XX:+UseG1GC` explicitly. Always state the collector explicitly in containers. (Lab 4 shows how
to check this yourself.)

## 5. Garbage collection fundamentals

### What is garbage?

An object is **live** if your program can still reach it by following references, starting
from a **GC root**. GC roots include:

- local variables and parameters on every thread's stack,
- `static` fields of loaded classes,
- references held by native code (JNI) and by the JVM itself.

Anything not reachable from a root is **garbage**, and its memory can be reused. This is why Java
has no problem with reference cycles. Two objects pointing at each other but unreachable from any
root are both garbage, because the GC never asks "how many references point here?", only "can I
reach this from a root?"

In this lab:

- `/lab/allocate` builds a `List<byte[]>` in a **local variable**. When the method returns, the
  stack frame is gone, nothing reaches the list, and all of it is garbage.
- `LeakService.leaked` is a **field of a singleton bean**. Spring's application context
  references the bean and the bean references the list, so everything in it stays live forever,
  until `clear()` drops the references.

That's the whole difference between churn and a leak.

### The three basic moves

Every collector combines these:

- **Mark**: walk the object graph from the roots and flag everything reachable.
- **Sweep**: treat everything unflagged as free space.
- **Compact or copy**: move live objects together so free space is one contiguous block.
  Without this, memory becomes **fragmented**: plenty of free bytes in total, but no single gap
  large enough for a big array.

Moving an object means updating every reference to it, which is why most collectors have to stop
your threads, at least briefly, while they do it.

### Stop-the-world, safepoints, concurrent work

A **stop-the-world (STW) pause** freezes all application threads. The JVM can only stop threads
at **safepoints**, points in the code where each thread's state is fully known, so "stopping
the world" also includes waiting for every thread to reach one.

Modern collectors do as much work as possible **concurrently**, on GC threads running alongside
your code, and keep the STW pauses short. Concurrent work isn't free: it uses CPU your app could
have used, and it needs **barriers**, small pieces of code the JIT inserts around reference reads
and writes so the GC can track what your code changes while it's working.

### The generational hypothesis

Measured across a huge number of programs: **most objects die young.** Request objects, strings
built for a log line, iterators, DTOs all live for microseconds or milliseconds. A few objects
(caches, connection pools, Spring beans) live for the whole run.

So the heap is split by age:

```
  YOUNG GENERATION                                   OLD GENERATION
 ┌──────────────────────────┬──────────┬──────────┐ ┌──────────────────────────────┐
 │ Eden                     │Survivor 0│Survivor 1│ │ objects that survived many   │
 │ every new object is born │  (from)  │   (to)   │ │ young collections            │
 │ here                     │          │          │ │                              │
 └──────────────────────────┴──────────┴──────────┘ └──────────────────────────────┘
        collected often, cheaply                        collected rarely, expensively
```

A **young (minor) collection** works like this:

1. Eden fills up, which triggers a young GC (a short STW pause).
2. Live objects in Eden and the "from" survivor space are **copied** into the "to" survivor
   space. Each object's **age** (number of young GCs survived) goes up by one.
3. Objects older than the **tenuring threshold** (up to 15) are copied into the old generation
   instead. This is **promotion**.
4. Eden and the "from" space are now entirely garbage and are reset in one step. Dead objects
   are never visited at all.

Point 4 is the key insight: **a young GC's cost depends on the number of live objects, not on
the amount of garbage.** Creating lots of short-lived garbage is cheap. Keeping lots of objects
alive for a medium amount of time (long enough to be promoted, then dying in the old generation)
is expensive.

**Allocation itself is very cheap.** Each thread gets a private chunk of Eden called a **TLAB**
(thread-local allocation buffer), and `new` just bumps a pointer inside it. No locking, no search
for free space. That's why Java code can allocate freely.

An **old-generation** or **full** collection has to deal with the big, long-lived part of the
heap, which is why collectors try very hard to avoid full STW collections of it.

### There is no free lunch: the three-way trade-off

Every collector balances three things against each other:

```
            Throughput (% of CPU doing your work, not GC)
                 ▲
                /  \
               /    \
   Latency ◀──────────▶ Footprint
 (pause length)        (memory overhead)
```

- **Parallel GC** maximizes throughput and accepts longer pauses.
- **ZGC / Shenandoah** minimize pauses and pay with CPU (barriers, concurrent threads) and some
  extra memory.
- **G1** sits in the middle, with a configurable pause-time *goal*.

## 6. G1, the default collector, in depth

G1 ("Garbage-First") is the default collector on server-class machines, and it's what this lab
runs unless you change `.env`.

### Regions

G1 doesn't use fixed, contiguous young and old areas. It divides the heap into many
**equal-sized regions** and gives each region a role on the fly:

```
 ┌───┬───┬───┬───┬───┬───┬───┬───┬───┬───┬───┬───┬───┬───┬───┬───┐
 │ E │ E │ O │ F │ S │ O │ H │ H │ H │ E │ F │ O │ F │ E │ O │ F │  ...  (≈256 regions of 1 MB)
 └───┴───┴───┴───┴───┴───┴───┴───┴───┴───┴───┴───┴───┴───┴───┴───┘
  E = Eden   S = Survivor   O = Old   H = Humongous (one object spanning 3 regions)   F = Free
```

**Region size** is chosen once, at startup: roughly `heap / 2048`, rounded to a power of two,
at least 1 MB and (when picked automatically) at most 32 MB. For `-Xmx256m`, 256 MB / 2048 =
128 KB, which is raised to the 1 MB minimum, so the heap has **1 MB regions** (256 of them).
Override with `-XX:G1HeapRegionSize=` (a power of two).

Because roles are per region, G1 can resize the young generation after every GC to meet its
pause goal: fewer Eden regions means a shorter young pause.

### The pause-time goal

`-XX:MaxGCPauseMillis` (default **200 ms**) is a **goal, not a guarantee**. G1 measures how long
it takes to evacuate a region and picks how many regions to collect in the next pause so it
stays under the goal. "Garbage-First" means that, given limited time, G1 collects the regions
with the **most garbage** first, because they give the most freed memory per millisecond of
pause.

### The G1 cycle

```
  young GCs ──▶ heap occupancy crosses IHOP ──▶ concurrent marking ──▶ mixed GCs ──▶ young GCs …
  (Eden only)   (≈45%, adaptive)                 (alongside your app)   (Eden + some
                                                                         old regions)
```

1. **Young GCs** (STW): evacuate live objects from Eden and Survivor regions into new Survivor
   or Old regions. In the GC log these appear as `Pause Young (Normal) (G1 Evacuation Pause)`.
2. When old-generation occupancy crosses the **IHOP** (Initiating Heap Occupancy Percent: 45% to
   start, then adjusted from what G1 observes), G1 starts **concurrent marking**. It works out
   which old objects are still live **while your application keeps running**, with only short
   STW pauses at the start (`Pause Young (Concurrent Start)`), and then `Remark` and `Cleanup`.
3. Once G1 knows how much garbage each old region holds, it runs one more young-only pause
   (`Pause Young (Prepare Mixed)`) and then **mixed GCs**: young GCs that also evacuate a few of
   the most-garbage-filled old regions (`Pause Young (Mixed)`).

   During the k6 run this lab logged 28 Normal, 12 Concurrent Start, 12 Prepare Mixed, 12 Mixed,
   12 Remark, 12 Cleanup and **zero Full** pauses. That's a healthy G1 keeping up with a
   leak-then-clear cycle.
4. If all of this can't keep up (allocation is faster than reclamation, or there's no free region
   to copy into), G1 falls back to a **Full GC**: a stop-the-world mark and compaction of the
   *entire* heap (multi-threaded since JDK 10, but still far longer than a young pause). Full GCs are what you want to avoid. Seeing them regularly means the heap is
   too small or something is leaking.

### Remembered sets and card tables: the hidden native-memory cost

To collect a few regions without scanning the whole heap, G1 has to know **who points into
each region from outside**. Every region keeps a **remembered set** of incoming references.
Write barriers update it every time your code stores a reference into a field, using a
**card table** (the heap split into 512-byte "cards" with a dirty bit each).

These structures live in **native memory**, outside the heap. They grow with the number of
cross-region references, which is part of the ~195 MB of unaccounted memory measured in
section 4.

### Humongous objects

This is the surprise you found while exploring `/lab/allocate`.

**Rule:** any single object of **at least half a region** is *humongous*. With 1 MB regions,
the threshold is **512 KB**.

Humongous objects:

- **bypass Eden**: they're allocated directly into one or more contiguous free regions that
  belong to the old generation from birth,
- need **contiguous** free regions, so a fragmented heap can fail to place them even with plenty
  of total free space,
- may start a concurrent marking cycle early, because they inflate old-generation occupancy,
- are reclaimed either by **eager reclaim** (during a young GC, if G1 can cheaply prove nothing
  references them) or only after marking.

The default `/lab/allocate` creates `byte[1024 * 1024]`. That's 1 MB of data plus a 16-byte
array header, which is more than one full region, so every one of the 200 arrays is humongous
and spans **two** regions (one full, one with 16 bytes). That's 400 regions of demand from a
256-region heap, all in one request. Sequential calls ran out of heap.

With `?chunkKb=64` the arrays are far below 512 KB and take the normal Eden path. Lab 1 and Lab
2 let you compare the two.

> **Practical lesson:** in real services, humongous allocations usually come from big
> `byte[]`/`char[]` buffers: reading a whole file or HTTP body into memory, huge `StringBuilder`s,
> large JSON documents. If GC logs show many `Humongous` events, either stream the data or
> increase the region size so those objects stop counting as humongous.

## 7. The other collectors

All of these ship with the Temurin JDK 25 used here. Switch with one flag in `.env`.

| Collector | Flag | Pauses | Strength | Cost |
|---|---|---|---|---|
| **Serial** | `-XX:+UseSerialGC` | STW, single-threaded, for both young and old | Smallest footprint, ideal for 1 CPU and tiny heaps | Pauses grow with heap size |
| **Parallel** | `-XX:+UseParallelGC` | STW, multi-threaded | Best raw throughput for batch jobs | Long full GCs on big heaps |
| **G1** | `-XX:+UseG1GC` | Short STW young/mixed pauses + concurrent marking | Balanced, predictable, the default | Barrier overhead, remembered-set memory |
| **ZGC** | `-XX:+UseZGC` | Sub-millisecond, independent of heap size | Latency-critical services, huge heaps | More CPU and memory headroom needed |
| **Shenandoah** | `-XX:+UseShenandoahGC` | Sub-10 ms, mostly concurrent | Low latency, including on smaller heaps | CPU for concurrent work |

**Serial and Parallel** use the classic contiguous young/old layout from section 5. They stop
everything for every collection. Serial uses one GC thread and Parallel uses many. With
`cpus: 1.0` in this container they behave almost the same.

**ZGC** does marking *and* relocation (moving objects) concurrently. It stores GC state in
spare bits of every reference ("colored pointers") and uses **load barriers**: whenever your code
reads a reference, a tiny check makes sure it points at the object's current location, fixing it
if the object has been moved. Pauses only handle thread stacks, so they stay well under a
millisecond whether the heap is 256 MB or 16 TB. Since JDK 23 ZGC is **generational** by default,
and in JDK 24+ the non-generational mode is gone. In the dashboard you'll see ZGC's pause panels
nearly flat and its *Concurrent GC work* panel busy.

**Shenandoah** also compacts concurrently, using a different barrier design. JDK 25 adds an
optional generational mode (`-XX:ShenandoahGCMode=generational`).

**How to choose, in practice:** keep G1 unless you have a measured reason. Pick ZGC when tail
latency matters and you can spare some CPU and memory. Pick Parallel for throughput-only batch
work. Use Serial for small single-CPU containers. Lab 4 lets you measure the difference.

## 8. Reading GC logs

The JVM writes GC events through **unified logging**, configured in `.env`:

```
-Xlog:gc*:file=/gc-logs/gc.log:time,uptime,level,tags
       │    │                   └ decorations on every line
       │    └ output (a file on the bind mount → ./docker/app/gc-logs/gc.log)
       └ which tags: gc and all of its sub-tags (gc+heap, gc+phases, …)
```

On every JVM start the previous file is rotated to `gc.log.0` … `gc.log.4` (five kept by
default), so after a crash-and-restart the evidence is in `gc.log.0`.

The most useful lines are the one-line pause summaries. A real one from this lab, taken while
the k6 leak scenario was running:

```
[2026-10-22T09:03:49.805+0000][254.594s][info][gc] GC(20) Pause Young (Normal) (G1 Evacuation Pause) 217M->137M(256M) 8.834ms
 │                             │                   │      │                     │                     │     │     │      │
 │                             │                   │      │                     │                     │     │     │      └ pause length
 │                             │                   │      │                     │                     │     │     └ heap size (committed)
 │                             │                   │      │                     │                     │     └ heap used after
 │                             │                   │      │                     │                     └ heap used before
 │                             │                   │      │                     └ why it happened (the "cause")
 │                             │                   │      └ what kind of collection
 │                             │                   └ GC number since JVM start
 │                             └ JVM uptime
 └ wall-clock time
```

137 MB still in use *after* a young collection is a warning sign: something is retaining a lot.
Here it was the leak.

Patterns to recognise:

| You see | It means |
|---|---|
| `Pause Young (Normal)` regularly, "after" stays low and flat | Healthy churn |
| "after" climbs from one GC to the next | Objects keep surviving: a leak, or a cache filling up |
| `Pause Young (Concurrent Start)` then `Concurrent Mark Cycle` | Old gen crossed IHOP; G1 is marking |
| `Pause Remark`, `Pause Cleanup` | The two short STW steps at the end of marking |
| `Pause Young (Prepare Mixed)` | Last young-only pause before mixed collections start |
| `Pause Young (Mixed)` | G1 is reclaiming old regions |
| `(Metadata GC Threshold)` as the cause | Metaspace hit its current limit and needs a GC to unload classes (normal right after startup) |
| `G1 Humongous Allocation` as the cause | A humongous object triggered the GC (Lab 2) |
| `Pause Full` | G1 gave up on incremental collection. Investigate |
| `To-space exhausted` / `Evacuation Failure` | No free region to copy into. Heap is too full |

Useful commands:

```bash
grep -E "Pause (Young|Full|Remark)" docker/app/gc-logs/gc.log | tail -20
grep -c "Pause Full" docker/app/gc-logs/gc.log
grep -i humongous docker/app/gc-logs/gc.log | tail
```

For a visual timeline, upload a GC log to a GC log analyzer (GCeasy, or GCViewer offline).

## 9. Leaks, OutOfMemoryError, and what happens after

A **memory leak** in Java isn't "memory nobody owns" like in C. It's **memory that's still
reachable but will never be used again**: a static map that only grows, a listener that's
registered and never removed, a cache without eviction. The GC behaves correctly: it can't free
what's still referenced.

`LeakService` is exactly that: a scheduled task appends a 256 KB `byte[]` to a field every
100 ms (~2.5 MB/s). 256 KB is deliberately under the 512 KB humongous threshold, so these arrays
take the normal path: born in Eden, survive young GCs, get promoted to old gen, and pile up there.

**How a leak looks in the dashboard** (and why each panel matters):

1. *Heap used* swings up and down with every GC, so it's a weak signal on its own.
2. **Old gen: live data after GC** climbs step by step. This is the strongest leak signal,
   because it's measured right after a collection, when all garbage is gone.
3. *Promotion rate* stays high: objects keep surviving.
4. GC frequency and *time in GC pauses* rise as free space shrinks. Concurrent cycles start
   back to back.
5. Finally: Full GCs, then `OutOfMemoryError: Java heap space`.

### What actually happened in this lab when the heap ran out

Measured, in order:

1. The leak reached ~188 MB of `byte[]` in a 256 MB heap.
2. `new byte[262144]` failed with `OutOfMemoryError` **inside the scheduler thread**. A
   `ScheduledExecutorService` silently cancels a periodic task when it throws, so the first
   version of `LeakService` showed `running: false` with **nothing in the logs**. The fix was a
   `try/catch` that logs and rethrows.
3. With the heap full, *other* threads started failing too: Tomcat's `http-nio-8080-Acceptor`
   and `Poller` threads and JFR's periodic task thread all died with `OutOfMemoryError`. Even the
   log statement couldn't allocate memory.
4. The process stayed **alive**, so Docker didn't restart it, but **HTTP was dead**. Requests
   timed out. That's a **zombie**: the worst outcome, because health-based restarts only happen
   if something notices.

The lesson: **after an `OutOfMemoryError` the JVM's state can't be trusted.** Any thread might
have died halfway through something. So `.env` now includes:

| Flag | Effect |
|---|---|
| `-XX:+ExitOnOutOfMemoryError` | The first OOM kills the JVM (exit code 3). `restart: unless-stopped` brings it back clean. |
| `-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/dumps` | Before exiting, write the whole heap to `./docker/app/dumps/java_pid1.hprof` so you can see *what* filled it. |

**Measured with these flags:** the leak reached ~186 MB at 70 s, then:

```
java.lang.OutOfMemoryError: Java heap space
Dumping heap to /dumps/java_pid1.hprof ...
Heap dump file created [228907101 bytes in 0.954 secs]
Terminating due to java.lang.OutOfMemoryError: Java heap space
```

The container exited with code 3, Docker restarted it, and `/hello` answered again within
seconds. Note that `leak task failed` never shows up in the logs this time.
`ExitOnOutOfMemoryError` terminates the JVM *at the moment the error is thrown*, before any
`catch` block runs. The `try/catch` in `LeakService` only matters when this flag is off.

**Container exit codes worth knowing** (`docker events --filter event=die` shows them):

| Code | Meaning |
|---|---|
| 0 | Normal exit |
| 3 | JVM terminated itself because of `-XX:+ExitOnOutOfMemoryError` |
| 137 | 128 + 9 = `SIGKILL`: the kernel OOM killer, or `docker stop` timed out |
| 143 | 128 + 15 = `SIGTERM` handled: a clean `docker stop`, which works because of `exec java` |

> Because the PID is always 1 in a container, the dump file always has the same name. The JVM
> won't overwrite an existing dump, so delete or rename the old one if you want a new one.

**Reading a heap dump:** open the `.hprof` in **Eclipse MAT** (Memory Analyzer) or **VisualVM**.
In MAT, the *Leak Suspects* report and the *Dominator Tree* show which object keeps the most
memory alive. Here that's `LeakService` → `CopyOnWriteArrayList` → hundreds of `byte[]`.

**Other `OutOfMemoryError` messages and what they mean:**

| Message | Meaning |
|---|---|
| `Java heap space` | Heap full even after GC |
| `GC overhead limit exceeded` | Parallel GC: >98% of time spent in GC recovering <2% of the heap |
| `Metaspace` | Too many classes loaded (often a classloader leak) |
| `unable to create native thread` | OS or container refused another thread (thread leak, pids limit) |
| `Direct buffer memory` | Off-heap NIO buffers exceeded `-XX:MaxDirectMemorySize` |

## 10. JFR: the flight recorder

**Java Flight Recorder** is built into the JVM. It records events (GC phases, allocation
samples, lock contention, thread states, CPU samples, exceptions, I/O, …) into an in-memory
buffer, with overhead low enough (~1%) to leave on in production.

`.env` starts a continuous recording at boot:

```
-XX:StartFlightRecording=disk=true,dumponexit=true,filename=/jfr/continuous.jfr
```

- `disk=true`: older data rolls off to a disk repository instead of being dropped. With no limit
  given, JFR caps it at 250 MB (the startup log says so).
- `dumponexit=true`: write everything to `filename` when the JVM exits **normally**.

That "normally" matters. Early in this lab the `ENTRYPOINT` was `sh -c "java …"`, so PID 1 was the
shell. `docker stop` sends `SIGTERM` to PID 1 only, the shell didn't forward it, and Java was
killed without a clean shutdown. `continuous.jfr` stayed at **0 bytes**. Changing it to
`sh -c "exec java …"` makes Java replace the shell as PID 1. After that, `docker stop` produced a
**1 MB recording**. (More in section 16.)

There's a second catch: after a crash, `continuous.jfr` doesn't help either. An OOM exit
(`ExitOnOutOfMemoryError`) is abrupt, so `dumponexit` never runs, and the restarted JVM creates a
fresh, empty `continuous.jfr` at the same path. Measured: 0 bytes after the Lab 3 crash. For
crashes, rely on the heap dump and the rotated `gc.log.0`. For recordings, dump on demand
before trouble hits, or give each start its own file name.

**On-demand dumps without stopping the app** need `jcmd`, which is a JDK tool, and the runtime
image is JRE-only. The production-style answer is a temporary **debug container** that joins the
app's PID namespace:

```bash
docker run --rm --pid=container:app eclipse-temurin:25-jdk jcmd 1 JFR.check
docker run --rm --pid=container:app eclipse-temurin:25-jdk \
  jcmd 1 JFR.dump name=1 filename=/jfr/manual.jfr
```

`--pid=container:app` lets the JDK container see the app's processes, and `jcmd` attaches to PID
1. The *app's* JVM writes the file, so `/jfr/manual.jfr` lands in `./docker/app/jfr/` on your
Mac. This is the same idea as `kubectl debug` in Kubernetes. The same trick works for other
tools: `jcmd 1 Thread.print` (thread dump), `jcmd 1 GC.heap_info`,
`jcmd 1 VM.flags` (effective JVM flags).

**Reading a recording:** open the `.jfr` in **JDK Mission Control** (JMC). Good starting pages:
*Automated Analysis*, *Memory → GC* (every pause, with cause), *Memory → Allocations* (which code
allocates the most), *Threads*, and *Method Profiling* (CPU hot spots). Without a GUI, the `jfr`
tool is already inside the app container:

```bash
docker exec app jfr summary /jfr/manual.jfr
docker exec app jfr print --events jdk.GarbageCollection /jfr/manual.jfr | head -40
```

## 11. Metrics: Micrometer, Prometheus, Grafana

### The pipeline

1. **Micrometer** is a metrics facade inside the app, like SLF4J but for metrics. Spring Boot
   Actuator registers JVM, process, and HTTP metrics automatically.
2. `micrometer-registry-prometheus` exposes them as plain text at
   **`/actuator/prometheus`**. Open it in a browser and look; it's readable.
3. **Prometheus pulls** (scrapes) that endpoint every 5 s (`docker/prometheus/prometheus.yml`)
   and stores each series with a timestamp.
4. **Grafana** queries Prometheus with **PromQL** and draws the result.

Pull-based means the app doesn't know Prometheus exists. If a scrape fails, Prometheus records
`up == 0`, which is how the `AppDown` alert works.

### Metric types

| Type | Behavior | Example |
|---|---|---|
| **Gauge** | A value that goes up and down | `jvm_memory_used_bytes` |
| **Counter** | Only goes up (resets on restart) | `jvm_gc_memory_allocated_bytes_total` |
| **Timer / histogram** | Count, sum and buckets of durations | `http_server_requests_seconds_*`, `jvm_gc_pause_seconds_*` |

A timer produces several series: `_count` (how many), `_sum` (total seconds), `_max` (recent
worst case) and, because `application.yml` enables histograms for HTTP, `_bucket` (how many
finished within each bound, used for percentiles).

Labels split a metric into series: `jvm_memory_used_bytes{area="heap", id="G1 Old Gen"}`.

### The PromQL you actually need

```promql
rate(jvm_gc_pause_seconds_count[1m])             # counter → per-second rate over the last minute
sum by (gc) (rate(jvm_gc_pause_seconds_sum[1m])) # seconds of pause per second = fraction of time paused
sum(jvm_memory_used_bytes{area="heap"})          # add up all heap pools
histogram_quantile(0.95,
  sum by (le, uri) (rate(http_server_requests_seconds_bucket[1m])))  # p95 latency per endpoint
```

Rules of thumb:

- **Never graph a raw counter.** Wrap it in `rate()`.
- `rate()` first, then `sum`.
- `[1m]` must cover several scrape intervals.

### The JVM Overview dashboard

`docker/grafana/dashboards/jvm-overview.json` is loaded automatically at startup (via
`provisioning/dashboards/dashboard.yml`). Every panel has a description: hover over the ⓘ next to
the title. Here's what to look at first:

| Row | Panel | Read it as |
|---|---|---|
| Overview | Old gen live after GC | The leak gauge. Should be flat. |
| Overview | Time in GC pauses | 1% is fine; above 5% your users feel it |
| Heap | Heap used by pool | Eden sawtooth = healthy churn. Rising Old Gen = retention |
| Heap | Allocation & promotion rate | High allocation is fine; high promotion is what costs |
| GC | GC pauses per second / Longest recent pause | Frequency vs severity |
| GC | Concurrent GC work | Where ZGC/Shenandoah do their work |
| Non-heap | Non-heap memory by pool | Memory that `-Xmx` doesn't cap |
| HTTP | p95 latency | GC pauses show up here as spikes |
| Logs | App logs | Same time range, straight from Loki |

**Explore** (compass icon) is a scratchpad for ad-hoc queries in any datasource: Prometheus,
Loki, or Tempo.

## 12. Logs: structured JSON, Alloy, Loki

### Structured logging

`application.yml` sets `logging.structured.format.console: logstash`, so every log line is one
JSON object instead of formatted text:

```json
{"@timestamp":"…","level":"INFO","thread_name":"http-nio-8080-exec-3",
 "logger_name":"com.ndys.jvmlab.controller.ChainController",
 "message":"chain finished after 87 ms of simulated work",
 "traceId":"4bf92f3577b34da6a3ce929d0e0e4736","spanId":"00f067aa0ba902b7", …}
```

Machines can filter on fields (`level`, `logger_name`, `traceId`) without fragile regexes.
`traceId` and `spanId` appear automatically once tracing is on (section 13).

### Getting logs into Loki

The app just writes to **stdout**. It doesn't know about Loki at all. That's the
twelve-factor way and how most container platforms work.

**Grafana Alloy** (`docker/alloy/config.alloy`) is the collector:

```
discovery.docker   find containers of this compose project via the Docker socket
discovery.relabel  turn Docker metadata into labels: service="app", container="app"
loki.source.docker read each container's log stream
loki.process       for the app only: parse JSON, promote "level" to a label
loki.write         push to http://loki:3100
```

Open **http://localhost:12345** to see this pipeline live, component by component.

> Alloy replaces **Promtail**, the older Loki agent, which Grafana has deprecated and reached
> end of life in 2026. The concepts are the same.

> Mounting `/var/run/docker.sock` gives the container full control of Docker (`:ro` doesn't
> limit API calls). That's fine on a laptop; in production you'd use the platform's own log
> collection.

### LogQL basics

Loki indexes **labels only**, not the log text. That's what makes it cheap. A query first selects
streams by label, then filters content:

```logql
{service="app"}                                     # all app logs
{service="app", level="ERROR"}                      # label filter (fast, indexed)
{service="app"} |= "leak"                           # substring filter
{service="app"} | json | logger_name=~".*Chain.*"   # parse JSON, filter on a field
sum by (level) (count_over_time({service="app"}[1m]))  # logs per level per minute
```

Keep labels **low-cardinality**: `service` and `level` are fine. Putting `traceId` in a label would
create one stream per request and overwhelm Loki. That's why trace IDs stay inside the line and
are matched with `| json`.

## 13. Traces: spans, propagation, Tempo

### Concepts

- A **trace** is one end-to-end operation, identified by a 128-bit **trace ID**.
- A **span** is one timed step inside it ("GET /lab/chain", "HTTP GET /hello"), with a start
  time, a duration, attributes, and a **parent span**. The parent links build the tree.
- **Context propagation**: when the app calls another service, it sends the trace ID and the
  current span ID along in the W3C **`traceparent`** HTTP header. The receiving side continues the
  same trace instead of starting a new one.

### How it's wired here

```
 Spring MVC / RestClient  ──▶  Micrometer Observation  ──▶  micrometer-tracing-bridge-otel
   (instrumented by Boot)        (timers AND spans from         (OpenTelemetry SDK)
                                  the same instrumentation)            │
                                                             opentelemetry-exporter-otlp
                                                                       │ HTTP, port 4318
                                                                       ▼
                                                                     Tempo
```

Just two dependencies in `pom.xml` plus `management.tracing.sampling.probability: 1.0` (keep
100% of traces. Production usually samples less) and the OTLP endpoint. Spring Boot instruments
incoming requests and outgoing `RestClient`/`RestTemplate`/`WebClient` calls automatically, **as
long as the client is built from the Spring-provided builder**. A `RestClient.create()` you build
yourself won't propagate the trace. That's why `ChainController` injects `RestClient.Builder`.

`/lab/chain` calls `/hello` on itself twice with simulated work in between, which produces:

A real trace from this lab, as Tempo stored it:

```
 start     duration  kind     span
 +0.0 ms   51.1 ms   SERVER   http get /lab/chain
 +1.1 ms    3.4 ms   CLIENT     ├─ http get            (RestClient call)
 +2.8 ms    1.3 ms   SERVER     │    └─ http get /hello (same app, receiving it)
                                │   … 40 ms gap: the simulated work (Thread.sleep)
 +47.0 ms   3.2 ms   CLIENT     └─ http get
 +48.4 ms   1.3 ms   SERVER          └─ http get /hello
```

Its four log lines (`chain started`, `hello called` ×2, `chain finished after 40 ms…`) all carry
the same `traceId`.

`ObservabilityConfig` registers an `ObservationPredicate` that skips `/actuator/**` requests.
Without it, every Prometheus scrape (every 5 s) would create its own trace and bury these in
Tempo's search results. A side effect: actuator requests also disappear from the
`http_server_requests` metrics.

### Traces ⇄ logs

The same trace ID appears in the JSON logs, so the datasources are linked both ways in
`docker/grafana/provisioning/datasources/datasource.yml`:

- **Tempo → Loki** (`tracesToLogsV2`): on a span, "Logs for this span" runs
  `{service="app"} | json | traceId="<id>"`.
- **Loki → Tempo** (`derivedFields`): a regex finds `"traceId":"…"` in a log line and turns it
  into a **View trace** button.

## 14. Load testing with k6

`docker/k6/scripts/load-test.js` runs three **scenarios** in parallel for about 3 minutes:

| Scenario | Executor | Traffic | Shows you |
|---|---|---|---|
| `browse` | `ramping-vus`: 0 → 10 virtual users, hold, ramp down | `/hello` + `/lab/chain`, 1 s think time | Request rate, latency, traces |
| `allocate` | `constant-arrival-rate`: one call every 2 s | `/lab/allocate?mb=20&chunkKb=64` | Eden sawtooth, young GC rate |
| `leak` | `per-vu-iterations`: 2 cycles | leak 20 s → stop → clear → wait | Old gen rising, then dropping after a GC |

**Thresholds** make the run pass or fail: `browse` must keep errors under 1% and p95 under
500 ms. k6 exits non-zero if they're broken, which is how load tests gate CI pipelines.

```bash
docker compose --profile load run --rm k6 run /scripts/load-test.js
```

`profiles: ["load"]` keeps k6 out of a plain `docker compose up`. The traffic goes to
`http://app:8080` over the compose network.

## 15. Alerting: Prometheus rules and Alertmanager

**Prometheus evaluates the rules** in `docker/prometheus/alerts.yml` every 5 s. A rule whose
expression returns results is **pending**. If it stays true for its `for:` duration it becomes
**firing** and is sent to **Alertmanager**. Alertmanager **groups** alerts (by `alertname` here),
**deduplicates** them, applies **silences**, and **routes** them to receivers (Slack, email,
PagerDuty, …). The receiver here does nothing, so you just watch alerts in the UI.

| Alert | Expression idea | Why this way |
|---|---|---|
| `AppDown` | `up == 0` for 30 s | Prometheus couldn't scrape. Catches crashes *and* zombies |
| `HeapUsageHigh` | used / max > 85% for 1 m | Raw heap. Noisy alone, so it has a `for:` |
| `LiveDataAfterGcHigh` | live-after-GC / old-gen max > 70% for 1 m | The real leak signal |
| `GcPauseTimeHigh` | pause seconds per second > 5% for 1 m | Users feel this as latency |
| `Http5xxRateHigh` | 5xx / all requests > 5% for 1 m | Ratio, not count, so it scales with traffic |

`for:` stops alerts from flapping on brief spikes. Where to look: Prometheus → *Alerts*
(pending and firing), Alertmanager at :9093, Grafana → *Alerting*.

Prometheus runs with `--web.enable-lifecycle`, so after editing `prometheus.yml` or
`alerts.yml` you can reload without restarting:

```bash
curl -X POST localhost:9090/-/reload
```

## 16. Docker details that matter for the JVM

**Multi-stage build.** Stage one (`maven:…-temurin-25`) compiles; stage two (`temurin:25-jre`)
only receives `app.jar`. The final image has no Maven, no source, and no compiler.
`COPY pom.xml` + `mvn dependency:go-offline` come *before* `COPY src`, so Docker's layer cache
skips the dependency download when only code changes. Editing `pom.xml` invalidates that cache.
You saw that when adding the tracing dependencies made the build slow again.

**PID 1 and signals.** `docker stop` sends `SIGTERM` to PID 1, waits 10 s, then sends `SIGKILL`.
With `ENTRYPOINT ["sh","-c","java …"]`, PID 1 is the shell and Java never gets `SIGTERM`, so
there's no graceful shutdown, no JFR dump, and no shutdown hooks. `exec java …` replaces the
shell with Java, so Java becomes PID 1. You can check this with `docker exec app ps aux`. The
`sh -c` is still needed so `$JAVA_OPTS` gets expanded; the exec-form array alone doesn't expand
variables.

**Resource limits.** `mem_limit: 512m` and `cpus: 1.0` are enforced by cgroups, and the JVM reads
them (section 4). `cpus: 1.0` also means GC thread counts, the JIT compiler threads, and
`Runtime.availableProcessors()` are all sized for one CPU.

**Bind mounts** (`./docker/app/gc-logs:/gc-logs`) aren't copies. The same file is visible in
the container and on your Mac, which is how you read GC logs and JFR files without `docker cp`.

**Networking.** Compose creates one bridge network (`spring-jvm-lab_default`). Containers reach
each other by **service name** (`app:8080`, `loki:3100`) through Docker's internal DNS. `ports:`
only matters for reaching a container *from your Mac*.

**`.env`.** Compose reads `.env` automatically. `${JAVA_OPTS:?…}` in `docker-compose.yml` makes
compose fail with a clear message if it's missing, instead of silently starting the JVM with no
flags. After editing `.env`, `docker compose up -d app` recreates the container with the new
flags; no rebuild is needed, because the flags aren't baked into the image.

---

# Part III: Labs

Before each lab: stack up, Grafana open on **JVM Overview**, time range *Last 15 minutes*.
A second terminal with `docker stats app` helps.

## Lab 1: Young-generation churn

**Goal:** see healthy garbage collection.

```bash
for i in $(seq 1 60); do curl -s "localhost:8080/lab/allocate?mb=50&chunkKb=64" >/dev/null; sleep 0.5; done
```

**Watch:** *Heap used by pool*: G1 Eden rises and drops back many times (the sawtooth). *Old gen
live after GC* barely moves. *GC pauses per second* shows `G1 Young Generation – G1 Evacuation
Pause`. In the log:

```bash
grep "Pause Young" docker/app/gc-logs/gc.log | tail -5
```

"After" values stay small: everything allocated was garbage by the time GC ran.

**Question:** 50 MB per call, 60 calls = 3 GB allocated through a 256 MB heap with no problem.
Why? (Section 5: young GC cost depends on live objects, not garbage.)

## Lab 2: Humongous objects

**Goal:** see the same amount of allocation behave very differently.

1. Confirm the region size:
   ```bash
   docker run --rm --entrypoint java spring-jvm-lab-app -Xmx256m -XX:+UseG1GC \
     -XX:+PrintFlagsFinal -version | grep G1HeapRegionSize
   ```
2. Allocate the same 50 MB with 1 MB chunks:
   ```bash
   for i in $(seq 1 10); do curl -s "localhost:8080/lab/allocate?mb=50&chunkKb=1024" >/dev/null; sleep 1; done
   grep -i humongous docker/app/gc-logs/gc.log | tail
   ```
3. Repeat with `chunkKb=256` (under the threshold) and `chunkKb=600` (over it).

**Watch:** GC causes switch to `G1 Humongous Allocation`, and the "Humongous regions" counts in
the log rise. Then try `-XX:G1HeapRegionSize=4m` in `.env` (threshold becomes 2 MB) and repeat
with 1 MB chunks: they're no longer humongous.

**Question:** why do 1 MB arrays need *two* regions each with 1 MB regions? (Hint: object
header.)

## Lab 3: A slow leak, all the way to OOM

**Goal:** see a leak from first symptom to crash, recovery, and post-mortem.

```bash
curl -X POST localhost:8080/lab/leak/start
watch -n 2 curl -s localhost:8080/lab/leak/status      # or repeat by hand
```

**Watch, in order** (about 75 seconds total):

1. *Old gen live after GC* climbs in steps, one after each GC.
2. *Promotion rate* stays high; the GC log shows `Concurrent Start` and `Mixed` pauses.
3. Prometheus → Alerts: `HeapUsageHigh` and `LiveDataAfterGcHigh` go pending, then firing.
4. Pause frequency rises; the GC log shows `Pause Full`.
5. OOM. Because of `ExitOnOutOfMemoryError`, the JVM exits, *Uptime* drops to 0, and the
   container restarts (`docker ps` shows it as "Up a few seconds"). `AppDown` may briefly go
   pending.
6. The post-mortem: `ls -lh docker/app/dumps/`. Open `java_pid1.hprof` in Eclipse MAT →
   *Leak Suspects*.
7. Logs: in Grafana Explore → Loki, `{service="app"} |= "OutOfMemoryError"` shows the dump and
   terminate lines from the dead JVM, still searchable after the restart. Now search
   `|= "leak task failed"`: nothing. Why? (Section 9.)

Then the gentle version: start, wait 20 s, `POST /lab/leak/stop`, then `POST /lab/leak/clear`.
Live-after-GC doesn't drop right away. **Why?** Clearing references doesn't free memory; the
next collection that covers old regions does.

## Lab 4: Compare garbage collectors

**Goal:** measure the trade-offs from section 7 under the same load.

For each collector (G1, Parallel, ZGC, Shenandoah, Serial):

1. Change the `-XX:+Use…GC` flag in `.env`.
2. `docker compose up -d app` and wait for the app to start.
3. `docker compose --profile load run --rm k6 run /scripts/load-test.js`
4. Note: k6's p95 for `browse`; dashboard *Longest recent pause*, *Time in GC pauses*,
   *Concurrent GC work*, *CPU usage*; the pool names in *Heap used by pool*.

| Collector | Max pause | % time paused | GC concurrent CPU | p95 browse | Notes |
|---|---|---|---|---|---|
| G1 | | | | | |
| Parallel | | | | | |
| ZGC | | | | | |
| Shenandoah | | | | | |
| Serial | | | | | |

Also check what the JVM picks **by itself** in this container:

```bash
docker run --rm --cpus=1 --memory=512m --entrypoint java spring-jvm-lab-app \
  -XX:+PrintFlagsFinal -version | grep -E " Use(Serial|G1|Parallel|Z)GC "
```

**Expect:** ZGC with near-zero pauses but more CPU; Parallel/Serial with longer but rarer pauses;
G1 in between. With only 1 CPU, the concurrent collectors compete with your app for that CPU.
This is a real reason not to use ZGC in tiny containers.

## Lab 5: Follow a trace into its logs

1. `curl localhost:8080/lab/chain` a few times.
2. Grafana → Explore → **Tempo** → *Search* → Service name `spring-jvm-lab` → open a trace for
   `GET /lab/chain`.
3. Look at the span tree: two client spans, each with a server span inside, and a gap between them
   (the simulated work).
4. Click a span → **Logs for this span**. Loki opens filtered to that `traceId`, showing
   `chain started`, `hello called` ×2, `chain finished`.
5. Reverse direction: Explore → **Loki** → `{service="app"} |= "chain finished"` → expand a line
   → **View trace**.

**Question:** the `/hello` server spans belong to the *same trace* even though they're separate
HTTP requests. What carried the trace ID across? (Hint: look for the `traceparent` header.)

## Lab 6: Record and read a JFR file

1. Run the k6 test or Lab 1 for a minute to generate activity.
2. Dump without stopping the app:
   ```bash
   docker run --rm --pid=container:app eclipse-temurin:25-jdk \
     jcmd 1 JFR.dump name=1 filename=/jfr/lab6.jfr
   ```
3. Summarise and list GC events:
   ```bash
   docker exec app jfr summary /jfr/lab6.jfr
   docker exec app jfr print --events jdk.GarbageCollection /jfr/lab6.jfr | head -40
   ```
4. Open `docker/app/jfr/lab6.jfr` in JDK Mission Control → *Memory → Allocations*. Can you find
   `MemoryController.allocate` and `LeakService` as top allocators?

## Lab 7: Make every alert fire

| Alert | How to trigger it |
|---|---|
| `AppDown` | `docker compose stop app`, wait ~45 s, then `docker compose start app` |
| `HeapUsageHigh`, `LiveDataAfterGcHigh` | Lab 3: start the leak and stop it around 180 MB of `approxBytes` |
| `GcPauseTimeHigh` | Switch to `-XX:+UseSerialGC`, then hammer `/lab/allocate?mb=200&chunkKb=64` in a loop |
| `Http5xxRateHigh` | `for i in $(seq 1 600); do curl -s "localhost:8080/lab/fail?rate=0.3" >/dev/null; sleep 0.2; done` |

Watch each one go *pending → firing* in Prometheus → Alerts, then appear grouped in
Alertmanager (:9093). Try **silencing** one in Alertmanager.

---

# Part IV: Reference

## File map

| Path | Purpose |
|---|---|
| `Dockerfile` | Multi-stage build; `exec java $JAVA_OPTS` entrypoint |
| `.dockerignore` | Keeps `target/`, `.git/`, `.idea/`, `docker/` out of the build context |
| `.env.example` → `.env` | `JAVA_OPTS`: collector, heap size, OOM handling, GC log, JFR |
| `docker-compose.yml` | All services, limits, mounts, the `load` profile |
| `pom.xml` | Boot 3.5, Java 25, Actuator, Prometheus registry, OTel tracing |
| `src/main/resources/application.yml` | Actuator exposure, histograms, JSON logs, tracing |
| `controller/HelloController` | `/hello`, the simplest endpoint |
| `controller/MemoryController` | `/lab/allocate`: short-lived allocation (churn or humongous) |
| `controller/LeakController` + `service/LeakService` | `/lab/leak/*`: a controllable leak |
| `controller/ChainController` | `/lab/chain`: multi-span trace with logs |
| `controller/FailureController` | `/lab/fail`: controllable HTTP 500s for the 5xx alert |
| `config/ObservabilityConfig` | Keeps `/actuator/**` out of traces and HTTP metrics |
| `docker/prometheus/prometheus.yml` | Scrape target, rule file, Alertmanager address |
| `docker/prometheus/alerts.yml` | Alert rules |
| `docker/alertmanager/alertmanager.yml` | Routing and receivers (UI only) |
| `docker/loki/loki-config.yml` | Single-node Loki, filesystem storage |
| `docker/alloy/config.alloy` | Log collection pipeline |
| `docker/tempo/tempo.yml` | OTLP receivers, local trace storage |
| `docker/grafana/provisioning/` | Datasources (with cross-links) and dashboard loading |
| `docker/grafana/dashboards/jvm-overview.json` | The JVM Overview dashboard |
| `docker/k6/scripts/load-test.js` | Load test scenarios |
| `docker/app/{gc-logs,jfr,dumps}/` | Output from the JVM (gitignored) |

## Endpoints

| Method + path | Does |
|---|---|
| `GET /hello` | Returns a string, logs one line |
| `GET /lab/allocate?mb=200&chunkKb=1024` | Allocates `mb` MB in `chunkKb` KB arrays, then drops them. Defaults are humongous with 1 MB regions |
| `POST /lab/leak/start` | Starts appending 256 KB every 100 ms to a retained list |
| `POST /lab/leak/stop` | Stops appending; the memory stays retained |
| `POST /lab/leak/clear` | Drops the references; the next GC can reclaim them |
| `GET /lab/leak/status` | `{running, chunkCount, approxBytes}` |
| `GET /lab/chain` | Calls `/hello` twice with simulated work in between (one trace, five spans) |
| `GET /lab/fail?rate=1.0` | Throws an exception (HTTP 500, ERROR log) with probability `rate` |
| `GET /actuator/prometheus` | Metrics, scraped by Prometheus |
| `GET /actuator/health` | Health details |

## Troubleshooting

| Symptom | Cause and fix |
|---|---|
| `required variable JAVA_OPTS is missing a value` | `cp .env.example .env` |
| `bind: address already in use` on 8080 | The app is also running on your Mac (IDE or `mvn spring-boot:run`). Stop it |
| Prometheus target still shows the old address | Prometheus reads its config at startup: `curl -X POST localhost:9090/-/reload` |
| App container up but HTTP hangs | Zombie after OOM. Check `docker logs app` for `OutOfMemoryError`; make sure `.env` has `ExitOnOutOfMemoryError` |
| `continuous.jfr` is 0 bytes | JFR only writes it on a clean exit; use `JFR.dump` (section 10) for a live copy |
| No logs in Loki | http://localhost:12345: is `loki.source.docker` healthy? Is Docker Desktop's socket at `/var/run/docker.sock`? |
| No traces in Tempo | `docker logs app` for OTLP export errors; is `tempo` up? |
| Grafana shows old datasources | `docker compose up -d --force-recreate grafana` |
| No new heap dump | The JVM doesn't overwrite `java_pid1.hprof`; delete the old one |

## Glossary

- **Allocation rate**: bytes per second your code creates with `new`.
- **Card table**: G1/Parallel bookkeeping: one byte per 512-byte heap "card", marked dirty when a
  reference inside it changes.
- **Concurrent**: done by GC threads while application threads keep running.
- **Eden**: young-generation space where new objects are allocated.
- **Evacuation**: copying live objects out of a region so the whole region can be freed.
- **Full GC**: a stop-the-world collection of the entire heap; a last resort for G1.
- **GC root**: a starting point for reachability: stack variables, static fields, JNI references.
- **Humongous object**: in G1, an object of at least half a region; allocated straight into
  old-generation regions.
- **IHOP**: Initiating Heap Occupancy Percent; the old-gen occupancy at which G1 starts concurrent
  marking.
- **JIT**: just-in-time compiler that turns hot bytecode into native code (C1, then C2).
- **Live data**: objects still reachable after a collection.
- **Metaspace**: native memory holding class metadata; not part of the heap.
- **Mixed GC**: a G1 pause that collects young regions plus some old regions.
- **Promotion**: moving an object that survived enough young GCs into the old generation.
- **Region**: G1's unit of heap management; 1 MB here.
- **Remembered set**: per-region record of references pointing into that region from elsewhere.
- **RSS**: resident set size; the real memory a process occupies, what `docker stats` shows.
- **Safepoint**: a point where a thread can be safely paused for GC.
- **Span / trace**: one timed step / the tree of steps for one request.
- **Stop-the-world (STW)**: a pause where all application threads are frozen.
- **Survivor space**: young-generation space for objects that survived at least one young GC.
- **Tenuring threshold**: number of young GCs an object must survive before promotion.
- **TLAB**: thread-local allocation buffer; a private slice of Eden that makes `new` lock-free.
