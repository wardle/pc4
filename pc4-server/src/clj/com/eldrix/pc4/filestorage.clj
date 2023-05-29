(ns com.eldrix.pc4.filestorage
  "Abstract temporary file storage for reports or data extracts that are
  generated asynchronously, stored and made available for later download with
  provision for subsequent clean-up.

  There are two concrete implementations (AWS S3 or local filesystem) which are
  available through dynamic configuration. The choice will depend on deployment
  target (e.g. on an NHS intranet, local filesystem will have to be used)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.tools.logging.readable :as log]
            [com.eldrix.pc4.system :as pc4]
            [integrant.core :as ig])
  (:import (java.io File)
           (java.net URL)
           (java.nio.file.attribute FileAttribute)
           (java.time Duration Instant ZonedDateTime)
           (java.util Date UUID)
           (com.amazonaws HttpMethod)
           (com.amazonaws.auth AWSStaticCredentialsProvider BasicAWSCredentials)
           (com.amazonaws.services.s3 AmazonS3 AmazonS3ClientBuilder)
           (com.amazonaws.services.s3.model DeleteObjectsRequest DeleteObjectsRequest$KeyVersion GeneratePresignedUrlRequest GetObjectRequest ObjectMetadata PutObjectRequest)))


(s/def ::f any?)
(s/def ::content-type string?)
(s/def ::object (s/keys :req-un [::f ::content-type]))

