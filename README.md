# re-view

A thin [React](https://facebook.github.io/react/) ClojureScript wrapper designed to play well with [re-db](https://github.com/mhuebert/re-db) and closely follow/expose ordinary React behaviour.

Example:

```clj

(ns app.core
  (:require [re-view.core :as view]))

(defcomponent my-first-component

   :initial-state
   (fn [_] {:color "purple"})

   ;; put rendered height into state
   :did-mount
   (fn [this] (view/swap-state! this assoc :height
                (.height (js/ReactDOM.findDOMNode this))))

   :render
   (fn [this] [:div "Hello, world"]))

```


Pass `component` React lifecycle methods (names are auto-converted from :hyphenated-keywords to camelCase) and get back a factory.

Important to know:

* access state and props as keyword properties on `this`, eg. `(:props this)` or `(:state this)`

```
...
  :render
  (fn [this]
    [:div "Hello, " (get-in this [:state :name])])
...
```

* define :react-key to assign unique keys to components based on props (required for React)

```
...
  :react-key
  (fn [{:keys [props]}] (:id props))
...
```

* call `render-component` to re-render a component, optionally with new props. this can be any component, not only root components.

```
(view/render-component my-component {:id <some-id>})
```

### Changelog

0.2.2

- Use global re-db.d database (simplifies db subscriptions)
- Use namespace kw for indexing views by id (`:re-view/id`)
- `subs/db` subscription macro takes an extra `should-update?` parameter, no attempt to infer this from props
- More efficient subscription initialization

0.2.1

- Removed pattern-based db subscriber. Use re-view.subscriptions/db (a macro) to create a reactive database subscription. When body of subs/db is executed, listeners are created on accessed db-patterns, and body is re-run when these entities/attributes change. If the body reads from :props, the subscription will be updated when props change.

```
(require '[re-view.subscriptions :as subs])

(defcomponent x
  :subscriptions {:name (subs/db [this] (d/get (get-in this [:props :uid]) :name))
                                 ;;^^ optional binding of `this` for reading :props.
                  :signed-in? (subs/db (d/get :app/state :signed-in?))}
  :render
  (fn [{{:keys [name signed-in?]} :state}]
    ...))

```

0.2

- `props` and `state` are keyword properties on `this`. Also available are `:prev-props, :prev-state, :children`. Destructuring on function arglist is encouraged:

```clj
(defcomponent x
  :render
  (fn [{:keys [props state] :as this}] ...))

(defcomponent y
  :render
  (fn [{[a b c] :children
        {:keys [id]} :props}] ...))

;; state behaves like an atom, forcing a re-render when appropriate
(v/swap-state! this assoc :x 10)
(get-in this [:state :x]) ;; => 10

;; list of relevant kw properties defined on `this`:
[:props :state :children :prev-props :prev-state :prev-children]        

```

- Swap state via `(view/swap-state! this ...)`. Or, if you are feeling evil, plain `swap!` is equivalent to `view/swap-state!`.
- Shorten keyword method names (`:will-mount`, `:should-update`, etc.)
- Depracate `expand-props`
- Upgrade to Clojure(Script) 1.9.*
