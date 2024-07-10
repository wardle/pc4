(ns pc4.deprivare.interface-test
  (:require [clojure.test :as test :refer [deftest is]]
            [integrant.core :as ig]
            [pc4.config.interface :as config]
            [pc4.deprivare.interface :as deprivare]))

(deftest test-wales-lsoa
  (let [config (config/config :dev)
        {:pc4.deprivare.interface/keys [svc]} (ig/init config [:pc4.deprivare.interface/svc])
        data (deprivare/fetch-lsoa svc "W01001552")]
    (is (= "Monmouthshire" (:wales-imd-2019-quantiles/authority_name data)))
    (is (= "Dixton with Osbaston" (:wales-imd-2019-ranks/lsoa_name data)))))
