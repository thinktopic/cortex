(ns think.cortex.keras.core
  (:require [think.hdf5.core :as hdf5]
            [cortex.nn.description :as desc]
            [think.resource.core :as resource]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.core.matrix :as m]
            [clojure.core.matrix.macros :refer [c-for]]
            [think.compute.datatype :as dtype]
            [clojure.string :as string]))


(defn read-model
  [fname]
  (json/parse-string (slurp fname) keyword))

(defmulti model-item->desc (fn [item]
                             (keyword (:class_name item))))

(defmethod model-item->desc :Convolution2D
  [{:keys [config]}]
  (let [[stride-x stride-y] (get config :subsample [1 1])
        [pad-x pad-y] (get config :padding [0 0])
        kernel-x (long (get config :nb_col))
        kernel-y (long (get config :nb_row))
        kernel-count (long (get config :nb_filter))
        id (keyword (get config :name))
        activation (keyword (get config :activation))
        conv-desc (first (desc/convolutional-expanded kernel-x kernel-y pad-x pad-y
                                                      stride-x stride-y kernel-count))
        conv-desc (assoc conv-desc
                         :id id)
        ]
    (when-not (= (:dim_ordering config) "tf")
      (throw
       (Exception. "Please convert model to 'tf' weights.  'th' weights are not supported.")))
    (if (and activation
             (not= activation :linear))
      [conv-desc {:type activation}]
      [conv-desc])))


(defmethod model-item->desc :MaxPooling2D
  [{:keys [config]}]
  (let [[kernel-x kernel-y] (get config :pool_size)
        [stride-x stride-y] (get config :strides)]
    (desc/max-pooling kernel-x kernel-y 0 0 stride-x stride-y)))

(defmethod model-item->desc :Activation
  [{:keys [config]}]
  {:type (keyword (:activation config))})

(defmethod model-item->desc :Dropout
  [{:keys [config]}]
  (desc/dropout (- 1.0 (:p config))))

(defmethod model-item->desc :Flatten
  [_]
  [])

(defmethod model-item->desc :Dense
  [{:keys [config]}]
  (let [output-size (long (:output_dim config))
        activation (keyword (get config :activation "linear"))
        retval (-> (first (desc/linear output-size))
                   (assoc :id (keyword (:name config))))
        ]
    (if-not (= activation :linear)
      [retval {:type activation}]
      [retval])))

(defn model->simple-description
  [model]
  (let [model  (if (= (:class_name model) "Sequential")
                 (:config model)
                 (vec model))
        [_ width height n-channels] (get-in model [0 :config :batch_input_shape])
        ;;move zeropadding into convolution modules
        model-vector (reduce (fn [model-vector {:keys [class_name config] :as current}]
                               (if (and (= (keyword class_name) :Convolution2D)
                                        (= (keyword (get (last model-vector) :class_name))
                                           :ZeroPadding2D))
                                 (conj (vec (drop-last model-vector))
                                       (update-in current [:config]
                                                  #(merge (get (last model-vector)
                                                               :config)
                                                          %)))
                                 (conj model-vector current)))
                             [] model)]
    ;;TODO models with a single channel input and figure out planar vs. interleaved
    (vec
     (flatten (concat (desc/input width height n-channels)
                      (mapv model-item->desc model-vector))))))

(defn hdf5-child-map
  [node]
  (into {} (map (fn [node-child]
                  [(keyword (hdf5/get-name node-child))
                   node-child])
                (hdf5/get-children node))))

(defn- reshape-time-test
  []
  (let [n-rows 100
        n-cols 1000
        src-array (double-array (* n-rows n-cols))]
    (println "reshape time")
    (time (dotimes [idx 10]
            (m/reshape src-array [n-rows n-cols])))
    (println "c-for time")
    (time (dotimes [idx 10]
            (let [^"[[D" dest (make-array Double/TYPE n-rows n-cols)]
              (c-for [row 0 (< row n-rows) (inc row)]
                     (java.lang.System/arraycopy src-array (* row n-cols)
                                                 (get dest row) 0 n-cols)))))))


(defn ensure-doubles
  ^doubles [data]
  (if (not= :double (dtype/get-datatype data))
    (let [double-data (double-array (m/ecount data))]
      (dtype/copy! data 0 double-data 0 (m/ecount data))
      double-data)
    data))

(defn- dims->strides
  [dims]
  (vec (reduce (fn [retval next-dim]
                 (let [last-stride (or (first retval) 1)
                       next-dim (or next-dim 1)]
                   (conj retval (* last-stride next-dim))))
               ()
               (reverse dims))))


