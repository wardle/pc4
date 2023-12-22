(ns com.eldrix.pc4.filestorage
  "File archival and temporary file storage.
  Abstract file storage for data files, reports or extracts. For example, pc4
  uses this for remote storage of pc4 data files as well as asynchronously
  generated extracts and reports, stored and made available for later download
  with provision for subsequent clean-up. There are two concrete implementations
  (AWS S3 or local filesystem) which are available through dynamic
  configuration. The choice will depend on deployment target (e.g. on an NHS
  intranet, local filesystem will have to be used)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s])
  (:import (java.io File)
           (java.net URL)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)
           (java.time Duration Instant)
           (java.util Collection UUID)
           (software.amazon.awssdk.auth.credentials AwsBasicCredentials AwsCredentialsProvider StaticCredentialsProvider)
           (software.amazon.awssdk.core SdkField SdkPojo)
           (software.amazon.awssdk.core.sync RequestBody)
           (software.amazon.awssdk.regions Region)
           (software.amazon.awssdk.services.s3 S3Client)
           (software.amazon.awssdk.services.s3.model Delete DeleteObjectsRequest GetObjectRequest GetObjectResponse ListObjectsV2Request NoSuchKeyException ObjectIdentifier PutObjectRequest S3Object)
           (software.amazon.awssdk.services.s3.presigner S3Presigner)
           (software.amazon.awssdk.services.s3.presigner.model GetObjectPresignRequest)))

(set! *warn-on-reflection* true)

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

(defn- s3-creds-provider
  "Create an S3 credentials provider."
  ^AwsCredentialsProvider [{:keys [access-key-id secret-access-key]}]
  (StaticCredentialsProvider/create (AwsBasicCredentials/create access-key-id secret-access-key)))

(defn- s3-client
  "Create a long-lived and thread-safe S3 client.
  Config:
  - access-key-id
  - secret-access-key
  - region "
  ^S3Client [{:keys [region] :as config}]
  (-> (S3Client/builder)
      (.credentialsProvider (s3-creds-provider config))
      (.region (if (instance? Region region) region (Region/of region)))
      (.build)))

(defn- s3-presigner
  "Create a long-lived S3 URL presigner."
  ^S3Presigner [{:keys [region] :as config}]
  (-> (S3Presigner/builder)
      (.credentialsProvider (s3-creds-provider config))
      (.region (if (instance? Region region) ^Region region (Region/of region)))
      (.build)))

(defn- s3->map
  [^SdkPojo o]
  (reduce (fn [acc ^SdkField field]
            (assoc acc (keyword (.memberName field)) (.getValueOrDefault field o))) {} (.sdkFields o)))

(defn- s3-list-objects
  ([^S3Client s3 bucket-name]
   (let [req (-> (ListObjectsV2Request/builder) (.bucket bucket-name) (.build))]
     (map s3->map (seq (.contents (.listObjectsV2Paginator s3 ^ListObjectsV2Request req))))))
  ([^S3Client s3 bucket-name prefix]
   (let [req (-> (ListObjectsV2Request/builder) (.bucket bucket-name) (.prefix prefix) (.build))]
     (map s3->map (seq (.contents (.listObjectsV2Paginator s3 ^ListObjectsV2Request req)))))))

