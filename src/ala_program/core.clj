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
            [clj-time.format :as f]
            [chime :refer [chime-at]]))

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

(defn panel-to-tweet [x]
  (clojure.string/join "" (list (x :date) ", " (x :time) ": " (x :title))))

(defn reject-panel?
  "Provides quality control. Reject obviously bad panel titles."
  [pname] (or (> (count pname) 120) ;;Want it short enough to tweet
              (some #(not= -1 (.indexOf % pname)) file-lines) ;;Matches our input corpus
              (> (count-colons pname) 1)    ;;More than one colon in title
              (< (count (clojure.string/split pname #" ")) 4))) ;;Less than four words in the title

(defn get-random-nonreject-panel [] 
  (first (filter #(not (reject-panel? %)) (repeatedly get-random-panel))))

(def panel-names
  "Cache of panel-names generated so far."
  (repeatedly get-random-nonreject-panel))

(def start-time (t/from-time-zone (t/today-at 0 0) (t/time-zone-for-offset -4)))

(def time-offsets
  "Presentation times assuming 20 minute talks, coffee breaks, and lunch break"
  '((8 30) (8 50) (9 20) (10 30) (10 50) (11 10) (13 0) (13 20) (13 50) (14 10) (15 0) (15 20) (15 40)))

(def tweet-date-formatter
  (f/with-zone (f/formatter "EEE, MMM d, y") (t/time-zone-for-offset -4)))

(def time-formatter
  (f/with-zone (f/formatter "h:mm a") (t/time-zone-for-offset -4)))

(defn get-date [whichday] 
  (f/unparse tweet-date-formatter (t/plus start-time (t/years 1000) (t/days whichday))))

(defn get-time [whichtime]
  (f/unparse time-formatter (t/plus start-time (t/years 1000) (t/hours (nth whichtime 0)) (t/minutes (nth whichtime 1)))))

(defn get-one-panel [x]
  (hash-map :date (get-date (int (/ x (count time-offsets)))),
            :time (get-time (nth time-offsets (mod x (count time-offsets)))), 
            :title (nth panel-names x)))

(defn time-to-id [time]
  (let [interval-len (t/in-minutes (t/interval start-time time))
        days (int (/ interval-len (* 24 60)))
        hours (int (/ (mod interval-len (* 24 60)) 60))
        minutes (int (mod interval-len 60))]
    (+ (* (count time-offsets) days)
          (.indexOf time-offsets (list hours minutes)))))

(defn id-to-time [id]
  (let [my-offset (nth time-offsets (mod id (count time-offsets)))]
    (t/plus start-time (t/days (int (/ id 13))) (t/hours (nth my-offset 0)) (t/minutes (nth my-offset 1)))))

(def chime-times
  (map id-to-time (range)))

(defn get-one-tweet [x] 
  (panel-to-tweet (get-one-panel x)))

(defn do-one-tweet [time]
  (statuses-update :oauth-creds my-creds
                   :params {:status (get-one-tweet (time-to-id time))}))

;;DANGER: In interactive mode this can cause the schedule to be reloaded over and over
(def cancel-chime-schedule 
  (chime-at chime-times
            (fn [time]
              (do-one-tweet time))))

(defn cleanup-app []
  (cancel-chime-schedule))

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