(defn- strides-idx->dim-indexes
  [strides ^long idx]
  (let [num-strides (count strides)]
   (loop [retval []
          leftover idx
          stride-idx 0]
     (if (< stride-idx num-strides)
       (let [stride (strides stride-idx)
             next-item (quot leftover stride)
             next-leftover (rem leftover stride)]
         (recur (if-not (= 0 stride-idx)
                  (conj retval next-item)
                  retval) next-leftover (inc stride-idx)))
       (conj retval leftover)))))


(defn- strides-dim-indexes->idx
  ^long [strides dim-indexes]
  (let [n-elems (count strides)]
    (loop [retval 0
           idx 0]
      (if (< idx n-elems)
        (recur (+ retval (* (long (if (= idx (- n-elems 1))
                                    1
                                    (strides (inc idx))))
                            (long (dim-indexes idx))))
               (inc idx))
        retval))))


(defn- input-idx->output-idx
  ^long [input-idx input-strides reshape-indexes output-strides]
  (let [input-dim-indexes (strides-idx->dim-indexes input-strides input-idx)
        output-dim-indexes (mapv input-dim-indexes reshape-indexes)]
    (strides-dim-indexes->idx output-strides output-dim-indexes)))


(defn- reshape-data
  "Given input with given dims and relative reshape indexes
produce a new array of double values in the order desired"
  ^doubles [data data-dims reshape-indexes]
  (let [^doubles data (ensure-doubles data)
        n-elems (long (reduce * data-dims))
        retval (double-array (alength data))
        input-strides (dims->strides data-dims)
        output-dims (mapv data-dims reshape-indexes)
        output-strides (dims->strides output-dims)]
    (c-for [idx 0 (< idx n-elems) (inc idx)]
           (let [output-idx (input-idx->output-idx idx input-strides reshape-indexes output-strides)]
             (aset retval output-idx (aget data idx))))
    retval))


(defn to-core-matrix
  [data ideal-shape]
  (let [^doubles data (ensure-doubles data)]
    ;;https://github.com/mikera/core.matrix/issues/299

    ;;The simple case of using m/reshape has serious performance issues.
    (case (count ideal-shape)
      1 data
      2 (let [[n-rows n-cols] ideal-shape
              ^"[[D" retval (make-array Double/TYPE n-rows n-cols)]
          (c-for [row 0 (< row n-rows) (inc row)]
                 (dtype/copy! data (* row n-cols) (aget retval row) 0 n-cols))
          retval))))


(defmulti get-weight-shape (fn [desc weight-raw-data] (:type desc)))

(defmethod get-weight-shape :convolutional
  [desc weight-raw-data]
  [(:num-kernels desc)
   (quot (m/ecount weight-raw-data) (:num-kernels desc))])

(defmethod get-weight-shape :linear
  [desc weights-raw-data]
  [(:output-size desc)
   (quot (m/ecount weights-raw-data) (:output-size desc))])

(defn built-desc->keras-dims
  [built-desc]
  (if (= (:type built-desc) :convolutional)))

(defn- load-weights
  [desc-seq weight-file]
  (let [weight-entry (first (filter (fn [node]
                                      (= (hdf5/get-name node)
                                         "model_weights"))
                                    (hdf5/get-children weight-file)))
        _ (when-not weight-entry
            (throw (Exception. "Weight file does not appear to contain model_weights.")))
        node-map (hdf5-child-map weight-entry)]
    (mapv (fn [[desc built-desc]]
            (let [weight-node (get node-map (:id desc))]
              (if (and weight-node (seq (hdf5/get-children weight-node)))
                (let [weight-map (hdf5-child-map weight-node)
                      ;;Is this any more robust than just assuming first child is weights
                      ;;and second child is bias?
                      weight-id (keyword (str (name (:id desc)) "_W"))
                      bias-id (keyword (str (name (:id desc)) "_b"))
                      weight-ds (get weight-map weight-id)
                      bias-ds (get weight-map bias-id)
                      [weight-ds bias-ds] (if (and weight-ds bias-ds)
                                            [weight-ds bias-ds]
                                            (let [children (hdf5/get-children weight-node)]
                                              [(first children) (second children)]))]
                  (when-not (and weight-ds bias-ds)
                    (throw (Exception.
                            (format "Failed to find weights and bias: wanted %s, found %s"
                                    [weight-id bias-id] (keys weight-map)))))
                  (println "loading weights/bias for" (:id desc))
                  (let [weight-clj (hdf5/->clj weight-ds)
                        weight-raw-data (:data weight-clj)
                        weight-shape (get-weight-shape desc weight-raw-data)
                        weight-double-data (if-let [keras-dims (built-desc->keras-dims built-desc)]
                                             ;;Keras dimensions are: 3 height 2 width 1 n-channels 0 n-filters
                                             ;;We want: n-filters n-channels height width
                                             (reshape-data weight-raw-data keras-dims [0 1 3 2])
                                             (ensure-doubles weight-raw-data))]
                    (assoc desc
                           :weights (to-core-matrix weight-double-data weight-shape)
                           :bias (ensure-doubles (:data (hdf5/->clj bias-ds))))))
                desc)))
          desc-seq)))


