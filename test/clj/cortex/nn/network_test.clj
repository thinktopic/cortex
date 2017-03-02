(ns cortex.nn.network-test
  (:require
    [clojure.test :refer :all]
    [clojure.core.matrix :as m]
    [cortex.graph :as graph]
    [cortex.dataset :as ds]
    [cortex.nn.layers :as layers]
    [cortex.nn.execute :as execute]
    [cortex.nn.network :as network]))


(deftest specify-weights-bias
  (let [weight-data [[1 2][3 4]]
        bias-data [0 10]
        built-network (network/build-network [(layers/input 2)
                                              (layers/linear
                                               2
                                               :weights weight-data
                                               :bias bias-data)])]
    (is (= (vec (m/eseq weight-data))
           (vec (m/eseq (get-in built-network [:layer-graph :buffers
                                               (get-in built-network
                                                       [:layer-graph :id->node-map
                                                        :linear-1 :weights :buffer-id])
                                               :buffer])))))
    (is (= (vec (m/eseq bias-data))
           (vec (m/eseq (get-in built-network [:layer-graph :buffers
                                               (get-in built-network
                                                       [:layer-graph :id->node-map
                                                        :linear-1 :bias :buffer-id])
                                               :buffer])))))))


(deftest generate-weights-bias
  (let [bias-data [0 0]
        built-network (network/build-network [(layers/input 2)
                                              (layers/linear
                                               2)
                                              (layers/relu)])]
    (is (not (nil? (m/eseq (get-in built-network
                                   [:layer-graph :buffers
                                    (get-in built-network
                                            [:layer-graph :id->node-map
                                             :linear-1 :weights :buffer-id])
                                    :buffer])))))
    (is (m/equals (vec (m/eseq bias-data))
                  (vec (m/eseq (get-in built-network
                                       [:layer-graph :buffers
                                        (get-in built-network
                                                [:layer-graph :id->node-map
                                                 :linear-1 :bias :buffer-id])
                                        :buffer])))))))



(deftest prelu-initialization
  (let [built-network (network/build-network [(layers/input 25 25 10)
                                              (layers/prelu)])]
    (is (= (vec (repeat 10 0.25))
           (vec (m/eseq (get-in built-network [:layer-graph :buffers
                                               (get-in built-network
                                                       [:layer-graph :id->node-map
                                                        :prelu-1 :neg-scale :buffer-id])
                                               :buffer])))))))


(deftest build-concatenate
  (let [network (network/build-network [(layers/input 25 25 10 :id :right)
                                        (layers/input 500 1 1 :parents [] :id :left)
                                        (layers/concatenate :parents [:left :right] :id :concat)
                                        (layers/linear 10)])
        graph (network/network->graph network)
        concat-node (graph/get-node graph :concat)]
    (is (= (+ (* 25 25 10) 500)
           (graph/node->output-size concat-node)))
    (is (= (set [(assoc (first (graph/node->output-dimensions (graph/get-node graph :right)))
                        :id :right)
                 (assoc (first (graph/node->output-dimensions (graph/get-node graph :left)))
                        :id :left)])
           (set (graph/node->input-dimensions concat-node))))))


(defn test-run
  []
  (let [dataset (ds/create-in-memory-dataset
                  {:data {:data CORN-DATA
                          :shape 2}
                   :yield {:data CORN-LABELS
                            :shape 1}}
                  (ds/create-index-sets (count CORN-DATA)
                                        :training-split 1.0
                                        :randomize? false))
        size-map (ds/dataset->stream->size-map dataset)]
    (execute/run [(layers/input 2 1 1 :id :data)
                  (layers/linear 1 :id :yield)]
                 dataset
                 :batch-size 1)))
