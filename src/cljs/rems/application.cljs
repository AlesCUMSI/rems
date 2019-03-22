(ns rems.application
  (:require [clojure.string :as str]
            [medley.core :refer [map-vals]]
            [re-frame.core :as rf]
            [rems.actions.action :refer [action-button action-form-view action-comment action-collapse-id button-wrapper]]
            [rems.actions.add-member :refer [add-member-action-button add-member-form]]
            [rems.actions.approve-reject :refer [approve-reject-action-button approve-reject-form]]
            [rems.actions.close :refer [close-action-button close-form]]
            [rems.actions.comment :refer [comment-action-button comment-form]]
            [rems.actions.decide :refer [decide-action-button decide-form]]
            [rems.actions.invite-member :refer [invite-member-action-button invite-member-form]]
            [rems.actions.remove-member :refer [remove-member-action-button remove-member-form]]
            [rems.actions.request-comment :refer [request-comment-action-button request-comment-form]]
            [rems.actions.request-decision :refer [request-decision-action-button request-decision-form]]
            [rems.actions.return-action :refer [return-action-button return-form]]
            [rems.application-util :refer [form-fields-editable? in-processing?]]
            [rems.atoms :refer [external-link flash-message info-field readonly-checkbox textarea]]
            [rems.catalogue-util :refer [get-catalogue-item-title]]
            [rems.collapsible :as collapsible]
            [rems.common-util :refer [index-by]]
            [rems.guide-utils :refer [lipsum lipsum-short lipsum-paragraphs]]
            [rems.phase :refer [phases]]
            [rems.spinner :as spinner]
            [rems.status-modal :as status-modal]
            [rems.text :refer [localize-decision localize-event localized localize-item localize-state localize-time text text-format]]
            [rems.util :refer [dispatch! fetch post!]])
  (:require-macros [rems.guide-macros :refer [component-info example]]))

;;;; Helpers

