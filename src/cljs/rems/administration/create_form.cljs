(ns rems.administration.create-form
  "Form editor for administrators used for editing and creating new forms.

  NB: Each field has a generated distinct `:field/id` so that we can track moving fields,
  their answers and especially references between fields. The `:field/id` is optional in
  the API, and would be generated by the backend, but we always send the one we have created.
  We also maintain a separate `:field/index` to automatically number fields for the user's
  benefit.

  NB: We need to sometimes track when a field has been rendered anew. We use the
  `data-field-index` property for this. When a field is e.g. moved, it takes a while for
  React to re-render it. So we want to wait until the element is rendered to the new place
  with the new index before we can scroll to the new position."
  (:require [better-cond.core :as b]
            [clojure.string :as str]
            [medley.core :refer [find-first indexed]]
            [re-frame.core :as rf]
            [reagent.core :as r]
            [reagent.format :as rfmt]
            [reagent.ratom :as ra]
            [rems.administration.administration :as administration]
            [rems.administration.components :refer [checkbox field-validation-message keys-to-id localized-text-field organization-field radio-button-group text-field text-field-inline update-form]]
            [rems.administration.items :as items]
            [rems.atoms :as atoms :refer [document-title]]
            [rems.collapsible :as collapsible]
            [rems.common.form :refer [field-visible? generate-field-id validate-form-template] :as common-form]
            [rems.common.util :refer [andstr not-blank parse-int]]
            [rems.config]
            [rems.dropdown :as dropdown]
            [rems.fetcher :as fetcher]
            [rems.fields :as fields]
            [rems.flash-message :as flash-message]
            [rems.focus :as focus]
            [rems.spinner :as spinner]
            [rems.text :refer [localized text text-format]]
            [rems.util :refer [event-value focus-input-field navigate! on-element-appear on-element-appear-async post! put! trim-when-string visibility-ratio]]))

(rf/reg-event-fx
 ::enter-page
 (fn [{:keys [db]} [_ form-id edit-form?]]
   {:db (assoc db
               ::form nil
               ::form-errors nil
               ::form-id form-id
               ::edit-form? edit-form?)
    :dispatch-n [(when form-id [::form])]}))

(rf/reg-sub ::form-id (fn [db] (::form-id db)))

(fetcher/reg-fetcher ::form "/api/forms/:id" {:path-params (fn [db] {:id (::form-id db)})})

;;;; form state

(def ^:private preview-fields (r/atom nil))