(defprotocol FileStorage
  :extend-via-metadata true
  (put-object [this k o]
    "Put an object to file storage using key 'k'. 'o' should be a map containing
    at least the keys:
    - :content-type   - content type of the file e.g. \"text/plain\"
    - :f              - anything coercible using `clojure.java.io/file`")
  (get-object [this k]
    "Get an object from file storage, returning result as a map of data
    containing at least the keys
     - :content-type  - content type of the file
     - :f             - a `java.io.File` for the file")
  (url [this k] "Return a time-limited URL that can be used to fetch an object using key 'k'. Not all implementations can
  provide a URL, so clients will need to fallback to using [[get-object]] and providing a different mechanism for download.")
  (clean [this] "Removes leftover files from storage according to storage retention policies in place.")
  (close [this]))

;;
;; AWS s3 implementation
;;

(defn- s3-client
  "Create a long-lived and thread-safe S3 client."
  ^AmazonS3 [{:keys [access-key-id secret-access-key region]}]
  (let [creds (BasicAWSCredentials. access-key-id secret-access-key)
        builder (doto (AmazonS3ClientBuilder/standard)
                  (.setCredentials (AWSStaticCredentialsProvider. creds))
                  (.setRegion region))]
    (.build builder)))

(defn- s3-list-objects [s3 bucket-name]
  (map bean (.getObjectSummaries (.listObjectsV2 s3 bucket-name))))

(defn- s3-list-old-objects
  "Returns a list of 'old' objects in the bucket."
  [s3 bucket-name duration]
  (let [date (Date/from (.toInstant (.minus (ZonedDateTime/now) duration)))]
    (->> (s3-list-objects s3 bucket-name)
         (filter #(.after date (:lastModified %))))))

(defn- s3-presigned-url
  "Returns a presigned URL that will be valid for the duration specified, or
  24 hours if no specific duration provided."
  (^URL [^AmazonS3 s3 bucket-name k] (s3-presigned-url s3 bucket-name k {}))
  (^URL [^AmazonS3 s3 bucket-name k {:keys [^Duration duration]}]
   (let [duration' (or duration (Duration/ofHours 24))
         expiration (Date/from (.toInstant (.plus (ZonedDateTime/now) duration')))]
     (.generatePresignedUrl s3 (-> (GeneratePresignedUrlRequest. bucket-name k)
                                   (.withMethod HttpMethod/GET)
                                   (.withExpiration expiration))))))

(defn- s3-put-object
  "Put an object to S3."
  [^AmazonS3 s3 ^String bucket-name ^String k {:keys [f content-type] :as o}]
  (when-not (s/valid? ::object o)
    (throw (ex-info "invalid object" (s/explain-data ::object o))))
  (let [omd (doto (ObjectMetadata.) (.setUserMetadata {"metadata" (pr-str (dissoc o :f))}))]
    (when content-type (.setContentType omd content-type))
    (.putObject s3 (-> (PutObjectRequest. bucket-name k (io/file f))
                       (.withMetadata omd)))))

(defn- s3-get-object
  [^AmazonS3 s3 ^String bucket-name ^String k]
  (let [obj (.getObject s3 bucket-name k)
        f (File/createTempFile (.getKey obj) ".tmp")
        omd (.getObjectMetadata obj)
        s3is (.getObjectContent obj)]
    (try (io/copy s3is f)
         (finally (.close s3is)))
    (assoc (edn/read-string (get (.getUserMetadata omd) "metadata")) :f f)))

(defn- s3-delete-objects
  "Delete objects from the bucket with keys `ks`."
  [s3 bucket-name ks]
  (.deleteObjects s3 (doto (DeleteObjectsRequest. bucket-name)
                       (.setKeys (map #(DeleteObjectsRequest$KeyVersion. %) ks)))))

(defn- s3-clean
  "Deletes 'old' objects from the bucket last modified `duration` time ago.
  Returns a list of objects that were deleted."
  [s3 bucket-name duration]
  (when-let [objects (seq (s3-list-old-objects s3 bucket-name duration))]
    (s3-delete-objects s3 bucket-name (map :key objects))
    objects))

(deftype ^:private S3FileStorage [s3 bucket-name link-duration retention-duration]
  FileStorage
  (put-object [_ k o]
    (s3-put-object s3 bucket-name k o))
  (get-object [_ k]
    (s3-get-object s3 bucket-name k))
  (url [_ k]
    (s3-presigned-url s3 bucket-name k link-duration))
  (clean [_]
    (s3-clean s3 bucket-name retention-duration))
  (close [_]
    (.shutdown s3)))

;;
;; Local filesystem implementation
;;

(defn- safe-file
  "Resolve key 'k' within directory 'dir' safely. Avoids a path injection
  attack by ensuring the target file is definitely within the parent directory."
  [dir k]
  (let [f (io/file dir k)                                   ;; take care to only allow files from within our directory
        parent (.getCanonicalPath dir)                      ;; turn into absolute paths
        path (.getCanonicalPath f)]                         ;; and check that target is within our parent
    (if-not (.startsWith path parent)
      (throw (ex-info "cannot return file from outside storage directory" {:dir dir :k k}))
      f)))

(defn- local-put-object [dir k {f :f, :as o}]
  (when-not (s/valid? ::object o)
    (throw (ex-info "invalid object" (s/explain-data ::object o))))
  (let [f' (safe-file dir k)
        mdf (safe-file dir (str k ".edn"))]
    (io/copy (io/file f) f')
    (spit mdf (pr-str (dissoc o :f)))))

(defn- local-get-object [dir k]
  (let [f (safe-file dir k)
        mdf (safe-file dir (str k ".edn"))]
    (when (and (.exists f) (.exists mdf))
      (let [md (edn/read-string (slurp mdf))]
        (assoc md :f f)))))

(defn- local-clean
  "Deletes 'old' objects from the dir with last modified `duration` time ago."
  [dir {:keys [^Duration duration] :as threshold}]
  (let [cut-off (.toEpochMilli (.minus (Instant/now) duration))
        for-deletion (filter #(> cut-off (.lastModified %)) (file-seq dir))]
    (run! #(.delete %) for-deletion)
    for-deletion))

(deftype ^:private LocalFileStorage [dir link-duration retention-duration]
  FileStorage
  (put-object [_ k o]
    (local-put-object dir k o))
  (get-object [_ k]
    (local-get-object dir k))
  (url [_ k])
  (clean [_]
    (local-clean dir {:duration retention-duration}))
  (close [_]))


;;
;;
;;

(defn make-file-store
  "Create a file store using the configuration specified.
  Supports kinds:
  - :s3 - uses AWS S3 for file storage using :access-key-id :secret-access-key and :bucket-name
  - :local - uses local file storage using :dir and :url-fn

  For 'local', a url-fn must be passed in that will generate a URL.

  Other configuration options:
  - :link-duration      - how long should the link be made available?
  - :retention-duration - how long should the file be retained before cleanup?"
  [{:keys [kind bucket-name dir link-duration retention-duration] :as config}]
  (let [link-duration' (or link-duration (Duration/ofHours 24))
        retention-duration' (or retention-duration (Duration/ofHours 24))]
    (case kind
      :s3
      (->S3FileStorage (s3-client config) bucket-name link-duration' retention-duration')
      :local
      (->LocalFileStorage dir link-duration' retention-duration'))))


(defn make-secure-random-key
  "Returns a cryptographically secure random key suitable for a file."
  []
  (UUID/randomUUID))

(defmethod ig/init-key :com.eldrix.pc4/filestorage [_ {:keys [link-duration retention-duration] :as config}]
  (let [config' (cond-> config
                        link-duration (assoc :link-duration (Duration/parse link-duration))
                        retention-duration (assoc :retention-duration (Duration/parse retention-duration)))]
    (log/info "registering filestore service" (select-keys config' [:kind :region :bucket-name :dir :link-duration :retention-duration]))
    (make-file-store config')))

(defmethod ig/halt-key! :com.eldrix.pc4/filestorage [_ fs]
  (close fs))

(comment
  (def config (:com.eldrix.pc4/filestorage (pc4/config :dev)))
  (def s3 (s3-client config))
  (.doesBucketExistV2 s3 "patientcare4")
  (s3-list-objects s3 "patientcare4")
  (map :key (s3-list-objects s3 "patientcare4"))
  (s3-delete-objects s3 "patientcare4" (map :key (s3-list-objects s3 "patientcare4")))

  (s3-put-object s3 "patientcare4" "README.md" {:f "README.md" :content-type "text/plain" :hi 1})
  (s3-put-object s3 "patientcare4" "wibble.md" {:f "README.md"})
  (s3-put-object s3 "patientcare4" "deps.edn" {:f "deps.edn" :content-type "text/plain" :hi 2})
  (s3-presigned-url s3 "patientcare4" "README.md")

  (def dir-path (java.nio.file.Files/createTempDirectory "wibble" (make-array FileAttribute 0)))
  (def dir (.toFile dir-path))
  dir
  (local-get-object dir "wibble")
  (local-put-object dir "README.md" {:f "README.md" :content-type "text/plain" :user 3})
  (local-get-object dir "README.md")

  (def store-s3 (make-file-store (merge config {:kind               :s3
                                                :bucket-name        "patientcare4"
                                                :retention-duration (Duration/ofSeconds 5)})))
  (put-object store-s3 "README.md" {:f "README.md" :content-type "text/plain"})
  (put-object store-fs "deps.edn" {:f "deps.edn" :content-type "application/edn"})
  (get-object store-s3 "README.md")
  (clean store-s3)
  (def store-fs (make-file-store {:kind :local :dir dir :retention-duration (Duration/ofSeconds 5)}))
  (put-object store-fs "README.md" {:f "README.md" :content-type "text/plain" :extra [1 2 3]})
  (put-object store-fs "deps.edn" {:f "deps.edn" :content-type "application/edn"})
  (get-object store-fs "README.md")
  (get-object store-fs "deps.edn")
  store-fs
  (clean store-fs))