(defn scroll-to-top! []
  (.setTimeout js/window #(.scrollTo js/window 0 0) 500)) ;; wait until faded out

(defn reload! [application-id]
  (rf/dispatch [:rems.application/enter-application-page application-id]))

(defn- disabled-items-warning [application]
  (when (in-processing? application)
    (when-some [resources (->> (:application/resources application)
                               (filter #(or (not (:catalogue-item/enabled %))
                                            (:catalogue-item/archived %)))
                               seq)]
      [:div.alert.alert-danger
       (text :t.form/alert-disabled-resources)
       (into [:ul]
             (for [resource resources]
               [:li (localized (:catalogue-item/title resource))]))])))

(defn apply-for [items]
  (let [url (str "#/application?items=" (str/join "," (sort (map :id items))))]
    (dispatch! url)))

(defn navigate-to
  "Navigates to the application with the given id.

  `replace?` parameter can be given to replace history state instead of push."
  [id & [replace?]]
  (dispatch! (str "#/application/" id) replace?))

(defn- format-validation-errors
  [application errors]
  (let [fields-by-id (->> (get-in application [:application/form :form/fields])
                          (index-by [:field/id]))
        licenses-by-id (->> (get-in application [:application/licenses])
                            (index-by [:license/id]))]
    [:div (text :t.form/validation.errors)
     (into [:ul]
           (concat
            (for [{:keys [type field-id]} (filter :field-id errors)]
              (let [field (get fields-by-id field-id)
                    field-title (localized (:field/title field))]
                [:li (text-format type field-title)]))
            (for [{:keys [type license-id]} (filter :license-id errors)]
              (let [license (get licenses-by-id license-id)
                    license-title (localized (:license/title license))]
                [:li (text-format type license-title)]))))]))


;;;; State

(rf/reg-sub ::application (fn [db _] (::application db)))
(rf/reg-sub ::edit-application (fn [db _] (::edit-application db)))

(rf/reg-event-fx
 ::enter-application-page
 (fn [{:keys [db]} [_ id]]
   {:db (dissoc db ::application ::edit-application)
    ::fetch-application id}))

(rf/reg-fx
 ::fetch-application
 (fn [id]
   (fetch (str "/api/v2/applications/" id "/migration") ;; TODO: remove v1 usage
          {:handler (fn [app-v1]
                      (fetch (str "/api/v2/applications/" id)
                             {:handler (fn [app-v2]
                                         (rf/dispatch [::fetch-application-result (merge app-v1 app-v2)]))}))})))

(rf/reg-event-db
 ::fetch-application-result
 (fn [db [_ application]]
   (assoc db
          ::application application
          ::edit-application {:items (into {} (for [field (get-in application [:application/form :form/fields])]
                                                [(:field/id field) {:value (:field/value field)
                                                                    :previous-value (:field/previous-value field)}]))
                              :licenses (into {} (map (fn [id] [id true]) (get (:application/accepted-licenses application)
                                                                               (:application/applicant application))))})))

(rf/reg-event-db
 ::set-validation
 (fn [db [_ errors]]
   (prn errors)
   (assoc-in db [::edit-application :validation] errors)))

(defn- save-application [description application-id catalogue-ids fields licenses]
  (status-modal/common-pending-handler! description)
  (post! "/api/applications/save"
         {:handler (partial status-modal/common-success-handler! #(rf/dispatch [::enter-application-page application-id]))
          :error-handler status-modal/common-error-handler!
          :params (merge {:command "save"
                          :items (map-vals :value fields)
                          :licenses licenses}
                         (if application-id
                           {:application-id application-id}
                           {:catalogue-items catalogue-ids}))}))

(rf/reg-event-fx
 ::save-application
 (fn [{:keys [db]} [_ description]]
   (let [application (::application db)
         app-id (:application/id application)
         catalogue-ids (map :catalogue-item/id (:application/resources application))
         items (get-in db [::edit-application :items])
         ;; TODO change api to booleans
         licenses (into {}
                        (for [[id checked?] (get-in db [::edit-application :licenses])
                              :when checked?]
                          [id "approved"]))]
     (save-application description app-id catalogue-ids items licenses))
   {:db (assoc-in db [::edit-application :validation] nil)}))

(defn- submit-application [application description application-id catalogue-items items licenses]
  (status-modal/common-pending-handler! description)
  (post! "/api/applications/save"
         {:handler (fn [response]
                     (if (:success response)
                       (post! "/api/applications/command"
                              {:handler (fn [response]
                                          (if (:success response)
                                            (status-modal/set-success! {:on-close #(rf/dispatch [::enter-application-page application-id (:errors %)])})
                                            (do
                                              (status-modal/set-error! {:result response
                                                                        :error-content (format-validation-errors application (:errors response))})
                                              (rf/dispatch [::set-validation (:errors response)]))))
                               :error-handler status-modal/common-error-handler!
                               :params {:type :rems.workflow.dynamic/submit
                                        :application-id application-id}})
                       (status-modal/common-error-handler! response)))
          :error-handler status-modal/common-error-handler!
          :params (merge {:command "save"
                          :items (map-vals :value items)
                          :licenses licenses}
                         (if application-id
                           {:application-id application-id}
                           {:catalogue-items catalogue-items}))}))

(rf/reg-event-fx
 ::submit-application
 (fn [{:keys [db]} [_ description]]
   (let [application (::application db)
         app-id (:application/id application)
         catalogue-ids (map :catalogue-item/id (:application/resources application))
         items (get-in db [::edit-application :items])
         ;; TODO change api to booleans
         licenses (into {}
                        (for [[id checked?] (get-in db [::edit-application :licenses])
                              :when checked?]
                          [id "approved"]))]
     (submit-application application description app-id catalogue-ids items licenses))
   {:db (assoc-in db [::edit-application :validation] nil)}))

(defn- save-attachment [{:keys [db]} [_ field-id file description]]
  (let [application-id (get-in db [::application :application/id])]
    (status-modal/common-pending-handler! description)
    (post! (str "/api/applications/add_attachment?application-id=" application-id "&field-id=" field-id)
           {:body file
            :handler (partial status-modal/common-success-handler! nil)
            :error-handler status-modal/common-error-handler!})
    {}))

(rf/reg-event-fx ::save-attachment save-attachment)

(defn- remove-attachment [_ [_ application-id field-id description]]
  (status-modal/common-pending-handler! description)
  (post! (str "/api/applications/remove_attachment?application-id=" application-id "&field-id=" field-id)
         {:body {}
          :handler (partial status-modal/common-success-handler! nil)
          :error-handler status-modal/common-error-handler!})
  {})

(rf/reg-event-fx ::remove-attachment remove-attachment)




;;;; UI components

(defn- pdf-button [id]
  (when id
    [:a.btn.btn-secondary
     {:href (str "/api/applications/" id "/pdf")
      :target :_new}
     "PDF " (external-link)]))

(rf/reg-event-db ::set-field (fn [db [_ id value]] (assoc-in db [::edit-application :items id :value] value)))
(rf/reg-event-db ::set-license (fn [db [_ id value]] (assoc-in db [::edit-application :licenses id] value)))
(defn- set-field-value
  [id]
  (fn [event]
    (rf/dispatch [::set-field id (.. event -target -value)])))

(defn- id-to-name [id]
  (str "field" id))

(defn- set-attachment
  [id description]
  (fn [event]
    (let [filecontent (aget (.. event -target -files) 0)
          form-data (doto (js/FormData.)
                      (.append "file" filecontent))]
      (rf/dispatch [::set-field id (.-name filecontent)])
      (rf/dispatch [::save-attachment id form-data description]))))

(defn- remove-attachment-action
  [app-id id description]
  (fn [event]
    (rf/dispatch [::set-field id nil])
    (rf/dispatch [::remove-attachment app-id id description])))

(defn- readonly-field [{:keys [id value]}]
  [:div.form-control {:id id} (str/trim (str value))])

(defn- diff [value previous-value]
  (let [dmp (js/diff_match_patch.)
        diff (.diff_main dmp
                         (str/trim (str previous-value))
                         (str/trim (str value)))]
    (.diff_cleanupSemantic dmp diff)
    diff))

(defn- formatted-diff [value previous-value]
  (->> (diff value previous-value)
       (map (fn [[change text]]
              (cond
                (pos? change) [:ins text]
                (neg? change) [:del text]
                :else text)))))

(defn- diff-field [{:keys [id value previous-value]}]
  (into [:div.form-control.diff {:id id}]
        (formatted-diff value previous-value)))

(defn- field-validation-message [validation title]
  (when validation
    [:div {:class "text-danger"}
     (text-format (:type validation) title)]))

(defn- toggle-diff-button [item-id diff-visible]
  [:a.toggle-diff {:href "#"
                   :on-click (fn [event]
                               (.preventDefault event)
                               (rf/dispatch [::toggle-diff item-id]))}
   [:i.fas.fa-exclamation-circle]
   " "
   (if diff-visible
     (text :t.form/diff-hide)
     (text :t.form/diff-show))])

(defn basic-field
  "Common parts of a form field.

  :field/id - number (required), field id
  :field/title - string (required), field title to show to the user
  :field/max-length - maximum number of characters (optional)
  :field/optional - boolean, true if the field is not required
  :field/value - string, the current value of the field
  :field/previous-value - string, the previously submitted value of the field
  :readonly - boolean, true if the field should not be editable
  :readonly-component - HTML, custom component for a readonly field
  :diff - boolean, true if should show the diff between :value and :previous-value
  :diff-component - HTML, custom component for rendering a diff
  :validation - validation errors

  editor-component - HTML, form component for editing the field"
  [{:keys [readonly readonly-component diff diff-component validation] :as opts} editor-component]
  (let [id (:field/id opts)
        title (localized (:field/title opts))
        optional (:field/optional opts)
        value (:field/value opts)
        previous-value (:field/previous-value opts)
        max-length (:field/max-length opts)]
    [:div.form-group.field
     [:label {:for (id-to-name id)}
      title " "
      (when max-length
        (text-format :t.form/maxlength (str max-length)))
      " "
      (when optional
        (text :t.form/optional))]
     (when (and previous-value
                (not= value previous-value))
       [toggle-diff-button id diff])
     (cond
       diff (or diff-component
                [diff-field {:id (id-to-name id)
                             :value value
                             :previous-value previous-value}])
       readonly (or readonly-component
                    [readonly-field {:id (id-to-name id)
                                     :value value}])
       :else editor-component)
     [field-validation-message validation title]]))

(defn- text-field
  [{:keys [validation] :as opts}]
  (let [id (:field/id opts)
        placeholder (localized (:field/placeholder opts))
        value (:field/value opts)
        max-length (:field/max-length opts)]
    [basic-field opts
     [:input.form-control {:type "text"
                           :id (id-to-name id)
                           :name (id-to-name id)
                           :placeholder placeholder
                           :max-length max-length
                           :class (when validation "is-invalid")
                           :value value
                           :on-change (set-field-value id)}]]))

(defn- texta-field
  [{:keys [validation] :as opts}]
  (let [id (:field/id opts)
        placeholder (localized (:field/placeholder opts))
        value (:field/value opts)
        max-length (:field/max-length opts)]
    [basic-field opts
     [textarea {:id (id-to-name id)
                :name (id-to-name id)
                :placeholder placeholder
                :max-length max-length
                :class (if validation "form-control is-invalid" "form-control")
                :value value
                :on-change (set-field-value id)}]]))

;; TODO: custom :diff-component, for example link to both old and new attachment
(defn attachment-field
  [{:keys [validation app-id] :as opts}]
  (let [id (:field/id opts)
        title (localized (:field/title opts))
        value (:field/value opts)
        click-upload (fn [e] (when-not (:readonly opts) (.click (.getElementById js/document (id-to-name id)))))
        filename-field [:div.field
                        [:a.btn.btn-secondary.mr-2
                         {:href (str "/api/applications/attachments/?application-id=" app-id "&field-id=" id)
                          :target :_new}
                         value " " (external-link)]]
        upload-field [:div.upload-file.mr-2
                      [:input {:style {:display "none"}
                               :type "file"
                               :id (id-to-name id)
                               :name (id-to-name id)
                               :accept ".pdf, .doc, .docx, .ppt, .pptx, .txt, image/*"
                               :class (when validation "is-invalid")
                               :on-change (set-attachment id title)}]
                      [:button.btn.btn-secondary {:on-click click-upload}
                       (text :t.form/upload)]]
        remove-button [:button.btn.btn-secondary.mr-2
                       {:on-click (remove-attachment-action app-id id (text :t.form/attachment-remove))}
                       (text :t.form/attachment-remove)]]
    [basic-field (assoc opts :readonly-component (if (empty? value)
                                                   [:span]
                                                   filename-field))
     (if (empty? value)
       upload-field
       [:div {:style {:display :flex :justify-content :flex-start}}
        filename-field
        remove-button])]))

(defn- date-field
  [{:keys [min max validation] :as opts}]
  (let [id (:field/id opts)
        value (:field/value opts)]
    ;; TODO: format readonly value in user locale (give basic-field a formatted :value and :previous-value in opts)
    [basic-field opts
     [:input.form-control {:type "date"
                           :id (id-to-name id)
                           :name (id-to-name id)
                           :class (when validation "is-invalid")
                           :defaultValue value
                           :min min
                           :max max
                           :on-change (set-field-value id)}]]))

(defn- option-label [value options]
  (let [label (->> options
                   (filter #(= value (:key %)))
                   first
                   :label)]
    (localized label)))

(defn option-field [{:keys [validation] :as opts}]
  (let [id (:field/id opts)
        value (:field/value opts)
        options (:field/options opts)]
    [basic-field
     (assoc opts :readonly-component [readonly-field {:id (id-to-name id)
                                                      :value (option-label value options)}])
     (into [:select.form-control {:id (id-to-name id)
                                  :name (id-to-name id)
                                  :class (when validation "is-invalid")
                                  :defaultValue value
                                  :on-change (set-field-value id)}
            [:option {:value ""}]]
           (for [{:keys [key label]} options]
             [:option {:value key}
              (localized label)]))]))

(defn normalize-option-key
  "Strips disallowed characters from an option key"
  [key]
  (str/replace key #"\s+" ""))

(defn encode-option-keys
  "Encodes a set of option keys to a string"
  [keys]
  (->> keys
       sort
       (str/join " ")))

(defn decode-option-keys
  "Decodes a set of option keys from a string"
  [value]
  (-> value
      (str/split #"\s+")
      set
      (disj "")))

(defn multiselect-field [{:keys [validation] :as opts}]
  (let [id (:field/id opts)
        value (:field/value opts)
        options (:field/options opts)
        selected-keys (decode-option-keys value)]
    ;; TODO: for accessibility these checkboxes would be best wrapped in a fieldset
    [basic-field
     (assoc opts :readonly-component [readonly-field {:id (id-to-name id)
                                                      :value (->> options
                                                                  (filter #(contains? selected-keys (:key %)))
                                                                  (map #(localized (:label %)))
                                                                  (str/join ", "))}])
     (into [:div]
           (for [{:keys [key label]} options]
             (let [option-id (str (id-to-name id) "-" key)
                   on-change (fn [event]
                               (let [checked (.. event -target -checked)
                                     selected-keys (if checked
                                                     (conj selected-keys key)
                                                     (disj selected-keys key))]
                                 (rf/dispatch [::set-field id (encode-option-keys selected-keys)])))]
               [:div.form-check
                [:input.form-check-input {:type "checkbox"
                                          :id option-id
                                          :name option-id
                                          :class (when validation "is-invalid")
                                          :value key
                                          :checked (contains? selected-keys key)
                                          :on-change on-change}]
                [:label.form-check-label {:for option-id}
                 (localized label)]])))]))

(defn- label [opts]
  (let [title (:field/title opts)]
    [:div.form-group
     [:label (localized title)]]))

(defn- set-license-approval
  [id]
  (fn [event]
    (rf/dispatch [::set-license id (.. event -target -checked)])))

(defn- license [id title approved readonly validation content]
  [:div.license
   [field-validation-message validation title]
   [:div.form-check
    [:input.form-check-input {:type "checkbox"
                              :name (str "license" id)
                              :disabled readonly
                              :class (when validation "is-invalid")
                              :checked (boolean approved)
                              :on-change (set-license-approval id)}]
    [:span.form-check-label content]]])

(defn- link-license
  [{:keys [readonly approved validation] :as opts}]
  (let [id (:license/id opts)
        title (localized (:license/title opts))
        link (localized (:license/link opts))]
    [license id title approved readonly validation
     [:a.license-title {:href link :target "_blank"}
      title " " (external-link)]]))

(defn- text-license
  [{:keys [approved readonly validation] :as opts}]
  (let [id (:license/id opts)
        title (localized (:license/title opts))
        text (localized (:license/text opts))]
    [license id title approved readonly validation
     [:div.license-panel
      [:span.license-title
       [:a.license-header.collapsed {:data-toggle "collapse"
                                     :href (str "#collapse" id)
                                     :aria-expanded "false"
                                     :aria-controls (str "collapse" id)}
        title " " [:i {:class "fa fa-ellipsis-h"}]]]
      [:div.collapse {:id (str "collapse" id)}
       [:div.license-block (str/trim (str text))]]]]))

(defn- unsupported-field
  [f]
  [:p.alert.alert-warning "Unsupported field " (pr-str f)])

(defn license-field [f]
  (case (:license/type f)
    :link [link-license f]
    :text [text-license f]
    [unsupported-field f]))

(defn- field [f]
  (case (:field/type f)
    :attachment [attachment-field f]
    :date [date-field f]
    :description [text-field f]
    :label [label f]
    :multiselect [multiselect-field f]
    :option [option-field f]
    :text [text-field f]
    :texta [texta-field f]
    [unsupported-field f]))

(defn- save-button []
  [button-wrapper {:id "save"
                   :text (text :t.form/save)
                   :on-click #(rf/dispatch [::save-application (text :t.form/save)])}])

(defn- submit-button []
  [button-wrapper {:id "submit"
                   :text (text :t.form/submit)
                   :class :btn-primary
                   :on-click #(rf/dispatch [::submit-application (text :t.form/submit)])}])

(defn- application-fields [application edit-application language]
  (let [{:keys [items validation]} edit-application
        field-validations (index-by [:field-id] validation)
        form-fields-editable? (form-fields-editable? application)
        readonly? (not form-fields-editable?)]
    [collapsible/component
     {:id "form"
      :title (text :t.form/application)
      :always
      [:div
       (into [:div]
             (for [fld (get-in application [:application/form :form/fields])]
               [field (assoc fld
                             :validation (field-validations (:field/id fld))
                             :readonly readonly?
                             :language language
                             :field/value (get-in items [(:field/id fld) :value])
                             :field/previous-value (get-in items [(:field/id fld) :previous-value])
                             :diff (get-in items [(:field/id fld) :diff])
                             :app-id (:application/id application))]))]}]))

(defn- application-licenses [application edit-application]
  (when-let [licenses (not-empty (:application/licenses application))]
    (let [edit-licenses (:licenses edit-application)
          validation (:validation edit-application)
          license-validations (index-by [:license-id] validation)
          form-fields-editable? (form-fields-editable? application)
          readonly? (not form-fields-editable?)]
      [collapsible/component
       {:id "form"
        :title (text :t.form/licenses)
        :always
        [:div.form-group.field
         (into [:div#licenses]
               (for [license licenses]
                 [license-field (assoc license
                                       :validation (license-validations (:license/id license))
                                       :readonly readonly?
                                       :approved (get edit-licenses (:license/id license)))]))]}])))


(defn- format-event [event]
  {:userid (:event/actor event)
   :event (localize-event (:event/type event))
   :comment (if (= :application.event/decided (:event/type event))
              (str (localize-decision (:application/decision event)) ": " (:application/comment event))
              (:application/comment event))
   :request-id (:application/request-id event)
   :commenters (:application/commenters event)
   :deciders (:application/deciders event)
   :time (localize-time (:event/time event))})

(defn- event-view [{:keys [time userid event comment commenters deciders]}]
  [:div.row
   [:label.col-sm-2.col-form-label time]
   [:div.col-sm-10
    [:div.col-form-label [:span userid] " — " [:span event]
     (when-let [targets (seq (concat commenters deciders))]
       [:span ": " (str/join ", " targets)])]
    (when comment [:div comment])]])

(defn- render-event-groups [event-groups]
  (for [group event-groups]
    (into [:div.group]
          (for [e group]
            [event-view e]))))

(defn- get-application-phases [state]
  (cond (contains? #{:rems.workflow.dynamic/rejected} state)
        [{:phase :apply :completed? true :text :t.phases/apply}
         {:phase :approve :completed? true :rejected? true :text :t.phases/approve}
         {:phase :result :completed? true :rejected? true :text :t.phases/rejected}]

        (contains? #{:rems.workflow.dynamic/approved} state)
        [{:phase :apply :completed? true :text :t.phases/apply}
         {:phase :approve :completed? true :approved? true :text :t.phases/approve}
         {:phase :result :completed? true :approved? true :text :t.phases/approved}]

        (contains? #{:rems.workflow.dynamic/closed} state)
        [{:phase :apply :closed? true :text :t.phases/apply}
         {:phase :approve :closed? true :text :t.phases/approve}
         {:phase :result :closed? true :text :t.phases/approved}]

        (contains? #{:rems.workflow.dynamic/draft :rems.workflow.dynamic/returned} state)
        [{:phase :apply :active? true :text :t.phases/apply}
         {:phase :approve :text :t.phases/approve}
         {:phase :result :text :t.phases/approved}]

        (contains? #{:rems.workflow.dynamic/submitted} state)
        [{:phase :apply :completed? true :text :t.phases/apply}
         {:phase :approve :active? true :text :t.phases/approve}
         {:phase :result :text :t.phases/approved}]

        :else
        [{:phase :apply :active? true :text :t.phases/apply}
         {:phase :approve :text :t.phases/approve}
         {:phase :result :text :t.phases/approved}]))

(defn- application-header [application]
  (let [state (get-in application [:application/workflow :workflow.dynamic/state])
        last-activity (:application/last-activity application)
        event-groups (->> (:application/events application)
                          (group-by #(or (:application/request-id %)
                                         (:event/id %)))
                          vals
                          (map (partial sort-by :event/time))
                          (sort-by #(:event/time (first %)))
                          reverse
                          (map #(map format-event %)))]
    [collapsible/component
     {:id "header"
      :title [:span#application-state
              (str
               (text :t.applications/state)
               (str ": " (localize-state state)))]
      :always (into [:div
                     [:div.mb-3 {:class (str "state-" (name state))}
                      (phases (get-application-phases state))]
                     [:h4 (text-format :t.applications/latest-activity (localize-time last-activity))]]
                    (when-let [g (first event-groups)]
                      (into [[:h4 (text :t.form/events)]]
                            (render-event-groups [g]))))
      :collapse (when-let [g (seq (rest event-groups))]
                  (into [:div]
                        (render-event-groups g)))}]))

(defn member-info
  "Renders a applicant, member or invited member of an application

  `:element-id`         - id of the element to generate unique ids
  `:attributes`         - user attributes to display
  `:application`        - application
  `:group?`             - specifies if a group border is rendered
  `:can-remove?`        - can the user be removed?
  `:accepted-licenses?` - has the member accepted the licenses?"
  [{:keys [element-id attributes application group? can-remove? accepted-licenses?]}]
  (let [application-id (:application/id application)
        user-id (or (:eppn attributes) (:userid attributes))
        sanitized-user-id (-> (or user-id "")
                              str/lower-case
                              (str/replace #"[^a-z]" ""))
        other-attributes (dissoc attributes :commonName :name :eppn :userid :mail :email)
        user-actions-id (str element-id "-" sanitized-user-id "-actions")]
    [collapsible/minimal
     {:id (str element-id "-" sanitized-user-id "-info")
      :class (when group? "group")
      :always
      [:div
       (cond (= (:application/applicant application) user-id) [:h5 (text :t.applicant-info/applicant)]
             (:userid attributes) [:h5 (text :t.applicant-info/member)]
             :else [:h5 (text :t.applicant-info/invited-member)])
       (when-let [name (or (:commonName attributes) (:name attributes))]
         [info-field (text :t.applicant-info/name) name {:inline? true}])
       (when user-id
         [info-field (text :t.applicant-info/username) user-id {:inline? true}])
       (when-let [mail (or (:mail attributes) (:email attributes))]
         [info-field (text :t.applicant-info/email) mail {:inline? true}])
       (when-not (nil? accepted-licenses?)
         [info-field (text :t.form/accepted-licenses) [readonly-checkbox accepted-licenses?] {:inline? true}])]
      :collapse (when (seq other-attributes)
                  (into [:div]
                        (for [[k v] other-attributes]
                          [info-field k v])))
      :footer [:div {:id user-actions-id}
               (when can-remove?
                 [:div.commands
                  [remove-member-action-button user-actions-id]])
               (when can-remove?
                 [remove-member-form application-id user-actions-id attributes (partial reload! application-id)])]}]))

(defn applicants-info
  "Renders the applicants, i.e. applicant and members."
  [application]
  (let [id "applicants-info"
        application-id (:application/id application)
        applicant (merge {:userid (:application/applicant application)}
                         (:application/applicant-attributes application))
        members (:application/members application)
        invited-members (:application/invited-members application)
        possible-commands (:application/permissions application)
        can-add? (contains? possible-commands :rems.workflow.dynamic/add-member)
        can-remove? (contains? possible-commands :rems.workflow.dynamic/remove-member)
        can-invite? (contains? possible-commands :rems.workflow.dynamic/invite-member)
        can-uninvite? (contains? possible-commands :rems.workflow.dynamic/uninvite-member)]
    [collapsible/component
     {:id id
      :title (text :t.applicant-info/applicants)
      :always
      (into [:div
             [member-info {:element-id id
                           :attributes applicant
                           :application application
                           :group? (or (seq members)
                                       (seq invited-members))
                           :can-remove? false
                           :accepted-licenses? (every? (or (get (:application/accepted-licenses application)
                                                                (:application/applicant application))
                                                           #{})
                                                       (map :license/id (:application/licenses application)))}]]
            (concat
             (for [member members]
               [member-info {:element-id id
                             :attributes member
                             :application application
                             :group? true
                             :can-remove? can-remove?}])
             (for [invited-member invited-members]
               [member-info {:element-id id
                             :attributes invited-member
                             :application application
                             :group? true
                             :can-remove? can-uninvite?}])))
      :footer [:div
               [:div.commands
                (when can-invite? [invite-member-action-button])
                (when can-add? [add-member-action-button])]
               [:div#member-action-forms
                [invite-member-form application-id (partial reload! application-id)]
                [add-member-form application-id (partial reload! application-id)]]]}]))

(defn- dynamic-actions [application]
  (let [commands-and-actions [:rems.workflow.dynamic/save-draft [save-button]
                              :rems.workflow.dynamic/submit [submit-button]
                              :rems.workflow.dynamic/return [return-action-button]
                              :rems.workflow.dynamic/request-decision [request-decision-action-button]
                              :rems.workflow.dynamic/decide [decide-action-button]
                              :rems.workflow.dynamic/request-comment [request-comment-action-button]
                              :rems.workflow.dynamic/comment [comment-action-button]
                              :rems.workflow.dynamic/approve [approve-reject-action-button]
                              :rems.workflow.dynamic/reject [approve-reject-action-button]
                              :rems.workflow.dynamic/close [close-action-button]]]
    (distinct (for [[command action] (partition 2 commands-and-actions)
                    :when (contains? (:application/permissions application) command)]
                action))))

(defn- actions-form [application]
  (let [app-id (:application/id application)
        actions (dynamic-actions application)
        reload (partial reload! app-id)
        forms [[:div#actions-forms.mt-3
                [request-comment-form app-id reload]
                [request-decision-form app-id reload]
                [comment-form app-id reload]
                [close-form app-id reload]
                [decide-form app-id reload]
                [return-form app-id reload]
                [approve-reject-form app-id reload]]]]
    (when (seq actions)
      [collapsible/component
       {:id "actions"
        :title (text :t.form/actions)
        :always (into [:div (into [:div.commands]
                                  actions)]
                      forms)}])))

(defn- applied-resources [application]
  [collapsible/component
   {:id "resources"
    :title (text :t.form/resources)
    :always [:div.form-items.form-group
             (into [:ul]
                   (for [resource (:application/resources application)]
                     ^{:key (:resource/id resource)}
                     [:li (localized (:catalogue-item/title resource))]))]}])

(defn- render-application [application edit-application language]
  (let [messages (remove nil?
                         [(disabled-items-warning application) ; NB: eval this here so we get nil or a warning
                          (when (:validation edit-application)
                            [flash-message
                             {:status :danger
                              :contents [format-validation-errors application (:validation edit-application)]}])])]
    [:div
     [:div {:class "float-right"} [pdf-button (:application/id application)]]
     [:h2 (text :t.applications/application)]
     (into [:div] messages)
     [application-header application]
     [:div.mt-3 [applicants-info application]]
     [:div.mt-3 [applied-resources application]]
     [:div.my-3 [application-fields application edit-application language]]
     [:div.my-3 [application-licenses application edit-application]]
     [:div.mb-3 [actions-form application]]]))

;;;; Entrypoint

(defn application-page []
  (let [application @(rf/subscribe [::application])
        edit-application @(rf/subscribe [::edit-application])
        language @(rf/subscribe [:language])
        loading? (not application)]
    (if loading?
      [:div
       [:h2 (text :t.applications/application)]
       [spinner/big]]
      [render-application application edit-application language])))






;;;; Guide

(defn guide []
  [:div
   (component-info member-info)
   (example "member-info"
            [member-info {:element-id "info1"
                          :attributes {:eppn "developer@uu.id"
                                       :mail "developer@uu.id"
                                       :commonName "Deve Loper"
                                       :organization "Testers"
                                       :address "Testikatu 1, 00100 Helsinki"}
                          :application {:application/id 42
                                        :application/applicant "developer"}
                          :accepted-licenses? true}])
   (example "member-info with name missing"
            [member-info {:element-id "info2"
                          :attributes {:eppn "developer"
                                       :mail "developer@uu.id"
                                       :organization "Testers"
                                       :address "Testikatu 1, 00100 Helsinki"}
                          :application {:application/id 42
                                        :application/applicant "developer"}}])
   (example "member-info"
            [member-info {:element-id "info3"
                          :attributes {:userid "alice"}
                          :application {:application/id 42
                                        :application/applicant "developer"}
                          :group? true
                          :can-remove? true}])
   (example "member-info"
            [member-info {:element-id "info4"
                          :attributes {:name "John Smith"
                                       :email "john.smith@invited.com"}
                          :application {:application/id 42
                                        :application/applicant "developer"}
                          :group? true}])

   (component-info applicants-info)
   (example "applicants-info"
            [applicants-info {:application/id 42
                              :application/applicant "developer"
                              :application/applicant-attributes {:eppn "developer"
                                                                 :mail "developer@uu.id"
                                                                 :commonName "Deve Loper"
                                                                 :organization "Testers"
                                                                 :address "Testikatu 1, 00100 Helsinki"}
                              :application/members #{{:userid "alice"}
                                                     {:userid "bob"}}
                              :application/invited-members #{{:name "John Smith" :email "john.smith@invited.com"}}
                              :application/licenses [{:license/id 1}]
                              :application/accepted-licenses {"developer" #{1}}
                              :application/permissions #{:rems.workflow.dynamic/add-member
                                                         :rems.workflow.dynamic/invite-member}}])

   (component-info disabled-items-warning)
   (example "no disabled items"
            [disabled-items-warning []])
   (example "two disabled items"
            [disabled-items-warning
             [{:state "disabled" :localizations {:en {:title "English title 1"}
                                                 :fi {:title "Otsikko suomeksi 1"}}}
              {:state "disabled" :localizations {:en {:title "English title 2"}
                                                 :fi {:title "Otsikko suomeksi 2"}}}
              {:state "enabled" :localizations {:en {:title "English title 3"}
                                                :fi {:title "Otsikko suomeksi 3"}}}]])

   (component-info field)
   (example "field of type \"text\""
            [:form
             [field {:field/type :text
                     :field/title {:en "Title"}
                     :field/placeholder {:en "prompt"}}]])
   (example "field of type \"text\" with maximum length"
            [:form
             [field {:field/type :text
                     :field/title {:en "Title"}
                     :field/placeholder {:en "prompt"}
                     :field/max-length 10}]])
   (example "field of type \"text\" with validation error"
            [:form
             [field {:field/type :text
                     :field/title {:en "Title"}
                     :field/placeholder {:en "prompt"}
                     :validation {:type :t.form.validation.required}}]])
   (example "non-editable field of type \"text\" without text"
            [:form
             [field {:field/type :text
                     :field/title {:en "Title"}
                     :field/placeholder {:en "prompt"}
                     :readonly true}]])
   (example "non-editable field of type \"text\" with text"
            [:form
             [field {:field/type :text
                     :field/title {:en "Title"}
                     :field/placeholder {:en "prompt"}
                     :readonly true
                     :field/value lipsum-short}]])
   (example "field of type \"texta\""
            [:form
             [field {:field/type :texta
                     :field/title {:en "Title"}
                     :field/placeholder {:en "prompt"}}]])
   (example "field of type \"texta\" with maximum length"
            [:form
             [field {:field/type :texta
                     :field/title {:en "Title"}
                     :field/placeholder {:en "prompt"}
                     :field/max-length 10}]])
   (example "field of type \"texta\" with validation error"
            [:form
             [field {:field/type :texta
                     :field/title {:en "Title"}
                     :field/placeholder {:en "prompt"}
                     :validation {:type :t.form.validation.required}}]])
   (example "non-editable field of type \"texta\""
            [:form
             [field {:field/type :texta
                     :field/title {:en "Title"}
                     :field/placeholder {:en "prompt"}
                     :readonly true
                     :field/value lipsum-paragraphs}]])
   (let [previous-lipsum-paragraphs (-> lipsum-paragraphs
                                        (str/replace "ipsum primis in faucibus orci luctus" "eu mattis purus mi eu turpis")
                                        (str/replace "per inceptos himenaeos" "justo erat hendrerit magna"))]
     [:div
      (example "editable field of type \"texta\" with previous value, diff hidden"
               [:form
                [field {:field/type :texta
                        :field/title {:en "Title"}
                        :field/placeholder {:en "prompt"}
                        :field/value lipsum-paragraphs
                        :field/previous-value previous-lipsum-paragraphs}]])
      (example "editable field of type \"texta\" with previous value, diff shown"
               [:form
                [field {:field/type :texta
                        :field/title {:en "Title"}
                        :field/placeholder {:en "prompt"}
                        :field/value lipsum-paragraphs
                        :field/previous-value previous-lipsum-paragraphs
                        :diff true}]])
      (example "non-editable field of type \"texta\" with previous value, diff hidden"
               [:form
                [field {:field/type :texta
                        :field/title {:en "Title"}
                        :field/placeholder {:en "prompt"}
                        :readonly true
                        :field/value lipsum-paragraphs
                        :field/previous-value previous-lipsum-paragraphs}]])
      (example "non-editable field of type \"texta\" with previous value, diff shown"
               [:form
                [field {:field/type :texta
                        :field/title {:en "Title"}
                        :field/placeholder {:en "prompt"}
                        :readonly true
                        :field/value lipsum-paragraphs
                        :field/previous-value previous-lipsum-paragraphs
                        :diff true}]])
      (example "non-editable field of type \"texta\" with previous value equal to current value"
               [:form
                [field {:field/type :texta
                        :field/title {:en "Title"}
                        :field/placeholder {:en "prompt"}
                        :readonly true
                        :field/value lipsum-paragraphs
                        :field/previous-value lipsum-paragraphs}]])])
   (example "field of type \"attachment\""
            [:form
             [field {:app-id 5
                     :field/id 6
                     :field/type :attachment
                     :field/title {:en "Title"}}]])
   (example "field of type \"attachment\", file uploaded"
            [:form
             [field {:app-id 5
                     :field/id 6
                     :field/type :attachment
                     :field/title {:en "Title"}
                     :field/value "test.txt"}]])
   (example "non-editable field of type \"attachment\""
            [:form
             [field {:app-id 5
                     :field/id 6
                     :field/type :attachment
                     :field/title {:en "Title"}
                     :readonly true}]])
   (example "non-editable field of type \"attachment\", file uploaded"
            [:form
             [field {:app-id 5
                     :field/id 6
                     :field/type :attachment
                     :field/title {:en "Title"}
                     :readonly true
                     :field/value "test.txt"}]])
   (example "field of type \"date\""
            [:form
             [field {:field/type :date
                     :field/title {:en "Title"}}]])
   (example "field of type \"date\" with value"
            [:form
             [field {:field/type :date
                     :field/title {:en "Title"}
                     :field/value "2000-12-31"}]])
   (example "non-editable field of type \"date\""
            [:form
             [field {:field/type :date
                     :field/title {:en "Title"}
                     :readonly true
                     :field/value ""}]])
   (example "non-editable field of type \"date\" with value"
            [:form
             [field {:field/type :date
                     :field/title {:en "Title"}
                     :readonly true
                     :field/value "2000-12-31"}]])
   (example "field of type \"option\""
            [:form
             [field {:field/type :option
                     :field/title {:en "Title"}
                     :field/value "y"
                     :field/options [{:key "y" :label {:en "Yes" :fi "Kyllä"}}
                                     {:key "n" :label {:en "No" :fi "Ei"}}]}]])
   (example "non-editable field of type \"option\""
            [:form
             [field {:field/type :option
                     :field/title {:en "Title"}
                     :field/value "y"
                     :readonly true
                     :field/options [{:key "y" :label {:en "Yes" :fi "Kyllä"}}
                                     {:key "n" :label {:en "No" :fi "Ei"}}]}]])
   (example "field of type \"multiselect\""
            [:form
             [field {:field/type :multiselect
                     :field/title {:en "Title"}
                     :field/value "egg bacon"
                     :field/options [{:key "egg" :label {:en "Egg" :fi "Munaa"}}
                                     {:key "bacon" :label {:en "Bacon" :fi "Pekonia"}}
                                     {:key "spam" :label {:en "Spam" :fi "Lihasäilykettä"}}]}]])
   (example "non-editable field of type \"multiselect\""
            [:form
             [field {:field/type :multiselect
                     :field/title {:en "Title"}
                     :field/value "egg bacon"
                     :readonly true
                     :field/options [{:key "egg" :label {:en "Egg" :fi "Munaa"}}
                                     {:key "bacon" :label {:en "Bacon" :fi "Pekonia"}}
                                     {:key "spam" :label {:en "Spam" :fi "Lihasäilykettä"}}]}]])
   (example "optional field"
            [:form
             [field {:field/type :texta
                     :field/optional true
                     :field/title {:en "Title"}
                     :field/placeholder {:en "prompt"}}]])
   (example "field of type \"label\""
            [:form
             [field {:field/type :label
                     :field/title {:en "Lorem ipsum dolor sit amet"}}]])
   (example "field of type \"description\""
            [:form
             [field {:field/type :description
                     :field/title {:en "Title"}
                     :field/placeholder {:en "prompt"}}]])

   (example "link license"
            [:form
             [license-field {:license/id 1
                             :license/type :link
                             :license/title {:en "Link to license"}
                             :license/link {:en "https://creativecommons.org/licenses/by/4.0/deed.en"}}]])
   (example "link license with validation error"
            [:form
             [license-field {:license/id 1
                             :license/type :link
                             :license/title {:en "Link to license"}
                             :license/link {:en "https://creativecommons.org/licenses/by/4.0/deed.en"}
                             :validation {:type :t.form.validation.required}}]])
   (example "text license"
            [:form
             [license-field {:license/id 1
                             :license/type :text
                             :license/title {:en "A Text License"}
                             :license/text {:en lipsum-paragraphs}}]])
   (example "text license with validation error"
            [:form
             [license-field {:license/id 1
                             :license/type :text
                             :license/title {:en "A Text License"}
                             :license/text {:en lipsum-paragraphs}
                             :validation {:type :t.form.validation.required}}]])

   (component-info render-application)
   (example "application, partially filled"
            [render-application
             {:application/id 17
              :application/workflow {:workflow.dynamic/state :rems.workflow.dynamic/draft}
              :application/resources [{:catalogue-item/title {:en "An applied item"}}]
              :application/form {:form/fields [{:field/id 1
                                                :field/type :text
                                                :field/title {:en "Field 1"}
                                                :field/placeholder {:en "prompt 1"}}
                                               {:field/id 2
                                                :field/type :label
                                                :title "Please input your wishes below."}
                                               {:field/id 3
                                                :field/type :texta
                                                :field/optional true
                                                :field/title {:en "Field 2"}
                                                :field/placeholder {:en "prompt 2"}}
                                               {:field/id 4
                                                :field/type :unsupported
                                                :field/title {:en "Field 3"}
                                                :field/placeholder {:en "prompt 3"}}
                                               {:field/id 5
                                                :field/type :date
                                                :field/title {:en "Field 4"}}]}
              :application/licenses [{:license/id 4
                                      :license/type :text
                                      :license/title {:en "A Text License"}
                                      :license/text {:en lipsum}}
                                     {:license/id 5
                                      :license/type :link
                                      :license/title {:en "Link to license"}
                                      :license/link {:en "https://creativecommons.org/licenses/by/4.0/deed.en"}}]}
             {:items {1 "abc"}
              :licenses {4 false 5 true}}
             :en])
   (example "application, applied"
            [render-application
             {:application/id 17
              :application/workflow {:workflow.dynamic/state :rems.workflow.dynamic/submitted}
              :application/resources [{:catalogue-item/title {:en "An applied item"}}]
              :application/form {:form/fields [{:field/id 1
                                                :field/type :text
                                                :field/title {:en "Field 1"}
                                                :field/placeholder {:en "prompt 1"}}]}
              :application/licenses [{:license/id 4
                                      :license/type :text
                                      :license/title {:en "A Text License"}
                                      :license/text {:en lipsum}}]}
             {:items {1 "abc"}
              :licenses {4 true}}
             :en])
   (example "application, approved"
            [render-application
             {:application/id 17
              :application/workflow {:workflow.dynamic/state :rems.workflow.dynamic/approved}
              :application/applicant-attributes {:eppn "eppn"
                                                 :mail "email@example.com"
                                                 :additional "additional field"}
              :application/resources [{:catalogue-item/title {:en "An applied item"}}]
              :application/form {:form/fields [{:field/id 1
                                                :field/type :text
                                                :field/title {:en "Field 1"}
                                                :field/placeholder {:en "prompt 1"}}]}
              :application/licenses [{:license/id 4
                                      :license/type :text
                                      :license/title {:en "A Text License"}
                                      :license/text {:en lipsum}}]}
             {:items {1 "abc"}
              :licenses {4 true}}
             :en])])
