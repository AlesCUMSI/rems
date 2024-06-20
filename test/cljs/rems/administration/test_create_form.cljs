(ns rems.administration.test-create-form
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [reagent.core :as r]
            [rems.administration.create-form :refer [build-request build-request-field]]
            [rems.common.util :refer [getx-in]]
            [rems.globals]
            [rems.testing :refer [init-spa-fixture]]))

(use-fixtures :each init-spa-fixture)

(defn- set-roles! [roles] (reset! rems.globals/roles roles))
(defn- set-languages! [languages] (r/rswap! rems.globals/config assoc :languages languages))

(defn- reset-form! [] (rf/dispatch-sync [:rems.administration.create-form/enter-page]))

(deftest add-form-field-test
  (set-roles! [:owner])
  (let [form (rf/subscribe [:rems.administration.create-form/form-data])]
    (testing "adds fields"
      (reset-form!)
      (is (= nil @form)
          "before")

      (rf/dispatch-sync [:rems.administration.create-form/add-form-field])

      (is (= {:form/fields [{:field/id "fld1" :field/type :text}]}
             @form)
          "after"))

    (testing "adds fields to the end"
      (reset-form!)
      (rf/dispatch-sync [:rems.administration.create-form/add-form-field])
      (rf/dispatch-sync [:rems.administration.create-form/update-form [:form/fields 0 :foo] "old field"])
      (is (= {:form/fields [{:field/id "fld1" :field/type :text :foo "old field"}]}
             @form)
          "before")

      (rf/dispatch-sync [:rems.administration.create-form/add-form-field])

      (is (= {:form/fields [{:field/id "fld1" :field/type :text :foo "old field"}
                            {:field/id "fld2" :field/type :text}]}
             @form)
          "after"))))

(deftest remove-form-field-test
  (set-roles! [:owner])
  (let [form (rf/subscribe [:rems.administration.create-form/form-data])]
    (testing "removes fields"
      (reset-form!)
      (rf/dispatch-sync [:rems.administration.create-form/add-form-field])
      (is (= {:form/fields [{:field/id "fld1" :field/type :text}]}
             @form)
          "before")

      (rf/dispatch-sync [:rems.administration.create-form/remove-form-field 0])

      (is (= {:form/fields []}
             @form)
          "after"))

    (testing "removes only the field at the specified index"
      (reset-form!)
      (rf/dispatch-sync [:rems.administration.create-form/add-form-field])
      (rf/dispatch-sync [:rems.administration.create-form/add-form-field])
      (rf/dispatch-sync [:rems.administration.create-form/add-form-field])
      (rf/dispatch-sync [:rems.administration.create-form/update-form [:form/fields 0 :foo] "field 0"])
      (rf/dispatch-sync [:rems.administration.create-form/update-form [:form/fields 1 :foo] "field 1"])
      (rf/dispatch-sync [:rems.administration.create-form/update-form [:form/fields 2 :foo] "field 2"])
      (is (= {:form/fields [{:field/id "fld1" :field/type :text :foo "field 0"}
                            {:field/id "fld2" :field/type :text :foo "field 1"}
                            {:field/id "fld3" :field/type :text :foo "field 2"}]}
             @form)
          "before")

      (rf/dispatch-sync [:rems.administration.create-form/remove-form-field 1])

      (is (= {:form/fields [{:field/id "fld1" :field/type :text :foo "field 0"}
                            {:field/id "fld3" :field/type :text :foo "field 2"}]}
             @form)
          "after"))))

(deftest move-form-field-up-test
  (set-roles! [:owner])
  (let [form (rf/subscribe [:rems.administration.create-form/form-data])]
    (testing "moves fields up"
      (reset-form!)
      (rf/dispatch-sync [:rems.administration.create-form/add-form-field])
      (rf/dispatch-sync [:rems.administration.create-form/add-form-field])
      (rf/dispatch-sync [:rems.administration.create-form/add-form-field])
      (rf/dispatch-sync [:rems.administration.create-form/update-form [:form/fields 0 :foo] "field 0"])
      (rf/dispatch-sync [:rems.administration.create-form/update-form [:form/fields 1 :foo] "field 1"])
      (rf/dispatch-sync [:rems.administration.create-form/update-form [:form/fields 2 :foo] "field X"])
      (is (= {:form/fields [{:field/id "fld1" :field/type :text :foo "field 0"}
                            {:field/id "fld2" :field/type :text :foo "field 1"}
                            {:field/id "fld3" :field/type :text :foo "field X"}]}
             @form)
          "before")

      (rf/dispatch-sync [:rems.administration.create-form/move-form-field-up 2])

      (is (= {:form/fields [{:field/id "fld1" :field/type :text :foo "field 0"}
                            {:field/id "fld3" :field/type :text :foo "field X"}
                            {:field/id "fld2" :field/type :text :foo "field 1"}]}
             @form)
          "after move 1")

      (rf/dispatch-sync [:rems.administration.create-form/move-form-field-up 1])

      (is (= {:form/fields [{:field/id "fld3" :field/type :text :foo "field X"}
                            {:field/id "fld1" :field/type :text :foo "field 0"}
                            {:field/id "fld2" :field/type :text :foo "field 1"}]}
             @form)
          "after move 2")

      (testing "unless already first"
        (rf/dispatch-sync [:rems.administration.create-form/move-form-field-up 0])

        (is (= {:form/fields [{:field/id "fld3" :field/type :text :foo "field X"}
                              {:field/id "fld1" :field/type :text :foo "field 0"}
                              {:field/id "fld2" :field/type :text :foo "field 1"}]}
               @form)
            "after move 3")))))

