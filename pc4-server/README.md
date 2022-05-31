# pc4-server

`pc4-server` provides a loose coupling of services under a single server, with
a graph API provided by [pathom3](https://github.com/wilkerlucio/pathom3), 
composing multiple backend libraries.

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

# Runnning a server

To run in a development environment:

```shell
clj -X:run :profile :dev
```

To run in a production environment (this uses :cvx)

```shell
clj -X:run :profile :cvx
```

Some configuration options are customisable at runtime using environmental
variables. For example:

```shell
PORT=9000 clj -X:run :profile :dev
```

View dependencies:
```shell
clj -X:deps tree
```
