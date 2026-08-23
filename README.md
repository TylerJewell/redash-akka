# redash-akka

Runs saved database queries on a schedule, keeps their answers, and raises an alarm when an
answer crosses a line somebody drew.

A port of [getredash/redash](https://github.com/getredash/redash) onto **Akka**, built with
**Akka Specify**.

---

## Where it came from

redash is a tool for asking questions of a database and putting the answers on a screen. It
was ported to derive a specification format precise enough to regenerate a system on a
different stack — the port is the vehicle, the specification is the deliverable.

The specifications the port was generated from are in
[TylerJewell/akka-specify-harness](https://github.com/TylerJewell/akka-specify-harness) under
`redash-port/`.

---

## getredash/redash → this port

📉 542 Python lines → **1,176 Java lines**<br>
📁 6 files → **16 files**<br>
✅ 238 answers compared → **238 agree**<br>
🧪 12 tests → **97 tests**<br>
⚡ 850 nanoseconds → **6 nanoseconds**, deciding whether a query is due<br>
⚡ 842 nanoseconds → **636 nanoseconds**, working out a query's fingerprint<br>
⚡ 202 nanoseconds → **7 nanoseconds**, deciding what an alarm should say<br>
🖥️ 3 processes → **1 process**

Full method and the numbers that did *not* make this list:
[`bench/REPORT.md`](https://github.com/TylerJewell/akka-specify-harness/blob/main/redash-port/bench/REPORT.md).

---

## What it took to build

⏱️ **2.8 hours** from the first command to the published repository, **2.7** of them active<br>
💬 **690** exchanges with the model<br>
✍️ **664,868** tokens written by the model, **273,619,665** counting everything sent and re-sent<br>
🙋 **0** questions to a human<br>
🧪 **97** tests

```bash
python toolkit/tokens.py --port redash    # turns, tokens, elapsed and active time
```

The record of every question, and where the time went, is in
[`port-log/`](https://github.com/TylerJewell/akka-specify-harness/tree/main/port-log).

---

## What it does

From the specification:

- **A query that has never run is due straight away.** No arithmetic happens; the first
  sweep that sees it runs it.
- **A query sitting exactly on its next start time is not due yet.** It becomes due the
  instant after, which is the difference between running at nine o'clock and running on the
  sweep after nine o'clock.
- **Every failed run in a row doubles the wait before the next attempt.** One failure adds
  two minutes, two failures add four, and a single success wipes the count out entirely.
- **Two saved queries whose text differs only by a bracketed note share one answer.**
  Bracketed notes, extra spaces, tabs and line breaks are all removed before the text is
  fingerprinted, so both queries are answered by one run.
- **A note that runs across two lines is not removed.** It stays in the text, so a query
  carrying one is a different question from the same query without it.
- **The time a query last started is kept apart from its last answer, and is read first.**
  A run that began and never finished still counts as having started, so the same query is
  not started twice while the first attempt is still going.
- **An alarm changes what it says even when nobody is told.** Silence is about the message,
  never about the state: an alarm that has been switched to quiet still knows it went off.
- **An alarm that is already sounding does not sound again** until as long as it was told to
  wait has passed. A wait of zero means never again, not immediately.
- **An answer too large to keep is refused**, the run counts as a failure, and whatever was
  already kept for that query is left alone.

---

## Design decisions

**The answer travels with the message.** When a run finishes, the answer it produced is
handed straight to the alarm check rather than looked up again, because a lookup can return
the previous answer for a moment. The alarm always judges the answer that was just produced,
never the one before it.

**One list of start times, not one per query.** All the start times live in a single place
that the sweep reads in one go, the way the original keeps them, because reading them one
query at a time would be a hundred separate reads and none of them would be of the same
instant. The sweep sees one consistent picture of what has already started.

**The screen is fed, not asked.** The page showing the alarms holds one open line to the
service and is sent changes as they happen, instead of asking again every few seconds. A
page left open with nothing changing costs nothing at all, and a change reaches it in under
a quarter of a second.

**Every skipped query says why it was skipped.** The sweep reports a reason for each query it
passed over rather than quietly moving on, because one of those reasons is not a skip at all —
a start time nobody can read gets switched off permanently, and a silent sweep would hide it.

**The original's screen, not a new one.** The page this port shows is redash's own, taken
from redash and changed in exactly two files, both of which only decide where the page gets
its data. Comparing two screens means something when only one thing about them changed.

---

## Running it — the short path

You do not need Java, Maven, or the Akka CLI installed. Akka Specify installs them for you.

**1. Install Akka Specify** in Claude Code:

```
/plugin marketplace add akka/ai-marketplace
/plugin install akka@akka-ai-marketplace
```

Restart Claude Code when it asks.

**2. Give it this prompt:**

> Clone https://github.com/TylerJewell/redash-akka into a new directory and open it.
> Then run /akka:setup to install everything this project needs, and /akka:build to
> compile it, run the tests, and start it locally.

**3. Open** http://localhost:9073/alerts.

---

## Running it — the developer path

### Requirements

- Java 21 or newer
- Maven 3.9 or newer
- An Akka download token — run `akka code token` once
- A PostgreSQL database for the queries to run against

### Start the database the queries run against

```bash
docker run -d --name redash-akka-pg -p 55432:5432 \
  -e POSTGRES_PASSWORD=postgres -e POSTGRES_USER=postgres -e POSTGRES_DB=redash_test \
  postgres:16-alpine
```

### Start the service

```bash
mvn compile
akka local run
```

The service starts on **port 9073**. The alarms page is at http://localhost:9073/alerts.

### Save a query, put an alarm on it, and run a sweep

```bash
curl -X POST localhost:9073/api/queries/q1 -H 'content-type: application/json' \
  -d '{"queryText":"SELECT 10 AS value","dataSourceId":"ds-1","intervalSeconds":3600}'

curl -X POST localhost:9073/api/alerts/a1 -H 'content-type: application/json' \
  -d '{"name":"Orders past the ceiling","queryId":"q1","column":"value",
       "operator":">","threshold":"5","thresholdIsNumber":true}'

curl -X POST localhost:9073/api/sweep
```

### Rebuild the page

The page is built from the sources under `frontend/`, which are redash's own.

```bash
cd frontend && pnpm install --frozen-lockfile --ignore-scripts && NODE_ENV=production npx webpack
cp -r client/dist/* ../src/main/resources/static-resources/static/
mv ../src/main/resources/static-resources/static/index.html ../src/main/resources/static-resources/
```

---

## Configuration

| Variable | Default | Notes |
|---|---|---|
| `REDASH_DATASOURCES` | `ds-1=jdbc:postgresql://localhost:55432/redash_test\|postgres\|postgres` | Where queries run. Several are separated by semicolons, each written as `id=url\|user\|password[\|paused]`. A paused one is passed over by every sweep. |

---

## Where it differs from getredash/redash

Everything not listed here behaves the same way on purpose, including the parts that look
like mistakes.

- **An answer too large to keep.** redash keeps an answer in a database column with no size
  limit it enforces, so an answer of any size is kept. This port refuses one over 1,048,475
  bytes and counts the run as a failure, because the store it is built on refuses a message
  that large and the two alternatives — keeping part of the answer, or keeping a pointer to
  it elsewhere — would both answer an alarm's question with something the query did not
  return. The size was measured, not read: see `probes/target-probe/results.txt` in the
  harness.
- **What somebody watching the screen sees after their connection drops.** redash's page
  asks for the alarms once when it opens and never again, so a page that loses its
  connection shows what it last received until somebody reloads it. This port sends the page
  changes as they happen over one open line, and when that line is re-established the page is
  sent the current state before anything else — so a watcher who dropped out is never left
  holding something that has been superseded. The cost is one redundant message each time the
  line comes back. redash has no behaviour here to copy, so this port was given one.
- **Asking for the same query twice after the first attempt is finished.** In redash, when a
  request finds a marker naming a run that has already finished, failed, been cancelled or
  expired, it clears the marker, starts a replacement, and leaves no marker for the
  replacement — so the very next request starts a third run. This port does the same thing,
  including the third run. It was reproduced rather than repaired because it is behaviour
  redash has settled rather than behaviour it lacks, and a copy that quietly disagreed on the
  third request would not be a copy.
- **The query runners.** redash can read from more than forty kinds of database. This port
  reads from PostgreSQL only, because which database a query runs against is a separate
  question from when the query runs.
- **Query text with values filled in, and automatic row limits.** redash rewrites a query's
  text before fingerprinting it, substituting saved values and appending a row limit. This
  port does neither, and its fingerprint is of the text as written. Not checked: the two
  would differ for any query using those features.
- **Who is told when an alarm goes off.** redash sends email, Slack, PagerDuty and a dozen
  other kinds of message. This port records that a subscriber was told and how many tellings
  failed, and sends nothing. The decision about *who* is told, and what one failing recipient
  costs the others, is the same in both.
- **Signing in.** redash has accounts, groups and permissions. This port has none: the page
  is served to anybody who asks, and the signed-in person it shows is a fixed one. Not
  checked: anything redash's page does differently for a person without permission.
- **Where the schedule is stored.** redash keeps it as free-form data in a database column;
  this port keeps it as named fields. The values are the same and every decision made from
  them agrees, across 29 compared cases. What is not checked is what either system does with
  a schedule carrying a field the other has never seen.
- **The clock a stored answer's age is measured against.** redash asks its database for the
  current time when deciding whether a kept answer is still fresh; this port uses the time
  the caller names. The rule is identical and the four compared cases agree, but two machines
  whose clocks disagree would answer differently.

---

## Licence

getredash/redash is BSD 2-Clause, © 2013-2020 Arik Fraimovich. This port is a derived work
and carries redash's own front end; see `ACKNOWLEDGEMENTS.md`.
