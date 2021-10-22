# pc4-server

`pc4-server` provides a loose coupling of services under a single server, with
a graph API provided by [pathom3](https://github.com/wilkerlucio/pathom3), 
composing multiple backend libraries.

It is designed to support lightweight client applications which send declarative 
events via an endpoint `/api` once authenticated.

It provides the following integrations:

* legacy rsdb ([PatientCare v3](https://github.com/wardle/rsdb))
* SNOMED CT (via [hermes](https://github.com/wardle/hermes))
* UK dictionary of medicines and devices (via [dmd](https://github.com/wardle/dmd))
* Deprivation indices (via [deprivare](https://github.com/wardle/deprivare))
* UK health and care organisational reference data (via [clods](https://github.com/wardle/clods))
* NHS Wales' integration (via [concierge](https://github.com/wardle/concierge))
* Geographical reference data (via [nhspd](https://github.com/wardle/nhspd))

The core abstraction is a property graph.

The services are loosely-coupled and configured by [resources/config.edn](resources/config.edn). 
This essentially injects the dependencies of different subsystems making their
services available in other modules via [integrant](https://github.com/weavejester/integrant).

Secrets are expected to be in ~/.secrets.edn

# Runnning a server

To run in a development environment:

```shell
clj -X:run :profile :dev
```

To run in a production environment (this uses :cav)

```shell
clj -X:run :profile :cav
```

