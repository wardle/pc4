# TODO

Tasks that are currently my priority for this development cycle:

## Tasks prior to deployment to https://patientcare.app:

* [ ] Migrate to htmx
* [ ] Add asynchronous 'job' queue system backed by https://github.com/msolli/proletarian
* [ ] Add flexible dynamic form system driven by encounter template configurations for at least what is deployed already
* [x] Need 'add form' functionality, which will need server-side involvement to autopopulate / initialise a form in context
* [x] Add 'delete form' functionality to front-end
* [ ] Need 'add encounter' functionality, which will need server side involvement to initialise forms and data in context
* [x] Add LSOA name when possible to pseudonymous patient data
* [x] Add 'change registration data' option for pseudonymous patients in case team(s) made a mistake originally
* [x] Add additional permission in order to select which users can change registration information for pseudonymously-registered patients (or just add to power user list?)
* [x] Edit and save death certificate information
* [x] Add search by patient identifier from project home page
* [x] Convert current 'edit' encounter page into a view encounter page as per legacy system
* [x] Fix patient sub menus
* [x] Fix navigation to patient record on patient register to use new routing. Needs current project to be represented in URL.
* [x] Always open patient record in context of a project, but allow easy switching when needed?
* [x] Change password page
* [x] Force change password page for newly created accounts
* [x] Improve 'loading' component so doesn't prevent user interaction when not dimming
* [x] Fix radio buttons so can click on text and select 

## Tasks prior to staged / parallel deployment to https://patientcare.wales.nhs.uk:

* [ ] Need to show and choose responsible user for each form
* [ ] Allow users to add any form not already completed or available in the user interface - e.g. 'add form...' button to open search dialog
* [ ] Allow patient search from top-level and simply open in context of intersection between user's default project and patient's projects
* [ ] Allow quick switch between projects in top-level - e.g. setting user default and then when open a record either choose to register or choose to switch project context
* [ ] Develop 'composite form' user interface when multiple forms are linked to an encounter and can be edited 'as one'.
* [ ] Minimal form support for at least one clinical service 
* [x] Pass through permissions and encounter locked status to forms so they are shown in read-only mode if required
* [ ] Add rich text editor 
* [ ] Edit 'notes' in encounter
* [ ] Messaging between users
* [ ] Encounter wizard, including clinic codes
* [ ] Generate documents from encounters; re-use original mechanics for backwards compatibility and then add new template model?
* [ ] Remove existing but unused 'job' queue system and replace with something off-the-shelf e.g. https://github.com/msolli/proletarian
* [ ] Fix concierge integration since external changes to services (ABHB and eMPI).
* [x] Consider always having a 'current project' switchable at top-level to aid user context and workflow
* [x] Return to using HTML5 routing for pages within patient record. 
* [ ] Improve MS event ordering errors to show a warning against any individual items
* [ ] Use 'close patient record' functionality to clear out any cached data
* [ ] Add more detail for each investigation result as per legacy app, rather than simply a date and notes field
* [ ] Add missing result forms e.g. neutralising interferon antibodies
* [ ] Add thyroid antibodies to the thyroid result page
* [x] Display 'encounter locked' / 'locking in...' on encounter page
* [ ] Add 'lemtrada dmt extract' as first example of new customisable download job - need to have configurable output directory and zip individual files
* [ ] EDSS graph generation -> basically a Clojure port of existing logic for server side rendering or evaluate front-end graph rendering of EDSS

## Other work - not critical to either deployment at the moment

* [x] Migrate to polylith workspace
* [ ] Validate admission dates so date of admission equal to or before date of discharge
* [x] Add resolver for a form by id, as when the front-end 'edit-form' is called, it performs a load which currently mainly fails but satisfied locally as already loaded. 
* [x] Don't show body mass index when age < 18
* [ ] Allow entry of weight and height in other units in form_weight_height
* [ ] Add default project configuration into top-level application configuration, and override (by merge) on per-project basis?
* [ ] Add network connectivity error bar when offline
* [x] Add current project identifier into URL to better support HTML routing (back and forward). Otherwise, one can go 
'back' and not view a patient in context of the same project that they originally used.
* [ ] Session timeout logic
* [ ] Replace /login endpoint with one using /api that, in absence of session, permits *some* queries such as login, as well as resolving session/authenticated-user, 
so that front-end can resume a live session from the same browser. This also means we will be able to create some pages that work without a logged in user.
* [x] Switch ods-weekly to a SQLite backend and use to derive lists of GPs for a given surgery
* [x] Upgrade codelists to use new versions of hermes and dmd
* [x] Check that hades uses latest hermes
* [ ] Better user profile page, perhaps shown modally when from context of project team page?
* [ ] Remove passwords in favour of non-password based authentication for non NHS Wales users
* [ ] Add status page in which connections to external systems are checked and status reported
* [ ] Add slide-out panel in encounter view to make it simple to see encounter list view?
* [ ] Add top-level 'project' context in top bar - and allow configuration of default between 'sticky' ie whatever was set last and by day of week, but allow very rapid project switching.
* [ ] Add patient search by identifier as per legacy app, using either top-level project context or the 'best' intersection between the user's projects and the patient's projects
* [ ] If there are errors in relapse ordering, highlight the individual problem records rather than only listing the errors 

