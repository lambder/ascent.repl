 (ns khroma.devtools
 	(:require [cljs.core.async :as async]
 	          [khroma.util :as kutil]
 	          [khroma.log :as log])

 	(:require-macros 
 		[cljs.core.async.macros :refer [go alt!]]))

(def available?
	(not (nil? js/chrome.devtools)))

(defn eval-exception? [r]
	(and r (map? r) (r "isException")))

(defn eval-error? [r]
	(and r (map? r) (r "isError")))

(defn eval-failed? [r]
	(or (eval-error? r) (eval-exception? r)))

(defn eval-message [r]
	(cond 
		(eval-error? r)
			(r "code")

		(eval-exception? r)
			(r "value")))

(defn inspected-eval! [statement & {:keys [ignore-exception?] :or {:ignore-exception? false}}]
	(log/debug "inspected-eval!: " statement)

	(let [channel (async/chan 1)]
		(.eval js/chrome.devtools.inspectedWindow statement  
			(fn [result exception]
				(go
					(log/debug result " / " exception)

					(let [result (js->clj 
									(if ignore-exception?
										result
										(or result exception)))]

						(>! channel 
							(kutil/escape-nil result)))

					(async/close! channel)))) 
		channel))