(deftest move-form-field-down-test
  (reset! rems.globals/roles [:owner])
  (let [form (rf/subscribe [:rems.administration.create-form/form-data])]
    (testing "moves fields down"
      (reset-form!)
      (rf/dispatch-sync [:rems.administration.create-form/add-form-field])
      (rf/dispatch-sync [:rems.administration.create-form/add-form-field])
      (rf/dispatch-sync [:rems.administration.create-form/add-form-field])
      (rf/dispatch-sync [:rems.administration.create-form/update-form [:form/fields 0 :foo] "field X"])
      (rf/dispatch-sync [:rems.administration.create-form/update-form [:form/fields 1 :foo] "field 1"])
      (rf/dispatch-sync [:rems.administration.create-form/update-form [:form/fields 2 :foo] "field 2"])
      (is (= {:form/fields [{:field/id "fld1" :field/type :text :foo "field X"}
                            {:field/id "fld2" :field/type :text :foo "field 1"}
                            {:field/id "fld3" :field/type :text :foo "field 2"}]}
             @form)
          "before")

      (rf/dispatch-sync [:rems.administration.create-form/move-form-field-down 0])

      (is (= {:form/fields [{:field/id "fld2" :field/type :text :foo "field 1"}
                            {:field/id "fld1" :field/type :text :foo "field X"}
                            {:field/id "fld3" :field/type :text :foo "field 2"}]}
             @form)
          "after move 1")

      (rf/dispatch-sync [:rems.administration.create-form/move-form-field-down 1])

      (is (= {:form/fields [{:field/id "fld2" :field/type :text :foo "field 1"}
                            {:field/id "fld3" :field/type :text :foo "field 2"}
                            {:field/id "fld1" :field/type :text :foo "field X"}]}
             @form)
          "after move 2")

      (testing "unless already last"
        (rf/dispatch-sync [:rems.administration.create-form/move-form-field-down 2])

        (is (= {:form/fields [{:field/id "fld2" :field/type :text :foo "field 1"}
                              {:field/id "fld3" :field/type :text :foo "field 2"}
                              {:field/id "fld1" :field/type :text :foo "field X"}]}
               @form)
            "after move 3")))))

(deftest build-request-field-test
  (set-languages! [:en :fi])

  (let [fields [{:field/id "fld1"
                 :field/title {:en "en title"
                               :fi "fi title"}
                 :field/info-text {:en "en info text"
                                   :fi "fi info text"}
                 :field/optional true
                 :field/type :text
                 :field/max-length "12"
                 :field/placeholder {:en "en placeholder"
                                     :fi "fi placeholder"}}]]
    (testing "basic fields"
      (is (= [{:field/id "fld1"
               :field/title {:en "en title"
                             :fi "fi title"}
               :field/info-text {:en "en info text"
                                 :fi "fi info text"}
               :field/optional true
               :field/type :text
               :field/max-length 12
               :field/placeholder {:en "en placeholder"
                                   :fi "fi placeholder"}}]
             (mapv build-request-field fields)))

      (testing "empty info texts should be passed on except when all empty"
        (is (= [{:field/id "fld1"
                 :field/title {:en "en title"
                               :fi "fi title"}
                 :field/optional true
                 :field/type :text
                 :field/max-length 12
                 :field/placeholder {:en "en placeholder"
                                     :fi "fi placeholder"}}]
               (mapv build-request-field
                     (assoc-in fields [0 :field/info-text] {:en "" :fi ""}))))
        (is (= [{:field/id "fld1"
                 :field/title {:en "en title"
                               :fi "fi title"}
                 :field/info-text {:en "en info text"
                                   :fi ""}
                 :field/optional true
                 :field/type :text
                 :field/max-length 12
                 :field/placeholder {:en "en placeholder"
                                     :fi "fi placeholder"}}]

               (mapv build-request-field
                     (assoc-in fields [0 :field/info-text :fi] ""))))))))

