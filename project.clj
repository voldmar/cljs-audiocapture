(defproject cljs-audiocapture "0.1.3-SNAPSHOT"
  :description "ClojureScript core.async interface to capture audio"
  :url "https://github.com/voldmar/cljs-audiocapture"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :min-lein-version "2.0.0"
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [org.clojure/clojurescript "0.0-2371"]
                 [org.clojure/core.async "0.1.346.0-17112a-alpha"]]

  :plugins [[lein-cljsbuild "1.0.4-SNAPSHOT"]]
  :source-paths ["src"]
  :cljsbuild {
    :builds [{:id "dev"
              :source-paths ["src"]
              :compiler {:output-to "cljs_audiocapture.js"
                         :output-dir "out"
                         :optimizations :none
                         :preamble ["swfobject.js"]
                         :source-map true}}
             {:id "prod"
              :source-paths ["src"]
              :libs ["microphone.js"]
              :compiler {:output-to "cljs_audiocapture.min.js"
                         :optimizations :advanced
                         :preamble ["swfobject.js"] 
                         :externs  ["externs/w3c_audio.js"
                                    "externs/swfobject.js" ]}}]})
