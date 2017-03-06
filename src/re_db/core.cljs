(ns re-db.core
  (:refer-clojure
    :exclude [get get-in select-keys set! peek contains?]
    :rename {get         get*
             contains?   contains?*
             select-keys select-keys*})
  (:require [cljs-uuid-utils.core :as uuid-utils]
            [clojure.set :as set])
  (:require-macros [re-db.core :refer [capture-patterns
                                       get-in*]]))

(enable-console-print!)

(def ^:dynamic *db-log* nil)                                ;; maintains tx-report while bound
(def ^:dynamic *mute* false)                                ;; ignore listeners
(def ^:dynamic *access-log* nil)                            ;; capture access patterns
(def ^:dynamic *debug* false)

(def id-attr :db/id)

(def fnil-conj-set (fnil conj #{}))

(defn log-read [path f v]
  (when *access-log*
    (set! *access-log* (update-in *access-log* path f v))))

(defn create
  "Create a new db, with optional schema, which should be a mapping of attribute keys to
  the following options:

    :db/index       [true, :db.index/unique]
    :db/cardinality [:db.cardinality/many]"
  ([] (create {}))
  ([schema]
   (atom {:eav    {}
          :ave    {}
          :schema schema})))

(defn merge-schema!
  "Merge additional schema options into a db. Indexes are not created for existing data."
  [db schema]
  (swap! db update :schema merge schema))

(defn index?
  "Returns true if attribute is indexed."
  ([schema]
   (contains?* schema :db/index))
  ([db-snap a]
   (index? (get-in* db-snap [:schema a]))))

(defn many?
  "Returns true for attributes with cardinality `many`, which store a set of values for each attribute."
  ([{:keys [db/cardinality]}]
   (keyword-identical? :db.cardinality/many cardinality))
  ([db-snap a]
   (many? (get-in* db-snap [:schema a]))))

(defn unique?
  "Returns true for attributes where :db/index is :db.index/unique."
  ([{:keys [db/index]}]
   (keyword-identical? :db.index/unique index))
  ([db-snap a]
   (unique? (get-in* db-snap [:schema a]))))

(defn ref?
  [{:keys [db/type]}]
  (keyword-identical? :db.type/ref type))

(defn get-schema [db-snap a]
  (get-in* db-snap [:schema a]))

(defn resolve-id
  "Returns id, resolving lookup refs (vectors of the form `[attribute value]`) to ids. Lookup refs are only supported for indexed attributes.
  Arity 3 is for known lookup refs, and does not check for uniqueness."
  ([db-snap attr val]
   (log-read [:attr-val] conj [nil attr val])
   (first (get-in* db-snap [:ave attr val])))
  ([db-snap id]
   (if ^:boolean (vector? id)
     (let [[attr val] id]
       (if-not (unique? db-snap attr)
         (throw (js/Error (str "Not a unique attribute: " attr ", with value: " val)))
         (resolve-id db-snap attr val)))
     id)))

(defn contains?
  "Returns true if entity with given id exists in db."
  [db-snap id]
  (contains?* (get* db-snap :eav) (resolve-id db-snap id)))

(defn- listen-path!
  [db path f]
  (doto db
    (swap! update-in path #(fnil-conj-set % f))))

(defn- unlisten-path!
  [db path f]
  (doto db
    (swap! update-in path disj f)))

(declare get entity)

(defn pattern->idx
  "Returns type of listener pattern."
  [pattern]
  (let [[id attr val] (mapv #(if (= % '_) nil %) pattern)]
    (cond (and id attr) :entity-attr
          (and attr val) :attr-val
          id :id
          attr :attr
          :else nil)))

(defn- pattern->listener-path
  "Returns path to listener set for the given access pattern."
  [[id attr val :as pattern]]
  (case (pattern->idx pattern)
    :entity-attr [:listeners :entity-attr id attr]
    :attr-val [:listeners :attr-val attr val]
    :id [:listeners :entity id]
    :attr [:listeners :attr attr]
    nil [:listeners :tx-log]))

(declare listen! unlisten!)

(defn- listen-lookup-ref!
  "Listen for a pattern where the id is a lookup ref (Requires an intermediate listener.)"
  [db pattern f]
  (let [[[lookup-attr lookup-val :as lookup-ref] attr] pattern]
    (let [unlisten (atom)
          cb #(f lookup-ref)]
      (listen! db [nil lookup-attr lookup-val]
               (fn [datom]
                 (let [added? (not (nil? (datom 2)))]
                   (when added? (reset! unlisten (listen! db [(datom 0) attr] cb)))
                   (when @unlisten (@unlisten) (reset! unlisten nil))
                   (cb))))
      (swap! db assoc-in [:listeners :lookup-ref pattern f]
             {:clear #(do (when @unlisten (@unlisten))
                          (swap! db update-in [:listeners :lookup-ref pattern] dissoc f))}))))

(defn unlisten!
  "Remove pattern listeners."
  [db & patterns]
  (let [f (last patterns)]
    (doseq [pattern (or (seq (drop-last patterns)) [[]])]
      (if (vector? (first pattern))
        ((get-in* @db [:listeners :lookup-ref pattern f :clear])))
      (unlisten-path! db (pattern->listener-path pattern) f))))

(defn listen!
  "Add pattern listeners. Supported patterns:
   [id attr _]  => :entity-attr
   [_ attr val] => :attr-val
   [id _ _]     => :entity
   [_ attr _]   => :attr"
  [db & patterns]
  (let [f (last patterns)]
    (doseq [pattern (seq (drop-last patterns))]
      (cond (= :tx-log pattern) (listen-path! db [:listeners :tx-log] f)
            (vector? (first pattern)) (listen-lookup-ref! db pattern f)
            :else (listen-path! db (pattern->listener-path pattern) f))))
  #(apply unlisten! db patterns))

(defn entity
  "Returns entity for resolved id."
  [db-snap id]
  (when-let [id (resolve-id db-snap id)]
    (log-read [:id] conj [id])
    (some-> (-> db-snap
                (get* :eav)
                (get* id))
            (assoc id-attr id))))

(defn get
  "Get attribute in entity with given id."
  ([db-snap id attr]
   (when-let [id (resolve-id db-snap id)]
     (log-read [:entity-attr id] fnil-conj-set [id attr])
     (get-in* db-snap [:eav id attr])))
  ([db-snap id attr not-found]
   (when-let [id (resolve-id db-snap id)]
     (log-read [:entity-attr id] fnil-conj-set [id attr])
     (get-in* db-snap [:eav id attr] not-found))))

(defn get-in
  "Get-in the entity with given id."
  ([db-snap id ks]
   (when-let [id (resolve-id db-snap id)]
     (log-read [:entity-attr id] fnil-conj-set [id (first ks)])
     (-> (get-in* db-snap [:eav id])
         (get-in* ks))))
  ([db-snap id ks not-found]
   (when-let [id (resolve-id db-snap id)]
     (log-read [:entity-attr id] fnil-conj-set [id (first ks)])
     (-> (get-in* db-snap [:eav id])
         (get-in* ks not-found)))))

(defn select-keys
  "Select keys from entity of id"
  [db-snap id ks]
  (when-let [id (resolve-id db-snap id)]
    (log-read [:entity-attr id] into (mapv #(do [id %]) ks))
    (-> (get-in* db-snap [:eav id])
        (assoc id-attr id)
        (select-keys* ks))))

(defn touch
  "Add refs to entity"
  [db-snap {:keys [db/id] :as entity}]
  (reduce-kv
    (fn [m attr ids]
      (assoc m (keyword (namespace attr) (str "_" (name attr))) ids))
    entity
    (get-in* db-snap [:vae id])))

(defn- add-index [[db-snap datoms] id a v schema]
  [(cond-> db-snap
           (index? schema) (update-in [:ave a v] fnil-conj-set id)
           (ref? schema) (update-in [:vae v a] fnil-conj-set id)) datoms])

(defn- add-index-many [state id attr additions schema]
  (reduce (fn [state v]
            (add-index state id attr v schema)) state additions))

(defn- remove-index [[db-snap datoms] id a v schema]
  [(cond-> db-snap
           (index? schema) (update-in [:ave a v] disj id)
           (ref? schema) (update-in [:vae v a] disj id)) datoms])

(defn- remove-index-many [state id attr removals schema]
  (reduce (fn [state v]
            (remove-index state id attr v schema))
          state
          removals))

(defn- clear-empty-ent [[db-snap datoms] id]
  [(cond-> db-snap
           (empty? (get-in* db-snap [:eav id])) (update :eav dissoc id))
   datoms])

(declare retract-attr)

(defn- disj-kill
  "m contains set at path attr. disj value from set; if empty, dissoc set."
  [m attr value]
  (if (= #{value} (get* m attr))
    (dissoc m attr)
    (update m attr disj value)))

(defn- retract-attr-many [[db-snap datoms :as state] id attr value schema]
  (let [prev-val (get-in* db-snap [:eav id attr])]
    (let [removals (if (nil? value) prev-val (set/intersection value prev-val))
          kill? (= removals prev-val)]
      (if (empty? removals)
        state
        (-> [(if kill? (update-in db-snap [:eav id] dissoc attr)
                       (update-in db-snap [:eav id attr] set/difference removals))
             (conj! datoms [id attr nil removals])]
            (remove-index-many id attr removals schema)
            (clear-empty-ent id))))))

(defn- retract-attr
  ([state id attr] (retract-attr state id attr (get-in* (state 0) [:eav id attr])))
  ([[db-snap datoms :as state] id attr value]
   (let [schema (get-schema db-snap attr)]
     (if (many? schema)
       (retract-attr-many state id attr value schema)
       (let [prev-val (if-not (nil? value) value (get-in* db-snap [:eav id attr]))]
         (if-not (nil? prev-val)
           (-> [(update-in db-snap [:eav id] dissoc attr)
                (conj! datoms [id attr nil prev-val])]
               (remove-index id attr prev-val (get-schema db-snap attr))
               (clear-empty-ent id))
           state))))))

(defn- retract-entity [state id]
  (reduce (fn [state [a v]]
            (retract-attr state id a v))
          state
          (entity (state 0) id)))

(defn- add
  [[db-snap datoms :as state] id attr val]
  {:pre [(not (keyword-identical? attr id-attr))]}
  (let [schema (get-schema db-snap attr)
        prev-val (get-in* db-snap [:eav id attr])]
    (if (many? schema)
      (let [additions (set/difference val prev-val)]
        (if (empty? additions)
          state
          (do
            (doseq [val additions]
              (assert (nil? (resolve-id db-snap attr val))))
            (-> [(update-in db-snap [:eav id attr] set/union additions)
                 (conj! datoms [id attr additions nil])]
                (add-index-many id attr additions schema)))))
      (if (= prev-val val)
        state
        (do
          (when (unique? schema)
            (assert (nil? (resolve-id db-snap attr val))))
          (cond-> (-> [(assoc-in db-snap [:eav id attr] val)
                       (conj! datoms [id attr val prev-val])]
                      (add-index id attr val schema))
                  prev-val (remove-index id attr prev-val schema)))))))

(defn- update-attr [[db-snap datoms :as state] id attr f & args]
  (let [prev-val (get-in* db-snap [:eav id attr])
        new-val (apply f prev-val args)]
    (if (many? db-snap attr)
      (let [additions (set/difference new-val prev-val)
            removals (set/difference prev-val new-val)]
        (cond-> state
                (not (empty? additions)) (add id attr additions)
                (not (empty? removals)) (add id attr removals)))
      (add [db-snap datoms] id attr new-val))))

(defn- assoc-in-attr [[db-snap datoms] id attr path new-val]
  (update-attr [db-snap datoms] id attr assoc-in path new-val))

(defn- notify-listeners [{:keys [db-after datoms]}]
  (when-let [listeners (get* db-after :listeners)]
    (let [seen-ids (atom #{})
          many? (reduce-kv (fn [s attr schema]
                             (cond-> s
                                     (many? schema) (conj attr))) #{} (get* db-after :schema))]

      (doseq [[id a v prev-v :as datom] datoms]

        (swap! seen-ids conj (datom 0))

        ;; entity-attr listeners
        (doseq [f (get-in* listeners [:entity-attr id a])]
          (f datom))

        ;; attr-val listeners
        (doseq [v (cond-> (or v prev-v)
                          (not (many? a)) (list))]
          (doseq [f (get-in* listeners [:attr-val a v])]
            (f datom)))

        ;; attr listeners
        (doseq [f (get-in* listeners [:attr a])]
          (f datom)))

      (doseq [id @seen-ids]
        (doseq [f (get-in* listeners [:entity id])]
          (f id)))

      ;; tx-log listeners
      (doseq [f (get* listeners :tx-log)] (f datoms)))))

(defn- map->txs!
  [m]
  (assert (contains?* m id-attr))
  (when-not (= 1 (count m))
    (let [id (get* m id-attr)]
      (reduce-kv (fn [txs attr val]
                   (conj txs (if (nil? val)
                               [:db/retract-attr id attr]
                               [:db/add id attr val]))) [] (dissoc m id-attr)))))

(defn- commit-tx [state tx]
  (apply (case (tx 0)
           :db/add add
           :db/update-attr update-attr
           :db/assoc-in-attr assoc-in-attr
           :db/retract-entity retract-entity
           :db/retract-attr retract-attr
           #(throw (js/Error (str "No re-db op: " (tx 0)))))
         (assoc tx 0 state)))

(defn- transaction [db-before new-txs]
  (let [resolve-id #(resolve-id db-before %)
        [db-after datoms] (reduce (fn [state tx]
                                    (if (vector? tx)
                                      (commit-tx state (update tx 1 resolve-id))
                                      (reduce commit-tx state (map->txs! (update tx id-attr resolve-id)))))
                                  [db-before (transient [])]
                                  new-txs)]
    {:db-before db-before
     :db-after  db-after
     :datoms    (persistent! datoms)}))

(defn transact!
  ([db txs] (transact! db txs {}))
  ([db txs options]
   (when-let [{:keys [db-after] :as tx} (cond (nil? txs) nil
                                              (and (map? txs) (contains?* txs :datoms)) txs
                                              (or (vector? txs)
                                                  (list? txs)
                                                  (seq? txs)) (transaction @db txs)
                                              :else (throw (js/Error "Transact! was not passed a valid transaction")))]
     (reset! db db-after)
     (when-not (nil? *db-log*)
       (reset! *db-log* (cond-> tx
                                (:db-before @*db-log*) (assoc :db-before @*db-log*))))
     (when-not (or (true? (get* options :mute)) (true? *mute*))
       (notify-listeners tx))
     db)))

(defn entity-ids
  [db-snap & qs]
  (->> qs
       (mapv (fn [q]
               (set (cond (fn? q)
                          (reduce-kv (fn [s id entity] (if ^:boolean (q entity) (conj s id) s)) #{} (get* db-snap :eav))

                          (keyword? q)
                          (do (log-read [:attr] conj [nil q nil])
                              (reduce-kv (fn [s id entity] (if ^:boolean (contains?* entity q) (conj s id) s)) #{} (get* db-snap :eav)))

                          :else
                          (let [[attr val] q]
                            (log-read [:attr-val] conj [nil attr val])
                            (if (index? db-snap attr)
                              (get-in* db-snap [:ave attr val])
                              (do (when ^:boolean (true? *debug*) (println (str "Not an indexed attribute: " attr)))
                                  (entity-ids db-snap #(= val (get* % attr))))))))))
       (apply set/intersection)))

(defn entities
  [db-snap & qs]
  (map (partial entity db-snap) (apply entity-ids db-snap qs)))

(defn squuid []
  (str (uuid-utils/make-random-uuid)))

(def blank-access-log
  {:id          #{}
   :attr        #{}
   :attr-val    #{}
   :entity-attr {}})

(defn access-log-patterns
  "Returns vector of access log patterns, pruning overlapping patterns"
  [{ids :id attr-vals :attr-val entity-attrs :entity-attr attrs :attr}]
  (apply set/union ids attr-vals attrs (vals (apply dissoc entity-attrs (map first ids)))))

(defn inc-lex
  "Increment string lexicographically"
  [c]
  (let [len (.-length c)]
    (str (subs c 0 (dec len))
         (.fromCodePoint js/String (inc (.codePointAt (aget c (dec len))))))))

(defn subseq-prefix
  "Subseq of keys that begin with string prefix"
  [sorted-m prefix]
  (subseq sorted-m >= prefix <= (inc-lex prefix)))

;; compare performance of subseq>>sorted-map and subrange>>data.avl/sorted-map for prefixes;
;; think about what kind of data structure we want to have when doing these subrange queries.