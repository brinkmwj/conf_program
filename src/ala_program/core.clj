(ns ala-program.core 
  (:use compojure.core)
  (:use cheshire.core)
  (:use ring.util.response)
  (:require [compojure.handler :as handler]
            [compojure.route :as route]
            [ring.middleware.json :as middleware]
            [clojure.java.io :as io]
            [clj-time.core :as t] 
            [clj-time.format :as f]))

(def data-file (io/file
                 (io/resource 
                   "biglist.csv")))

(def home-page (io/file
                (io/resource
                 "index.html")))
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

(defn get-key-from-trigram [trigram]
  (map (fn [x] (clojure.string/replace (clojure.string/lower-case x) #"[^a-zA-Z0-9]" "")) (take 2 trigram)))

(def trigram-lookup-list
  (group-by get-key-from-trigram trigram-list))

(defn get-random-starter []
  (nth starters (rand-int (count starters))))

(defn get-random-next-trigram [bigram]
  (let [options (trigram-lookup-list (get-key-from-trigram bigram))]
    (nth options (rand-int (count options)))))

(defn get-random-from [bigram]
  (let [next-trigram (get-random-next-trigram bigram)]
    (cons (first bigram) 
          (if (ender? next-trigram)
            (list (nth bigram 1))
            (get-random-from (rest next-trigram))))))

(defn get-random-panel []
  (clojure.string/join " " (get-random-from (get-random-starter))))

(defn count-colons [pname]
  (count (re-seq #":" pname)))

(defn reject-panel? [pname]
  (or (some #(= pname %) file-lines);;Matches our input corpus
      (> (count-colons pname) 1)))  ;;More than one colon in the title

(def panel-names (filter #(not (reject-panel? %)) (repeatedly get-random-panel)))

(def time-slots
  '("8:30am" "8:50am" "9:20am" "10:30am" "10:50am" "11:10am"
    "1:00pm" "1:20pm" "1:50pm" "2:10pm" "3:00pm" "3:20pm" "3:40pm"))

(def date-formatter
  (f/formatter "EEEE, MMMMM d, y"))

(defn get-date [whichday] 
  (f/unparse date-formatter (t/plus (t/date-time 2112 06 25) (t/days whichday))))

(defn get-one-panel [x]
  (hash-map :date (get-date (int (/ x (count time-slots)))),
            :time (nth time-slots (mod x (count time-slots))), 
            :title (nth panel-names x)))

(defn parse-int [s]
   (Integer. (re-find  #"\d+" s)))

(defroutes app-routes
  (GET "/" [] (slurp home-page))
  (context "/sessions" [] 
           (defroutes documents-routes
             (GET "/" [] (get-one-panel 0))
             (context "/:id" [id] 
                      (defroutes document-routes
                        (GET "/" [] (response (get-one-panel (parse-int id))))))
             (context "/:id1/:id2" [id1 id2] 
                      (defroutes document-routes
                        (GET "/" [] (response (map get-one-panel (range (parse-int id1) (parse-int id2)))))))))
  (route/not-found "Not Found"))

(def app
  (-> (handler/api app-routes)
      (middleware/wrap-json-body)
      (middleware/wrap-json-response)))
