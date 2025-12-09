(ns pc4.common-ui.interface
  "Public interface for common-ui component providing reusable UI components and utilities."
  (:require [clojure.string :as str]
            [pc4.common-ui.icons :as icons]
            [pc4.common-ui.dates :as dates]
            [pc4.common-ui.render :as render]
            [pc4.common-ui.components :as components]
            [rum.core :as rum])
  (:import (java.time LocalDate)))

(def format-date dates/format-date)
(def format-date-time dates/format-date-time)
(def day-of-week dates/day-of-week)

(def icon-home icons/icon-home)
(def icon-team icons/icon-team)
(def icon-user icons/icon-user)
(def icon-building-office icons/icon-building-office)
(def icon-building-library icons/icon-building-library)
(def icon-envelope-open icons/icon-envelope-open)
(def icon-plus-circle icons/icon-plus-circle)
(def icon-magnifying-glass icons/icon-magnifying-glass)
(def icon-folder icons/icon-folder)
(def icon-calendar icons/icon-calendar)
(def icon-inbox icons/icon-inbox)
(def icon-reports icons/icon-reports)
(def icon-bell icons/icon-bell)
(def icon-chevron-down icons/icon-chevron-down)
(def icon-chevron-left icons/icon-chevron-left)
(def icon-cog-6-tooth icons/icon-cog-6-tooth)

(def avatar-8 icons/avatar-8)
(def avatar-14 icons/avatar-14)

(def render render/render)
(def render-file render/render-file)
(def html->text render/html->text)

(defn badge
  "Display a small badge with the text specified."
  [opts]
  (components/badge opts))

(defn box-error-message
  [opts]
  (components/box-error-message opts))

(defn alert-error
  "Display an error alert with consistent styling."
  [opts]
  (components/ui-alert-error opts))

(defn alert-warning
  "Display a warning alert with consistent styling."
  [opts]
  (components/ui-alert-warning opts))

(defn alert-info
  "Display an informational alert with consistent styling."
  [opts]
  (components/ui-alert-info opts))

(defn alert-success
  "Display a success alert with consistent styling."
  [opts]
  (components/ui-alert-success opts))

(defn active-panel
  [opts & content]
  (apply components/active-panel opts content))

(defn ui-modal
  "A modal dialog that is initially hidden and can be revealed with JavaScript.

   Parameters:
   - id: The ID of the modal element for targeting with HTMX
   - hidden?: Boolean to determine if modal is initially hidden (defaults to true)
   - title: The title of the modal
   - actions: A sequence of actions (maps) with :id, :title, :role (primary/secondary),
             :disabled?, and data attributes for behavior
   - cancel: Either an action map for clicking outside the modal to cancel/dismiss it,
             or an ID (default :cancel) that references an action in the actions list.
   - size: Size of the modal - :small, :medium, :large, :xl, :full or custom classes (defaults to :large)

   Usage example:
   (ui-modal
     {:id \"my-modal\"
      :title \"Confirmation\"
      :size :large
      :cancel :cancel
      :actions [{:id \"confirm\" :title \"Confirm\" :role :primary :hx-post \"/confirm\"}
                {:id :cancel :title \"Cancel\" :hx-target \"#my-modal\" :hx-swap \"outerHTML\" :hx-select \".hidden\"}]}
     [:p \"Are you sure you want to continue?\"])"
  [opts & content]
  (apply components/ui-modal opts content))

(defn ui-label
  [opts]
  (components/ui-label opts))

(defn ui-textfield*
  "HTML input control for a textfield."
  [opts]
  (components/ui-textfield* opts))

(defn ui-textfield
  "Label and input for a textfield, with optional help-text."
  [opts]
  (components/ui-textfield opts))

(defn ui-submit-button
  [opts content]
  (components/ui-submit-button opts content))

(defn ui-cancel-button
  [opts content]
  (components/ui-cancel-button opts content))

(defn ui-delete-button
  [opts content]
  (components/ui-delete-button opts content))

(defn ui-select-button
  [opts]
  (components/ui-select-button opts))

(defn ui-title
  [opts]
  (components/ui-title opts))

(defn ui-table
  [& content]
  (apply components/ui-table content))

(defn ui-table-head
  [& content]
  (apply components/ui-table-head content))

(defn ui-table-heading
  [opts content]
  (components/ui-table-heading opts content))

(defn ui-table-body
  [& content]
  (apply components/ui-table-body content))

(def ui-table-row components/ui-table-row)

(defn ui-table-cell
  [opts & content]
  (apply components/ui-table-cell opts content))

(defn ui-simple-form-title
  [opts]
  (components/ui-simple-form-title opts))

(defn ui-simple-form-item
  [opts & content]
  (apply components/ui-simple-form-item opts content))

(defn ui-simple-form
  [& content]
  (apply components/ui-simple-form content))

(defn ui-local-date
  [opts local-date]
  (components/ui-local-date opts local-date))

(defn ui-local-date-time
  "DateTime input using HTML5 datetime-local control."
  [opts local-date-time]
  (components/ui-local-date-time opts local-date-time))

(defn ui-local-date-accuracy
  "Simple pop-up to choose date accuracy."
  [opts value]
  (components/ui-local-date-accuracy opts value))

(defn ui-textarea
  [opts value]
  (components/ui-textarea opts value))

(defn ui-rich-text-script
  "Include once per page that uses ui-rich-text. Loads Quill editor library."
  []
  (components/ui-rich-text-script))

(defn ui-rich-text
  "Rich text editor using Quill. Must include ui-rich-text-script on the page."
  [opts value]
  (components/ui-rich-text opts value))

(defn ui-button
  [opts content]
  (components/ui-button opts content))

(defn ui-action-bar
  [& content]
  (apply components/ui-action-bar content))

(defn ui-spinner
  [opts]
  (components/ui-spinner opts))

(defn ui-checkbox
  "Checkbox component with label and optional description"
  [opts]
  (components/ui-checkbox opts))

(defn ui-radio-button
  "Radio button group component"
  [opts]
  (components/ui-radio-button opts))

;;
;;
;;

(defn login-page
  [params]
  (render/render-file "ui/templates/pages/login-page.html" params))

