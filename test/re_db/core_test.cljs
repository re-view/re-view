(ns re-db.core-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-db.core2 :as d :include-macros true])
  (:require-macros [tests.helpers :refer [throws]]))

(deftest basic
  (let [db (d/create {})
        tx-log (atom [])]

    (is (satisfies? cljs.core/IDeref db)
        "DB is an atom")

    (d/listen! db :tx-log #(swap! tx-log conj %))

    (d/transact! db [{:db/id "herman"}])

    (is (false? (d/contains? @db "herman"))
        "Inserting an entity without attributes is no-op")

    (d/transact! db [{:db/id "herman" :occupation "teacher"}])

    (is (= (last @tx-log)
           [["herman" :occupation "teacher" nil]])
        "Tx-log listener called with datoms")

    (is (= {:db/id "herman" :occupation "teacher"} (d/entity @db "herman"))
        "Entity is returned as it was inserted")

    (is (= "herman" (:db/id (d/entity @db "herman")))
        "Entity is returned with :db/id attribute")

    (is (= "teacher" (d/get @db "herman" :occupation))
        "d/get an attribute of an entity")

    (is (= 1 (count (d/entities @db [:occupation "teacher"])))
        "Query on non-indexed attr")

    (d/transact! db [{:db/id "fred" :occupation "teacher"}])

    (is (= 2 (count (d/entities @db [:occupation "teacher"])))
        "Verify d/insert! and query on non-indexed field")

    (d/transact! db [[:db/retract-attr "herman" :occupation]])

    (is (nil? (d/get @db "herman" :occupation))
        "Retract attribute")

    (is (= 1 (count (d/entities @db [:occupation "teacher"])))
        "Retract non-indexed field")

    (d/transact! db [[:db/retract-attr "herman" :id]])
    (is (nil? (d/entity @db "herman"))
        "Entity with no attributes is removed")

    (is (false? (contains? (get-in @db [:ave :id]) "herman"))
        "Index has been removed")

    (d/transact! db [{:db/id "me"
                      :dog   "herman"
                      :name  "Matt"}])
    (d/transact! db [{:db/id "me"
                      :dog   nil}])

    (is (= (d/entity @db "me") {:db/id "me" :name "Matt"})
        "Setting a value to nil is equivalent to retracting it")

    (is (= :error (try (d/transact! db [[:db/add "fred" :db/id "some-other-id"]])
                       nil
                       (catch js/Error e :error)))
        "Cannot change :db/id of entity")))

(deftest lookup-refs
  (let [db (-> (d/create {:email {:db/index :db.index/unique}})
               (d/transact! [{:db/id "fred"
                              :email "fred@example.com"}]))]

    (is (= (d/entity @db "fred")
           (d/entity @db [:email "fred@example.com"]))
        "Can substitute unique attr for id (à la 'lookup refs')")))

(deftest refs
  (let [db (-> (d/create {:owner {:db/type :db.type/ref}})
               (d/transact! [{:db/id "fred"
                              :name  "Fred"}
                             {:db/id "ball"
                              :name  "Ball"
                              :owner "fred"}]))]
    (is (= {:db/id  "fred"
            :name   "Fred"
            :_owner #{"ball"}} (d/touch @db (d/entity @db "fred")))
        "touch adds refs to entity"))

  (let [db (-> (d/create {:authors {:db/type        :db.type/ref
                                    :db/cardinality :db.cardinality/many}})
               (d/transact! [{:db/id "fred"
                              :name  "Fred"}
                             {:db/id "mary"
                              :name  "Mary"}
                             {:db/id   "1"
                              :name    "One"
                              :authors #{"fred" "mary"}}]))]
    (is (= {:db/id    "fred"
            :name     "Fred"
            :_authors #{"1"}} (d/touch @db (d/entity @db "fred")))
        "refs with cardinality-many")))

(deftest cardinality-many
  (let [db (-> (d/create {:db/id    {:db/index :db.index/unique}
                          :children {:db/cardinality :db.cardinality/many
                                     :db/index       true}})
               (d/transact! [{:db/id    "fred"
                              :children #{"pete"}}]))]

    (is (true? (contains? (get-in @db [:ave :children]) "pete"))
        "cardinality/many attribute can be indexed")

    ;; second child
    (d/transact! db [[:db/add "fred" :children #{"sally"}]])

    (is (= #{"sally" "pete"} (d/get @db "fred" :children))
        "cardinality/many attribute returned as set")

    (is (= #{"fred"}
           (d/entity-ids @db [:children "sally"])
           (d/entity-ids @db [:children "pete"]))
        "look up via cardinality/many index")

    (testing "remove value from cardinality/many attribute"
      (d/transact! db [[:db/retract-attr "fred" :children #{"sally"}]])

      (is (= #{} (d/entity-ids @db [:children "sally"]))
          "index is removed on retraction")
      (is (= #{"fred"} (d/entity-ids @db [:children "pete"]))
          "index remains for other value")
      (is (= #{"pete"} (d/get @db "fred" :children))
          "attribute has correct value"))

    (testing "unique cardinality/many attribute"

      (d/merge-schema! db {:pets {:db/cardinality :db.cardinality/many
                                  :db/index       :db.index/unique}})

      (d/transact! db [[:db/add "fred" :pets #{"fido"}]])

      (is (= "fred" (:db/id (d/entity @db [:pets "fido"]))))

      (throws (d/transact! db [[:db/add "herman" :pets #{"fido"}]])
              "some message"))))

(deftest listeners
  (let [db (d/create {:person/children {:db/cardinality :db.cardinality/many
                                        :db/index       :db.index/unique}})
        log (atom {})
        cb (fn [path] (partial swap! log assoc path))]

    (d/transact! db [{:db/id "mary"
                      :name  "Mary"}
                     [:db/add "mary"
                      :person/children #{"john"}]
                     {:db/id "john"
                      :name  "John"}])

    (is (= "Mary" (d/get @db [:person/children "john"] :name)))

    (d/listen! db ["mary"] (cb :mary-entity))

    (d/listen! db [[:person/children "peter"]] (fn [& args]
                                                 (apply (cb :peter-children) args)))

    (d/transact! db [{:db/id "peter"
                      :name  "Peter"}
                     [:db/add "mary"
                      :person/children #{"peter"}]
                     [:db/add "mary" :name "MMMary"]])

    (is (= "MMMary" (:name (d/entity @db (:mary-entity @log))))
        "Entity listener called with entity id")

    (is (= [:person/children "peter"] (:peter-children @log))
        "Lookup ref entity listener is called with lookup ref")

    (d/transact! db [[:db/retract-attr "mary" :person/children #{"peter"}]])

    (is (and (= [:person/children "peter"] (:peter-children @log))
             (= nil (d/entity @db (:peter-children @log))))
        "Lookup ref entity listener is called with lookup ref")


    ;; attr-val listeners - look for `nil` on retraction, id on added

    ))