# Acknowledgements

This project is a port of **[getredash/redash](https://github.com/getredash/redash)**.

## The original's licence

**BSD 2-Clause.** `redash-src/LICENSE` reads in full:

> Copyright (c) 2013-2020, Arik Fraimovich. All rights reserved.
>
> Redistribution and use in source and binary forms, with or without modification, are
> permitted provided that the following conditions are met:
> 1. Redistributions of source code must retain the above copyright notice, this list of
>    conditions and the following disclaimer.
> 2. Redistributions in binary form must reproduce the above copyright notice, this list of
>    conditions and the following disclaimer in the documentation and/or other materials
>    provided with the distribution.

Read from the file rather than taken from a badge. There is a second licence file,
`LICENSE.borders`, covering map boundary data downloaded from cartographyvectors.com and
used by redash's map visualisations — free for personal and commercial use with
attribution. That data is inside the front end this port carries (see below), so the
attribution is carried with it.

**What that forces on this project.** redash-akka redistributes redash source in the form
below, so it is BSD 2-Clause: the copyright notice and the two conditions above are retained
here and in the repository's own `LICENSE`.

## Copied verbatim

Verbatim-copy: frontend

**`redash-akka/frontend/` is redash's own front end**, copied from the clone at
`client/`, `viz-lib/`, `webpack.config.js`, `package.json`, `pnpm-lock.yaml`,
`pnpm-workspace.yaml` and `.npmrc`. RENDERING.md R3 requires a port to ship the interface
the original already has rather than build its own, and R5's appearance comparison has no
subject otherwise. That is 325 source files under `client/app` alone plus the whole
`viz-lib` workspace, all of it Arik Fraimovich's and redash's contributors', unchanged
except as listed next.

**The change this port made to it is two files**, and both are the data layer only:

| File | Change |
|---|---|
| `frontend/client/app/services/alert.js` | 15 lines added: a `subscribe` function opening one `EventSource` on `api/streams/alerts`. Nothing removed. |
| `frontend/client/app/pages/alerts/AlertsList.jsx` | 45 added, 35 removed: the list subscribes to that stream instead of fetching `api/alerts` once, and the items-list controller it fetched through is removed rather than left resolving an empty list. Sorting and pagination move to local state with the same defaults the controller was configured with. |

Measured with `git diff --no-index` against the clone. Everything else — components,
styling, routes, assets, layout, the whole `viz-lib` — is byte-for-byte the original's.

**`redash-akka/src/main/resources/static-resources/`** is the webpack output of that front
end: the same code, compiled. It is a build artefact of the directory above rather than a
second copy.

**`redash-akka/src/main/resources/static-resources/session.json`** is redash's own
`/api/session` document, captured from a running redash and edited in one place — the
signed-in person's Gravatar URL is blanked, because a capture that waits on gravatar.com is
waiting on somebody else's availability.

**`redash-port/src/test/resources/alert-*-from-redash.txt`** (three files under
`redash-akka/src/test/resources/`) are tables of answers **produced by running redash**, not
copied from its source: the state its `Alert.evaluate` reached for each of 89 inputs, the
notification decision for each of 54, and what its `check_alerts_for_query` left an alert as
for each of 18. They are redash's behaviour written down, which is the whole point of a
port, and they carry no redash text.

## Not copied

The Java under `redash-akka/src/main/java/` was written for this port. The strings it shares
with redash are the ones this section exists to name:

| String | Why it is in both |
|---|---|
| `sq:executed_at` | The redis key redash keeps its execution tracker under (`ScheduledQueriesExecutions.KEY_NAME`). The port keeps the same name for the same thing so that a reader of both sees one concept rather than two. |
| `query_hash_job` | The prefix of redash's in-flight lock key. Same reason. |
| `greater than`, `less than`, `equals` | Three of the nine operator names an alert can be configured with. These are the configuration vocabulary a stored alert carries, so a port that spelled them differently could not read one. |
| `unknown`, `ok`, `triggered` | The three alert states, likewise stored values rather than prose. |
| `triggered`, `muted`, `selector`, `column`, `op`, `value`, `rearm`, `last_triggered_at`, `updated_at`, `created_at`, `state`, `query_hash`, `data_source_id`, `interval`, `day_of_week`, `until`, `disabled` | Field names in the JSON redash's own front end expects, reproduced by `AlertsUiEndpoint` so that front end can read the port. A wire format is a vocabulary both ends must agree on. |
| `/api/organization/status`, `object_counters`, `data_sources`, `dashboards` | The counters redash's page shell asks for before it renders anything, and the keys it reads them under. Reproduced by `AlertsUiEndpoint` because the shell will not render without them — a wire format both ends must agree on, like the field names above. |
| `/dashboards` | One of the front end's own routes. The port serves the page shell there so the sidebar's links do not 404; it is redash's route because it is redash's front end. |
| `YYYY-MM-DD` | The format a schedule's `until` is stored in. `Schedule`'s javadoc names it so a reader knows what the string holds; redash's date parameters name the same format for their own reason. Two systems arriving at the same phrase, not a copy. |
| `query-hash`, `subscriptions/` | Labels this port's benchmark gives its own workloads, in `BenchmarkRunner`. They coincide with redash's `query_hash`-adjacent vocabulary because they name the same thing; nothing was copied. |
| `SELECT ...` fragments in tests and benchmarks | SQL written for this port's fixtures. They coincide with redash's own test SQL because `SELECT 1` is what everybody writes. |

`python toolkit/copied_strings.py redash --source redash-src` is what produced this list. It
reports every literal of ten characters or more that occurs in both, with `frontend/` excluded
by the `Verbatim-copy` declaration above — without that declaration it names 1,593 strings,
every one of them inside the interface this file already accounts for in a sentence, which is
a check nobody would read.

## Behaviour is derived

Plainly: yes, and that is the point. Every rule in `specs/SPEC-001-redash.md` describes what
redash does, established by running redash and written down — the boundary arithmetic, the
hash normalisation, the sweep's skip rules, the execution tracker's precedence, the alert
state machine and the notification decision are all redash's, reproduced. Two rules are not:
R24 and R25 are decisions this port had to be given because redash has no corresponding
behaviour, and `bench/rules-not-compared.json` says so.

## Also used

- **Akka** — the SDK and runtime this port is built on, `io.akka:akka-javasdk-parent:3.6.3`.
- **PostgreSQL JDBC driver** — `org.postgresql:postgresql:42.7.4`, BSD 2-Clause, the one
  query runner this port ships.
