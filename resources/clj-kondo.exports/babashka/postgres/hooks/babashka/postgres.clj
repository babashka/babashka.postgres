(ns hooks.babashka.postgres
  (:require [clj-kondo.hooks-api :as api]))

(defn with-conn
  "(with-conn [sym conninfo opts?] body) lints as (let [sym (do conninfo opts)] body)."
  [{:keys [node]}]
  (let [[binding-vec & body] (rest (:children node))
        [sym conninfo opts] (:children binding-vec)]
    (when-not (and sym conninfo (<= (count (:children binding-vec)) 3))
      (api/reg-finding! (assoc (meta binding-vec)
                               :message "with-conn takes [sym conninfo opts?]"
                               :type :syntax)))
    {:node (api/list-node
            (list* (api/token-node 'let)
                   (api/vector-node
                    [sym (api/list-node (cond-> [(api/token-node 'do) conninfo]
                                          opts (conj opts)))])
                   body))}))
