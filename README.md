# PatientCare v4

PatientCare v4 is a new type of electronic health and care patient record. 

Previous versions have been in constant use within NHS Wales, UK since 2008, 
helping support the care of a range of patients with both neurological and 
non-neurological disorders.

Version 4 brings together a suite of loosely-coupled modules and a high
degree of interoperability with other health and care systems. 

There is a high degree of separation between user-facing applications and 
underlying data and computing services, with adoption of a range of 
health and care technical standards.

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

# Components

## pc4-server

This is the main server component, providing an API for graph-like queries for reads and writes. Data is pulled into
the application, and events are streamed to the server in order to perform effects such as login or recording new data.

## pc4-ward

This repository is the first substantial user-facing component. 
It is designed as an electronic observations application.
