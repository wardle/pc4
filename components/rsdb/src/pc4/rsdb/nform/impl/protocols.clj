(ns pc4.rsdb.nform.impl.protocols
  (:require [clojure.spec.alpha :as s]))

(s/def ::table keyword)
(s/def ::form-pk int?)
(s/def ::id (s/or :uuid uuid? :wo (s/tuple ::table ::form-pk)))
(s/def ::encounter-id int?)
(s/def ::encounter-ids (s/every int? :min-count 1))
(s/def ::patient-id int?)
(s/def ::form-type keyword?)
(s/def ::form-types (s/coll-of ::form-type))
(s/def ::is-deleted (s/nilable boolean?))
(s/def ::fetch-params
  (s/keys :req-un [(or ::id ::patient-id ::encounter-id ::encounter-ids)]
          :opt-un [::form-type ::form-types ::is-deleted]))

(def fetch-params-spec
  (s/get-spec ::fetch-params))

(defprotocol FormStore
  "Definition of a form 'store' with functions to insert/update (upsert), and
  fetch one or many forms."
  :extend-via-metadata true
  (upsert [this form]
    "Insert, or update, a form.
    Parameter:
     - form - a map representing form data")
  (form [this id]
    "Fetch single form by type and id
    Parameter:
    - id  : id of the form, either UUID (nform) or tuple (WebObjects form).

    Example:
    ```
    (form store [:t_form_edss 4454])
    => { ... }
    ```")
  (forms
    [this params]
    "Fetch forms according to parameters specified:

    The specification for the parameters is given as ::fetch-params.

    Parameters:
    |- id           : id of the form - either UUID for an nform or tuple of
    |               : table and primary key for a legacy WebObjects form
    |               : e.g. #uuid \"305a5f64-95ee-4834-8596-a84344d1a08a\" or [:t_form_edss 123]
    |- encounter-id : return forms linked to this encounter
    |- patient-id   : return forms linked to this patient
    |- form-type    : keyword representing form type e.g. :edss/v1
    |- is-deleted   : nil, true or false, to return all forms, deleted forms or
    |               : not deleted forms. All implementations defaults to false,
    |               : so must explicitly set to nil to get both deleted and active forms.

    Parameters may, of course, be combined. Forms may be returned in any order."))
