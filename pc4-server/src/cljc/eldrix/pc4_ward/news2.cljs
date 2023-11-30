(ns eldrix.pc4-ward.news2
  (:require
    [cljs-time.core :as time-core]
    [cljs-time.format :as time-format]
    [cljs-time.predicates :as time-predicates]
    [clojure.spec.alpha :as s]))


;; colours from the RCP NEWS2 chart
(def colour-score-3 "#E89078")
(def colour-score-2 "#F4C487")
(def colour-score-1 "#FFF0A8")
(def colour-dark-blue "#36609D")
(def colour-light-blue "#ACB3D1")
(def colour-abcde "#7487B6")

(defn news-score-to-colour
  [s]
  (print s)
  (cond
    (>= (:news-score s) 7) colour-score-3
    (>= (:news-score s) 5) colour-score-2
    :else "black"))


(def ventilation
  [{:value :air :abbreviation "A"}                          ;; (breathing air)
   {:value :nasal-cannula :abbreviation "N" :properties [:flow-rate]} ;; N (nasal cannula)
   {:value :simple-mask :abbreviation "SM" :properties [:flow-rate]} ;; SM (simple mask)
   {:value :venturi-mask :abbreviation "V" :properties [:fiO2]} ;; V (Venturi mask and percentage) eg V24, V28, V35, V40, V60
   {:value :non-invasive-ventilation :abbreviation "NIV"}   ;; NIV (patient on NIV system)
   {:value :reservoir-mask :abbreviation "RM" :properties [:flow-rate]} ;; RM (reservoir mask)
   {:value :tracheostomy-mask :abbreviation "TM"}           ;; TM (tracheostomy mask)
   {:value :cpap-mask :abbreviation "CP"}                   ;; CP (CPAP mask)
   {:value :humidified-oxygen :abbreviation "H" :properties [:fiO2]}]) ;; H (humidified oxygen and percentage) eg H28, H35, H40, H60)

(def consciousness
  [{:value       :clin/alert :display-name "Alert"
    :description "A fully awake patient. Such patients will have spontaneous opening of the eyes, will respond to voice and will have motor function. "}
   {:value       :clin/confused :display-name "New confusion"
    :description "A patient may be alert but confused or disorientated. It is not always possible to determine whether the confusion is ‘new’ when a patient presents acutely ill. Such a presentation should always be considered to be ‘new’ until confirmed to be otherwise. New-onset or worsening confusion, delirium or any other altered mentation should always prompt concern about potentially serious underlying causes and warrants urgent clinical evaluation."}
   {:value       :clin/voice :display-name "Responds to voice"
    :description "The patient makes some kind of response when you talk to them, which could be in any of the three component measures of eyes, voice or motor – eg patient’s eyes open on being asked ‘Are you okay?’. The response could be as little as a grunt, moan, or slight movement of a limb when prompted by voice."}
   {:value       :clin/pain :display-name "Responds to pain"
    :description "The patient makes a response to a pain stimulus. A patient who is not alert and who has not responded to voice (hence having the test performed on them) is likely to exhibit only withdrawal from pain, or even involuntary flexion or extension of the limbs from the pain stimulus. The person undertaking the assessment should always exercise care and be suitably trained when using a pain stimulus as a method of assessing levels of consciousness."}
   {:value       :clin/unresponsive :display-name "Unresponsive"
    :description "This is also commonly referred to as ‘unconscious’. This outcome is recorded if the patient does not give any eye, voice or motor response to voice or pain."}])

(defn in-range?
  "Is the number (n) within the range defined, inclusive, handling special case of missing start or end"
  [n start end]
  (cond (and (>= n start) (nil? end)) true
        :else (<= start n end)))                            ;; <= handles a nil 'start' but not a nil 'end'

(defn range-to-label
  "Turns a range (vector of two) into a string label, with an optional 'more' keyword "
  [[start end more]]
  (let [nm (if (nil? more) "" (str " " (name more)))]
    (cond (nil? end) (str "   ≥ " start nm)
          (nil? start) (str "   ≤ " end nm)
          :else (str start " - " end nm))))

