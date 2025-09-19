(ns pc4.rsdb.nform.impl.protocols
  (:require [clojure.spec.alpha :as s]))

(s/def ::table keyword)
(s/def ::form-pk int?)
(s/def ::id (s/or :uuid uuid? :wo (s/tuple ::table ::form-pk)))
(s/def ::encounter-id int?)
(s/def ::encounter-ids (s/every int? :min-count 1))
(s/def ::patient-pk int?)
(s/def ::form-type keyword?)
(s/def ::form-types (s/coll-of ::form-type))
(s/def ::is-deleted (s/nilable boolean?))
(s/def ::select (s/every #{:date-time}))
(s/def ::fetch-params
  (s/keys :req-un [(or ::id ::patient-pk ::encounter-id ::encounter-ids)]
          :opt-un [::form-type ::form-types ::is-deleted ::select]))

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
    |- id            : id of the form - either UUID for an nform or tuple of
    |                : table and primary key for a legacy WebObjects form
    |                : e.g. #uuid \"305a5f64-95ee-4834-8596-a84344d1a08a\" or [:t_form_edss 123]
    |- encounter-id  : return forms linked to this encounter
    |- encounter-ids : return forms linked to these encounters
    |- patient-pk    : return forms linked to this patient
    |- form-type     : keyword representing form type e.g. :edss/v1
    |- is-deleted    : return all (nil), deleted (true) or not deleted (false)
    |- select        : a set of additional fields to be included in return
                            - :date-time - encounter's date_time


    Parameters may, of course, be combined. Forms may be returned in any order."))
