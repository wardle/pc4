# ARAF-clinician module

An ARAF is an 'Annual Risk Acknowledgement Form'.

A clinician needs to manage the ARAF lifecycle to ensure safe prescribing and
monitoring of high risk medications.

This will work in conjunction with the araf-patient-server and araf components
that provide business logic, persistence and user interface for patients to 
complete a specified ARAF. These components are already complete.

The araf-clinician module needs to 

- deal with versioning in and changes over time in the forms mandated by the MHRA
- keep track of a cohort of patients - ie register and discharge
- keep track of drug and ARAF type
- record decision to use drug (this may mean recording decision by two clinicians)
- queue ARAF - e.g. by sent out by post, or by online questionnaire, SMS or email
- record ARAF - e.g. scan, patient scans using QR code immediately in clinic
- track status of every patient
  - all patients - eg. list all known registered patients and status - e.g. "active Vx.y", "overdue", and due date etc.
  - patients with inadequate initiation documentation
  - patients with overdue ARAFs
  - patients with ARAFs due to expire within (x) time period

The business logic, persistence and user interface should be cleanly separated.

The module should be usable in a standalone application, but also readily 
embedded within the larger clinical application (see components 'http-server' 
and 'rsdb'). The problem with 'rsdb' is that it currently conflates persistence
with business logic, and this was a mistake. 

Where possible, we *should* re-use functionality in rsdb - so for example the
patient index, encounters, forms, project registration and discharge. BUT, the 
code within rsdb has design problems and it would probably be better to use this
as an opportunity to reimagine some of the design decisions and architecture. 

Existing forms, for example, have to use the legacy architecture for forms, but
it should be possible to re-imagine how they work.
