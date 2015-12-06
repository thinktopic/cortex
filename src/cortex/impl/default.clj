(ns cortex.impl.default
  "Default implementations for coretx protocols."
  (:require [cortex.protocols :as cp])
  (:require [clojure.core.matrix :as m])
  (:require [cortex.util :as util :refer [error]])
  (:import [mikera.vectorz Vectorz]))

(def EMPTY-VECTOR (Vectorz/newVector 0))

(extend-protocol cp/PParameters
  Object
	  (parameters 
      ([m]
        ;; default to assuming zero parameters
        EMPTY-VECTOR))
    (update-parameters 
      ([m parameters]
        (when (> 0 (m/ecount parameters)) (error "Non-zero length for parameter update"))
        m)))

(extend-protocol cp/PGradient
  ;; default to assuming zero parameters
  Object
    (gradient 
      ([m]
        EMPTY-VECTOR)))


