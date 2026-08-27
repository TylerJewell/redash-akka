# redash-akka

Lets people write questions in SQL, run them against seventy-five kinds of database, put the
answers on shared pages, and raise an alarm when an answer crosses a line somebody drew.

A port of [getredash/redash](https://github.com/getredash/redash) onto **Akka**, built with
**Akka Specify**.

---

## Where it came from

redash is a tool for asking questions of a database and putting the answers on a screen. It
was ported to derive a specification format precise enough to regenerate a system on a
different stack — the port is the vehicle, the specification is the deliverable.

This one is the whole of it, not one capability: every route it serves, every kind of
database it connects to, every setting it reads, every command it offers, and its own web
pages, which this rebuild serves rather than replaces.

The specifications the port was generated from are in
[TylerJewell/akka-specify-harness](https://github.com/TylerJewell/akka-specify-harness) under
`redash-port/`.

---

## getredash/redash → this port

📉 21,353 Python lines → **17,204 Java lines**<br>
📁 168 files → **90 files**<br>
✅ 187 requests compared → **187 agree**<br>
✅ 238 scheduling and alarm answers compared → **238 agree**<br>
✅ 879 recorded answers compared → **879 agree**<br>
✅ 30 alarm messages compared → **30 agree**<br>
🖼️ 8 screens compared → **8 agree**<br>
🧪 156 of the original's own browser tests → **152 pass**<br>
🧪 90 of the original's own unit tests → **89 pass, 1 skipped**<br>
🧪 12 tests → **141 tests**<br>
⚡ 974 nanoseconds → **10 nanoseconds**, deciding whether a question is due to be asked again<br>
⚡ 911 nanoseconds → **712 nanoseconds**, working out a question's fingerprint<br>
⚡ 218 nanoseconds → **7 nanoseconds**, deciding what an alarm should say<br>
⚡ 30 milliseconds → **24 milliseconds**, the middle request of 187<br>
🖥️ 3 processes → **1 process**

Full method and the numbers that did *not* make this list:
[`bench/REPORT.md`](https://github.com/TylerJewell/akka-specify-harness/blob/main/redash-port/bench/REPORT.md).

---

## What it took to build

⏱️ **107.5 hours** from the first command to the published repository, **10.7** of them active<br>
💬 **3,465** exchanges with the model<br>
✍️ **2,925,883** tokens written by the model, **1,689,566,164** counting everything sent and re-sent<br>
🙋 **0** questions to a human<br>
🧪 **141** tests

```bash
python toolkit/tokens.py --port redash    # turns, tokens, elapsed and active time
```

The record of every question, and where the time went, is in
[`port-log/`](https://github.com/TylerJewell/akka-specify-harness/tree/main/port-log).

---

## What it does

From the specification:

- **A question that has never been asked is due straight away.** No arithmetic happens; the
  first sweep that sees it runs it.
- **A question sitting exactly on its next start time is not due yet.** It becomes due the
  instant after, which is the difference between running at nine o'clock and running on the
  sweep after nine o'clock.
- **Every failed run in a row doubles the wait before the next attempt.** One failure adds
  two minutes, two failures add four, and a single success wipes the count out entirely.
- **Two saved questions whose text differs only by a bracketed note share one answer.**
  Bracketed notes, extra spaces, tabs and line breaks are all removed before the text is
  fingerprinted, so both questions are answered by one run.
- **A note that runs across two lines is not removed.** It stays in the text, so a question
  carrying one is a different question from the same one without it.
- **An alarm changes what it says even when nobody is told.** Silence is about the message,
  never about the state: an alarm switched to quiet still knows it went off.
- **An alarm that is already sounding does not sound again** until as long as it was told to
  wait has passed. A wait of zero means never again, not immediately.
- **Who may see a thing is decided by the groups the thing is in, not by who made it.**
  Somebody in one group that may only look cannot change it; somebody who is also in a
  group that may change it can, on the same thing.
- **A saved answer may stand in for a fresh one only while it is young enough.** How young
  is asked per request; minus one accepts any age and zero accepts nothing at all.
- **A value filled into a question is checked against the kind of value it is declared to
  be** before the question is sent anywhere, and a name the question never declared is
  refused with the name in the message.

---

## Design decisions

**The answer travels with the message.** When a run finishes, the answer it produced is
handed straight to the alarm check rather than looked up again, because a lookup can return
the previous answer for a moment. The alarm always judges the answer that was just produced,
never the one before it.

**Numbers for names.** Everything gets a number from a counter that hands out one at a time,
because the original's addresses are numbers and its own pages build addresses out of them.
The pages work unchanged, and the price is that one thing hands out all the numbers of a
kind.

**The screen is fed, not asked.** Every list page holds one open line to the service and is
sent changes as they happen, instead of asking again every few seconds. A page left open
with nothing changing costs nothing at all, and a change reaches it in a fifth of a second.

**The original's screens, not new ones.** The pages this port shows are redash's own, taken
from redash and changed in eleven files out of 589, all of which only decide where a page
gets its data. Comparing two screens means something when only one thing about them changed.

**Every kind of database is listed, whatever is installed.** All seventy-five appear in the
list, their forms draw, and their settings are checked, even where the software needed to
reach one is not present. Which databases a deployment can actually reach is a separate
question from which ones it knows about, and the original's own list is the same everywhere.

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

**3. Open** http://localhost:9156.

---

## Running it — the developer path

### Requirements

- Java 21 or newer
- Maven 3.9 or newer
- An Akka download token — run `akka code token` once
- A database for the questions to run against

### Start a database to ask questions of

```bash
docker run -d --name redash-akka-pg -p 55432:5432 \
  -e POSTGRES_PASSWORD=postgres -e POSTGRES_USER=postgres -e POSTGRES_DB=postgres \
  postgres:16-alpine
```

### Start the service

```bash
export REDASH_COOKIE_SECRET=change-me
mvn compile
akka local run
```

The service starts on **port 9156**. Open http://localhost:9156 and it offers to create the
first account.

### Rebuild the pages

The pages are built from the sources under `frontend/`, which are redash's own.

```bash
cd frontend && pnpm install --frozen-lockfile --ignore-scripts && NODE_ENV=production npx webpack
cp -r client/dist/* ../src/main/resources/static-resources/static/
mv ../src/main/resources/static-resources/static/index.html ../src/main/resources/static-resources/
```

### Run the original's own tests against it

```bash
cd frontend
npx jest --ci                                        # 90 tests of the pages themselves
CYPRESS_baseUrl=http://127.0.0.1:9156 npx cypress run  # 156 tests through a browser
```

---

## The command line

Every command the original offers, at the same names and with the same printed wording.
It reaches a running service rather than the database, and proves who it is with the same
secret every sign-in cookie is signed with.

```bash
export REDASH_COOKIE_SECRET=change-me
java -cp target/classes io.akka.redash.cli.Cli users create_root you@example.com "Your Name" --password secret
java -cp target/classes io.akka.redash.cli.Cli ds list_types
java -cp target/classes io.akka.redash.cli.Cli groups list
```

Seven groups — `database`, `users`, `groups`, `ds`, `org`, `queries`, `rq` — plus `version`,
`status`, `check_settings`, `send_test_mail` and `runserver`.

---

## Configuration

Every one of the 127 settings the original reads, under the same name and with the same
default. The ones a deployment usually sets:

| Variable | Default | Notes |
|---|---|---|
| `REDASH_COOKIE_SECRET` | none — the service refuses to start | Signs every sign-in cookie, every invitation link and every cross-site token. |
| `REDASH_SECRET_KEY` | the cookie secret | Encrypts what a database connection is configured with. |
| `REDASH_HOST` | empty | The address shared links are built from. |
| `REDASH_DATASOURCES` | `ds-1=jdbc:postgresql://localhost:55432/postgres\|postgres\|postgres` | A shortcut for starting with one connection already there. Several are separated by semicolons, each written as `id=url\|user\|password[\|paused]`. |
| `REDASH_MAIL_SERVER`, `REDASH_MAIL_PORT`, `REDASH_MAIL_DEFAULT_SENDER` | `localhost`, `25`, none | Where invitations and alarm messages are sent. With no sender set, an invitation answers a link instead of sending one. |
| `REDASH_ENFORCE_CSRF` | `false` | Whether a request from somebody not signed in must carry a cross-site token. |
| `REDASH_ENABLED_QUERY_RUNNERS` | all 66 | Which kinds of database are offered. `REDASH_ADDITIONAL_QUERY_RUNNERS` adds and `REDASH_DISABLED_QUERY_RUNNERS` takes away. |
| `REDASH_PASSWORD_LOGIN_ENABLED` | `true` | Whether the sign-in form accepts a password at all. |
| `REDASH_REMOTE_USER_LOGIN_ENABLED` | `false` | Whether a header set by something in front of this service signs somebody in. |

`java -cp target/classes io.akka.redash.cli.Cli check_settings` prints all 127 as the
service reads them.

---

## Where it differs from getredash/redash

Everything not listed here behaves the same way on purpose, including the parts that look
like mistakes.

- **What a page shows after its connection drops.** redash's pages ask for their rows once
  when they open and never again, so a page that loses its connection shows what it last
  received until somebody reloads it. This port sends the pages changes as they happen over
  one open line, and when that line is re-established the page is sent everything again
  before any further change — so somebody who dropped out is never left holding something
  that has been superseded. The cost is one redundant message each time the line comes back.
  redash has no behaviour here to copy, so this port was given one.
- **A test of the original's that relies on a page asking again.** Because those pages no
  longer ask, a browser test that answers the question on their behalf has nothing to answer,
  and one of redash's own 156 fails for that reason. Named in `bench/REPORT.md`.
- **The databases this can actually reach.** All seventy-five kinds are listed, their forms
  draw and their settings are checked. Four drivers ship, covering eleven of them; asking any
  of the other sixty-four to run something answers with what is missing rather than failing
  to offer the kind at all. redash decides the same question by whether a piece of software
  happens to be installed, and on the image it ships all but one are.
- **An answer too large to keep.** redash keeps an answer in a database column with no size
  limit it enforces, so an answer of any size is kept. This port refuses one over 1,048,475
  bytes and counts the run as a failure, because the store it is built on refuses a message
  that large and the two alternatives — keeping part of the answer, or keeping a pointer to
  it elsewhere — would both answer an alarm's question with something the question did not
  return.
- **Asking for the same thing twice after the first attempt has finished.** In redash, when a
  request finds a marker naming a run that has already finished, failed, been cancelled or
  expired, it clears the marker, starts a replacement, and leaves no marker for the
  replacement — so the very next request starts a third run. This port does the same thing,
  including the third run. It was reproduced rather than repaired because it is behaviour
  redash has settled rather than behaviour it lacks.
- **Where a message to somebody else's chat product goes.** redash can post an alarm into
  Slack, Discord, Teams, Mattermost, Hangouts Chat, Chatwork and Webex. All seven are still
  offered, their forms still draw and an alarm can still be pointed at one; nothing is sent.
  An integration with a third party's own platform is outside what this port set out to
  rebuild. The other five — email, a web address of your own, PagerDuty, Datadog and Asana —
  send exactly what redash sends, compared message by message.
- **Where the numbers come from.** redash's addresses are numbers from a database counter.
  This port hands them out from one place per kind of thing, so the addresses look the same
  and the pages work unchanged. Under enough simultaneous creation of one kind of thing, that
  one place is the limit. Not checked: how much simultaneous creation it takes to notice.
- **Redirecting a request that arrived without encryption.** redash can be told to send such
  a request to the encrypted address of the same page. This port reads the setting and does
  not act on it, because rebuilding it needs the address that was asked for and a handler
  here is given the request only if it asks for it — sending somebody to the front page
  instead of where they asked for would be worse than not redirecting. Not checked: nothing
  compares the two under that setting.
- **How deep a page address may go.** redash answers its own pages at any depth. This port
  declares five levels, one more than its pages use, and a sixth answers not-found where
  redash would answer the page. Not checked: whether anything ever builds one that deep.
- **How a search finds things.** redash ranks matches with its database's own full-text
  search. This port reproduces the same rule — the same words match, weighted the same way by
  which field they were found in — and works it out in the service instead. Where the two
  could differ is a tie between two equally good matches; this port breaks such a tie by age,
  and redash leaves it to its database.
- **Changing the shape of stored data.** redash ships 29 files that move an older database to
  a newer shape. This port's store has no shape to move, so there is nothing corresponding.
  Not checked: nothing compares an upgrade from an older version.
- **How long a background job is allowed to run, and how long its outcome is kept.** redash
  gives each of its eight repeating jobs a limit and a lifetime, both properties of the queue
  it puts them on. This port has a timer where redash has a queue, so the two settings have
  nothing to act on. The jobs themselves run on the same intervals and decide the same
  things, compared job by job.
- **What a database's own error looks like.** A failure travelling back from PostgreSQL is
  worded by whichever client library is speaking: redash's prints the message, the offending
  line and a caret, this port's prints the message and a character offset. Neither wording is
  either system's. The fact of the failure, and whether it counts as a failed run, are the
  same.
- **The clock a saved answer's age is measured against.** redash asks its database for the
  current time when deciding whether a kept answer is still fresh; this port uses the service's
  own clock. The rule is identical and the compared cases agree, but two machines whose clocks
  disagree would answer differently.

---

## Licence

getredash/redash is BSD 2-Clause, © 2013-2020 Arik Fraimovich. This port is a derived work
and carries redash's own front end; see `ACKNOWLEDGEMENTS.md`.
