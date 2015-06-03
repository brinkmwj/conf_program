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
                   "biglist.csv")))

(def file-lines
  (clojure.string/split-lines (slurp data-file)))

;;Don't want any lines with less than 2 words because they can't generate useful data
(def clean-lines
  (filter (fn [x] (>= (count x) 2)) (map (fn [x] (clojure.string/replace x #"\s+" " ")) file-lines)))

(def starters
  (vec (map (fn [x] (take 2 (clojure.string/split x #" "))) clean-lines)))

(def enders
  (map (fn [x] (concat (take-last 2 (clojure.string/split x #" ")) (list nil))) clean-lines))

(defn ender? [x]
  (nil? (nth x 2)))

(def trigram-list
  (concat enders (apply concat (map (fn [x] (partition 3 1 (clojure.string/split x #" "))) clean-lines))))

(defn get-two-and-lowercase [trigram]
  (map clojure.string/lower-case (take 2 trigram)))

(def trigram-lookup-list
  (group-by get-two-and-lowercase trigram-list))

(defn get-random-starter []
  (nth starters (rand-int (count starters))))

(defn get-random-next-trigram [bigram]
  (let [options (trigram-lookup-list (get-two-and-lowercase bigram))]
    (nth options (rand-int (count options)))))

(defn get-random-from [bigram]
  (let [next-trigram (get-random-next-trigram bigram)]
    (cons (first bigram) 
          (if (ender? next-trigram)
            (list (nth bigram 1))
            (get-random-from (rest next-trigram))))))

(defn get-random-panel []
  (clojure.string/join " " (get-random-from (get-random-starter))))

(defn levenshtein [w1 w2]
  (letfn [(cell-value [same-char? prev-row cur-row col-idx]
            (min (inc (nth prev-row col-idx))
                 (inc (last cur-row))
                 (+ (nth prev-row (dec col-idx)) (if same-char?
                                                   0
                                                   1))))]
    (loop [row-idx  1
           max-rows (inc (count w2))
           prev-row (range (inc (count w1)))]
      (if (= row-idx max-rows)
        (last prev-row)
        (let [ch2           (nth w2 (dec row-idx))
              next-prev-row (reduce (fn [cur-row i]
                                      (let [same-char? (= (nth w1 (dec i)) ch2)]
                                        (conj cur-row (cell-value same-char?
                                                                  prev-row
                                                                  cur-row
                                                                  i))))
                                    [row-idx] (range 1 (count prev-row)))]
          (recur (inc row-idx) max-rows next-prev-row))))))

(defn min-lev-dist [string coll]
  (reduce min (map (fn [x] (levenshtein string x)) coll)))

(defn get-one-panel []
  (let [pname (get-random-panel)]
    (response {:title pname :alreadyhere (some #(= pname %) file-lines)})))

(defroutes app-routes
  (GET "/" [] "<h1>floop</h1>")
  (GET "/a_panel" [] (get-one-panel))
  (route/not-found "Not Found"))

(def app
  (-> (handler/api app-routes)
      (middleware/wrap-json-body)
      (middleware/wrap-json-response)))
