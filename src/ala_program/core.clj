(ns ala-program.core 
  (:use compojure.core)
  (:use cheshire.core)
  (:use ring.util.response)
  (:use
   [twitter.oauth]
   [twitter.callbacks]
   [twitter.callbacks.handlers]
   [twitter.api.restful])
  (:import
   (twitter.callbacks.protocols SyncSingleCallback))
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

(def cred-file (io/file
                (io/resource
                 "creds.txt")))

(def file-lines
  "Raw lines of corpus. Used for creating trigrams and reject testing."
  (clojure.string/split-lines (slurp data-file)))

(def my-creds (apply make-oauth-creds (clojure.string/split-lines (slurp cred-file))))

(defn ender?
  "Check a tri-gram to see if it a sentence ender (indicated by nil sentinel in last position)"
  [x] (nil? (nth x 2)))

(defn get-key-from-trigram
  "The key for a trigram is the first two words, lowercased, and with non-alphanumeric characters removed"
  [trigram] (map (fn [x] (clojure.string/replace (clojure.string/lower-case x) #"[^a-zA-Z0-9]" "")) (take 2 trigram)))

(let [clean-lines ;;Don't want any lines with less than 2 words because they can't generate useful data
      (filter (fn [x] (>= (count x) 2)) (map (fn [x] (clojure.string/replace x #"\s+" " ")) file-lines))]

  (def starters
    "List of bigrams that can start a session title"
    (vec (map (fn [x] (take 2 (clojure.string/split x #" "))) clean-lines)))

  (def enders
    "List of nil-terminated trigrams that can end a session title."
    (map (fn [x] (concat (take-last 2 (clojure.string/split x #" ")) (list nil))) clean-lines))

  (defn trigram-list []
    "Generate list of all trigrams from source corpus, including ender trigrams"
    (concat enders (apply concat (map (fn [x] (partition 3 1 (clojure.string/split x #" "))) clean-lines))))

  (def trigram-lookup-list
    "The main data structure. Allows lookup of all trigrams that start with a certain bigram."
    (group-by get-key-from-trigram (trigram-list))));;end let clean-lines

(defn get-random-starter []
  (nth starters (rand-int (count starters))))

(defn get-random-next-trigram
  "Given a bigram, select a random trigram that starts with that bigram."
  [bigram] (let [options (trigram-lookup-list (get-key-from-trigram bigram))]
             (nth options (rand-int (count options)))))

(defn get-random-from
  "Given a bigram, recursively construct a session title starting with that bigram."
  [bigram] (let [next-trigram (get-random-next-trigram bigram)]
             (cons (first bigram) 
                   (if (ender? next-trigram)
                     (list (nth bigram 1))
                     (get-random-from (rest next-trigram))))))

(defn get-random-panel []
  (clojure.string/join " " (get-random-from (get-random-starter))))

(defn count-colons [pname]
  (count (re-seq #":" pname)))

(defn reject-panel?
  "Provides quality control. Reject obviously bad panel titles."
  [pname] (or (some #(not= -1 (.indexOf % pname)) file-lines) ;;Matches our input corpus
              (> (count-colons pname) 1)    ;;More than one colon in title
              (< (count (clojure.string/split pname #" ")) 4))) ;;Less than four words in the title

(def panel-names
  "Cache of panel-names generated so far."
  (filter #(not (reject-panel? %)) (repeatedly get-random-panel)))

(def time-slots
  '("8:30am" "8:50am" "9:20am" "10:30am" "10:50am" "11:10am"
    "1:00pm" "1:20pm" "1:50pm" "2:10pm" "3:00pm" "3:20pm" "3:40pm"))

(def date-formatter
  (f/formatter "EEEE, MMMMM d, y"))

(defn get-date [whichday] 
  (f/unparse date-formatter (t/plus (t/date-time 3015 06 24) (t/days whichday))))

(defn get-one-panel [x]
  (hash-map :date (get-date (int (/ x (count time-slots)))),
            :time (nth time-slots (mod x (count time-slots))), 
            :title (nth panel-names x)))

(defn do-one-tweet []
  (statuses-update :oauth-creds my-creds
                   :params {:status "hello world"}))

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
