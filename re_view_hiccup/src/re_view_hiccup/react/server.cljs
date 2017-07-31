(ns re-view-hiccup.react.server
  (:refer-clojure :exclude [string])
  (:require [cljsjs.react.dom.server]))

(defn string
  "Returns HTML string for React element. Use for server-side rendering of static markup."
  [element]
  (.renderToStaticMarkup js/ReactDOMServer element))

(defn react-string
  "Returns HTML string for React element, preserving React identifiers.
  Use for server-side rendering when elements will be rendered again by React in the client."
  [element]
  (.renderToString js/ReactDOMServer element))