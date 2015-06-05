(ns conf-program.core 
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

  (defn trigram-list
    "Generate list of all trigrams from source corpus, including ender trigrams"
    []
    (concat enders (apply concat (map (fn [x] (partition 3 1 (clojure.string/split x #" "))) clean-lines))))

  (def trigram-lookup-list
    "The main data structure. Allows lookup of all trigrams that start with a certain bigram."
    (group-by get-key-from-trigram (trigram-list))));;end let clean-lines

(defn get-random-starter
  []
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

(defn get-random-session
  []
  (clojure.string/join " " (get-random-from (get-random-starter))))

(defn count-colons 
  [pname]
  (count (re-seq #":" pname)))

(defn session-to-tweet
  "Converts session info (as a map) into a tweetable string."
  [x]
  (clojure.string/join "" (list (x :date) ", " (x :time) ": " (x :title))))

(defn reject-session?
  "Provides quality control. Reject obviously bad session titles."
  [pname] (or (> (count pname) 120) ;;Want it short enough to tweet
              (some #(not= -1 (.indexOf % pname)) file-lines) ;;Matches our input corpus
              (> (count-colons pname) 1)    ;;More than one colon in title
              (< (count (clojure.string/split pname #" ")) 4))) ;;Less than four words in the title

(defn get-random-nonreject-session
  [] 
  (first (filter #(not (reject-session? %)) (repeatedly get-random-session))))

(def session-names
  "Cache of session-names generated so far."
  (repeatedly get-random-nonreject-session))

(def start-time (t/from-time-zone (t/today-at 0 0) (t/time-zone-for-offset -4)))

(def time-offsets
  "Presentation times assuming 20 minute talks, coffee breaks, and lunch break"
  '((8 30) (8 50) (9 20) (10 30) (10 50) (11 10) (13 0) (13 20) (13 50) (14 10) (15 0) (15 20) (15 40)))

(def tweet-date-formatter
  (f/with-zone (f/formatter "EEE, MMM d, y") (t/time-zone-for-offset -4))) ;;Dates need to be short or else tweets will be too long

(def time-formatter
  (f/with-zone (f/formatter "h:mm a") (t/time-zone-for-offset -4)))

(defn fmt-pres-date
  "Input is an int, the number of days since start of conference. Compute the date and format it for printing."
  [whichday] 
  (f/unparse tweet-date-formatter (t/plus start-time (t/years 1000) (t/days whichday))))

(defn fmt-pres-time
  "Input is a pair which is hours and minutes since midnight. Compute the time and format it for printing."
  [whichtime]
  (f/unparse time-formatter (t/plus start-time (t/years 1000) (t/hours (nth whichtime 0)) (t/minutes (nth whichtime 1)))))

(defn get-one-session
  "Given an integer >= 0, which is the session id number, build the map with info about the session."
  [x]
  (hash-map :date (fmt-pres-date (int (/ x (count time-offsets)))),
            :time (fmt-pres-time (nth time-offsets (mod x (count time-offsets)))), 
            :title (nth session-names x)))

(defn time-to-id
  "Given a time from the chime-times list, calculated the corresponding id."
  [time]
  (let [interval-len (t/in-minutes (t/interval start-time time))
        days (int (/ interval-len (* 24 60)))
        hours (int (/ (mod interval-len (* 24 60)) 60))
        minutes (int (mod interval-len 60))]
    (+ (* (count time-offsets) days)
          (.indexOf time-offsets (list hours minutes)))))

(defn id-to-time 
  "Given an integer >= 0, which is a session id, calculate the day/time it should be tweeted."
  [id]
  (let [my-offset (nth time-offsets (mod id (count time-offsets)))]
    (t/plus start-time (t/days (int (/ id (count time-offsets)))) (t/hours (nth my-offset 0)) (t/minutes (nth my-offset 1)))))

(def chime-times
  (map id-to-time (range)))

(defn get-one-tweet
  "Given an id >= 0, get a tweetable string for that session"
  [id]
  (session-to-tweet (get-one-session id)))

(defn do-one-tweet
  "Intended to be called by chime. Takes a DateTime and makes an appropriate tweet on @farfar_conf"
  [time]
  (statuses-update :oauth-creds my-creds
                   :params {:status (get-one-tweet (time-to-id time))}))

(def cancel-chime-schedule 
  (chime-at chime-times ;;chime-at returns a function that can be called to cancel the schedule
            (fn [time]
              (do-one-tweet time))))

;;The hope is that this cancels the schedule when a live update happens, but
;; I haven't confirmed that it works
(defn cleanup-app
  []
  (cancel-chime-schedule))

(defn parse-int
  "Extract the first integer found in a string. If not found return 0."
  [s]
  (Integer. (or (re-find  #"\d+" s) "0")))

(defroutes app-routes
  (GET "/" [] (slurp home-page))
  (context "/sessions" [] 
           (defroutes documents-routes
             (GET "/" {params :params} (response 
                                        (map get-one-session 
                                             (range 
                                              (parse-int (or (params :start) "0")) 
                                              (parse-int (or (params :end) "13")))))) ;;Default is to give first day's program
             (context "/:id" [id] 
                      (defroutes document-routes
                        (GET "/" [] (response (get-one-session (parse-int id))))))))
  (route/not-found "Not Found"))

(def app
  (-> (handler/api app-routes)
      (middleware/wrap-json-body)
      (middleware/wrap-json-response)))
