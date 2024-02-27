# PatientCare v4

[![Scc Count Badge](https://sloc.xyz/github/wardle/pc4)](https://github.com/wardle/pc4/)
[![Scc Cocomo Badge](https://sloc.xyz/github/wardle/pc4?category=cocomo&avg-wage=100000)](https://github.com/wardle/pc4/)

PatientCare v4 is an electronic health and care patient electronic record system. 

Previous versions have been in constant use within NHS Wales, UK since 2008, 
helping support the care of a range of patients with both neurological and 
non-neurological disorders.

Version 4 brings together a suite of loosely-coupled modules and a high
degree of interoperability with other health and care systems. 

It is a work-in-progress, but the many of the backend components are now complete. 
The front-end applications are small and highly modular, while providing the 
appearance of a single seamless system. The first applications will be broken-up
and common functionality provided in client libraries.

As a result, this is still in an exploratory phase. The core API is a graph-like 
API for read operations, with 'commands' (mutations) used for write operations. 

Even though I expect many of the implementation details to change, the 
basic principles will not.

There is a high degree of separation between user-facing applications and 
underlying data and computing services, with adoption of a range of 
health and care technical standards.

The applications simply ask for data in the structure and format that best suits their need, and the backend services provide those data. Many of the examples use HL7 FHIR data models at the moment, 
but I have used the same approach to deliver the same data as openEHR archetypes.

* SNOMED CT as a *lingua franca*, as provided by [hermes](https://github.com/wardle/hermes).
* Organisational and geographical data and computing services, as provided by [clods](https://github.com/wardle/clods)
* Integration with UK NHS data and computing services, as provided by [concierge](https://github.com/wardle/concierge) including
    * Staff authentication and authorisation
    * Staff information lookup (photo/job role/title etc)
    * Patient lookup using the national enterprise master patient index
    * Patient Administrative System (PAS) integration within individual health boards
    * Local and national document repository integration - for persistence of clinical documentation
    
The principles are:

* a focus on data : immutable and standards-based
* open 
* modularisation
* first class identifier resolution and mapping
* graph-based queries
* loose-coupling
* clear separation of data, logic and user interface; with user facing applications
lightweight, ephemeral and focused on workflow and process, providing multiple
  user centric views of the same data.
* different semantics for reading data comparing to writing data; we read using a graph API across disparate federated datasets and write using an event model.


The server provides a loose coupling of services under a single server, with
a graph API provided by [pathom3](https://github.com/wilkerlucio/pathom3), composing multiple backend libraries.

It is designed to support lightweight client applications which send declarative
events via an endpoint `/api` once authenticated.

It provides the following integrations:

* SNOMED CT (via [hermes](https://github.com/wardle/hermes))
* UK dictionary of medicines and devices (via [dmd](https://github.com/wardle/dmd))
* Deprivation indices (via [deprivare](https://github.com/wardle/deprivare))
* UK health and care organisational reference data (via [clods](https://github.com/wardle/clods))
* GP surgery general practitioners (via [ods-weekly](https://github.com/wardle/ods-weekly))
* NHS Wales' integration (via [concierge](https://github.com/wardle/concierge))
* Geographical reference data (via [nhspd](https://github.com/wardle/nhspd))
* Legacy rsdb ([PatientCare v3](https://github.com/wardle/rsdb))

The core abstraction is a property graph.

Properties are namespaced and form triples, just like RDF. Most entities
are represented by loosely-collected sets of properties. Usually, these will
be flattened as much as possible but they can be nested.

The schemas define how these properties can be collected, defining both a
reference model and then more dynamic aggregates of properties. I have worked
on tooling to take other health and care standards such as HL7 FHIR and
openEHR, to create a property-based abstraction. This is still in development.

The services are loosely-coupled and configured by [resources/config.edn](resources/config.edn).
This essentially injects the dependencies of different subsystems making their
services available in other modules via [integrant](https://github.com/weavejester/integrant).

Secrets are expected to be in ~/.secrets.edn


# Running front-end(s)

```shell
bb watch-cljs
```

```shell
bb watch-css
```

These will continually monitor and rebuild js and css files for any front-ends.

The server currently monitors these builds automatically and uses either a 
development build, or production build, depending on what was built most 
recently. 

# Running a server

You would usually run a server from the REPL. See the `dev` directory.

But you can also run from the command line:

Run a development environment:

```shell
clj -X:run :profile :dev
```

To run in a production environment (this example uses :cvx)

```shell
clj -X:run :profile :cvx
```

To run any pending database migrations

```shell
clj -X:migrate :profile :dev
```


Some configuration options are customisable at runtime using environmental
variables. For example:

```shell
PORT=9000 clj -X:run :profile :dev
```

To build an uberjar

```shell
clj -T:build uber
```

To run on development machine from uberjar:
```shell
java --add-opens "java.base/java.nio=ALL-UNNAMED" --add-opens "java.base/sun.nio.ch=ALL-UNNAMED" -jar target/pc4-server-1.0.1726.jar dev
```

To run in production:

```shell
java --add-opens "java.base/java.nio=ALL-UNNAMED" --add-opens "java.base/sun.nio.ch=ALL-UNNAMED" -jar target/pc4-server-1.0.1726.jar pc4
```

To run on combinations of architectures and operating systems that are not supported by the built-in native lmdb binaries, 
you can install your own lmdb library and use that:

e.g. on FreeBSD:

```shell
$ pkg info -lx lmdb | grep liblmdb

	/usr/local/lib/liblmdb.a
	/usr/local/lib/liblmdb.so
	/usr/local/lib/liblmdb.so.0
```

And then run like this:

```shell
java -Dlmdbjava.native.lib=/usr/local/lib/liblmdb.so -jar pc4-v1.0.815-921-g59b12ca.jar --profile pc4 validate
```

In some constrained environments, that make running from source code difficult, the server
runs a socket REPL on port 5555.

As such, you can then connect to that REPL and invoke certain developer-time functions.

e.g.
```shell
nc localhost 5555
=>
user=> (require '[com.eldrix.pc4.modules.dmt])
user=> (com.eldrix.pc4.modules.dmt/export {:profile :dev :centre :cardiff})
```

# Developer information

Install babashka, and run

```shell
bb tasks
```

for commonly needed development requirements.

For example, it would be usual to run shadow-cljs and to run a server REPL:
```shell
bb watch-cljs
bb nrepl
```

### Current status

This is a work-in-progress. 

I am principally porting functionality from 
PatientCare v3 into this new architectural design. The immediate priorities
are of an electronic referral system for inpatient liaison services across
multiple organisations and an e-observations module for the identification
of the deteriorating patient. These modules are not present in PatientCare v3,
so complement that legacy application. The idea is to build the new application in
parallel with the old, with both running together and newer functionality
gradually replacing the old as time progresses. This is the Martin Fowler
['strangler' pattern](https://martinfowler.com/bliki/StranglerFigApplication.html), 
which suits me a lot, as a single developer working in my spare time, I cannot 
hope to deliver a big-bang re-write. Instead, I can deliver incrementally.

# Components

A core principle is that user-facing applications should be smart in
user interactions but dumb in terms of business logic; the latter are
delegated to the backend server.

Fetching data from the backend uses a graph API. Changes are made using
events, sent to the server.

At the moment, this component does not initialise or create any database, expecting a fully initialised database for usual
operation as part of module 'rsdb'. This is because it is designed to 'wrap' the legacy PatientCare v3 application which
currently is responsiblefor database structure and migration. pc4-server can be used without 'rsdb', but at least currently
service and project membership relies on legacy rsdb information. 

Once pc4 provides most of the functionality available in rsdb, and I am happy that all new development will use pc4 and not
rsdb, I will switch the database initialisation and migration to pc4. 

*Mark*






