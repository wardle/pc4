(ns pc4.report.impl.woc
  (:require
   [clojure.spec.alpha :as s]
   [clojure.spec.gen.alpha :as gen]
   [clojure.string :as str]
   [clojure.java.io :as io]
   [selmer.filters :as sfilters]
   [selmer.parser :as selmer]
   [pc4.nhs-number.interface :as nnn])
  (:import
    (java.io ByteArrayOutputStream)
    (java.time LocalDate LocalDateTime)
    (java.util Base64)
    (org.xhtmlrenderer.pdf ITextRenderer)))

(sfilters/add-filter! :format-nhs-number nnn/format-nnn)

(defn html->pdf
  "Convert the HTML in `s` to a PDF."
  [s]
  (let [xout (ByteArrayOutputStream.)]
    (doto (ITextRenderer.)
      (.setDocumentFromString s)
      (.layout)
      (.createPDF xout))
    (.toByteArray xout)))

(defn ^:private encounter-report*
  [ctx]
  (selmer/render-file "report/encounter-report.html" ctx))

(defn gen-local-date []
  (gen/fmap (fn [days] (.minusDays (LocalDate/now) days))
            (s/gen (s/int-in 1 (* 365 10)))))

(defn gen-local-date-time []
  (gen/fmap (fn [seconds] (.minusSeconds (LocalDateTime/now) (long seconds)))
            (s/gen (s/int-in 1 (* 365 20 24 60 60)))))

(defn gen-crn []
  (gen/fmap (fn [[a b]] (str a b))
            (gen/tuple (gen/char-alpha) (gen/choose 100000 999999))))

(s/def ::local-date
  (s/with-gen #(instance? LocalDate %) #(gen-local-date)))

(s/def ::local-date-time
  (s/with-gen #(instance? LocalDateTime %) #(gen-local-date-time)))

(defn gen-human-name-prefix []
  (gen/elements ["Mr" "Mrs" "Dr" nil "Professor" "Prof"]))

(defn gen-human-name []
  (gen/fmap (fn [names] (str/join " " (remove nil? names)))
            (gen/tuple (gen-human-name-prefix) (gen/string-ascii) (gen/string-ascii))))

(defn gen-address-line []
  (gen/one-of [(gen/fmap (fn [tokens] (str/join " " tokens))
                         (gen/vector (gen/string-ascii) 1 3))
               (gen/return nil)]))

(defn gen-address []
  (gen/vector (gen-address-line) 0 5))

(comment
  (gen/generate (gen-address)))
(s/def ::name (s/with-gen string? #(gen-human-name)))
(s/def ::id pos-int?)
(s/def ::title string?)
(s/def ::patient-identifier pos-int?)
(s/def ::crn (s/with-gen (s/nilable string?)
               #(gen-crn)))
(s/def ::nhs-number (s/with-gen (s/nilable nnn/valid?)
                      #(nnn/gen-nhs-number 9)))
(s/def ::date-birth (s/nilable ::local-date))
(s/def ::date-time ::local-date-time)
(s/def ::job-title string?)
(s/def ::signature-url string?)
(s/def ::address (s/with-gen (s/coll-of string?)
                   #(gen-address)))

(s/def ::user
  (s/keys :req-un [::name]
          :opt-un [::job-title ::signature-url]))

(s/def ::users (s/coll-of ::user))

(s/def ::patient
  (s/keys :req-un [::name ::patient-identifier]
          :opt-un [::address ::date-birth ::crn ::nhs-number]))

(s/def ::to
  (s/keys :req-un [::name ::address]))

(s/def ::signed
  (s/keys :req-un [::user]))

(s/def ::report
  (s/keys :req-un [::id ::date-time ::signed]
          :opt-un [::to]))

(s/def ::encounter-template
  (s/keys :req-un [::title]))

(s/def ::encounter (s/keys :req-un [::encounter-template
                                    ::date-time
                                    ::users]))
(s/def ::encounter-report
  (s/keys :req-un [::title
                   ::patient
                   ::encounter
                   ::report]))

(def gen-encounter-report
  (s/gen ::encounter-report))

(defn encounter-report
  "Generate a PDF encounter report given the context specified."
  [ctx]
  (when-not (s/valid? ::encounter-report ctx)
    (throw (ex-info "Invalid context" (s/explain-data ::encounter-report ctx))))
  (html->pdf (encounter-report* ctx)))

(defn ^:private file->bytes [in]
  (with-open [xin (io/input-stream in)
              xout (ByteArrayOutputStream.)]
    (io/copy xin xout)
    (.toByteArray xout)))

(defn make-data-url
  "Encode a binary file as a HTML data URL in base64.
  Parameters:
  - in        : anything coercible to an input-stream using [[clojure.java.io/input-stream]]
  - mime-type : a string representing mime-type of the binary file."
  [in mime-type]
  (str "data:" mime-type ";base64," (.encodeToString (Base64/getEncoder) (file->bytes in))))

(comment

  (selmer.parser/cache-off!)
  (io/resource "report/encounter-report.html")
  (def s (selmer/render-file "report/encounter-report.html"
                             {}
                             :cache? false))
  (make-data-url (io/input-stream "http://images.metmuseum.org/CRDImages/ma/web-large/DP241865.jpg") "image/jpg")

  (def ctx {:title "Hi there"
            ;:test-image-url (to-data-url (io/input-stream "http://images.metmuseum.org/CRDImages/ma/web-large/DP241865.jpg") "image/jpg")
            :patient {:name "Mr John Smith"
                      :date-birth (LocalDate/of 1960 1 1)
                      :crn "A123456"
                      :nhs-number "1111111111"
                      :patient-identifier 14032}
            :report {:id 65576
                     :date-time (LocalDateTime/of 2023 9 1 14 15)
                     :signed {:sign-off "Yours,"
                              :user {:signature-url (make-data-url "https://www.jsign.com/wp-content/uploads/2022/06/graphic-signature-style.png" "image/png")
                                     :name "Dr Mark Wardle"}}
                     :to {:name "Dr Mark Wardle"
                          :address ["1 Station Road"
                                    "Smallville"
                                    "Space City"
                                    "South County"
                                    "CF14 4XW"]}}
            :encounter {:encounter-template {:title "NHS Clinic"}
                        :date-time (LocalDateTime/now)
                        :users [{:name "Ms Dawn French" :job-title "Physiotherapist"}
                                {:name "Joe Bloggs"
                                 :job-title "Speech and Language Therapist"}]}})
  (gen/generate (s/gen ::encounter-report))
  (require '[pc4.report.impl.stamp :as stamp])
  (def ctx (gen/generate (s/gen ::encounter-report)))
  *e
  (with-open [out (io/output-stream "final.pdf")]
    (io/copy (-> (encounter-report ctx) ;; generate a PDF report
                 (stamp/stamp-pdf-text {:centre "DRAFT" :footer (str "Printed on " (LocalDateTime/now))})  ;; add status information
                 #_(stamp/stamp-pdf-template "https://www.maths.ed.ac.uk/~dmarsh/files/printer-testcard-colour.pdf")) out)))  ;; add template header/footer