(rf/reg-sub ::edit-form? (fn [db _] (::edit-form? db)))
(rf/reg-sub ::form-errors (fn [db _] (::form-errors db)))
(rf/reg-sub ::form-data (fn [db] (get-in db [::form :data])))
(rf/reg-sub ::fields (fn [db] (vec (get-in db [::form :data :form/fields] []))))
(rf/reg-sub ::field
            :<- [::fields]
            (fn [form-fields [_ field-id]]
              (find-first (comp #{field-id} :field/id) form-fields)))

(defn- field-collapsible-id [id] (str "field-collapsible-" id))

(defn- data-field-id [^js elem] (.. elem -dataset -fieldId))
(defn- data-field-index [^js elem] (.. elem -dataset -fieldIndex))

(defn- field-selector [id & [idx]]
  (cond-> ".form-field"
    :always (str (rfmt/format "[data-field-id='%s']" id))
    idx (str (rfmt/format "[data-field-index='%s']" idx))))

(defn- preview-selector [id & [idx]]
  (cond-> ".field-preview"
    :always (str (rfmt/format "[data-field-id='%s']" id))
    idx (str (rfmt/format "[data-field-index='%s']" idx))))

(rf/reg-fx
 ::focus-field-editor
 (fn [field-id]
   (-> (on-element-appear-async {:selector (field-selector field-id)})
       (.then (fn [element]
                (rf/dispatch [:rems.collapsible/set-expanded (field-collapsible-id field-id) true])
                (on-element-appear-async {:selector "textarea"
                                          :target element})))
       (.then focus/focus-without-scroll))))

(rf/reg-event-fx
 ::add-form-field
 (fn [{:keys [db]} [_ & [index]]]
   (let [new-item (-> db
                      (get-in [::form :data :form/fields])
                      generate-field-id
                      (merge {:field/type :text}))]
     {:db (update-in db [::form :data :form/fields] items/add new-item index)
      ::focus-field-editor (:field/id new-item)})))

(rf/reg-event-db
 ::remove-form-field
 (fn [db [_ field-index]]
   (update-in db [::form :data :form/fields] items/remove field-index)))

(defn- track-moved-field-editor [field-id field-index button-selector]
  (when-some [before (some-> js/document
                             (.querySelector (field-selector field-id))
                             (.getBoundingClientRect))]
    (on-element-appear {:selector (field-selector field-id field-index)
                        :on-resolve (fn [element]
                                      (let [after (.getBoundingClientRect element)]
                                        (focus/scroll-offset before after)
                                        (focus/focus-without-scroll (.querySelector element button-selector))))})))

(rf/reg-event-fx
 ::move-form-field-up
 (fn [{:keys [db]} [_ field-index]]
   (let [field-id (get-in db [::form :data :form/fields field-index :field/id])]
     (track-moved-field-editor field-id (dec field-index) ".move-up")
     {:db (update-in db
                     [::form :data :form/fields]
                     items/move-up
                     field-index)})))

(rf/reg-event-fx
 ::move-form-field-down
 (fn [{:keys [db]} [_ field-index]]
   (let [field-id (get-in db [::form :data :form/fields field-index :field/id])]
     (track-moved-field-editor field-id (inc field-index) ".move-down")
     {:db (update-in db
                     [::form :data :form/fields]
                     items/move-down
                     field-index)})))

(defn- add-item [k db [_ field-index]]
  (update-in db [::form :data :form/fields field-index k] items/add {}))

(defn- remove-item [k db [_ field-index item-index]]
  (update-in db [::form :data :form/fields field-index k] items/remove item-index))

(defn- move-item [k direction db [_ field-index item-index]]
  (case direction
    :up (update-in db [::form :data :form/fields field-index k] items/move-up item-index)
    :down (update-in db [::form :data :form/fields field-index k] items/move-down item-index)))

(rf/reg-event-db ::add-option (partial add-item :field/options))
(rf/reg-event-db ::remove-option (partial remove-item :field/options))
(rf/reg-event-db ::move-option-up (partial move-item :field/options :up))
(rf/reg-event-db ::move-option-down (partial move-item :field/options :down))

(rf/reg-event-db ::add-column (partial add-item :field/columns))
(rf/reg-event-db ::remove-column (partial remove-item :field/columns))
(rf/reg-event-db ::move-column-up (partial move-item :field/columns :up))
(rf/reg-event-db ::move-column-down (partial move-item :field/columns :down))

;;;; form submit

(defn build-localized-string [lstr]
  (let [v (into {} (for [language @rems.config/languages]
                     [language (trim-when-string (get lstr language ""))]))]
    (when-not (every? str/blank? (vals v))
      v)))

(defn build-request-field [field]
  (let [columns? (common-form/supports-columns? field)
        info-text? (common-form/supports-info-text? field)
        max-length? (common-form/supports-max-length? field)
        optional? (common-form/supports-optional? field)
        options? (common-form/supports-options? field)
        placeholder? (common-form/supports-placeholder? field)
        privacy? (common-form/supports-privacy? field)
        visibility? (common-form/supports-visibility? field)]
    (merge {:field/id (:field/id field)
            :field/title (build-localized-string (:field/title field))
            :field/type (:field/type field)
            :field/optional (if optional?
                              (boolean (:field/optional field))
                              false)}
           (when info-text?
             (when-let [v (build-localized-string (:field/info-text field))]
               {:field/info-text v}))
           (when placeholder?
             (when-let [v (build-localized-string (:field/placeholder field))]
               {:field/placeholder v}))
           (when max-length?
             {:field/max-length (parse-int (:field/max-length field))})
           (when options?
             {:field/options (for [{:keys [key label]} (:field/options field)]
                               {:key key
                                :label (build-localized-string label)})})
           (when columns?
             {:field/columns (for [{:keys [key label]} (:field/columns field)]
                               {:key key
                                :label (build-localized-string label)})})
           (when privacy?
             (when (= :private (get-in field [:field/privacy]))
               {:field/privacy (:field/privacy field)}))
           (when visibility?
             (when (= :only-if (get-in field [:field/visibility :visibility/type]))
               {:field/visibility (select-keys (:field/visibility field)
                                               [:visibility/type :visibility/field :visibility/values])})))))

(defn build-request [form]
  {:organization {:organization/id (get-in form [:organization :organization/id])}
   :form/internal-name (trim-when-string (:form/internal-name form))
   :form/external-title (build-localized-string (:form/external-title form))
   :form/fields (mapv build-request-field (:form/fields form))})

;;;; form validation

(rf/reg-event-fx
 ::create-form
 (fn [{:keys [db]} _]
   (let [request (build-request (get-in db [::form :data]))
         form-errors (validate-form-template request @rems.config/languages)]
     (when-not form-errors
       (post! "/api/forms/create"
              {:params request
               :handler (flash-message/default-success-handler :top
                                                               [text :t.administration/create-form]
                                                               #(navigate! (str "/administration/forms/" (:id %))))
               :error-handler (flash-message/default-error-handler :top
                                                                   [text :t.administration/create-form])}))
     {:db (assoc db ::form-errors form-errors)})))

(rf/reg-event-fx
 ::edit-form
 (fn [{:keys [db]} _]
   (let [request (build-request (get-in db [::form :data]))
         form-errors (validate-form-template request @rems.config/languages)]
     (when-not form-errors
       (put! "/api/forms/edit"
             {:params (assoc request :form/id (::form-id db))
              :handler (flash-message/default-success-handler :top
                                                              [text :t.administration/edit-form]
                                                              #(navigate! (str "/administration/forms/" (::form-id db))))
              :error-handler (flash-message/default-error-handler :top
                                                                  [text :t.administration/edit-form])}))
     {:db (assoc db ::form-errors form-errors)})))

;;;; preview auto-scrolling

(defn- true-height [element]
  (let [style (.getComputedStyle js/window element)]
    (+ (.-offsetHeight element)
       (parse-int (.-marginTop style))
       (parse-int (.-marginBottom style)))))

(defn- set-visibility-ratio [frame element ratio]
  (when (and frame element)
    (let [element-top (- (.-offsetTop element) (.-offsetTop frame))
          element-height (true-height element)
          top-margin (/ (.-offsetHeight frame) 4)
          position (+ element-top
                      element-height
                      (* -1 ratio element-height)
                      (- top-margin))]
      (-> frame
          (.scrollTo 0 position)))))

(defn- partially-visible? [^js elem] (<= 0 (-> elem .getBoundingClientRect .-bottom)))

(defn- autoscroll []
  (b/when-some [edit-field (->> (.querySelectorAll js/document "#create-form .form-field:not(.new-form-field)")
                                array-seq
                                (find-first partially-visible?))
                id (data-field-id edit-field)
                preview-frame (.querySelector js/document "#preview-form-contents")
                preview-field (.querySelector js/document (preview-selector id))
                ratio (visibility-ratio edit-field)]
    (r/after-render
     #(set-visibility-ratio preview-frame preview-field ratio))))

(def ^:private use-autoscroll
  "Hook-like helper that attaches autoscroll event listener on component mount,
   and removes it on unmount."
  (ra/make-reaction #(.addEventListener js/window "scroll" autoscroll)
                    {:on-dispose #(.removeEventListener js/window "scroll" autoscroll)}))

;;;; UI

(rf/reg-sub ::get-in-form :<- [::form-data] (fn [form [_ key-path]] (get-in form key-path)))
(rf/reg-sub ::get-form-error :<- [::form-errors] (fn [errors [_ key-path]] (get-in errors key-path)))
(rf/reg-event-db ::update-form (fn [db [_ keys value]] (assoc-in db (concat [::form :data] keys) value)))

(def ^:private context
  {:get-in-form ::get-in-form
   :get-form-error ::get-form-error
   :update-form ::update-form})

(defn- form-organization-field []
  [organization-field context {:keys [:organization]}])

(defn- form-internal-name-field []
  [text-field context {:keys [:form/internal-name]
                       :label (text :t.administration/internal-name)}])

(defn- form-external-title-field []
  [localized-text-field context {:keys [:form/external-title]
                                 :label (text :t.administration/external-title)}])

(defn- form-field-id-field [field-index]
  [text-field-inline context {:keys [:form/fields field-index :field/id]
                              :label (text :t.create-form/field-id)}])

(defn- form-field-title-field [field-index]
  [localized-text-field context {:keys [:form/fields field-index :field/title]
                                 :label (text :t.create-form/field-title)}])

(defn- form-field-placeholder-field [field-index]
  [localized-text-field context {:keys [:form/fields field-index :field/placeholder]
                                 :collapse? true
                                 :label (text :t.create-form/placeholder)}])

(defn- form-field-info-text [field-index]
  [localized-text-field context {:keys [:form/fields field-index :field/info-text]
                                 :collapse? true
                                 :label (text :t.create-form/info-text)}])

(defn- form-field-max-length-field [field-index]
  [text-field-inline context {:keys [:form/fields field-index :field/max-length]
                              :label (text :t.create-form/maxlength)}])



;;; option fields

(defn- form-field-option-field [field-index option-index]
  [:div.form-field-option
   [:div.form-field-header
    [:h4 (text-format :t.create-form/option-n (inc option-index))]
    [:div.form-field-controls
     [items/move-up-button {:on-click #(rf/dispatch [::move-option-up field-index option-index])
                            :data-index option-index}]
     [items/move-down-button {:on-click #(rf/dispatch [::move-option-down field-index option-index])
                              :data-index option-index}]
     [items/remove-button {:on-click #(rf/dispatch [::remove-option field-index option-index])
                           :data-index option-index}]]]

   [text-field-inline context {:keys [:form/fields field-index :field/options option-index :key]
                               :label [text :t.create-form/option-key]
                               :normalizer common-form/normalize-option-key}]
   [localized-text-field context {:keys [:form/fields field-index :field/options option-index :label]
                                  :label [text :t.create-form/option-label]}]])

(defn- form-field-option-fields [field-index]
  (let [options @(rf/subscribe [::get-in-form [:form/fields field-index :field/options]])]
    [:div
     (into [:<>] (for [option-index (range (count options))]
                   [form-field-option-field field-index option-index]))
     [:div.form-field-option.new-form-field-option
      [atoms/action-link {:class "add-option"
                          :id (str "fields-" field-index "-add-option")
                          :label [:<>
                                  [atoms/add-symbol] " " [text :t.create-form/add-option]]
                          :on-click #(rf/dispatch [::add-option field-index])}]]]))



;;; column fields

;; TODO column code is an exact duplication of option code
;; TODO column code reuses some option styles

(defn- add-form-field-column-button [field-index]
  [:a.add-option {:href "#"
                  :id (str "fields-" field-index "-add-column")
                  :on-click (fn [event]
                              (.preventDefault event)
                              (rf/dispatch [::add-column field-index]))}
   [text :t.create-form/add-column]])

(defn- form-field-column-field [field-index column-index]
  [:div.form-field-option
   [:div.form-field-header
    [:h4 (text-format :t.create-form/column-n (inc column-index))]
    [:div.form-field-controls
     [items/move-up-button {:on-click #(rf/dispatch [::move-column-up field-index column-index])
                            :data-index column-index}]
     [items/move-down-button {:on-click #(rf/dispatch [::move-column-down field-index column-index])
                              :data-index column-index}]
     [items/remove-button {:on-click #(rf/dispatch [::remove-column field-index column-index])
                           :data-index column-index}]]]
   [text-field-inline context {:keys [:form/fields field-index :field/columns column-index :key]
                               :label (text :t.create-form/column-key)
                               :normalizer common-form/normalize-option-key}]
   [localized-text-field context {:keys [:form/fields field-index :field/columns column-index :label]
                                  :label (text :t.create-form/column-label)}]])

(defn- form-field-column-fields [field-index]
  (let [columns @(rf/subscribe [::get-in-form [:form/fields field-index :field/columns]])]
    [:div {:id (str "fields-" field-index "-columns")}
     (into [:<>]
           (for [column-index (range (count columns))]
             [form-field-column-field field-index column-index]))
     [:div.form-field-option.new-form-field-option
      [add-form-field-column-button field-index]]]))



;;; field visibility

(defn- select-visibility-type [context {:keys [id key-path]}]
  (let [value @(rf/subscribe [(:get-in-form context) key-path])
        error @(rf/subscribe [(:get-form-error context) key-path])]
    [:div.form-group.field.row
     [:label.col-sm-3.col-form-label {:for id} (text :t.create-form/type-visibility)]
     [:div.col-sm-9
      [:select.form-control
       {:id id
        :class (when error "is-invalid")
        :on-change #(->> (event-value %)
                         keyword
                         (update-form context key-path))
        :value (or value "")}
       [:option {:value "always"} (text :t.create-form.visibility/always)]
       [:option {:value "only-if"} (text :t.create-form.visibility/only-if)]]
      [field-validation-message error (text :t.create-form/type-visibility)]]]))

(defn- select-visibility-field [context {:keys [id key-path]}]
  (let [value @(rf/subscribe [(:get-in-form context) key-path])
        error @(rf/subscribe [(:get-form-error context) key-path])
        can-select-visibility? #(contains? #{:option :multiselect} (:field/type %))]
    [:div.form-group.field.row
     [:label.col-sm-3.col-form-label {:for id} (text :t.create-form.visibility/field)]
     [:div.col-sm-9
      (into [:select.form-control {:id id
                                   :class (when error "is-invalid")
                                   :on-change #(->> (event-value %)
                                                    (assoc {} :field/id)
                                                    (update-form context key-path))
                                   :value (or (:field/id value) "")}
             [:option ""]] ; not selected
            (for [[idx field] (indexed @(rf/subscribe [::fields]))
                  :when (can-select-visibility? field)
                  :let [option-label (text-format :t.create-form/field-n (inc idx) (localized (:field/title field)))]]
              [:option {:value (:field/id field)}
               option-label]))
      [field-validation-message error (text :t.create-form.visibility/field)]]]))

(defn- select-visibility-values [context {:keys [id key-path visibility-field-id]}]
  (let [values (set @(rf/subscribe [(:get-in-form context) key-path]))
        error @(rf/subscribe [(:get-form-error context) key-path])
        visibility-field @(rf/subscribe [::field visibility-field-id])]
    [:div.form-group.field.row
     [:label.col-sm-3.col-form-label {:for id} (text :t.create-form.visibility/has-value)]
     [:div.col-sm-9
      [dropdown/dropdown
       {:id id
        :items (->> (:field/options visibility-field)
                    (mapv #(assoc % ::label (localized (:label %)))))
        :item-key :key
        :item-label ::label
        :item-selected? #(contains? values (:key %))
        :multi? true
        :clearable? true
        :on-change #(->> (mapv :key %)
                         (update-form context key-path))}]
      [field-validation-message error (text :t.create-form.visibility/has-value)]]]))

(defn- form-field-visibility
  "Component for specifying form field visibility rules"
  [field-index]
  (let [key-path [:form/fields field-index :field/visibility]
        value @(rf/subscribe [(:get-in-form context) key-path])]
    [:div {:class (when (= :only-if (:visibility/type value))
                    "form-field-visibility")}
     [select-visibility-type context {:id (str "fields-" field-index "-visibility-type")
                                      :key-path [:form/fields field-index :field/visibility :visibility/type]}]
     (when (= :only-if (:visibility/type value))
       [:<>
        [select-visibility-field context {:id (str "fields-" field-index "-visibility-field")
                                          :key-path [:form/fields field-index :field/visibility :visibility/field]}]
        (when-let [visibility-field-id (get-in value [:visibility/field :field/id])]
          [select-visibility-values context {:id (str "fields-" field-index "-visibility-value")
                                             :key-path [:form/fields field-index :field/visibility :visibility/values]
                                             :visibility-field-id visibility-field-id}])])]))

(defn- form-field-privacy
  "Component for specifying form field privacy rules.

  Privacy concerns the reviewers as they can see only public fields."
  [field-index]
  (let [key-path [:form/fields field-index :field/privacy]
        value @(rf/subscribe [(:get-in-form context) key-path])
        error @(rf/subscribe [(:get-form-error context) key-path])
        id (str "fields-" field-index "-privacy-type")]
    [:div.form-group.field.row
     [:label.col-sm-2.col-form-label {:for id}
      [text :t.create-form/type-privacy]]
     [:div.col-sm-10
      [:select.form-control
       {:id id
        :class (when error "is-invalid")
        :on-change #(->> (event-value %)
                         keyword
                         (update-form context key-path))
        :value (or value "public")}
       [:option {:value "public"} (text :t.create-form.privacy/public)]
       [:option {:value "private"} (text :t.create-form.privacy/private)]]]]))

(defn- form-field-type-radio-group [field-index]
  [radio-button-group context {:id (str "radio-group-" field-index)
                               :keys [:form/fields field-index :field/type]
                               :label (text :t.create-form/field-type)
                               :orientation :horizontal
                               :options [{:value :description :label (text :t.create-form/type-description)}
                                         {:value :text :label (text :t.create-form/type-text)}
                                         {:value :texta :label (text :t.create-form/type-texta)}
                                         {:value :option :label (text :t.create-form/type-option)}
                                         {:value :multiselect :label (text :t.create-form/type-multiselect)}
                                         {:value :table :label (text :t.create-form/type-table)}
                                         {:value :date :label (text :t.create-form/type-date)}
                                         {:value :email :label (text :t.create-form/type-email)}
                                         {:value :phone-number :label (text :t.create-form/type-phone-number)}
                                         {:value :ip-address :label (text :t.create-form/type-ip-address)}
                                         {:value :attachment :label (text :t.create-form/type-attachment)}
                                         {:value :label :label (text :t.create-form/type-label)}
                                         {:value :header :label (text :t.create-form/type-header)}]}])

(defn- form-field-optional-checkbox [field-index]
  [checkbox context {:keys [:form/fields field-index :field/optional]
                     :label [text :t.create-form/optional]}])

(defn- form-field-table-optional-checkbox [field-index]
  [checkbox context {:keys [:form/fields field-index :field/optional]
                     :negate? true
                     :label (text :t.create-form/required-table)}])

(defn- add-form-field-button [index]
  [:a.add-form-field {:href "#"
                      :on-click (fn [event]
                                  (.preventDefault event)
                                  (rf/dispatch [::add-form-field index]))}
   [atoms/add-symbol] " " (text :t.create-form/add-form-field)])

(defn- format-validation-link [target content]
  [:li [:a {:href "#" :on-click (focus-input-field target)}
        content]])

(defn- format-error-for-localized-field [error label lang]
  (text-format error (str (text label) " (" (str/upper-case (name lang)) ")")))

(defn- format-field-validation [field field-index field-errors]
  (let [title (text-format :t.create-form/field-n
                           (inc field-index)
                           (localized (:field/title field)))]
    [:li title
     (into [:ul]
           (concat
            (for [[lang error] (:field/title field-errors)]
              (format-validation-link (str "fields-" field-index "-title-" (name lang))
                                      (format-error-for-localized-field error :t.create-form/field-title lang)))
            (for [[lang error] (:field/placeholder field-errors)]
              (format-validation-link (str "fields-" field-index "-placeholder-" (name lang))
                                      (format-error-for-localized-field error :t.create-form/placeholder lang)))
            (for [[lang error] (:field/info-text field-errors)]
              (format-validation-link (str "fields-" field-index "-info-text-" (name lang))
                                      (format-error-for-localized-field error :t.create-form/info-text lang)))
            (when (:field/max-length field-errors)
              [(format-validation-link (str "fields-" field-index "-max-length")
                                       (str (text :t.create-form/maxlength) ": " (text (:field/max-length field-errors))))])
            (when (-> field-errors :field/visibility :visibility/type)
              [(format-validation-link (str "fields-" field-index "-visibility-type")
                                       (str (text :t.create-form/type-visibility) ": " (text-format (-> field-errors :field/visibility :visibility/type) (text :t.create-form/type-visibility))))])
            (when (-> field-errors :field/visibility :visibility/field)
              [(format-validation-link (str "fields-" field-index "-visibility-field")
                                       (str (text :t.create-form/type-visibility) ": " (text-format (-> field-errors :field/visibility :visibility/field) (text :t.create-form.visibility/field))))])
            (when (-> field-errors :field/visibility :visibility/values)
              [(format-validation-link (str "fields-" field-index "-visibility-value")
                                       (str (text :t.create-form/type-visibility) ": " (text-format (-> field-errors :field/visibility :visibility/values) (text :t.create-form.visibility/has-value))))])
            (if (= :t.form.validation/options-required (:field/options field-errors))
              [[:li
                [:a {:href "#" :on-click #(focus/focus-selector (str "#fields-" field-index "-add-option"))}
                 (text :t.form.validation/options-required)]]]
              (for [[option-id option-errors] (into (sorted-map) (:field/options field-errors))]
                [:li (text-format :t.create-form/option-n (inc option-id))
                 [:ul
                  (when (:key option-errors)
                    (format-validation-link (str "fields-" field-index "-options-" option-id "-key")
                                            (text-format (:key option-errors) (text :t.create-form/option-key))))
                  (into [:<>]
                        (for [[lang error] (:label option-errors)]
                          (format-validation-link (str "fields-" field-index "-options-" option-id "-label-" (name lang))
                                                  (format-error-for-localized-field error :t.create-form/option-label lang))))]]))
            (if (= :t.form.validation/columns-required (:field/columns field-errors))
              [[:li
                [:a {:href "#" :on-click #(focus/focus-selector (str "#fields-" field-index "-add-column"))}
                 (text :t.form.validation/columns-required)]]]
              (for [[column-id column-errors] (into (sorted-map) (:field/columns field-errors))]
                [:li (text-format :t.create-form/column-n (inc column-id))
                 [:ul
                  (when (:key column-errors)
                    (format-validation-link (str "fields-" field-index "-columns-" column-id "-key")
                                            (text-format (:key column-errors) (text :t.create-form/option-key))))
                  (into [:<>]
                        (for [[lang error] (:label column-errors)]
                          (format-validation-link (str "fields-" field-index "-columns-" column-id "-label-" (name lang))
                                                  (format-error-for-localized-field error :t.create-form/option-label lang))))]]))))]))

;; XXX: shared component with rems.administration.form
(defn format-validation-errors [form-errors form]
  ;; TODO: deduplicate with field definitions
  (into [:ul
         (when-let [error (:organization form-errors)]
           (format-validation-link "organization"
                                   (text-format error (text :t.administration/organization))))

         (when-let [error (:form/internal-name form-errors)]
           (format-validation-link "internal-name"
                                   (text-format error (text :t.administration/internal-name))))

         (for [[lang error] (:form/external-title form-errors)]
           (format-validation-link (str "external-title-" (name lang))
                                   (format-error-for-localized-field error :t.administration/external-title lang)))]

        (for [[field-index field-errors] (into (sorted-map) (:form/fields form-errors))]
          (let [field (get-in form [:form/fields field-index])]
            [format-field-validation field field-index field-errors]))))

(defn- validation-errors-summary []
  (when-some [errors (not-empty @(rf/subscribe [::form-errors]))]
    [:div.alert.alert-danger (text :t.actions.errors/validation-errors)
     [format-validation-errors errors @(rf/subscribe [::form-data])]]))

(defn- field-controls [idx title field-count]
  [:div.form-field-header.d-flex
   [:h3 (text-format :t.create-form/field-n (inc idx) (localized title))]
   [:div.form-field-controls.text-nowrap.ml-auto
    [items/move-up-button {:on-click (when (> idx 0) #(rf/dispatch [::move-form-field-up idx]))
                           :data-index idx}]
    [items/move-down-button {:on-click (when (< idx (dec field-count)) #(rf/dispatch [::move-form-field-down idx]))
                             :data-index idx}]
    [items/remove-button {:on-click (let [label (text :t.create-form/confirm-remove-field)]
                                      #(when (js/confirm label)
                                         (rf/dispatch [::remove-form-field idx])))
                          :data-index idx}]]])

(defn- field-editor [idx field field-count]
  (let [columns? (common-form/supports-columns? field)
        info-text? (common-form/supports-info-text? field)
        max-length? (common-form/supports-max-length? field)
        optional? (common-form/supports-optional? field)
        options? (common-form/supports-options? field)
        placeholder? (common-form/supports-placeholder? field)
        privacy? (common-form/supports-privacy? field)
        visibility? (common-form/supports-visibility? field)
        collapsible-id (field-collapsible-id (:field/id field))]

    [collapsible/minimal
     {:id collapsible-id
      :always [field-controls idx (:field/title field) field-count]
      :footer [collapsible/toggle-control collapsible-id]
      :collapse [:<>
                 [form-field-title-field idx]
                 [form-field-type-radio-group idx]

                 (when optional?
                   (if (= :table (:field/type field))
                     [form-field-table-optional-checkbox idx]
                     [form-field-optional-checkbox idx]))

                 (when placeholder? [form-field-placeholder-field idx])
                 (when options? [form-field-option-fields idx])
                 (when info-text? [form-field-info-text idx])

                 (let [additional-settings-id (keys-to-id [:form/fields idx :additional-settings])]
                   [:div.form-group.field
                    [:label.administration-field-label.d-flex.align-items-center
                     (text :t.create-form/additional-settings)
                     [collapsible/toggle-control additional-settings-id]]
                    [collapsible/minimal
                     {:id additional-settings-id
                      :collapse [:div.solid-group
                                 [form-field-id-field idx]
                                 (when max-length? [form-field-max-length-field idx])
                                 (when privacy? [form-field-privacy idx])
                                 (when visibility? [form-field-visibility idx])]}]])

                 (when columns? [form-field-column-fields idx])]}]))

(defn- get-field-key [field idx]
  (or (not-blank (:field/id field))
      (str "new-field-" idx)))

(defn- form-fields []
  (let [fields (indexed @(rf/subscribe [::fields]))
        field-count (count fields)]
    (into [:<>
           [:div.new-form-field
            [add-form-field-button 0]]]
          (for [[idx field] fields]
            ^{:key (get-field-key field idx)}
            [:<>
             [:div.form-field {:data-field-id (:field/id field)
                               :data-field-index idx}
              [field-editor idx field field-count]]
             [:div.new-form-field
              [add-form-field-button (inc idx)]]]))))

(defn- render-preview-hidden []
  [:div {:style {:position :absolute
                 :top 0
                 :left 0
                 :right 0
                 :bottom 0
                 :z-index 1
                 :display :flex
                 :flex-direction :column
                 :justify-content :center
                 :align-items :flex-end
                 :border-radius "0.4rem"
                 :margin "-0.5rem"
                 :background-color "rgba(230,230,230,0.5)"}}
   [:div.pr-4 (text :t.create-form.visibility/hidden)]])

(defn- preview-field [field]
  (let [field-id (:field/id field)
        preview (r/cursor preview-fields [field-id])
        depended-id (common-form/field-depends-on-field field)]
    [:<>
     [fields/field (assoc field
                          :form/id 1 ; dummy value
                          :on-change (r/partial reset! preview)
                          :field/value @preview)]
     ;; performance trick, to avoid referencing all preview fields
     (when-not (field-visible? field {depended-id @(r/cursor preview-fields [depended-id])})
       [render-preview-hidden])]))

(defn- use-preview-fields
  "Resets form preview state on component unmount."
  []
  @(ra/make-reaction identity {:on-dispose #(reset! preview-fields nil)}))

;; XXX: shared component with rems.administration.form
(defn form-preview [& [form]]
  @(r/track use-preview-fields)

  (let [form-fields (if form
                      (:form/fields form)
                      @(rf/subscribe [::fields]))]
    [collapsible/component
     {:id "preview-form"
      :title (text :t.administration/preview)
      :always (into [:div#preview-form-contents]
                    (for [[idx field] (indexed form-fields)]
                      ^{:key (get-field-key field idx)}
                      [:div.field-preview {:data-field-id (:field/id field)
                                           :data-field-index idx}
                       [preview-field field]]))}]))

(defn- cancel-action []
  (atoms/cancel-action
   {:url (str "/administration/forms" (andstr "/" @(rf/subscribe [::form-id])))}))

(defn- save-form-action []
  (let [editing? @(rf/subscribe [::edit-form?])
        always-on-save #(rf/dispatch [:rems.spa/user-triggered-navigation])] ; scroll to top
    (atoms/save-action
     {:id :save
      :on-click (if editing?
                  (comp #(rf/dispatch [::edit-form]) always-on-save)
                  (comp #(rf/dispatch [::create-form]) always-on-save))})))

(defn- form-page-wrapper []
  @use-autoscroll

  [:div.row
   [:div.col-lg
    [collapsible/component
     {:id "create-form"
      :title (text :t.administration/form)
      :always [:div.fields
               [form-organization-field]
               [form-internal-name-field]
               [form-external-title-field]
               [form-fields]
               [:div.col.commands
                [atoms/action-button (cancel-action)]
                [atoms/rate-limited-action-button (save-form-action)]]]}]]
   [:div.col-lg [form-preview]]])

(defn create-form-page []
  (let [editing? @(rf/subscribe [::edit-form?])]
    [:div
     [administration/navigator]
     [document-title (if editing?
                       (text :t.administration/edit-form)
                       (text :t.administration/create-form))]
     [flash-message/component :top]
     (cond
       (and editing? (not @(rf/subscribe [::form :initialized?])))
       [spinner/big]

       (and editing? @(rf/subscribe [::form :fetching?]))
       [spinner/big]

       :else
       [:<>
        [validation-errors-summary]
        [form-page-wrapper]])]))
