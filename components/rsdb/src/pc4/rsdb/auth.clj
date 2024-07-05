(ns pc4.rsdb.auth
  "Implementation of the legacy rsdb authentication model.

  Each user is given a role, or multiple roles, within a project.
  That role corresponds to a set of permissions.

  Individual permissions control what a user can and cannot do.

  See https://github.com/wardle/rsdb/blob/master/Frameworks/RSBusinessLogic/Sources/com/eldrix/rsdb/model/PermissionSet.java
  and https://github.com/wardle/rsdb/blob/master/Frameworks/RSBusinessLogic/Sources/com/eldrix/rsdb/model/:java

  Roles are defined in table 't_project_user'. They should not be confused with
  rsdb's earlier 't_role' table which gives a global role across all of rsdb and
  should essentially be entirely removed at some point. Its only purpose now is
  to define which users are system users.")

(def all-permissions
  "All used legacy permissions."
  #{:LOGIN,                                                 ;; can this user login?

    :PATIENT_REGISTER,                                      ;; register and de-register patients?
    :PATIENT_EDIT,                                          ;; edit a patient?
    :PATIENT_VIEW,                                          ;; view a patient?
    :PATIENT_VIEWPID,                                       ;; view name and address fields for a patient?
    :PATIENT_MERGE,                                         ;; merge patients
    :PATIENT_FAMILY_MERGE,                                  ;; merge families
    :PATIENT_UNLOCK,                                        ;; unlock a patient record for editing (for data that has an editing time window)
    :PATIENT_CHANGE_PSEUDONYMOUS_DATA                       ;; change information used to generate pseudonym
    :PATIENT_DELETE,                                        ;; explicitly delete a patient

    :USER_REGISTER,                                         ;; register (or deregister) a user?
    :USER_EDIT,                                             ;; edit other users in this team?
    :USER_VIEW,                                             ;; view other users in this team?

    :TEAM_REGISTER,
    :TEAM_EDIT,
    :TEAM_VIEW,

    :PROJECT_REGISTER,                                      ;; register or deregister projects
    :PROJECT_EDIT,
    :PROJECT_CONSENT,                                       ;; can user consent for this project?
    :PROJECT_VIEW,

    :NEWS_CREATE,                                           ;; administer news for this project
    :NEWS_EDIT,                                             ;; edit existing news for this project

    :TEMPLATE_CREATE,                                       ;; register templates to this project
    :TEMPLATE_EDIT,

    :DATA_DOWNLOAD,                                         ;; download data
    :DATA_DOWNLOAD_PID,                                     ;; download data with PID

    :BIOBANK_UPLOAD,                                        ;; upload a new copy of the biobank?
    :BIOBANK_CREATE_LOCATION,                               ;; create a location
    :BIOBANK_MOVE_SAMPLES,                                  ;; move samples

    :SYSTEM})

(def permission-sets
  {:POWER_USER
   #{:LOGIN,
     :USER_REGISTER,
     :USER_EDIT,
     :DATA_DOWNLOAD,
     :PATIENT_REGISTER,
     :PATIENT_EDIT,
     :PATIENT_VIEW,
     :PATIENT_VIEWPID,
     :PATIENT_UNLOCK,
     :PATIENT_CHANGE_PSEUDONYMOUS_DATA
     :NEWS_CREATE}

   :PID_DATA
   #{:DATA_DOWNLOAD_PID},

   :NORMAL_USER
   #{:LOGIN,
     :DATA_DOWNLOAD,
     :PATIENT_REGISTER,
     :PATIENT_EDIT,
     :PATIENT_VIEW,
     :PATIENT_VIEWPID},

   :LIMITED_USER
   #{:LOGIN,
     :PATIENT_VIEW},

   :BIOBANK_ADMINISTRATOR
   #{:LOGIN,
     :BIOBANK_UPLOAD}})


(defn expand-permission-sets
  "Given a set of what are essentially 'roles', expand into the permissions that
  these roles provide.
  e.g.
  ```
  (expand-permission-sets #{:NORMAL_USER :POWER_USER})
  =>
  ```"
  [sets]
  (into #{} (mapcat permission-sets) sets))

(defprotocol AuthorizationManager
  "Finely-grained authorization, based upon users, roles and projects.
  Given a set of project-ids, for example, representing the set of patient's
  project identifiers, determine whether the user has the permission specified."
  (authorized? [this project-ids permission]
    "Is there authorization for the `permission` for at least one of the
    projects, as specified by a set `project-ids`?")
  (authorized-any? [this permission]
    "Does the user have the 'permission' for any project?"))

