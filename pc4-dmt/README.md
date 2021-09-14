
pc4-dmt
=======

A customised micro front-end to PatientCare for the monitoring of disease-modifying
drugs in multiple sclerosis.

This is designed to support the capture of longitudinal outcome data and is
suitable for post-marketing surveillance using real-life clinical data.

A micro front-end is a small, simple user-facing application designed to be
composed into a greater whole.

It is designed for use in two ways:

1. As a component of a wider electronic health record, ie used in the process 
   of direct clinical care. 
2. As a standalone registry for centres to use as, what is essentially, an audit
   tool.

In the former case, it uses patient-identifiable data and forms a component of
a wider user-facing application.

In the latter case. it uses de-identified data with pseudonymisation, and operates
standalone.

In both cases, it provides a prism through which to view the more general
health and care record; a prism that carefully makes the right data available
at the right time in the context of multiple sclerosis.

It is designed as a separate module simply because it can provide a standalone
version of the wider PatientCare application but for a limited purpose to solve
a specific need.

A core goal is to provide the same user-facing application that is configured
at runtime to operate in one of the two ways.

In the former case, data are made available through the wider single shared 
record. While users think this is a bespoke database for their needs, it is
simply one perspective on a larger record.

As such, the software code is lightweight and potentially ephemeral; suiting the
computing devices in use at the time of creation. The backend services are
not ephemeral, but simply manipulate data, principally structured through
entity-attribute-value triples rather than rigid class-based hierarchies. 
Most business logic is delegated to backend services, while this code focuses
on creating an excellent user experience.

A core principle is that it is conceivable that all client code is deprecated in
favour of, for example, a native mobile application.