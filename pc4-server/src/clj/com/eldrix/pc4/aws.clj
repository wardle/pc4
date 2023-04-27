(ns com.eldrix.pc4.aws
  (:require [clojure.java.io :as io])
  (:import (java.net URL)
           (java.time Duration ZonedDateTime)
           (java.util Date)
           (com.amazonaws HttpMethod)
           (com.amazonaws.auth AWSStaticCredentialsProvider BasicAWSCredentials)
           (com.amazonaws.services.s3 AmazonS3 AmazonS3ClientBuilder)
           (com.amazonaws.services.s3.model DeleteObjectsRequest DeleteObjectsRequest$KeyVersion GeneratePresignedUrlRequest ObjectMetadata PutObjectRequest)))

(defn s3-client
  "Create a long-lived and thread-safe S3 client."
  ^AmazonS3 [{:keys [access-key secret-key region]}]
  (let [creds (BasicAWSCredentials. access-key secret-key)
        builder (doto (AmazonS3ClientBuilder/standard)
                  (.setCredentials (AWSStaticCredentialsProvider. creds))
                  (.setRegion region))]
    (.build builder)))

(defn close [^AmazonS3 s3]
  (.shutdown s3))

(defn list-bucket [s3 bucket-name]
  (map bean (.getObjectSummaries (.listObjectsV2 s3 bucket-name))))

(defn presigned-url
  "Returns a presigned URL that will be valid for the duration specified, or
  24 hours."
  (^URL [^AmazonS3 s3 bucket-name key] (presigned-url s3 bucket-name key {}))
  (^URL [^AmazonS3 s3 bucket-name key {:keys [^Duration duration]}]
   (let [duration' (or duration (Duration/ofHours 24))
         expiration (Date/from (.toInstant (.plus (ZonedDateTime/now) duration')))]
     (.generatePresignedUrl s3 (-> (GeneratePresignedUrlRequest. bucket-name key)
                                   (.withMethod HttpMethod/GET)
                                   (.withExpiration expiration))))))

(defn upload-file
  [^AmazonS3 s3 ^String bucket-name ^String key f content-type]
  (let [metadata (doto (ObjectMetadata.) (.setContentType content-type))
        req (-> (PutObjectRequest. bucket-name key (io/file f))
                (.withMetadata metadata))]
    (.putObject s3 req)))

(defn delete-objects
  "Delete objects from the bucket with keys `ks`."
  [s3 bucket-name ks]
  (.deleteObjects s3 (doto (DeleteObjectsRequest. bucket-name)
                       (.setKeys (map #(DeleteObjectsRequest$KeyVersion. %) ks)))))

(comment
  (def config (clojure.edn/read-string (slurp "aws-config.edn")))
  (def s3 (s3-client config))
  (.doesBucketExistV2 s3 "patientcare4")
  (list-bucket s3 "patientcare4")
  (map :key (list-bucket s3 "patientcare4"))
  (delete-objects s3 "patientcare4" (map :key (list-bucket s3 "patientcare4")))

  (upload-file s3 "patientcare4" "README.md" "README.md" "text/plain")
  (upload-file s3 "patientcare4" "deps.edn" "deps.edn" "text/plain")
  (presigned-url s3 "patientcare4" "hello.txt"))

