(ns cljs-audiocapture.core
  (:require
    [cljs.core.async.impl.protocols :as p]
    [cljs.core.async :as async])
  (:require-macros
    [cljs.core.async.macros :refer [go go-loop]]))

(def ^:dynamic *AUDIO_FORMAT* "audio/x-pcm;bit=16;rate=44100")
(def ^:dynamic *FRAME_SIZE* 4096) ; samples in frame

(def SAMPLE_RATE 44100) ; There are no plans to change it,
                        ; so it is no ^:dynamic

(defn- bidirectional
  "Constructs bidirectional channel"
  [read-ch write-ch & [{:keys [on-close]}]]
  (reify
    p/ReadPort
    (take! [_ handler]
      (p/take! read-ch handler))

    p/WritePort
    (put! [_ msg handler]
      (p/put! write-ch msg handler))

    p/Channel
    (close! [_]
      (p/close! read-ch)
      (p/close! write-ch)
      (when on-close
        (on-close)))))

(defn pcm-frames->wav
  "Encode seq of chunks to (downloadable) .wav BLOB"
  [chunks]
  (let [pcm-size (transduce (map #(.-byteLength %)) + chunks)
        pcm-data (js/ArrayBuffer. (+ 44 pcm-size))
        view (js/DataView. pcm-data)
        int-view (js/Int16Array. pcm-data)]

    ; Do RIFF WAV magic
    (.setUint32 view 0 0x52494646) ; "RIFF"
    (.setUint32 view 4 (+ 32 pcm-size))
    (.setUint32 view 8 0x57415645)
    (.setUint32 view 12 0x666d7420)
    (.setUint32 view 16 16 true);
    (.setUint16 view 20 1 true);
    (.setUint16 view 22 1 true) ; Channels
    (.setUint32 view 24 SAMPLE_RATE, true);
    (.setUint32 view 28 (* 2 SAMPLE_RATE), true)
    (.setUint16 view 32 2 true)
    (.setUint16 view 34 16 true)
    (.setUint32 view 36 0x64617461)
    (.setUint32 view 40 pcm-size true)

    ; Write chunks of PCM data
    (loop [offset 22 ; NB: we use int16 view so init offset is 22 (two-bytes, not 44 (bytes)
           chunks chunks]
      (when-first [chunk chunks]
        (.set int-view chunk offset)
        (recur (+ offset (alength chunk))
               (rest chunks))))
    (js/Blob. (js/Array. pcm-data) #js {:type "audio/x-wav"})))

(defn- convert
  "Converts frame from AudioContext to PCM"
  [frame]
  (let [pcm-data (js/Int16Array. (alength frame))]
    (doseq [i (range (alength frame))]
      ; Clip and convert to int_16
      (aset pcm-data i (-> (aget frame i)
                           (max -1)
                           (min 1)
                           (* 0x7FFF))))
    pcm-data))

(defn- capture-audio-flash
  [& [buffer swf-uri]]
  (let [wait-chan (async/chan)
        commands-chan (async/chan)
        frames-chan (async/chan buffer)
        audio-chan (bidirectional frames-chan
                                  commands-chan)
        samples-handler-fn-name (gensym "cljs_audiocapture_ok__")
        fail-fn-name (gensym "cljs_audiocapture_fail__")

        push (fn [data]
               (async/put! frames-chan (convert data)))
        drop (fn [data])

        connect-input (fn [obj]
                        (async/put! wait-chan {:audio-chan audio-chan :error nil})
                        (async/close! wait-chan)
                        (go-loop []
                                 (let [command (async/<! commands-chan)]
                                   (condp = command
                                     :start (aset js/window samples-handler-fn-name push)
                                     :pause (aset js/window samples-handler-fn-name drop)))
                                 (recur)))]
    (aset js/window
          fail-fn-name
          (fn [error]
            (async/close! audio-chan)
            (async/put! wait-chan {:audio-chan nil :error error})
            (async/close! wait-chan)))

    (let [flashvars #js {:samples samples-handler-fn-name
                         :fail fail-fn-name}
          params #js {}
          attributes #js {}
          callback (fn [event]
                     (if-let [obj (.-ref event)]
                       (connect-input obj)
                       ((aget js/window fail-fn-name) "Flash embed failed")))]
      (.embedSWF js/swfobject
                 swf-uri
                 "audiocapture-flash-fallback"
                 "215" ; Minimum allowed width
                 "138" ; Minimum allowed hight
                 "10.1.0"
                 false
                 flashvars
                 params
                 attributes
                 callback))

    wait-chan))

(defn- capture-audio-native
  [& [buffer]]
  (let [wait-chan (async/chan)
        commands-chan (async/chan)
        frames-chan (async/chan buffer)

        ; We should save pointer to processor and input objects
        ; to avoid garbage collection
        processor-avoid-gc (gensym "audio_context__")
        input-avoid-gc (gensym "audio_input__")

        audio-chan (bidirectional frames-chan
                                  commands-chan
                                  {:on-close (fn []
                                               (js-delete js/window input-avoid-gc)
                                               (js-delete js/window processor-avoid-gc))})
        context (cond
                  (exists? js/AudioContext) (js/AudioContext.)
                  (exists? js/webkitAudioContext) (js/webkitAudioContext.))
        processor (.createScriptProcessor context *FRAME_SIZE* 1 1)
        muter (.createGain context)]

    ; Dark magic of Web Audio API
    ; We have to connect pseudo output to processor or...
    ; ...or it won’t work. Modern browsers.
    (aset js/window processor-avoid-gc processor)
    (.connect processor muter)
    (aset muter "gain" 0)
    (.connect muter (aget context "destination"))

    (defn- connect-input [stream]
      (let [input (.createMediaStreamSource context stream)
            data (fn [e] (.. e -inputBuffer (getChannelData 0)))]
        (aset js/window input-avoid-gc input)
        (.connect input processor)
        (async/put! wait-chan {:audio-chan audio-chan :error nil})
        (async/close! wait-chan)
        (go-loop []
          (let [command (async/<! commands-chan)]
            (condp = command
              :start  (aset processor
                            "onaudioprocess"
                            (fn [e]
                              (async/put! frames-chan
                                          (convert (data e)))))
              :pause (do
                      (js-delete js/window input-avoid-gc)
                      (aset processor "onaudioprocess" nil))))
          (recur))))

    (defn- fail [error]
      (async/close! audio-chan)
      (async/put! wait-chan {:audio-chan nil :error error})
      (async/close! wait-chan))

    ; Make deal with JS async
    (cond
      (exists? js/navigator.getUserMedia)
        (js/navigator.getUserMedia #js {:audio true} connect-input fail)
      (exists? js/navigator.webkitGetUserMedia)
        (js/navigator.webkitGetUserMedia #js {:audio true} connect-input fail)
      (exists? js/navigator.mozGetUserMedia)
        (js/navigator.mozGetUserMedia #js {:audio true} connect-input fail))
    wait-chan))

(defn capture-audio
  "Create bidirectional channel

  Put :start to start or resume audio capturing
  Put :pause to pause capturing

  You can use map with keys
    :buffer - core.async buffer to use
    :swf-uri - URI to Flash fallback"
  ([]
   (capture-audio {}))
  ([{:keys [buffer swf-uri]
     :or {:buffer nil :swf-uri "audiocapture.swf"}}]
   (cond
     (exists? js/navigator.getUserMedia) (capture-audio-native buffer)
     (exists? js/navigator.webkitGetUserMedia) (capture-audio-native buffer)
     (exists? js/navigator.mozGetUserMedia) (capture-audio-native buffer)
     :else (capture-audio-flash buffer swf-uri))))