(defn- s3-list-old-objects
  "Returns a list of 'old' objects in the bucket."
  [s3 bucket-name ^Duration duration]
  (let [cut-off (.minus (Instant/now) duration)]
    (->> (s3-list-objects s3 bucket-name)
         (filter #(.isAfter cut-off (:LastModified %))))))

(defn- s3-presigned-url
  "Returns a presigned URL that will be valid for the duration specified, or
  24 hours if no specific duration provided."
  (^URL [^S3Presigner s3-presigner bucket-name k]
   (s3-presigned-url s3-presigner bucket-name k {}))
  (^URL [^S3Presigner s3-presigner bucket-name k {:keys [^Duration duration]}]
   (let [duration' (or duration (Duration/ofHours 24))
         o-req (-> (GetObjectRequest/builder) (.bucket bucket-name) (.key k) (.build))
         p-req (-> (GetObjectPresignRequest/builder) (.signatureDuration duration') (.getObjectRequest ^GetObjectRequest o-req) (.build))]
     (.url (.presignGetObject s3-presigner p-req)))))

(defn- s3-put-object
  "Put an object to S3."
  [^S3Client s3 ^String bucket-name ^String k {:keys [f content-type] :as o}]
  (when-not (s/valid? ::object o)
    (throw (ex-info "invalid object" (s/explain-data ::object o))))
  (let [req (-> (PutObjectRequest/builder)
                (.bucket bucket-name)
                (.key k)
                (.contentType content-type)
                (.metadata {"metadata" (pr-str (dissoc o :f))})
                (.build))]
    (.putObject s3 ^PutObjectRequest req (RequestBody/fromFile (io/file f)))))

(defn- s3-get-object
  [^S3Client s3 ^String bucket-name ^String k]
  (try
    (let [req (-> (GetObjectRequest/builder)
                  (.bucket bucket-name)
                  (.key k)
                  (.build))
          f (File/createTempFile k ".tmp")
          is (.getObject s3 ^GetObjectRequest req)
          metadata (edn/read-string (get (.metadata ^GetObjectResponse (.response is)) "metadata"))
          response-data (s3->map (.response is))]
      (try (io/copy is f)
           (-> (merge response-data metadata)
               (assoc :f f))
           (finally (.close is))))
    (catch NoSuchKeyException _ nil)))

(defn- s3-delete-objects
  "Delete objects from the bucket with keys `ks`."
  [^S3Client s3 bucket-name ks]
  (let [objects (mapv #(-> (ObjectIdentifier/builder) (.key %) (.build)) ks)
        del (-> (Delete/builder) (.objects ^Collection objects) (.build))
        req (-> (DeleteObjectsRequest/builder) (.bucket bucket-name) (.delete ^Delete del) (.build))]
    (.deleteObjects s3 ^DeleteObjectsRequest req)))

(defn- s3-clean
  "Deletes 'old' objects from the bucket last modified `duration` time ago.
  Returns a list of objects that were deleted."
  [s3 bucket-name duration]
  (when-let [objects (seq (s3-list-old-objects s3 bucket-name duration))]
    (s3-delete-objects s3 bucket-name (map :key objects))
    objects))


(deftype ^:private S3FileStorage [^S3Client s3 ^S3Presigner presigner
                                  bucket-name link-duration retention-duration]
  FileStorage
  (put-object [_ k o]
    (s3-put-object s3 bucket-name k o))
  (get-object [_ k]
    (s3-get-object s3 bucket-name k))
  (url [_ k]
    (s3-presigned-url presigner bucket-name k link-duration))
  (clean [_]
    (s3-clean s3 bucket-name retention-duration))
  (close [_]
    (.close s3)))


;;
;; Local filesystem implementation
;;

(defn- safe-file
  "Resolve key 'k' within directory 'dir' safely. Avoids a path injection
  attack by ensuring the target file is definitely within the parent directory."
  ^File [dir k]
  (let [f (io/file dir k)                                   ;; take care to only allow files from within our directory
        parent (.getCanonicalPath (io/file dir))            ;; turn into absolute paths
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
        for-deletion (filter #(> cut-off (.lastModified ^File %)) (file-seq dir))]
    (run! #(.delete ^File %) for-deletion)
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
  - :local - uses local file storage using :dir

  Other configuration options:
  - :link-duration      - how long should the link be made available?
  - :retention-duration - how long should the file be retained before cleanup?"
  [{:keys [kind bucket-name dir link-duration retention-duration] :as config}]
  (let [link-duration' (or link-duration (Duration/ofHours 24))
        retention-duration' (or retention-duration (Duration/ofHours 24))]
    (case kind
      :s3
      (->S3FileStorage (s3-client config) (s3-presigner config) bucket-name link-duration' retention-duration')
      :local
      (->LocalFileStorage dir link-duration' retention-duration')
      (throw (ex-info "unknown file store type" config)))))


(defn make-secure-random-key
  "Returns a cryptographically secure random key suitable for a file."
  []
  (UUID/randomUUID))

(comment
  (require '[com.eldrix.pc4.system :as pc4])
  (def config (:com.eldrix.pc4/filestorage (pc4/config :dev)))
  (def s3 (s3-client config))
  (def s3ps (s3-presigner config))
  (s3-list-objects s3 "patientcare4")
  (map :Key (s3-list-objects s3 "patientcare4"))
  (s3-delete-objects s3 "patientcare4" (map :Key (s3-list-objects s3 "patientcare4")))

  (s3-put-object s3 "patientcare4" "README.md" {:f "README.md" :content-type "text/plain" :hi 1})
  (s3-get-object s3 "patientcare4" "README.md")
  (s3-put-object s3 "patientcare4" "wibble.md" {:f "README.md"})
  (s3-put-object s3 "patientcare4" "deps.edn" {:f "deps.edn" :content-type "text/plain" :hi 2})
  (s3-presigned-url s3 "patientcare4" "README.md")

  (def dir-path (Files/createTempDirectory "wibble" (make-array FileAttribute 0)))
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