(defn load-weights-for-description
  [desc-seq weights-fname]
  (resource/with-resource-context
    (load-weights desc-seq (hdf5/open-file weights-fname))))


(defn model->description
  "Given a json model and weight hdf5 file load model into a cortex description layer."
  [model-json-fname weight-hdf5-fname]
  (-> (read-model model-json-fname)
      model->simple-description
      (load-weights-for-description weight-hdf5-fname)))

(defn tokenize-output-name
  [out-name]
  (let [parts
        (string/split out-name #"_")]
    (if (= (count parts) 4)
      {:index (Long/parseLong (parts 1))
       :layer-type (keyword (parts 3))}
      (throw (Exception. (format "fixme: %s" out-name))))))

(defn layer-output->ordered-data
  [layer-outputs]
  (->> (hdf5-child-map layer-outputs)
       (mapv (fn [[name-keywd node]]
               (let [{:keys [index layer-type] } (tokenize-output-name (name name-keywd))
                     clj-node (hdf5/->clj node)
                     node-data (:data clj-node)]
                 [index {:layer-type layer-type
                         :data (to-core-matrix node-data [(m/ecount node-data)])}])))
       (sort-by first)
       (map second)
       (remove #(= (:layer-type %) :Flatten))
       vec))

(def layer-type-map
  (into {}
   (mapv vec (partition 2
                        [:Convolution2D :convolutional
                         :Activation #{:relu :softmax}
                         :MaxPooling2D :max-pooling
                         :Dense :linear
                         :Dropout :dropout]))))


(defn output-types-differ?
  [layer-types]
  (->> layer-types
       (map (fn [[cortex-type keras-type]]
              (let [entry (layer-type-map keras-type)]
                (when-not (and entry
                               (if (set? entry)
                                 (contains? entry cortex-type)
                                 (= entry cortex-type)))
                  [cortex-type keras-type]))))
       (remove nil?)
       seq))


(defn load-combined-hdf5-file
  [fname]
  (resource/with-resource-context
    (let [model-file (hdf5/open-file fname)
          file-child-map (hdf5-child-map model-file)
          printer (fn [item]
                    (clojure.pprint/pprint item)
                    item)
          src-desc (-> (:model_config file-child-map)
                       hdf5/->clj
                       :data
                       first
                       (json/parse-string keyword)
                       model->simple-description)

          weight-desc (load-weights (mapv vector
                                          src-desc
                                          (desc/build-full-network-description src-desc))
                                    model-file)
          input-desc (first weight-desc)
          input-shape (if (:output-width input-desc)
                        [(:output-channels input-desc)
                         (* (:output-height input-desc)
                            (:output-width input-desc))]
                        [(:output-size input-desc)])
          file-data (if-let [input-data (get file-child-map :test_image)]
                      (do
                        (println "Using file input data")
                        (:data (hdf5/->clj input-data)))
                      (double-array (vec (repeat (reduce * input-shape) 1.0))))
          input (to-core-matrix file-data input-shape)
          layer-outputs (layer-output->ordered-data (:layer_outputs file-child-map))
          type-map (vec (map vector
                             (map :type (drop 1 weight-desc))
                             (map :layer-type layer-outputs)))]
      (when-let [verify-seq (seq (desc/build-and-verify-trained-network weight-desc))]
        (throw (Exception. (format "Built items failed verification:\n %s" (vec verify-seq)))))
      (when-not (= (count layer-outputs)
                   (- (count weight-desc) 1))
        (throw (Exception. (format "Layer output count mismatch: %s"
                                   type-map))))
      (when (output-types-differ? type-map)
        (throw (Exception. (format "Layer output type mismatch %s %s"
                                   type-map
                                   (vec (output-types-differ? type-map))))))
      {:model weight-desc
       :input input
       :layer-outputs (mapv :data layer-outputs)})))
