(ns tests.web3-tests
  (:require-macros [cljs.test :refer [deftest testing is async]]
                   [cljs.core.async.macros :refer [go]])
  (:require [cljs.test :as t]
            [cljs-web3.macros]
            [tests.macros :refer [slurpit]]
            [cljs-web3.core :as web3-core]
            [cljs-web3.eth :as web3-eth]
            [cljs-web3.helpers :as web3-helpers]
            [cljs.nodejs :as nodejs]
            [clojure.string :as string]
            [cljs.core.async :refer [<!] :as async]
            [tests.smart-contracts-test :refer [smart-contracts]]
            [district.shared.async-helpers :as async-helpers]
            [web3.impl.web3js :as web3js]))

(async-helpers/extend-promises-as-channels!)

(def abi (aget (js/JSON.parse (slurpit "./resources/public/contracts/build/MyContract.json")) "abi"))

(deftest test-web3 []
  (let [inst (web3js/new)
        web3 {:instance inst
              :provider (web3-core/websocket-provider inst "ws://127.0.0.1:8545")}]
    (async done
           (go
             (let [connected? (<! (web3-eth/is-listening? web3))
                   accounts (<! (web3-eth/accounts web3))
                   block-number (<! (web3-eth/get-block-number web3))
                   block (js->clj (<! (web3-eth/get-block web3 block-number false)) :keywordize-keys true)
                   address (-> smart-contracts :my-contract :address)
                   my-contract (web3-eth/contract-at web3 abi address)
                   event-interface (web3-helpers/event-interface my-contract :SetCounterEvent)
                   event-emitter (web3-eth/subscribe-events web3
                                                            my-contract
                                                            :SetCounterEvent
                                                            {:from-block block-number}
                                                            (fn [_ event]
                                                              (let [evt-block-number (aget event "blockNumber")
                                                                    return-values (aget event "returnValues")
                                                                    evt-values (web3-helpers/return-values->clj return-values event-interface)]

                                                                (prn "@@@" (:new-value evt-values))

                                                                (is (= "3" (:new-value evt-values)))
                                                                (is (= (inc block-number) evt-block-number)))))
                   tx (<! (web3-eth/contract-send web3
                                                  my-contract
                                                  :set-counter
                                                  [3]
                                                  {:from (first accounts)
                                                   :gas 4000000}))
                   past-events (<! (web3-eth/get-past-events web3
                                                             my-contract
                                                             :SetCounterEvent
                                                             {:from-block 0
                                                              :to-block (+ 20 block-number)}))]

               (is (= address (string/lower-case (aget my-contract "_address"))))

               (is connected?)
               (is (= 10 (count accounts)))
               (is (int? block-number))
               (is (map? block))

               (prn past-events  )

               ;; (is (= "3" (:new-value (web3-helpers/return-values->clj (aget past-events "0" "returnValues") event-interface))))


               (web3-eth/unsubscribe web3 event-emitter)
               (web3-core/disconnect web3)
               (done))))))
