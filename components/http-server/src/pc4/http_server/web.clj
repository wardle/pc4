(ns pc4.http-server.web
  (:require
    [pc4.log.interface :as log]
    [rum.core :as rum]
    [selmer.parser :as selmer]))

(defn render
  "Render the markup 'src' using rum. This is designed only for server-side
  rendering and omits all React affordances. Uses rum.
  - src - HTML as Clojure data (aka hiccup)."
  [src]
  (rum/render-static-markup src))

(defn render-file
  "Render the context-map using the template from the filename or URL specified.
  Uses selmer."
  [filename-or-url context-map]
  (selmer/render-file filename-or-url context-map))

(defn ok
  [content]
  {:status  200
   :headers {"Content-Type" "text/html"}
   :body    content})

(defn page
  "Convenience function to generate a response with a page containing the static
  markup."
  [{:keys [title]} src]
  (ok (render-file "templates/page.html" {:title title, :body (render src)})))

(def empty-success-response
  {:status  200
   :headers {"content-type" "text/plain"}})

(defn moved-permanently
  "The HTTP 301 Moved Permanently redirection response status code indicates
  that the requested resource has been permanently moved to the URL in the
  Location header."
  [path]
  {:status  301
   :headers {"Location" path}})

(defn redirect-found
  "The HTTP 302 Found redirection response status code indicates that the
  requested resource has been temporarily moved to the URL in the Location
  header."
  [path]
  {:status  302
   :headers {"Location" path}})

(defn redirect-see-other
  "The HTTP 303 See Other redirection response status code indicates that the
  browser should redirect to the URL in the Location header instead of rendering
  the requested resource."
  [path]
  {:status  303
   :headers {"Location" path}})

(defn hx-redirect
  "Return a response that triggers a client-side full page redirect using HTMX.
  Use this when returning a page fragment that needs to initiate a full-page
  redirect. See https://htmx.org/headers/hx-redirect/"
  [path]
  {:status  200
   :headers {"HX-Redirect" path}})

(defn hx-location
  "Return a response that triggers a client-side redirect using HTMX.
  See https://htmx.org/headers/hx-location/
  'opts' can be a map containing the optional string keys (source, event,
  handler, target, swap, values, headers, select) as per htmx documentation."
  ([path]
   (hx-location path {}))
  ([path opts]
   {:status  200
    :headers {"HX-Location" (merge opts {"path" path})}}))

(defn forbidden
  "The HTTP 403 Forbidden client error response status code indicates that the
  server understood the request but refused to process it. This status is
  similar to 401, except that for 403 Forbidden responses, authenticating or
  re-authenticating makes no difference. The request failure is tied to
  application logic, such as insufficient permissions to a resource or action."
  [body]
  {:status 403
   :body   (or body "Forbidden")})

(defn not-found
  [body]
  {:status 404
   :body   (or body "Not found")})

(defn no-content
  []
  {:status 204})
