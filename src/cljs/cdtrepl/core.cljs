(ns cdtrepl.core
  (:require 
      [om.core :as om :include-macros true]
      [cdtrepl.ui :as ui]
      [cdtrepl.eval :as eval]
      [cdtrepl.background :as background]
      [cdtrepl.comp :as comp]
      [cdtrepl.settings :as settings]
      [cdtrepl.util :as util]
      [khroma.devtools :as devtools]
      [khroma.extension :as extension]
      [khroma.runtime :as runtime]
      [cljs.core.async :as async] 
      [khroma.tabs :as tabs]
      [khroma.log :as log])
 
  (:require-macros 
    [cljs.core.async.macros :refer [go alt! go-loop]]))

(defn ns-handler [in {:keys [tab-info]}]
  (async/map< 
    (fn [{:keys [response-ns] :as messge}]  
      (async/put! tab-info {:ns response-ns}) messge) in))

(defn setup-routing [{:keys [execute result] :as channels}]
  (-> execute
      (comp/compiler)
      (comp/divert-errors result)
      (ns-handler channels)
      (eval/evaluator)  
      (async/pipe result)))

(defn make-tab-info-listener [{:keys [tab-info]}]
  (background/handler "tab-info"                      
    (fn [{:keys [info] :as messge}]
      (log/debug "got tab-info: " messge)  
        
      (let [{:keys [url agentInfo]} info]
        (if agentInfo
          (if-not (:is_cljs agentInfo)
            (background/inject-cljs))
          
          (if (settings/auto-inject? url)
            (background/inject-agent @devtools/tab-id)))
          
        (async/put! tab-info {:url url :agent-info agentInfo})))))

(defn consume-inject-requests [{:keys [inject-agent]}]
  (util/consume inject-agent 
    (fn [{:keys [save-auto url]}] 
      (background/inject-agent @devtools/tab-id)
    
      (when save-auto
        (settings/add-auto-inject! url)))))

(defn ^:export run []
  (when (and devtools/available? runtime/available?)
    (background/connect-and-listen @devtools/tab-id))

  (background/log "starting REPL ui")    
    (let [channels (ui/run-ui (.-body js/document))]
      (setup-routing channels)
      (make-tab-info-listener channels)
      (consume-inject-requests channels)))
