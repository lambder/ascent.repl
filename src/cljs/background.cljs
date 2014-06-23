(ns background
	(:require
		[cljs.core.async :as async]
  	[clojure.walk :as walk]
		[khroma.log :as log]
		[khroma.runtime :as runtime]

		[goog.string :as gstring]
		[clojure.string :as s])
	(:require-macros
		[cljs.core.async.macros :refer [go go-loop]]))

(def debug
	(atom true))

(def tab-infos
	(atom {}))

(def connections
	(atom {}))

(defn set-port! [destination tab-id port]
	(let [path [(str destination) (str tab-id)]]
  	(swap! connections
    	#(assoc-in % path port))))

(defn get-port [destination tab-id]
 	(get-in @connections [(str destination) (str tab-id)]))

(defn remove-port! [destination tab-id]
  (swap! connections
    #(update-in % [(str destination)] dissoc (str tab-id))))
  
(defn set-local-port! [destination handler]
	(set-port! "background" destination handler))

(defn get-local-port [destination]
	(get-port "background" destination))

(defn set-tab-info! [tab-id info]
  (swap! tab-infos assoc (str tab-id) info))

(defn get-tab-info [tab-id]
  (@tab-infos (str tab-id)))

(defn remove-tab-info! [tab-id]
  (swap! tab-infos dissoc (str tab-id)))

(defn connect [port source tabId]
	(set-port! source tabId 
    (fn [message] 
      (log/debug "sending message to %s:%s" source tabId message)
      (async/put! port message)))
 
 	(go-loop []
  	(if-let [message (<! port)]
     	(let [message (walk/keywordize-keys message) {:keys [type destination]} message background? (= destination "background")]
        (log/debug "received message: " message " background: " background?)
    		(if-let [port-fn (if background?
	                     	   (get-local-port type)
	                       	 (get-port destination tabId))]
          (do       
	        	(when (and @debug (not= type "log"))
	          	(log/debug "message %s:%s -> %s:%s" source tabId destination (if-not background? tabId "*")) message)
	        
		      	(port-fn
		        	(assoc message :source source :sourceTabId tabId)))
          
          (log/debug "message %s:%s -> /dev/null" source tabId))
      	(recur))
      
     	(remove-port! source tabId))))


; move to khroma
(defn tab-updated-events []
  (let [ch (async/chan)]
    (.addListener js/chrome.tabs.onUpdated
      (fn [id info tab]
        (async/put! ch (walk/keywordize-keys (js->clj {:tabId id :changeInfo info :tab tab}))))) ch))


(defn init []
	(log/debug "Just for boot checking...")

 	(let [ch (runtime/connections)]
  	(go-loop []
    	(when-let [connection (<! ch)]
       	(let [connection-name (runtime/port-name connection) parts (s/split connection-name ":")]
					(when (= 2 (count parts))
       			(let [destination (first parts) tabId (second parts)]
							(when @debug
	        			(log/debug (str "incoming connection from " connection-name)))
	      
							(connect connection destination tabId)
	      
							(when-let [tab-info (get-tab-info tabId)]
								(async/put! connection {:type "tab-info" :info tab-info})))))

        (recur))))

	(set-local-port! "log"
		(fn [message]
			(if @debug (log/debug "*** " (:text message)))))

	(set-local-port! "inject-agent"
		(fn [{:keys [tabId]} message]
			(if @debug 
     		(log/debug "agent injection requested for: " tabId))
   
			(.executeScript js/chrome.tabs tabId (clj->js {:file "js/injected.js"})
				(fn [result]
					(when @debug 
       			(log/debug "connecting to tab:%s" tabId))
     
     			(let [port (runtime/channel-from-port 
                     	  (.connect js/chrome.tabs tabId  (clj->js {:name (str "background:" tabId)})))]
          
          	(connect port "tab" tabId))))))

	(set-local-port! "tab-info"
		(fn [{:keys [agentInfo source sourceTabId] :as message}]
			(when @debug 
     		(log/debug "background page received tab-info: " message))
   
			(if (= "tab" source)
				(if-let [tab-info (get-tab-info sourceTabId)]
					(if-let [port-fn (get-port "repl" sourceTabId)]
						(let [out {:type "tab-info" :info (assoc tab-info :agentInfo agentInfo)}]
   						(when @debug
								(log/debug "sending tab-info to repl:" sourceTabId out))
							(port-fn out))
						(log/warn "cannot find port repl:%s" sourceTabId))
					(log/warn "no tab-info found for tab-info message: " message)))))

 
	(let [ch (tab-updated-events)]
		(go-loop []
	  	(when-let [{:keys [tabId changeInfo tab]} (<! ch)]
				(when (= "complete" (:status changeInfo))
					(let [tab-info (get-tab-info tabId)]
	  				(log/info "tab load complete" tabId)
						(set-tab-info! tabId {:agentInfo nil :url (:url tab)})

						(when-let  [port-fn (get-port "repl" tabId)]
							(when @debug
	      				(log/debug  "to repl:%s" tabId tab-info))
	    
							(port-fn {:type "tab-info" :info tab-info}))))
	      
	    	(recur))))
 
	(.addListener js/chrome.tabs.onRemoved
		(fn [tab-id remove-info]
			(remove-tab-info! tab-id)))

	(.addListener js/chrome.tabs.onReplaced
		(fn [added-tab-id removed-tab-id]
			(remove-tab-info! added-tab-id))))







