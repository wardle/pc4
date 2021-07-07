(ns com.eldrix.pc4.server.rsdb.auth
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
  "All of the legacy permissions that are used."
  #{:LOGIN,                                                 ;; can this user login?

    :PATIENT_REGISTER,                                      ;; register and de-register patients?
    :PATIENT_EDIT,                                          ;; edit a patient?
    :PATIENT_VIEW,                                          ;; view a patient?
    :PATIENT_VIEWPID,                                       ;; view name and address fields for a patient?
    :PATIENT_MERGE,                                         ;; merge patients
    :PATIENT_FAMILY_MERGE,                                  ;; merge families
    :PATIENT_UNLOCK,                                        ;; unlock a patient record for editing (for data that has an editing time window)
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
  {:POWER_USER            #{:LOGIN,
                            :USER_REGISTER,
                            :USER_EDIT,
                            :DATA_DOWNLOAD,
                            :PATIENT_REGISTER,
                            :PATIENT_EDIT,
                            :PATIENT_VIEW,
                            :PATIENT_VIEWPID,
                            :PATIENT_UNLOCK,
                            :NEWS_CREATE}

   :PID_DATA              #{:DATA_DOWNLOAD_PID},

   :NORMAL_USER           #{:LOGIN,
                            :DATA_DOWNLOAD,
                            :PATIENT_REGISTER,
                            :PATIENT_EDIT,
                            :PATIENT_VIEW,
                            :PATIENT_VIEWPID},

   :LIMITED_USER          #{:LOGIN,
                            :PATIENT_VIEW},

   :BIOBANK_ADMINISTRATOR #{:LOGIN,
                            :BIOBANK_UPLOAD}})

(defprotocol AuthorizationManager
  (can-for-project? [this project-id permission]
    "Does the user have the 'permission' specified for the project?")
  (can-for-any? [this permission]
    "Does the user have the 'permission' for any project?")
  (can-for-patient? [this patient permission]
    "Does the user have the 'permission' for the patient specified?"))

