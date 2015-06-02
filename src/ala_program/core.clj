(ns ala-program.core 
  (:use compojure.core)
  (:use cheshire.core)
  (:use ring.util.response)
  (:require [compojure.handler :as handler]
            [compojure.route :as route]
            [ring.middleware.json :as middleware]
            [clojure.java.io :as io]))

(def data-file (io/file
                 (io/resource 
                   "2701.txt")))

(defn get-corpus []
  (group-by (fn [x] (take 2 (key x))) (frequencies (partition 3 1 (take 100000 (clojure.string/split (clojure.string/replace (slurp data-file) #"\s+" " ") #" "))))))

(def the-corpus (get-corpus))

(defn count-bigrams-for-key [k] 
  (reduce + (map (fn [x] (nth x 1)) (the-corpus k))))

(defn get-bigram [num coll]
  (if (< num (nth (first coll) 1))
    (first (first coll))
    (get-bigram (- num (nth (first coll) 1)) (rest coll))))

(defn get-bigram-for-key [key num] 
  (get-bigram num (the-corpus key)))

(defn get-random-next-word [word]
  (let [count (count-bigrams-for-key word)]
    (nth (get-bigram-for-key word (rand-int count)) 2)))

(defn get-some-words [startword count]
  (let [nextword (get-random-next-word startword)] 
    (if (<= count 1) 
      startword
      (cons (first startword) (get-some-words (list (nth startword 1) nextword) (- count 1))))))

(defn get-one-title []
  (clojure.string/join " " (get-some-words (list "the" "wall") 15)))

(defn get-one-panel []
  (response {:title (get-one-title) :author "Author name"}))

(defroutes app-routes
  (GET "/" [] "<h1>floop</h1>")
  (GET "/a_panel" [] (get-one-panel))
  (route/not-found "Not Found"))

(def app
  (-> (handler/api app-routes)
      (middleware/wrap-json-body)
      (middleware/wrap-json-response)))
