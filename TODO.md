# TODO

Tasks that are currently my priority for this development cycle:

## Tasks prior to deployment to patientcare.app:

* [x] Add 'change registration data' option for pseudonymous patients in case team(s) made a mistake originally
* [x] Add additional permission in order to select which users can change registration information for pseudonymously-registered patients (or just add to power user list?)
* [x] Edit and save death certificate information
* [x] Add search by patient identifier from project home page
* [ ] Convert current 'edit' encounter page into a view encounter page as per legacy system
* [ ] Add flexible dynamic form system driven by encounter template configurations for at least what is deployed already
* [x] Add LSOA name when possible to pseudonymous patient data
* [x] Fix patient sub menus
* [x] Fix navigation to patient record on patient register to use new routing. Needs current project to be represented in URL.
* [x] Change password page
* [x] Force change password page for newly created accounts

## Tasks prior to staged / parallel deployment to patientcare.wales.nhs.uk:

* [ ] Develop 'composite form' user interface when multiple forms are linked to an encounter and can be edited 'as one'.
* [ ] Minimal form support for at least one clinical service 
* [ ] Messaging between users
* [ ] Encounter wizard, including clinic codes
* [ ] Generate documents from encounters; re-use original mechanics for backwards compatibility and then add new template model?
* [ ] Remove existing but unused 'job' queue system and replace with something off-the-shelf 
* [ ] Fix concierge integration since external changes to services (ABHB and eMPI).

## Other work - not critical to either deployment at the moment

* [ ] Add default project configuration into top-level application configuration, and override (by merge) on per-project basis?
* [ ] Add network connectivity error bar when offline
* [x] Add current project identifier into URL to better support HTML routing (back and forward). Otherwise, one can go 
'back' and not view a patient in context of the same project that they originally used.
* [ ] EDSS graph generation -> basically a Clojure port of existing logic for server side rendering or evaluate front-end graph rendering of EDSS
* [ ] Session timeout logic
* [ ] Replace /login endpoint with one using /api that, in absence of session, permits *some* queries such as login, as well as resolving session/authenticated-user, 
so that front-end can resume a live session from the same browser.
* [x] Switch ods-weekly to a SQLite backend and use to derive lists of GPs for a given surgery
* [x] Upgrade codelists to use new versions of hermes and dmd
* [x] Check that hades uses latest hermes
* [ ] Better user profile page, perhaps shown modally when from context of project team page?
* [ ] Remove passwords in favour of non-password based authentication for non NHS Wales users
* [ ] Add status page in which connections to external systems are checked and status reported

