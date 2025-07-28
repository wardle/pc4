(ns pc4.nhspd.interface
  (:require
   [clojure.java.io :as io]
   [pc4.log.interface :as log]
   [com.eldrix.nhspd.api :as nhspd]
   [com.eldrix.nhspd.postcode :as pc]
   [integrant.core :as ig]))

(defmethod ig/init-key ::svc
  [_ {:keys [root f path]}]
  (let [path' (or path (.getCanonicalPath (io/file root f)))]
    (log/info "opening nhspd index " path')
    (nhspd/open path')))

(defmethod ig/halt-key! ::svc
  [_ nhspd]
  (.close nhspd))

;;
;;
;;
;;

(defn distance-between
  "Calculates crude distance between two postcodes, determined by the square
  root of the sum of the square of the difference in grid coordinates
  (Pythagoras), result in metres.
  Parameters:
  - pcd1 - first postcode NHSPD data (map)
  - pcd2 - second postcode NHSPD data (map)"
  [pcd1 pcd2]
  (pc/distance-between pcd1 pcd2))

(defn egif
  "Normalizes a postcode into uppercase with outward code and inward codes
  separated by a single space.
  This is the PCDS standard formatting."
  [s]
  (pc/egif s))

(defn fetch-postcode
  "Return a map of data for the given postal code from the NHS postcode directory.
  - s : a string containing a postal code. It will be normalised to the PCD2 standard
  automatically prior to search."
  [svc s]
  (nhspd/fetch-postcode svc s))

(defn fetch-wgs84
  "Returns WGS84 geographical coordinates for a given postcode.
   - pc : a UK postal code; will be coerced into the PCD2 postcode standard."
  [svc s]
  (nhspd/with-wgs84 (nhspd/postcode svc s)))

(defn normalize
  "Normalizes a postcode into uppercase 8-characters with left-aligned outward
  code and right-aligned inward code returning the original if normalization
  not possible.
  This is the PCD2 standard formatting."
  [s]
  (pc/normalize s))


