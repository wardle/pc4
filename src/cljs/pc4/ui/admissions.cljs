(ns pc4.ui.admissions
  (:require [clojure.string :as str]
            [com.fulcrologic.fulcro.algorithms.form-state :as fs]
            [com.fulcrologic.fulcro.algorithms.merge :as merge]
            [com.fulcrologic.fulcro.algorithms.tempid :as tempid]
            [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
            [com.fulcrologic.fulcro.data-fetch :as df]
            [com.fulcrologic.fulcro.dom :as dom :refer [div]]
            [com.fulcrologic.fulcro.mutations :as m :refer [defmutation]]
            [com.fulcrologic.fulcro.routing.dynamic-routing :as dr]
            [pc4.rsdb]
            [pc4.ui.core :as ui]
            [pc4.ui.patients :as patients]
            [taoensso.timbre :as log]))

(declare EditAdmission)

(defn close-modal* [state patient-identifier]
  (assoc-in state [:t_patient/patient_identifier patient-identifier :ui/editing-admission] {}))

(defmutation close-modal [patient-identifier]
  (action [{:keys [state]}]
          (swap! state close-modal* patient-identifier)))

(defn cancel-edit-admission*
  [state patient-identifier {:t_episode/keys [id] :as episode}]
  (log/info "Cancelling edit" episode)
  (cond-> (-> state
              (fs/pristine->entity* [:t_episode/id id])     ;; restore form to pristine state
              (close-modal* patient-identifier))
          (tempid/tempid? id)                               ;; if cancelling a newly created admission, delete it and its relationship
          (merge/remove-ident* [:t_episode/id id] [:t_patient/patient_identifier patient-identifier :t_patient/episodes])))

(defmutation cancel-edit-admission
  [{:keys [patient-identifier episode]}]
  (action [{:keys [state]}]
          (swap! state cancel-edit-admission* patient-identifier episode)))

(defn edit-admission*
  [state patient-identifier {:t_episode/keys [id]}]
  (-> state
      (fs/add-form-config* EditAdmission [:t_episode/id id] {:destructive? true})
      (assoc-in [:t_patient/patient_identifier patient-identifier :ui/editing-admission] [:t_episode/id id])))

(defmutation edit-admission
  [{:keys [patient-identifier episode]}]
  (action [{:keys [state]}]
          (swap! state edit-admission* patient-identifier episode)))

(defn add-admission*
  [state patient-identifier {:t_episode/keys [id] :as episode}]
  (-> state
      (assoc-in [:t_episode/id id] episode)
      (update-in [:t_patient/patient_identifier patient-identifier :t_patient/episodes] (fnil conj []) [:t_episode/id id])
      (edit-admission* patient-identifier episode)))

(defmutation add-admission
  [{:keys [patient-identifier episode]}]
  (action [{:keys [state]}]
          (swap! state add-admission* patient-identifier episode)))

(defsc Project [this {:t_project/keys [id title is_admission]}]
  {:ident :t_project/id
   :query [:t_project/id :t_project/title :t_project/is_admission]})

(defsc EditAdmission
  [this {:t_episode/keys [id date_registration date_discharge encounters] :as episode
         :ui/keys        [current-patient]}]
  {:ident       :t_episode/id
   :query       [:t_episode/id
                 :t_episode/patient_fk
                 :t_episode/date_registration
                 :t_episode/date_discharge
                 :t_episode/encounters
                 fs/form-config-join
                 {[:ui/current-patient '_] [:t_patient/patient_identifier]}]
   :form-fields #{:t_episode/date_registration :t_episode/date_discharge}}
  (tap> {:edit-admission episode})
  (let [patient-identifier (:t_patient/patient_identifier current-patient)
        do-save #(comp/transact! this [(pc4.rsdb/save-admission (select-keys episode [:t_episode/id :t_episode/date_registration :t_episode/date_discharge :t_episode/patient_fk]))]
                                 {:ref [:t_patient/patient_identifier patient-identifier]})
        do-delete #(comp/transact! this [(pc4.rsdb/delete-admission (select-keys episode [:t_episode/id :t_episode/patient_fk]))]
                                   {:ref [:t_patient/patient_identifier patient-identifier]})
        cancel-editing #(comp/transact! this [(cancel-edit-admission {:patient-identifier patient-identifier :episode episode})])]
    (ui/ui-modal
      {:actions [{:id ::save :title "Save" :role :primary :onClick do-save :disabled? (nil? date_registration)}
                 {:id ::delete :title "Delete" :onClick do-delete :disabled? (or (tempid/tempid? id) (seq encounters))}
                 {:id ::cancel :title "Cancel" :onClick cancel-editing}]
       :onClose cancel-editing}
      (ui/ui-simple-form {}
        (ui/ui-simple-form-title {:title "Admission to hospital"})
        (ui/ui-simple-form-item {:label "Date of admission"}
          (ui/ui-local-date {:name     "date-registration"
                             :value    date_registration
                             :onChange #(m/set-value!! this :t_episode/date_registration %)}))
        (ui/ui-simple-form-item {:label "Date of discharge"}
          (ui/ui-local-date {:name     "date-discharge"
                             :value    date_discharge
                             :onChange #(m/set-value!! this :t_episode/date_discharge %)}))
        (when (seq encounters)
          (dom/p :.text-gray-500.pt-8 "This episode cannot be deleted as it has encounters linked to it."))))))


(def ui-edit-admission (comp/factory EditAdmission))

(defsc EpisodeListItem
  [this {:t_episode/keys [id date_registration date_discharge]}
   {:keys [onClick] :as computed-props}]
  {:ident :t_episode/id
   :query [:t_episode/id :t_episode/patient_fk :t_episode/date_registration :t_episode/date_discharge
           {:t_episode/project (comp/get-query Project)}]}
  (ui/ui-table-row computed-props
    (ui/ui-table-cell {} (ui/format-date date_registration))
    (ui/ui-table-cell {} (ui/format-date date_discharge))))

(def ui-episode-list-item (comp/computed-factory EpisodeListItem {:keyfn :t_episode/id}))

(defsc PatientAdmissions
  [this {:t_patient/keys [id patient_identifier episodes] :as patient
         :>/keys         [banner], :ui/keys [editing-admission]}]
  {:ident         :t_patient/patient_identifier
   :query         [:t_patient/id :t_patient/patient_identifier
                   {:>/banner (comp/get-query patients/PatientBanner)}
                   {:t_patient/episodes (comp/get-query EpisodeListItem)}
                   {:ui/editing-admission (comp/get-query EditAdmission)}]
   :route-segment ["pt" :t_patient/patient_identifier "admissions"]
   :will-enter    (fn [app {:t_patient/keys [patient_identifier] :as route-params}]
                    (when-let [patient-identifier (some-> patient_identifier (js/parseInt))]
                      (dr/route-deferred [:t_patient/patient_identifier patient-identifier]
                                         (fn []
                                           (df/load! app [:t_patient/patient_identifier patient-identifier] PatientAdmissions
                                                     {:target               [:ui/current-patient]
                                                      :post-mutation        `dr/target-ready
                                                      :post-mutation-params {:target [:t_patient/patient_identifier patient-identifier]}})))))
   :pre-merge     (fn [{:keys [current-normalized data-tree]}]
                    (merge {:ui/editing-admission {}} current-normalized data-tree))}
  (when patient_identifier
    (let [do-edit #(df/load! this [:t_episode/id (:t_episode/id %)] EditAdmission
                             {:post-mutation        `edit-admission
                              :post-mutation-params {:patient-identifier patient_identifier :episode %}})
          do-add #(comp/transact! this [(add-admission {:patient-identifier patient_identifier
                                                        :episode            {:t_episode/id         (tempid/tempid)
                                                                             :t_episode/patient_fk id}})])]
      (patients/ui-layout
        {:banner (patients/ui-patient-banner banner)
         :menu   (patients/ui-patient-menu
                   patient
                   {:selected-id :admissions
                    :sub-menu    {:items [{:id      :add-admission
                                           :onClick do-add
                                           :content "Add admission"}]}})}
        (comp/fragment
          (when (:t_episode/id editing-admission)
            (ui-edit-admission editing-admission))
          (dom/div
            (when (seq episodes)
              (ui/ui-table {}
                (ui/ui-table-head {}
                  (ui/ui-table-row {}
                    (map #(ui/ui-table-heading {:react-key %} %) ["Date of admission" "Date of discharge" "Problems"])))
                (ui/ui-table-body {}
                  (for [episode (->> episodes
                                     (filter #(-> % :t_episode/project :t_project/is_admission))
                                     (sort-by #(some-> % :t_episode/date_registration .valueOf))
                                     reverse)]
                    (ui-episode-list-item episode
                                          {:onClick (fn [] (do-edit episode))
                                           :classes ["cursor-pointer" "hover:bg-gray-200"]})))))))))))

