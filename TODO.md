# TODO

Tasks that are currently my priority for this development cycle:

## Tasks prior to deployment to patientcare.app:

* [x] Add 'change registration data' option for pseudonymous patients in case team(s) made a mistake originally
* [x] Add additional permission in order to select which users can change registration information for pseudonymously-registered patients (or just add to power user list?)
* [ ] Finish edit encounter page to at least cover what is deployed currently.
* [ ] Edit and save death certificate information
* [x] Add LSOA name when possible to pseudonymous patient data
* [x] Fix patient sub menus

## Tasks prior to staged / parallel deployment to patientcare.wales.nhs.uk:

* [ ] Minimal form support for at least one clinical service 
* [ ] Messaging between users
* [ ] Encounter wizard
* [ ] Generate documents from encounters; re-use original mechanics for backwards compatibility and then add new template model?

## Other work - not critical to either deployment at the moment

* [ ] Add network connectivity error bar when offline
* [ ] EDSS graph generation
* [ ] Session timeout logic
* [ ] Replace /login endpoint with one using /api that, in absence of session, permits *some* queries such as login, as well as resolving session/authenticated-user, 
so that front-end can resume a live session from the same browser.
* [ ] Switch ods-weekly to a SQLite backend and use to derive lists of GPs for a given surgery