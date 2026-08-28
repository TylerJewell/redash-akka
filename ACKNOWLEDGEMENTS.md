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

**The change this port made to it is eleven files of 589**, and every one of them is the
data layer only — which is the measure RENDERING.md R3 sets. `client/cypress/`, redash's own
end-to-end suite, is carried unchanged and runs against this rebuild: 152 of its 156 tests
pass, and `bench/REPORT.md` §1 names the four that do not and why:

| File | Change |
|---|---|
| `client/app/services/stream.js` | **new**, 39 lines: `subscribe` opens one `EventSource`; `subscribeToList` keeps the latest frame so a caller can ask for "the rows now" without a request. |
| `client/app/components/items-list/classes/StreamItemsSource.js` | **new**, 93 lines: an items source fed by that stream instead of by a fetch. The pages that use it keep their own columns, search boxes, tag lists and pagers. |
| `client/app/services/alert.js` | 15 lines added: a `subscribe` for the alerts stream. Nothing removed. |
| `client/app/pages/alerts/AlertsList.jsx` | 45 added, 35 removed: the list subscribes instead of fetching, and the items-list controller it fetched through is removed rather than left resolving an empty list. |
| `client/app/pages/groups/GroupsList.jsx` | 2 lines: the items source it constructs, and the import that names it. |
| `client/app/pages/queries-list/QueriesList.jsx` | 2 lines, same shape. The item processor and the four sets the page can be pointed at are kept. |
| `client/app/pages/dashboards/DashboardList.jsx` | 2 lines, same shape. |
| `client/app/pages/users/UsersList.jsx` | 2 lines, same shape. The three sets the page can be pointed at are kept. |
| `client/app/pages/query-snippets/QuerySnippetsList.jsx` | 2 lines, same shape. |
| `client/app/pages/data-sources/DataSourcesList.jsx` | 12 lines: the list subscribes; the type catalogue is still one fetch, because it is a property of the deployment rather than state the server owns. |
| `client/app/pages/destinations/DestinationsList.jsx` | 14 lines: the same, plus the re-fetch after a create removed — the stream already carries the new row. |

Two more files differ that are not the data layer, and both are deployment configuration
rather than behaviour: `client/cypress/seed-data.js` reads the address of the database its
fixture seeds a data source with from the environment, defaulting to redash's own value,
because redash's suite runs inside a compose network and this rebuild is driven from the
host; and `cypress.config.js` is carried from the clone's root rather than from `client/`.

Measured file by file with line endings normalised, over all 589 files under
`frontend/client`: 578 are byte for byte the original's, nine differ, and two are new.
Everything else — components, styling, routes, assets, layout, the whole `viz-lib` — is the
original's.

**`redash-akka/src/main/resources/static-resources/`** is the webpack output of that front
end: the same code, compiled. It is a build artefact of the directory above rather than a
second copy.

**`redash-akka/src/main/resources/templates/`** is redash's own server-rendered pages,
copied from `redash/templates/`: the login, setup, invitation, reset and verification pages,
the shared signed-out layout, the error page, and the four account emails with their shared
layout. They are rendered here by a Jinja subset written for this port (`api/Jinja.java`),
not by Jinja.

**`redash-akka/src/main/resources/disposable-email-domains.txt`** is the domain list the
`disposable_email_domains` package ships, exported from a running redash — 4,055 domains.
redash reads it from that package rather than declaring it, and R29 refuses an invitation to
an address in it, so the list is data this port needs and did not write. The package is
public domain (CC0).

**`redash-akka/src/test/resources/from-redash/*.json`** and the three
`alert-*-from-redash.txt` files are tables of answers **produced by running redash**, not
copied from its source: the 75 data source schemas its registry answers, the 12 destination
schemas, the state its `Alert.evaluate` reached for each of 89 inputs, the notification
decision for each of 54, and what its `check_alerts_for_query` left an alert as for each of
18. They are redash's behaviour written down, which is the whole point of a port.

## Not copied

The Java under `redash-akka/src/main/java/` was written for this port. The strings it shares
with redash are the ones this section exists to name:

| String | Why it is in both |
|---|---|
| Every configuration schema in `queryrunner/Registry.java` and `destinations/Destinations.java` | 75 data source types and 12 destinations, each with the property names, titles, defaults, `required`, `secret`, `order` and `extra_options` redash declares. These are **wire formats**: the front end draws every connection form from them, so a port that reworded a title ships a form nobody can fill in. Compared against the original's own registry by `RegistryTest`. |
| `sq:executed_at` | The redis key redash keeps its execution tracker under (`ScheduledQueriesExecutions.KEY_NAME`). The port keeps the same name for the same thing so that a reader of both sees one concept rather than two. |
| `query_hash_job` | The prefix of redash's in-flight lock key. Same reason. |
| `greater than`, `less than`, `equals` | Three of the nine operator names an alert can be configured with. These are the configuration vocabulary a stored alert carries, so a port that spelled them differently could not read one. |
| `unknown`, `ok`, `triggered` | The three alert states, likewise stored values rather than prose. |
| Every field name in every serialiser (`api/Serializers.java`) | The JSON redash's own front end expects. A wire format is a vocabulary both ends must agree on, and this port ships that front end. |
| Every route path in `api/*Endpoint.java` | Likewise: the front end builds these URLs, and the walk in `bench/REPORT.md` compares the two systems at them. |
| Every refusal message — `Can't modify built-in groups.`, `Email already taken.`, `Bad email address.`, `Page is out of range.`, `Public URLs are disabled.`, `Must provide current password to update password.`, and the rest | A person reads these in the interface. They are reproduced word for word, and walk steps 10, 11, 15, 16, 17, 21, 27, 28, 29, 32, 76 and 115 are what checks that. |
| `The browser (or proxy) sent a request that this server could not understand.` and `The requested URL was not found on the server. If you entered the URL manually please check your spelling and try again.` | Werkzeug's own wording, which redash answers when a handler refuses without a message. Reproduced because a caller reading the text sees it; checked by walk steps 18 and 135. |
| `YYYY-MM-DD` | The format a schedule's `until` is stored in. `Schedule`'s javadoc names it so a reader knows what the string holds; redash's date parameters name the same format for their own reason. Two systems arriving at the same phrase, not a copy. |
| `SELECT ...` fragments in tests and benchmarks | SQL written for this port's fixtures. They coincide with redash's own test SQL because `SELECT 1` is what everybody writes. |

