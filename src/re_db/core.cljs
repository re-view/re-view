(ns re-db.core
  (:refer-clojure
    :exclude [get get-in select-keys set! peek contains? namespace]
    :rename {get         get*
             contains?   contains?*
             select-keys select-keys*
             namespace   namespace*})
  (:require [cljs-uuid-utils.core :as uuid-utils]
            [clojure.set :as set])
  (:require-macros [re-db.core :refer [capture-patterns
                                       get-in*]]))

(enable-console-print!)

(def ^:dynamic *notify* true)                               ;; if false, datoms are not tracked & listeners are not notified. faster.
(def ^:dynamic *access-log* nil)                            ;; used to track read patterns
(def ^:dynamic *db-log* nil)                                ;; maintains log of transactions while bound

(def fnil-conj-set (fnil conj #{}))
(def fnil-into-set (fnil into #{}))

(defn log-read [path f v]
  (when-not (nil? *access-log*)
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

(defn get-schema [db-snap a]
  (get-in* db-snap [:schema a]))

(defn index?
  "Returns true if attribute is indexed."
  ([schema]
   (contains?* schema :db/index))
  ([db-snap a]
   (index? (get-schema db-snap a))))

(defn many?
  "Returns true for attributes with cardinality `many`, which store a set of values for each attribute."
  ([schema]
   (keyword-identical? :db.cardinality/many (get* schema :db/cardinality)))
  ([db-snap a]
   (many? (get-schema db-snap a))))

(defn unique?
  "Returns true for attributes where :db/index is :db.index/unique."
  ([schema]
   (keyword-identical? :db.index/unique (get* schema :db/index)))
  ([db-snap a]
   (unique? (get-schema db-snap a))))

(defn ref?
  [schema]
  (keyword-identical? :db.type/ref (get* schema :db/type)))

(defn resolve-id
  "Returns id, resolving lookup refs (vectors of the form `[attribute value]`) to ids.
  Lookup refs are only supported for indexed attributes.
  The 3-arity version is for known lookup refs, and does not check for uniqueness."
  ([db-snap attr val]
   (log-read [:_av] conj [nil attr val])
   (first (get-in* db-snap [:ave attr val])))
  ([db-snap id]
   (if ^:boolean (vector? id)
     (let [[attr val] id]
       (if-not (unique? db-snap attr)
         (throw (js/Error. (str "Not a unique attribute: " attr ", with value: " val)))
         (resolve-id db-snap attr val)))
     id)))

(defn contains?
  "Returns true if entity with given id exists in db."
  [db-snap id]
  (let [id (resolve-id db-snap id)]
    (when-not ^:boolean (nil? id) (log-read [:e__] conj [id]))
    (true? (contains?* (get* db-snap :eav) id))))

(defn- listen-path!
  [db path f]
  (doto db
    (swap! update-in path #(fnil-conj-set % f))))

(defn- unlisten-path!
  [db path f]
  (doto db
    (swap! update-in path disj f)))

(declare get entity)

(defn p-nil?
  "Return true if value is nil or '_"
  [x]
  (or (nil? x) (= '_ x)))

(defn pattern->idx
  "Returns type of listener pattern."
  [[e a v :as x]]
  (let [pattern (keyword (str (if (p-nil? e) "_" "e")
                              (if (p-nil? a) "_" "a")
                              (if (p-nil? v) "_" "v")))]
    (when-not (#{:e__ :ea_ :_av :_a_} pattern)
      (println {:pattern pattern :x x})
      (throw (js/Error. (str "Unsupported pattern: " " " pattern "e: " e "a: " a "v: " v))))
    pattern))

;; spec


(defn- pattern->listener-path
  "Returns path to listener set for the given access pattern."
  [[id attr val :as pattern]]
  (case (pattern->idx pattern)
    :e__ [:listeners :e__ id]
    :ea_ [:listeners :ea_ id attr]
    :_av [:listeners :_av attr val]
    :_a_ [:listeners :_a_ attr]
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
   [id attr _]  => :ea_
   [_ attr val] => :_av
   [id _ _]     => :e__
   [_ attr _]   => :_a_"
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
    (log-read [:e__] conj [id])
    (some-> (get-in* db-snap [:eav id])
            (assoc :db/id id))))

(defn get
  "Get attribute in entity with given id."
  ([db-snap id attr]
   (when-let [id (resolve-id db-snap id)]
     (log-read [:ea_ id] fnil-conj-set [id attr])
     (get-in* db-snap [:eav id attr])))
  ([db-snap id attr not-found]
   (when-let [id (resolve-id db-snap id)]
     (log-read [:ea_ id] fnil-conj-set [id attr])
     (get-in* db-snap [:eav id attr] not-found))))

(defn get-in
  "Get-in the entity with given id."
  ([db-snap id ks]
   (when-let [id (resolve-id db-snap id)]
     (log-read [:ea_ id] fnil-conj-set [id (first ks)])
     (-> (get-in* db-snap [:eav id])
         (get-in* ks))))
  ([db-snap id ks not-found]
   (when-let [id (resolve-id db-snap id)]
     (log-read [:ea_ id] fnil-conj-set [id (first ks)])
     (-> (get-in* db-snap [:eav id])
         (get-in* ks not-found)))))

(defn select-keys
  "Select keys from entity of id"
  [db-snap id ks]
  (when-let [id (resolve-id db-snap id)]
    (log-read [:ea_ id] into (mapv #(do [id %]) ks))
    (-> (get-in* db-snap [:eav id])
        (assoc :db/id id)
        (select-keys* ks))))

(defn touch
  "Add refs to entity"
  [db-snap {:keys [db/id] :as entity}]
  (reduce-kv
    (fn [m attr ids]
      (assoc m (keyword (namespace* attr) (str "_" (name attr))) ids))
    entity
    (get-in* db-snap [:vae id])))

(defn- assert-uniqueness [db-snap id attr val]
  (when-not (empty? (get-in* db-snap [:ave attr val]))
    (throw (js/Error. (str "Unique index on " attr "; attempted to write duplicate value " val " on id " id ".")))))

(defn- add-index [db-snap id a v schema]
  (let [index (get* schema :db/index)]
    (when (keyword-identical? index :db.index/unique)
      (assert-uniqueness db-snap id a v))
    (cond-> db-snap
            (not (nil? index)) (update-in [:ave a v] fnil-conj-set id)
            (ref? schema) (update-in [:vae v a] fnil-conj-set id))))

(defn- add-index-many [db-snap id attr added schema]
  (reduce (fn [state v]
            (add-index state id attr v schema)) db-snap added))

(defn- remove-index [db-snap id attr removed schema]
  (cond-> db-snap
          (index? schema) (update-in [:ave attr removed] disj id)
          (ref? schema) (update-in [:vae removed attr] disj id)))

(defn- remove-index-many [db-snap id attr removals schema]
  (reduce (fn [db-snap v]
            (remove-index db-snap id attr v schema))
          db-snap
          removals))

(defn- update-index [db-snap id attr added removed schema]
  (if (many? schema)
    (cond-> db-snap
            added (add-index-many id attr added schema)
            removed (remove-index-many id attr removed schema))
    (cond-> db-snap
            added (add-index id attr added schema)
            removed (remove-index id attr added schema))))

(defn- clear-empty-ent [db-snap id]
  (cond-> db-snap
          (#{{:db/id id} {}} (get-in* db-snap [:eav id])) (update :eav dissoc id)))

(declare retract-attr)

(defn- retract-attr-many [[db-snap datoms :as state] id attr value schema]
  (let [prev-val (get-in* db-snap [:eav id attr])]
    (let [removals (if (nil? value) prev-val (set/intersection value prev-val))
          kill? (= removals prev-val)]
      (if (empty? removals)
        state
        [(-> (if kill? (update-in db-snap [:eav id] dissoc attr)
                       (update-in db-snap [:eav id attr] set/difference removals))
             (update-index id attr nil removals schema)
             (clear-empty-ent id))
         (cond-> datoms
                 (true? *notify*) (conj! [id attr nil removals]))]))))

(defn- retract-attr
  ([state id attr] (retract-attr state id attr (get-in* (state 0) [:eav id attr])))
  ([[db-snap datoms :as state] id attr value]
   (let [schema (get-schema db-snap attr)]
     (if (many? schema)
       (retract-attr-many state id attr value schema)
       (let [prev-val (if-not (nil? value) value (get-in* db-snap [:eav id attr]))]
         (if-not (nil? prev-val)
           [(-> (update-in db-snap [:eav id] dissoc attr)
                (update-index id attr nil prev-val (get-schema db-snap attr))
                (clear-empty-ent id))
            (cond-> datoms
                    (true? *notify*) (conj! [id attr nil prev-val]))]
           state))))))

(defn- retract-entity [state id]
  (reduce (fn [state [a v]]
            (retract-attr state id a v))
          state
          (entity (state 0) id)))

(defn- add
  [[db-snap datoms :as state] id attr val]
  {:pre [(not (keyword-identical? attr :db/id))]}
  (let [schema (get-schema db-snap attr)
        prev-val (get-in* db-snap [:eav id attr])]
    (if (many? schema)
      (let [additions (set/difference val prev-val)]
        (if (empty? additions)
          state
          [(-> (update-in db-snap [:eav id attr] fnil-into-set additions)
               (update-index id attr additions nil schema))
           (cond-> datoms
                   (true? *notify*) (conj! [id attr additions nil]))]))
      (if (= prev-val val)
        state
        [(-> (assoc-in db-snap [:eav id attr] val)
             (update-index id attr val prev-val schema))
         (cond-> datoms
                 (true? *notify*) (conj! [id attr val prev-val]))]))))

(defn add-map-indexes [db-snap id m prev-m]
  (reduce-kv
    (fn [db-snap attr val]
      (let [schema (get-schema db-snap attr)
            prev-val (get* prev-m attr)]
        (cond (many? schema)
              (update-index db-snap id attr
                            (set/difference val prev-val)
                            (set/difference prev-val val)
                            schema)
              (not= val prev-val)
              (update-index db-snap id attr val prev-val schema)
              :else db-snap)))
    db-snap m))

(defn add-map-datoms [datoms id m prev-m db-snap]
  (reduce-kv
    (fn [datoms attr val]
      (let [prev-val (get* prev-m attr)]
        (cond-> datoms
                (not= val prev-val) (conj! (if (many? db-snap attr)
                                             [id attr
                                              (set/difference val prev-val)
                                              (set/difference prev-val val)]
                                             [id attr val prev-val])))))
    datoms m))

(defn- remove-nils [m]
  (reduce-kv (fn [m k v]
               (cond-> m
                       (nil? v) (dissoc k))) m m))

(defn- add-map
  [[db-snap datoms] m]
  (let [id (get* m :db/id)
        m (dissoc m :db/id)
        prev-m (get-in* db-snap [:eav id])]
    [(-> (assoc-in db-snap [:eav id] (remove-nils (merge prev-m m)))
         (add-map-indexes id m prev-m)
         (clear-empty-ent id))
     (cond-> datoms
             (true? *notify*) (add-map-datoms id m prev-m db-snap))]))

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
        (doseq [f (get-in* listeners [:ea_ id a])]
          (f datom))

        ;; attr-val listeners
        (doseq [v (cond-> (or v prev-v)
                          (not (many? a)) (list))]
          (doseq [f (get-in* listeners [:_av a v])]
            (f datom)))

        ;; attr listeners
        (doseq [f (get-in* listeners [:_a_ a])]
          (f datom)))

      (doseq [id @seen-ids]
        (doseq [f (get-in* listeners [:e__ id])]
          (f id)))

      ;; tx-log listeners
      (doseq [f (get* listeners :tx-log)] (f datoms)))))

(defn- map->txs!
  [m]
  (assert (contains?* m :db/id))
  (when-not (= 1 (count m))
    (let [id (get* m :db/id)]
      (reduce-kv (fn [txs attr val]
                   (conj txs (if (nil? val)
                               [:db/retract-attr id attr]
                               [:db/add id attr val]))) [] (dissoc m :db/id)))))

(defn- commit-tx [state tx]
  (apply (case (tx 0)
           :db/add add
           :db/add-map add-map
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
                                      (commit-tx state [:db/add-map (update tx :db/id resolve-id)])
                                      #_(reduce commit-tx state (map->txs! (update tx :db/id resolve-id)))))
                                  [db-before (transient [])]
                                  new-txs)]
    {:db-before db-before
     :db-after  db-after
     :datoms    (persistent! datoms)}))

(defn transact!
  ([db txs] (transact! db txs {}))
  ([db txs {:keys [notify
                   mute]
            :or   {notify true}}]
   (when mute (.warn js/console "Mute is no longer a re-db option. Use `:notify false` instead."))
   (binding [*notify* notify]
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

       (when *notify* (notify-listeners tx))
       db))))

(defn entity-ids
  [db-snap & qs]
  (->> qs
       (mapv (fn [q]
               (set (cond (fn? q)
                          (reduce-kv (fn [s id entity] (if ^:boolean (q entity) (conj s id) s)) #{} (get* db-snap :eav))

                          (keyword? q)
                          (do (log-read [:_a_] conj [nil q nil])
                              (reduce-kv (fn [s id entity] (if ^:boolean (contains?* entity q) (conj s id) s)) #{} (get* db-snap :eav)))

                          :else
                          (let [[attr val] q]
                            (log-read [:_av] conj [nil attr val])
                            (if (index? db-snap attr)
                              (get-in* db-snap [:ave attr val])
                              (entity-ids db-snap #(= val (get* % attr)))))))))
       (apply set/intersection)))

(defn entities
  [db-snap & qs]
  (map (partial entity db-snap) (apply entity-ids db-snap qs)))

(defn squuid []
  (str (uuid-utils/make-random-uuid)))

(def blank-access-log
  {:e__ #{}
   :_a_ #{}
   :_av #{}
   :ea_ {}})

(defn access-log-patterns
  "Returns vector of access log patterns, pruning overlapping patterns"
  [{ids :e__ attr-vals :_av entity-attrs :ea_ attrs :_a_}]
  (apply set/union
         ids
         attr-vals
         attrs
         (vals (apply dissoc entity-attrs (map first ids)))))

(defn namespace [db-snap ns]
  ;(log-read [:namespace] fnil-conj-set ns)
  (map #(entity db-snap (first %)) (subseq (get* db-snap :eav) >= (keyword (name ns) "!") <= (keyword (name ns) "z"))))