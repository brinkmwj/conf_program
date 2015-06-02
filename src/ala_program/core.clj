(ns ala-program.core 
  (:use compojure.core)
  (:use cheshire.core)
  (:use ring.util.response)
  (:require [compojure.handler :as handler]
            [compojure.route :as route]
            [ring.middleware.json :as middleware]))

(defn get-one-title []
  (response '{:title "Panel title" :author "Author name"}))

(defroutes app-routes
  (GET "/" [] (get-one-title))
  (route/not-found "Not Found"))

(def app
  (-> (handler/api app-routes)
      (middleware/wrap-json-body)
      (middleware/wrap-json-response)))