### Every shared string, by class

`python toolkit/copied_strings.py redash --source redash-src` reports every literal of ten
characters or more that occurs in both, with `frontend/` and the bundler's output excluded by
`.vendored`. On this port that is **1,076 strings, 1,042 of them not named one at a time
above** — a whole system shares a whole vocabulary with the system it reproduces. A file with
1,042 sentences in it is the failure the method's own step d names: a hundred findings nobody
will read has the same effect as no check.

So every one of the 1,042 is assigned to a class that is true of all its members, and the
class carries the sentence. `python redash-port/bench/classify_copied_strings.py` does the
assigning and prints, individually, any string no class claims — the counts below are its
output rather than an estimate, and the list it is computed from is committed at
`redash-port/bench/copied-strings.json` so any single string can be traced.

| | Class | Why it is in both |
|---:|---|---|
| 264 | the 75 data source configuration schemas | Wire formats: the front end draws every connection form from them, so a reworded title ships a form nobody can fill in. |
| 218 | the names of environment variables and organisation settings | A deployment sets these. A port that renamed one would silently ignore a configured value. |
| 217 | route paths, field names and stored values the front end reads | The front end is redash's own and builds these URLs and reads these fields. |
| 180 | the vocabulary the domain and the store share | Alert states, operator names, table names, the redis key prefixes — values that are stored and read back, not prose. |
| 38 | field names and stored values, wherever else they are read | The same vocabulary, in files the classes above do not cover. |
| 32 | the 12 destination configuration schemas | The same argument as the data source schemas. |
| 29 | what a destination puts on the wire | Compared against the original's own, thirty cases, in `DeliveryTest`. |
| 28 | the SQL a runner sends to read a schema | Each runner's schema query is what that database answers; a different query answers a different question. |
| 10 | what the command line prints, and the words it is driven with | A command line is read by people and by scripts, so the text is the interface. |
| 10 | wording a person reads, reproduced word for word | Refusals and page text. Compared at the walk steps listed above. |
| 5 | the names of HTTP headers | `Content-Type`, `Authorization` and three more. Neither system's. |

Eleven the classifier leaves for a reader, each here so nobody has to look them up:
`--password` and `create_root` and `create_tables` are how this port's own tests drive its
command line, which is redash's command line; `Active: True`, `Name: admin`,
`Organization: default`, `Slug: default`, `Type: builtin`, `Type: sqlite` and
`already an admin` are lines that command line prints, asserted word for word;
`Authorization`, `X-Frame-Options` and `X-Forwarded-Remote-User` are HTTP header names.

**`copied_strings.py` still exits 1 on this port, and will until it can be told about a
class.** It matches a literal against the text of this file, so a class cannot satisfy it and
1,042 sentences would. The change it needs is written up in
`redash-port/method/proposal.md` with an implementation ready to move.

## Behaviour is derived

Plainly: yes, and that is the point. Every rule in `specs/SPEC-001-redash.md` describes what
redash does, established by running redash and written down — the boundary arithmetic, the
hash normalisation, the sweep's skip rules, the execution tracker's precedence, the alert
state machine, the notification decision, the access matrix, the parameter validator, the
automatic row limit and the whole HTTP surface are all redash's, reproduced. Three rules are
not: R154 and R155 are decisions this port had to be given because redash has no
corresponding behaviour, and D-4 through D-7 in the specification name the four places where
the target could not reproduce the mechanism and the observable answer was reproduced
instead. `bench/rules-not-compared.json` says which of those could not be compared and why.

## Also used

- **Akka** — the SDK and runtime this port is built on, `io.akka:akka-javasdk-parent:3.6.3`.
- **PostgreSQL JDBC driver** — `org.postgresql:postgresql:42.7.4`, BSD 2-Clause.
- **MySQL Connector/J** — `com.mysql:mysql-connector-j:9.1.0`, GPL-2.0 with the Universal
  FOSS Exception.
- **Microsoft JDBC Driver for SQL Server** — `com.microsoft.sqlserver:mssql-jdbc:12.8.1`, MIT.
- **SQLite JDBC** — `org.xerial:sqlite-jdbc:3.47.1.0`, Apache-2.0.
