(ns com.eldrix.pc4.partials
  "pc4 partials are interceptors that generate partial HTML responses. They are
  designed for htmx responses.

  In general, the response differs based on whether the request is HTTP GET or
  POST. Most partials will return a form or display in response to GET. For HTTP
  POST, they will validate the submitted form data and return a response. These
  will usually accept a parameter to redirect if appropriate (e.g. if submission
  is acceptable), or return a form with validation errors if the submission is
  unacceptable.

  For client-side validation, HTML5 form validation can be used to avoid
  excessive client-side scripting. More complex partials may make use of
  compiled javascript if required.")

(def inspect-edit-user
  {:enter})

