# PatientCare v4


[![Scc Count Badge](https://sloc.xyz/github/wardle/pc4)](https://github.com/wardle/pc4/)
[![Scc Cocomo Badge](https://sloc.xyz/github/wardle/pc4?category=cocomo&avg-wage=100000)](https://github.com/wardle/pc4/)

PatientCare v4 is a new type of electronic health and care patient electronic record system. 

Previous versions have been in constant use within NHS Wales, UK since 2008, 
helping support the care of a range of patients with both neurological and 
non-neurological disorders.

Version 4 brings together a suite of loosely-coupled modules and a high
degree of interoperability with other health and care systems. 

It is a work-in-progress, but the much of the backend components are now complete. 
The front-end applications are small and highly modular, while providing the 
appearance of a single seamless system.

There is a high degree of separation between user-facing applications and 
underlying data and computing services, with adoption of a range of 
health and care technical standards.

The applications simply ask for data in the structure and format that best suits their need, and the backend services provide those data. Many of the examples use HL7 FHIR data models. 

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

# Components

A core principle is that user-facing applications should be smart in
user interactions but dumb in terms of business logic; the latter are
delegated to the backend server.

Fetching data from the backend uses a graph API. Changes are made using
events, sent to the server.

## pc4-server

This is the main server component, providing an API for graph-like queries for reads and writes. Data is pulled into
the application, and events are streamed to the server in order to perform effects such as login or recording new data.

## pc4-ward

This repository is the first substantial user-facing component. 
It is designed to provide the workflow of making an electronic referral.