(defn dates-from
  "Returns a lazy sequence of consecutive dates from the start date specified; time set at midnight"
  [start-date]
  (map #(time-core/at-midnight (time-core/plus start-date (time-core/days %))) (range)))

(defn dates-from-data
  "Returns a lazy sequence of dates sourced from the data provided"
  [start-date data]
  (->> data
       (map :date-time)
       (filter #(time-core/after? % start-date))
       (sort #(time-core/before? %1 %2))))

(defn datetime->map
  [dt now {:keys [time-formatter day-week-formatter day-month-formatter month-formatter]}]
  (hash-map :time (time-format/unparse time-formatter dt)
            :day-of-week (time-format/unparse day-week-formatter dt)
            :day-of-month (time-format/unparse day-month-formatter dt)
            :month (time-format/unparse month-formatter dt)
            :is-today (if (nil? now) false (time-predicates/same-date? dt now))))

(defn scale-one-day
  "A scale using one square per day, returning transformed data
  with an x representing the square from zero"
  [start-date width data]
  (let [start-midnight (time-core/at-midnight start-date)
        end-date (time-core/plus start-midnight (time-core/days width))
        sorted-data (->> data
                         (filter #(and (time-core/after? (:date-time %) start-midnight) (time-core/before? (:date-time %) end-date)))
                         (sort-by :date-time #(time-core/before? %1 %2)))]
    (map #(assoc % :x (time-core/in-days (time-core/interval start-midnight (:date-time %)))) sorted-data)))

(defn scale-one-day-fractional
  "A scale using one square per day, returning transformed data
  with fractional x's representing the x coordinate."
  [start-date width data]
  (let [start-midnight (time-core/at-midnight start-date)
        hours (* 24 width)
        end-date (time-core/plus start-midnight (time-core/hours hours))
        sorted-data (->> data
                         (filter #(and (time-core/after? (:date-time %) start-midnight) (time-core/before? (:date-time %) end-date)))
                         (sort-by :date-time #(time-core/before? %1 %2)))]
    (map #(assoc % :x (/ (time-core/in-hours (time-core/interval start-midnight (:date-time %))) 24)) sorted-data)))

(defn scale-consecutive
  "A scale that records data consecutively, one column per item,
  returning transformed data with an x representing square from zero"
  [start-date width data]
  (let [sorted-data (->> data
                         (filter #(time-core/after? (:date-time %) start-date))
                         (sort-by :date-time #(time-core/before? %1 %2))
                         (take width))]
    (map-indexed (fn [idx item] (assoc item :x idx)) sorted-data)))

(defmulti scale-dates
          "Returns a sequence of dates from the start-date for the data specified using the scale specified."
          (fn [scale start-date width data] scale))
(defmethod scale-dates :days [_ start-date width _] (take width (dates-from start-date)))
(defmethod scale-dates :fractional [_ start-date width _] (take width (dates-from start-date)))
(defmethod scale-dates :consecutive [_ start-date width data] (take width (dates-from-data start-date data)))

(defmulti scale-data
          "Returns data with an additional (x) key defining the (x) position in the scale specified according to :date-time"
          (fn [scale _ _ _] scale))
(defmethod scale-data :days [_ start-date width data] (scale-one-day start-date width data))
(defmethod scale-data :fractional [_ start-date width data] (scale-one-day-fractional start-date width data))
(defmethod scale-data :consecutive [_ start-date width data] (scale-consecutive start-date width data))


(defmulti default-start-date
          "Returns a 'reasonable' initial start date given today's date and the data provided"
          (fn [scale width data] scale))
(defmethod default-start-date :days [_ width _] (time-core/minus (time-core/at-midnight (time-core/now)) (time-core/days (- width 1))))
(defmethod default-start-date :consecutive [_ width data] (->> data
                                                               (map :date-time)
                                                               (sort #(time-core/after? %1 %2))
                                                               (take width)
                                                               (last)))


(defn index-by-numeric-range
  "Calculates an 'index' for a category by taking a numeric value and identifying the range from a vector of vectors, containing ranges."
  [v ranges]
  (if (nil? v) nil (first (keep-indexed (fn [index [from to]] (if (in-range? v from to) index nil)) ranges))))

(defn index-by-category
  "Calculates an 'index' for a category based on simple equivalence, with categories a vector of values"
  [v categories]
  (first (keep-indexed (fn [index item] (if (= v item) index nil)) categories)))

(defn index-by-range-and-category
  "Calculates an index for a hybrid numeric value (e.g. 96) and category (:on-air or :on-O2)"
  [[v category] categories]
  (if (or (nil? v) (nil? category))
    nil
    (first (keep-indexed (fn [index [from to cat]]
                           ;;     (println "checking '" v  category "' against " index " " from " " to " " cat)
                           (if (and (in-range? v from to) (or (nil? cat) (= cat category))) index nil)) categories))))

(s/def ::respiratory-rate pos-int?)
(s/def ::spO2-scale-1 (s/int-in 0 100))
(s/def ::air-or-oxygen #{:air :oxygen})
(s/def ::spO2-scale-2 (s/tuple ::spO2-chart-1 ::air-or-oxygen))
(s/def ::systolic pos-int?)
(s/def ::diastolic pos-int?)
(s/def ::blood-pressure (s/keys :req-un [::systolic-bp] :opt-un [::diastolic-bp]))
(s/def ::pulse pos-int?)
(s/def ::consciousness #{:clin/alert :clin/confused :clin/voice :clin/pain :clin/unresponsive})
(s/def ::temperature pos?)
(s/def ::ventilation (set (map :value ventilation)))
(s/def ::spO2 (s/int-in 0 100))
(s/def ::news (s/keys :req-un [::respiratory-rate ::spO2 ::ventilation ::temperature ::consciousness ::pulse ::blood-pressure]))

(def respiratory-rate-chart
  {:heading    "A+B"
   :title      "Respirations"
   :subtitle   "Breaths/min"
   :categories [[25 nil] [21 24] [18 20] [15 17] [12 14] [9 11] [nil 8]]
   :scores     [3 2 0 0 0 1 3]})

(def spO2-chart-1
  {:heading    "A+B"
   :title      "SpO2 scale 1"
   :subtitle   "Oxygen sats (%)"
   :categories [[96 nil] [94 95] [92 93] [nil 91]]
   :scores     [0 1 2 3]})

(def spO2-chart-2
  {:heading    "A+B"
   :title      "SpO2 scale 2"
   :subtitle   "Oxygen sats (%)"
   :categories [[97 nil :O2] [95 96 :O2] [93 94 :O2] [93 nil :air]
                [88 92] [86 87] [84 85] [nil 83]]
   :scores     [3 2 1 0 0 1 2 3]})

(def air-or-oxygen-chart
  {:heading    ""
   :title      "Air or oxygen"
   :categories [:air :O2 :device]
   :labels     ["Air" "O2 L/min" "Device"]
   :scores     [0 2 0]})

(def blood-pressure-chart
  {:heading    "C"
   :title      "Blood pressure"
   :subtitle   "mmHg"
   :categories [[220 nil] [201 219] [181 200] [161 180] [141 160] [121 140] [111 120]
                [101 110] [91 100] [81 90] [71 80] [61 70] [51 60] [nil 50]]
   :scores     [3 0 0 0 0 0 0 1 2 3 3 3 3 3]
   :value      :blood-pressure})

(def pulse-rate-chart
  {:heading    "C"
   :title      "Pulse"
   :subtitle   "Beats/min"
   :categories [[131 nil] [121 130] [111 120] [101 110] [91 100] [81 90] [71 80]
                [61 70] [51 60] [41 50] [31 40] [nil 30]]
   :scores     [3 2 2 1 1 0 0 0 0 1 3 3]})

(def consciousness-chart
  {:heading    "D"
   :title      "Consciousness"
   :categories [:clin/alert :clin/confused :clin/voice :clin/pain :clin/unresponsive]
   :labels     ["A" "Confused" "V" "P" "U"]
   :scores     [0 3 3 3 3]})

(def temperature-chart
  {:heading    "E"
   :title      "Temperature"
   :subtitle   "ºC"
   :categories [[39.1 nil] [38.1 39.0] [37.1 38.0] [36.1 37.0] [35.1 36.0] [nil 35.0]]
   :scores     [2 1 0 0 1 3]})

(defn score-news
  "Scores the individual set of observations according to the National Early Warning Score 2"
  [{:keys [date-time respiratory-rate spO2 air-or-oxygen blood-pressure pulse-rate temperature consciousness] :as obs} hypercapnic?]
  (let [rr (index-by-numeric-range respiratory-rate (:categories respiratory-rate-chart))
        spO2-1 (index-by-numeric-range spO2 (:categories spO2-chart-1))
        spO2-2 (index-by-range-and-category [spO2 air-or-oxygen] (:categories spO2-chart-2))
        air-or-oxygen (index-by-category air-or-oxygen (:categories air-or-oxygen-chart))
        sbp (index-by-numeric-range (:systolic blood-pressure) (:categories blood-pressure-chart))
        dbp (index-by-numeric-range (:diastolic blood-pressure) (:categories blood-pressure-chart))
        pulse (index-by-numeric-range pulse-rate (:categories pulse-rate-chart))
        temp (index-by-numeric-range temperature (:categories temperature-chart))
        consciousness (index-by-category consciousness (:categories consciousness-chart))
        results
        (merge
          {:date-time date-time :results (dissoc obs :date-time)}
          (when-not (nil? rr) {:respiratory-rate {:y rr, :score (nth (:scores respiratory-rate-chart) rr)}})
          (when-not (or (nil? spO2-1) hypercapnic?) {:spO2-chart-1 {:y spO2-1, :score (nth (:scores spO2-chart-1) spO2-1)}})
          (when (and (not (nil? spO2-2)) hypercapnic?) {:spO2-chart-2 {:y spO2-2, :score (nth (:scores spO2-chart-2) spO2-2)}})
          (when-not (nil? air-or-oxygen) {:air-or-oxygen {:y air-or-oxygen :score (nth (:scores air-or-oxygen-chart) air-or-oxygen)}})
          (when-not (nil? sbp) {:blood-pressure {:y sbp :y-dbp dbp :score (nth (:scores blood-pressure-chart) sbp)}})
          (when-not (nil? pulse) {:pulse-rate {:y pulse :score (nth (:scores pulse-rate-chart) pulse)}})
          (when-not (nil? temp) {:temperature {:y temp :score (nth (:scores temperature-chart) temp)}})
          (when-not (nil? consciousness) {:consciousness {:y consciousness :score (nth (:scores consciousness-chart) consciousness)}}))
        scores (->> (tree-seq map? vals results) (keep :score))]
    (if (= 7 (count scores)) (merge results {:news-score (apply + scores)}) results)))


(defn score-all-news
  "Scores all observations within the data according the the National Early Warning Score 2"
  [data hypercapnic?]
  (map #(score-news % hypercapnic?) data))


;;
;; RENDERING
;;


(defn render-labels
  "Generate SVG labels for categories"
  [x start-y categories]
  (map-indexed
    (fn [index item]
      (vector :text {:key item :x x :y (+ start-y 4 (* index 5)) :fill "black" :font-size 4 :text-anchor "middle"}
              (cond
                (vector? item) (range-to-label item)        ;; turn a vector into a label based on range
                (keyword? item) (name item)                 ;; turn keywords into simple labels
                (string? item) item                         ;; use a string if specified
                :else " "))) categories))

(defn render-scores
  "Render scores using the title specified, scores a sequence of results
  kv: a function to get score
  kc: a function to get colour"
  [y width title scores kv kc]
  ; find out the maximum score for each (x) so we don't have overprinting
  (let [sc (->> scores (sort-by kv) (group-by :x) (vals) (map (comp (juxt :x kv) last)))]
    [:<>
     [:rect {:x 0 :y (+ y 0) :width 56 :height 5 :stroke "black" :stroke-width 0.1 :fill colour-dark-blue}]
     [:text {:key title :x 5 :y (+ y 4) :fill "white" :font-size 4 :font-weight "bold"} title]
     [:rect {:x 56 :y y :width (* 7 width) :height 5 :stroke "black" :stroke-width 0.1 :fill "url(#grid-score-0"}]
     (remove nil? (map-indexed (fn [index [x score]]
                                 [:text {:key  (str x score) :x (+ 56 3.5 (* 7 x)) :y (+ y 4)
                                         :fill "black" :font-size 4 :text-anchor "middle"} score]) sc))]))

(defn render-axes
  "Renders the background for a chart using the labels
  specified, or the categories if no explicit labels provided."
  [y width {:keys [heading title subtitle categories labels scores]}]
  (let [n-categories (count categories)
        total-height (* 5 n-categories)
        titles-height (+ 8 2 4 2 3 4)
        hy (+ y (- (/ total-height 2) (/ titles-height 2)))]
    [:<>
     [:rect {:x 0 :y (+ y 0) :width 32 :height total-height :fill colour-dark-blue :stroke "black" :stroke-width 0.1}]
     [:text {:x 16 :y (+ hy 10) :fill colour-abcde :font-size "10" :text-anchor " middle "} heading]
     [:text {:x 16 :y (+ hy 15) :fill "white" :font-size "4" :font-weight " bold " :text-anchor " middle "} title]
     [:text {:x 16 :y (+ hy 18) :fill "white" :font-size "3" :text-anchor " middle "} subtitle]
     (render-labels 44 y (if (nil? labels) categories labels))

     (for [i (range n-categories)]
       ^{:key i} [:<>
                  [:rect {:x 32 :y (+ y (* i 5)) :width 24 :height 5 :fill "none" :stroke "black" :stroke-width 0.1}]
                  [:rect {:x 56 :y (+ y (* i 5)) :width (* 7 width) :height 5 :stroke "black" :stroke-width 0.1 :fill (str "url(#grid-score-" (nth scores i) ") ")}]])]))


(defn render-dates
  "Renders dates"
  [x y dates {:keys [scale box-width width panel-width label-width] :as config}]
  (let [px-width (* box-width width)
        show-times (= scale :consecutive)
        left-column-width (+ panel-width label-width)
        now (time-core/now)
        mdates (map #(datetime->map % now config) dates)]
    [:<>
     [:rect {:x x :y (+ y 0) :width panel-width :height (if show-times 20 15) :stroke "black" :fill "none" :stroke-width 0.1}]
     [:text {:x (+ 5 x) :y (+ y 6) :fill "black" :font-size 4} (time-format/unparse (:date-formatter config) (first dates))]
     [:rect {:x panel-width :y (+ y 0) :width label-width :height 5 :fill "none" :stroke "black" :stroke-width "0.1"}]
     [:rect {:x panel-width :y (+ y 5) :width label-width :height 5 :fill "none" :stroke "black" :stroke-width "0.1"}]
     [:rect {:x panel-width :y (+ y 10) :width label-width :height 5 :fill "none" :stroke "black" :stroke-width "0.1"}]
     [:text {:x (+ panel-width (/ label-width 2)) :y (+ y 4) :fill "black" :font-size "4" :text-anchor "middle"} "Day"]
     [:text {:x (+ panel-width (/ label-width 2)) :y (+ y 9) :fill "black" :font-size "4" :text-anchor "middle"} "Date"]
     [:text {:x (+ panel-width (/ label-width 2)) :y (+ y 14) :fill "black" :font-size "4" :text-anchor "middle"} "Month"]
     (when show-times
       [:<> [:text {:x (+ panel-width (/ label-width 2)) :y (+ y 19) :fill "black" :font-size "4" :text-anchor "middle"} "Time"]
        [:rect {:x panel-width :y (+ y 15) :width label-width :height 5 :fill "none" :stroke "black" :stroke-width "0.1"}]])
     [:rect {:x left-column-width :y (+ y 0) :width px-width :height (if show-times 20 15) :stroke "black" :stroke-width 0.1 :fill "url(#grid-score-0"}]
     (doall (map-indexed (fn [index item]                   ;; highlight today's date
                           (if (:is-today item)
                             [:rect {:x     (+ left-column-width (* index 7)) :y (+ y 0) :key (str "td" index)
                                     :width 7 :height (if show-times 20 15) :stroke "black" :stroke-width 0.5 :fill "#ffdddd"}])) mdates))
     (doall (map-indexed (fn [index item]                   ;; name of day
                           [:text {:x           (+ left-column-width 3.5 (* index 7)) :y (+ y 4) :font-size 3 :key (str "nd" item)
                                   :text-anchor "middle" :font-weight (if (:is-today item) "bold" "normal")}
                            (take 2 (:day-of-week item))]) mdates))
     (doall (map-indexed (fn [index item]                   ;; write out days of month
                           [:text {:x    (+ left-column-width 3.5 (* index 7)) :y (+ y 9) :key (str "dm" index)
                                   :fill "black" :font-size "3" :text-anchor "middle"} (:day-of-month item)]) mdates))
     (doall (map-indexed (fn [index item]                   ;; write out month
                           [:text {:x    (+ left-column-width 3.5 (* index 7)) :y (+ y 14)
                                   :fill "black" :font-size "3" :key (str "m-" index) :text-anchor "middle"} (:month item)]) mdates))
     (when show-times (doall (map-indexed (fn [index item]  ;; write out times
                                            [:text {:x    (+ left-column-width 3.5 (* index 7)) :y (+ y 18)
                                                    :fill "black" :font-size 2 :key (str "m-" index) :text-anchor "middle"} (:time item)]) mdates)))]))

(defn data->lines
  "Generates SVG points data for a line using :x and :y data from the map provided"
  [start-x start-y k data]
  (doall (remove nil? (flatten
                        (map #(let [idx (get-in % [k :y])
                                    x (+ 56 (* 7 (:x %)))
                                    y (+ 2.5 (* 5 idx))]
                                (when-not (nil? idx) (vector (+ 3.5 x) (+ start-y y)))) data)))))

(defn render-results
  "Render results for a chart"
  [start-x start-y data k]
  (let [line-points (data->lines start-x start-y k data)]
    [:<>
     (doall (map #(let [idx (get-in % [k :y])
                        x (+ 56 (* 7 (:x %)))
                        y (+ 2.5 (* 5 idx))]
                    (when-not (nil? idx)
                      (vector :circle {:cx (+ 3.5 x) :cy (+ start-y y) :r "0.2" :stroke "black" :fill "black" :key (:date-time %)}))) data))
     (when (> (count line-points) 0)
       [:polyline {:points line-points
                   :fill   "none" :stroke "black" :stroke-width 0.2 :stroke-dasharray "1 1"}])]))

(defn render-results-bp
  "Specialised results plot for blood pressure"
  [start-y data]
  [:<>
   (doall (remove nil? (map #(let [v (get-in % [:blood-pressure :y])
                                   x (+ 56 (* 7 (:x %)))
                                   systolic (+ 2.5 (* 5 v))
                                   dbp (get-in % [:blood-pressure :y-dbp])
                                   diastolic (if (nil? dbp) (+ systolic 0.2) (+ 2.5 (* 5 dbp)))]

                               (when-not (nil? v) [:<> (vector
                                                         :polyline {:points       [(+ 3.5 x) (+ start-y systolic 0.7) (+ 3.5 x) (+ start-y -0.7 diastolic)]
                                                                    :key          %
                                                                    :fill         "none" :stroke "black" :stroke-width 0.4
                                                                    :marker-start "url(#circle)"
                                                                    :marker-mid   (if (nil? dbp) "" "url(#circle)")
                                                                    :marker-end   (if (nil? dbp) "" "url(#circle)")})
                                                   (vector
                                                     :text {:x    (+ 3.5 x) :y (+ -0.1 start-y systolic) :text-anchor "middle"
                                                            :fill "black" :font-size 3} (get-in % [:results :blood-pressure :systolic]))
                                                   [:text {:x    (+ 3.5 x) :y (+ 2.1 start-y diastolic) :text-anchor "middle"
                                                           :fill "black" :font-size 3} (get-in % [:results :blood-pressure :diastolic])]])) data)))])

(def default-chart-configuration
  {:box-width           7                                   ;; the viewbox is based on the paper NEWS chart in millimetres, so our internal scale is same as "millimetres"
   :box-height          5
   :start-y             0
   :start-x             0
   :width               28                                  ;; number of boxes wide
   :panel-width         32                                  ;; the dark blue left column - "pixels"
   :label-width         24                                  ;; the labels     ;; 32+24 = 56 which is a multiple of 7
   :scale               :days                               ;; :days :fractional or :consecutive
   :time-formatter      (time-format/formatter "HH:mm")
   :date-formatter      (time-format/formatter "dd MMM yyyy")
   :day-week-formatter  (time-format/formatter "E")
   :day-month-formatter (time-format/formatter "dd")
   :month-formatter     (time-format/formatter "MM")})



(defn news-chart-wrapper
  [c]
  [:svg {:width "100%" :viewBox "0 0 255 391" :xmlns "http://www.w3.org/2000/svg"}
   [:defs
    [:pattern#grid-score-3 {:width "7" :height "5" :patternUnits "userSpaceOnUse"}
     [:rect {:width 7 :height 5 :fill colour-score-3 :stroke "black" :stroke-width 0.1}]]
    [:pattern#grid-score-2 {:width "7" :height "5" :patternUnits "userSpaceOnUse"}
     [:rect {:width 7 :height 5 :fill colour-score-2 :stroke "black" :stroke-width 0.1}]]
    [:pattern#grid-score-1 {:width "7" :height "5" :patternUnits "userSpaceOnUse"}
     [:rect {:width 7 :height 5 :fill colour-score-1 :stroke "black" :stroke-width 0.1}]]
    [:pattern#grid-score-0 {:width "7" :height "5" :patternUnits "userSpaceOnUse"}
     [:rect {:width 7 :height 5 :fill "white" :stroke "black" :stroke-width 0.1}]]
    [:pattern#dates-6-hourly {:width 14 :height 5 :patternUnits "userSpaceOnUse"}
     [:rect {:width 14 :height 5 :fill "white" :stroke "black" :stroke-width 0.1}]]
    [:marker#arrow {:viewBox "0 0 10 10" :refX "5" :refY "5" :markerWidth "6" :markerHeight "6" :orient "auto-start-reverse"}
     [:path {:d "M 0 0 L 10 5 L 0 10 z"}]]
    [:marker#circle {:markerWidth "6" :markerHeight "6" :refX "5" :refY "5"}
     [:circle {:cx "5" :cy "5" :r "2"}]]] c])


(defn render-news-chart
  "Render an SVG NEWS chart"
  [config start-date data hypercapnia?]
  (let [config (merge default-chart-configuration config)
        width (:width config)
        scored-data (score-all-news data hypercapnia?)
        scaled-data (scale-data (:scale config) start-date width scored-data)
        scaled-dates (scale-dates (:scale config) start-date width scored-data)
        time-adjust (if (= (:scale config) :consecutive) 5 0)
        sp02-adjust (+ time-adjust (if hypercapnia? 20 0))]
    [news-chart-wrapper
     [:<>
      [render-dates 0 0 scaled-dates config]

      (render-scores (+ 20 time-adjust) width
                     (if (= :days (:scale config)) "MAX NEWS TOTAL" "NEWS TOTAL") scaled-data :news-score news-score-to-colour)
      [render-axes (+ 30 time-adjust) width respiratory-rate-chart]
      [render-results 0 (+ 30 time-adjust) scaled-data :respiratory-rate]

      (if hypercapnia?
        [:<> [render-axes (+ 70 time-adjust) width spO2-chart-2]
         [render-results 0 (+ 70 time-adjust) scaled-data :spO2-chart-2]]
        [:<> [render-axes (+ 70 time-adjust) width spO2-chart-1]
         [render-results 0 (+ 70 time-adjust) scaled-data :spO2-chart-1]])

      (render-axes (+ 95 sp02-adjust) width blood-pressure-chart)
      (render-results-bp (+ 95 sp02-adjust) scaled-data)

      (render-axes (+ 170 sp02-adjust) width pulse-rate-chart)
      (render-results 0 (+ 170 sp02-adjust) scaled-data :pulse-rate)
      (render-axes (+ 235 sp02-adjust) width consciousness-chart)
      (render-results 0 (+ 235 sp02-adjust) scaled-data :consciousness)
      (render-axes (+ 265 sp02-adjust) width temperature-chart)
      (render-results 0 (+ 265 sp02-adjust) scaled-data :temperature)

      (render-scores (+ 300 sp02-adjust) width "NEWS TOTAL" scaled-data :news-score nil)
      (comment
        (plot-results 60 start-date width-in-boxes :day spO2-chart-2 data #(vector (:spO2 %) (:air-or-oxygen %)))

        ;;    (draw-chart-axes 60 width (:spO2-chart-1 charts))
        ;;    (plot-results 60 start-date width-in-boxes :day (:spO2-chart-1 charts) data :spO2)
        ;;   (draw-chart-axes 85 width (:air-or-oxygen charts))
        (draw-chart-axes 105 width blood-pressure-chart)
        (plot-results-bp 105 start-date width-in-boxes :day data :blood-pressure)
        (plot-results 180 start-date width-in-boxes :day pulse-rate-chart data :pulse-rate)
        (draw-chart-axes 245 width consciousness-chart)
        (plot-results 245 start-date width-in-boxes :day consciousness-chart data :consciousness)
        (draw-chart-axes 275 width temperature-chart)
        (plot-results 275 start-date width-in-boxes :day temperature-chart data :temperature))]]))




