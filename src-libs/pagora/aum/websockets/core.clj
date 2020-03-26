(ns pagora.aum.websockets.core
  (:require
   [pagora.aum.security :as security]
   [pagora.aum.util :as au]
   [integrant.core :as ig]
   [taoensso.sente :as sente]
   [taoensso.sente.server-adapters.http-kit :refer [get-sch-adapter]]
   [taoensso.timbre :as timbre]
   [websockets.dispatcher :as dispatcher]
   ))

(defn get-user-id
  "Returns sente user id (eg uid-1), deduced from remember token in cookie that
  came with the request for the connection."
  [env req]
  ;; (timbre/info "Getting user id for sente!!!!!!!")
  (let [user-id (:id (security/get-user-by-remember-token env req [:id]))]
    (au/user-id->uid user-id)))

(defn init [config parser-env]
  (let [user-id-fn (fn [req]
                     (get-user-id parser-env req))
        chsk-server (sente/make-channel-socket! (get-sch-adapter)
                                                {:packer :edn
                                                 :user-id-fn user-id-fn})
        {:keys [ch-recv send-fn connected-uids
                ajax-post-fn ajax-get-or-ws-handshake-fn]} chsk-server]
    {:ring-ajax-post ajax-post-fn
     :ring-ajax-get-or-ws-handshake ajax-get-or-ws-handshake-fn
     :chsk-recv  ch-recv                 ; ChannelSocket's receive channel
     :chsk-send! send-fn                 ; ChannelSocket's send API fn
     :connected-uids connected-uids} ; Watchable, read-only atom
    ))

(defn add-connected-uids-watch [{:keys [connected-uids]}]
  (add-watch connected-uids :logger
             (fn [_ _ old new]
               (when (not= old new)
                 (timbre/infof "Connected uids change: %s" new)))))

(defn start-websocket! [{:keys [sente-route watch-connected-uids?]
                         :or {sente-route "/chsk"
                              watch-connected-uids? true}
                         :as config} parser parser-env]
  (let [{:keys [chsk-recv
                ring-ajax-get-or-ws-handshake
                ring-ajax-post] :as websocket} (init config parser-env)
        _ (when watch-connected-uids?
            (add-connected-uids-watch websocket))
        event-msg-handler (fn [ev-msg]
                            (dispatcher/ev-msg-handler ev-msg {:config config
                                                               :websocket websocket
                                                               :parser parser
                                                               :parser-env parser-env}))
        stop-fn (sente/start-chsk-router! chsk-recv event-msg-handler
                                                  {:simple-auto-threading? true})]
    (assoc websocket
           :sente-route {sente-route {:get { "" ring-ajax-get-or-ws-handshake }
                                      :post { ""  ring-ajax-post }}}
           :stop-fn stop-fn)))

(defmethod ig/init-key ::websocket-listener [k {:keys [config parser parser-env]}]
  (when (:integrant-log config) (timbre/info :#g "[INTEGRANT] creating" (name k)))
  (let [{:keys [stop-fn] :as websocket} (start-websocket! config parser parser-env)]
    {:stop-fn stop-fn
     :websocket websocket
     :config config}))

(defmethod ig/halt-key! ::websocket-listener [k {:keys [stop-fn config]
                                                 {:keys [connected-uids]} :websocket}]
  (when (:integrant-log config) (timbre/info :#g "[INTEGRANT] halting" (name k)))
  (remove-watch connected-uids :logger)
  (stop-fn))

;; ;; debug fn
;; (defn sente? []
;;   (sente/chsk-send! :sente/nil-uid [:some/request-id {:name "michiel"}])
;;   )

;; (get-user-id {:cookies {"remember_token" {:value "f830c933-9ee9-4e40-8796-59428f448e32"}} })