(deftest build-request-test
  (set-languages! [:en :fi])

  (let [form {:organization {:organization/id "abc"}
              :form/internal-name "the name"
              :form/external-title {:en "en external title"
                                    :fi "fi external title"}
              :form/fields [{:field/id "fld1"
                             :field/title {:en "en title"
                                           :fi "fi title"}
                             :field/info-text {:en "en info text"
                                               :fi "fi info text"}
                             :field/optional true
                             :field/type :text
                             :field/max-length "12"
                             :field/placeholder {:en "en placeholder"
                                                 :fi "fi placeholder"}}]}]

    (testing "basic form"
      (is (= {:organization {:organization/id "abc"}
              :form/internal-name "the name"
              :form/external-title {:en "en external title"
                                    :fi "fi external title"}
              :form/fields [{:field/id "fld1"
                             :field/title {:en "en title"
                                           :fi "fi title"}
                             :field/info-text {:en "en info text"
                                               :fi "fi info text"}
                             :field/optional true
                             :field/type :text
                             :field/max-length 12
                             :field/placeholder {:en "en placeholder"
                                                 :fi "fi placeholder"}}]}
             (build-request form))))

    (testing "with empty info text"
      (is (= {:organization {:organization/id "abc"}
              :form/internal-name "the name"
              :form/external-title {:en "en external title"
                                    :fi "fi external title"}
              :form/fields [{:field/id "fld1"
                             :field/title {:en "en title"
                                           :fi "fi title"}
                             :field/optional true
                             :field/type :text
                             :field/max-length 12
                             :field/placeholder {:en "en placeholder"
                                                 :fi "fi placeholder"}}]}
             (build-request (assoc-in form [:form/fields 0 :field/info-text] {:en "" :fi ""})))))

    (testing "with missing title"
      (is (= {:form/external-title {:en "en external title" :fi ""}}
             (select-keys (build-request (assoc-in form [:form/external-title :fi] "")) [:form/external-title])))
      (is (= {:form/external-title {:en "en external title" :fi nil}}
             (select-keys (build-request (assoc-in form [:form/external-title :fi] nil)) [:form/external-title]))))

    (testing "trim strings"
      (is (= {:organization {:organization/id "abc"}
              :form/internal-name "the name"
              :form/external-title {:en "en external title"
                                    :fi "fi external title"}
              :form/fields [{:field/id "fld1"
                             :field/title {:en "en title"
                                           :fi "fi title"}
                             :field/info-text {:en "en info text"
                                               :fi "fi info text"}
                             :field/optional true
                             :field/type :text
                             :field/max-length 12
                             :field/placeholder {:en "en placeholder"
                                                 :fi "fi placeholder"}}]}
             (build-request (assoc form :form/internal-name " the name\t\n")))))

    (testing "zero fields"
      (is (= {:organization {:organization/id "abc"}
              :form/internal-name "the name"
              :form/external-title {:en "en external title"
                                    :fi "fi external title"}
              :form/fields []}
             (build-request (assoc-in form [:form/fields] [])))))

    (testing "date fields"
      (let [form (assoc-in form [:form/fields 0 :field/type] :date)]
        (is (= {:organization {:organization/id "abc"}
                :form/internal-name "the name"
                :form/external-title {:en "en external title"
                                      :fi "fi external title"}
                :form/fields [{:field/id "fld1"
                               :field/title {:en "en title"
                                             :fi "fi title"}
                               :field/info-text {:en "en info text"
                                                 :fi "fi info text"}
                               :field/optional true
                               :field/type :date}]}
               (build-request form)))))

    (testing "missing optional implies false"
      (is (false? (getx-in (build-request (assoc-in form [:form/fields 0 :field/optional] nil))
                           [:form/fields 0 :field/optional]))))

    (testing "placeholder is optional"
      (is (= nil
             (get-in (build-request (assoc-in form [:form/fields 0 :field/placeholder] nil))
                     [:form/fields 0 :field/placeholder])
             (get-in (build-request (assoc-in form [:form/fields 0 :field/placeholder] {:en ""}))
                     [:form/fields 0 :field/placeholder])
             (get-in (build-request (assoc-in form [:form/fields 0 :field/placeholder] {:en "" :fi ""}))
                     [:form/fields 0 :field/placeholder]))))

    (testing "max length is optional"
      (is (nil? (getx-in (build-request (assoc-in form [:form/fields 0 :field/max-length] ""))
                         [:form/fields 0 :field/max-length])))
      (is (nil? (getx-in (build-request (assoc-in form [:form/fields 0 :field/max-length] nil))
                         [:form/fields 0 :field/max-length]))))

    (testing "option fields"
      (let [form (-> form
                     (assoc-in [:form/fields 0 :field/type] :option)
                     (assoc-in [:form/fields 0 :field/options] [{:key "yes"
                                                                 :label {:en "en yes"
                                                                         :fi "fi yes"}}
                                                                {:key "no"
                                                                 :label {:en "en no"
                                                                         :fi "fi no"}}]))]
        (is (= {:organization {:organization/id "abc"}
                :form/internal-name "the name"
                :form/external-title {:en "en external title"
                                      :fi "fi external title"}
                :form/fields [{:field/id "fld1"
                               :field/title {:en "en title"
                                             :fi "fi title"}
                               :field/info-text {:en "en info text"
                                                 :fi "fi info text"}
                               :field/optional true
                               :field/type :option
                               :field/options [{:key "yes"
                                                :label {:en "en yes"
                                                        :fi "fi yes"}}
                                               {:key "no"
                                                :label {:en "en no"
                                                        :fi "fi no"}}]}]}
               (build-request form)))))

    (testing "multiselect fields"
      (let [form (-> form
                     (assoc-in [:form/fields 0 :field/type] :multiselect)
                     (assoc-in [:form/fields 0 :field/options] [{:key "egg"
                                                                 :label {:en "Egg"
                                                                         :fi "Munaa"}}
                                                                {:key "bacon"
                                                                 :label {:en "Bacon"
                                                                         :fi "Pekonia"}}]))]
        (is (= {:organization {:organization/id "abc"}
                :form/internal-name "the name"
                :form/external-title {:en "en external title"
                                      :fi "fi external title"}
                :form/fields [{:field/id "fld1"
                               :field/title {:en "en title"
                                             :fi "fi title"}
                               :field/info-text {:en "en info text"
                                                 :fi "fi info text"}
                               :field/optional true
                               :field/type :multiselect
                               :field/options [{:key "egg"
                                                :label {:en "Egg"
                                                        :fi "Munaa"}}
                                               {:key "bacon"
                                                :label {:en "Bacon"
                                                        :fi "Pekonia"}}]}]}
               (build-request form)))))

    (testing "privacy"
      (is (nil? (get-in (build-request (assoc-in form [:form/fields 0 :field/privacy] :public))
                        [:form/fields 0 :field/privacy]))
          "public is the default so nothing needs to be included")
      (is (nil? (get-in (build-request (assoc-in form [:form/fields 0 :field/privacy] :does-not-exist))
                        [:form/fields 0 :field/privacy]))
          "wrong data is not included")
      (is (= :private
             (getx-in (build-request (assoc-in form [:form/fields 0 :field/privacy] :private))
                      [:form/fields 0 :field/privacy]))))
    (testing "visibility"
      (let [form (-> form
                     (assoc-in [:form/fields 0 :field/id] "fld1")
                     (assoc-in [:form/fields 0 :field/type] :option)
                     (assoc-in [:form/fields 0 :field/options] [{:key "yes"
                                                                 :label {:en "en yes"
                                                                         :fi "fi yes"}}
                                                                {:key "no"
                                                                 :label {:en "en no"
                                                                         :fi "fi no"}}])
                     (assoc-in [:form/fields 1] {:field/id "fld2"
                                                 :field/title {:en "en title additional"
                                                               :fi "fi title additional"}
                                                 :field/optional false
                                                 :field/type :text
                                                 :field/info-text {:en "en info text"
                                                                   :fi "fi info text"}
                                                 :field/max-length "12"
                                                 :field/placeholder {:en "en placeholder"
                                                                     :fi "fi placeholder"}}))]

        (testing "default"
          (is (nil? (get-in (build-request (assoc-in form [:form/fields 1 :field/visibility] {:visibility/type :always}))
                            [:form/fields 1 :field/visibility]))
              "always is the default so nothing needs to be included")
          (is (= {:visibility/type :only-if}
                 (getx-in (build-request (assoc-in form
                                                   [:form/fields 1 :field/visibility]
                                                   {:visibility/type :only-if}))
                          [:form/fields 1 :field/visibility]))
              "missing data is not present"))

        (testing "correct data"
          (is (= {:field/id "fld2"
                  :field/title {:en "en title additional"
                                :fi "fi title additional"}
                  :field/info-text {:en "en info text"
                                    :fi "fi info text"}
                  :field/optional false
                  :field/type :text
                  :field/max-length 12
                  :field/placeholder {:en "en placeholder"
                                      :fi "fi placeholder"}
                  :field/visibility {:visibility/type :only-if
                                     :visibility/field {:field/id "fld1"}
                                     :visibility/values ["yes"]}}
                 (getx-in (build-request (assoc-in form
                                                   [:form/fields 1 :field/visibility]
                                                   {:visibility/type :only-if
                                                    :visibility/field {:field/id "fld1"}
                                                    :visibility/values ["yes"]}))
                          [:form/fields 1]))))))))
