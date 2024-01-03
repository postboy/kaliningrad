(ns kaliningrad.core
  (:use [kaliningrad.term :only [get-screen]])
  (:require [lanterna.screen :as s]
            [lanterna.constants :as c]))


; Constants -------------------------------------------------------------------
(def rows 15)
(def cols 40)

(def welcome-message
  ["Welcome to Zen."
   ""
   "In this game, you tend to a"
   "small zen garden."
   ""
   "There is no winning, losing,"
   "or saving."
   ""
   "Press any key to begin."])

(def help-message
  [" -- COMMANDS ------- "
   " hjkl - move         "
   " r    - rake         "
   " q    - quit         "
   " ?    - help         "
   "                     "
   " -- press any key -- "])


(def dir-keys {\h :left
               \j :down
               \k :up
               \l :right})
(def rake-keys {\1 "~"
                \2 "="
                \3 "≈"})


(def item-color {:rock :white
                 :shrub :green
                 :sand :yellow})

(def solid? #{:rock :shrub})

; World/screen state ----------------------------------------------------------
(def world (ref {}))
(def player-x (ref 0))
(def player-y (ref 0))
(def canvas-rows (ref rows))
(def canvas-cols (ref cols))

; Data structures -------------------------------------------------------------
(defrecord Slot [kind ch])

(defn make-sand
  ([] (make-sand (rand-nth ["~" "=" "≈"])))
  ([ch] (new Slot :sand ch)))

(defn make-footprint []
  (make-sand (rand-nth [":" ";"])))

(defn make-rock []
  (new Slot :rock "*"))

(defn make-shrub []
  (new Slot :shrub "&"))


; Utility functions -----------------------------------------------------------
(defn draw [screen x y s & {:keys [fg bg] :or {fg :default bg :default}}]
  (.putString screen x y s (c/colors fg) (c/colors bg) #{}))

(defn draw-message
  "Draw a message at the bottom of the screen.

  Moves the cursor past the end of the message.  Refreshes the screen."
  [screen msg]
  (draw screen 0 rows msg)
  (s/move-cursor screen (inc (count msg)) rows)
  (s/redraw screen))

(defn draw-lines
  "Draw a sequence of lines down the left side of the screen."
  [screen lines]
  (loop [i 0
         [l & ls] lines]
    (when l
      (draw screen 0 i l)
      (recur (inc i) ls)))
  (s/redraw screen))

(defn get-choice
  "Get an input from the user.

  Pressing escape will return nil.  Otherwise if the user presses one of the
  keys in the choice map, return its value.  Otherwise loop, forcing the user
  to either give a valid input or escape."
  [screen choices]
  (let [k (s/get-key-blocking screen)]
    (cond
      (= k :esc) nil
      (contains? choices k) (choices k)
      :else (recur screen choices))))

(defn prompt
  "Prompt a user for some input."
  [screen msg choices]
  (draw-message screen msg)
  (get-choice screen choices))

(defn calc-coords
  "Calculate the new coordinates after moving dir from [x y].

  Does not do any bounds checking, so (calc-coords 0 0 :left) will
  return [-1 0] and let you deal with it."
  [x y dir]
  (case dir
    :left  [(dec x) y]
    :right [(inc x) y]
    :up    [x (dec y)]
    :down  [x (inc y)]))


; Rendering -------------------------------------------------------------------
(defn render
  "Draw the world and the player on the screen."
  [screen]
  (dosync
    (doseq [y (range @canvas-rows)
            x (range @canvas-cols)]
      (draw screen x y " "))
    (doseq [y (range rows)
            x (range cols)
            :let [{:keys [ch kind]} (@world [x y])]]
      (draw screen x y ch :fg (item-color kind)))
    (draw screen @player-x @player-y "@")
    (draw screen 0 rows (apply str (repeat cols \space)))
    (s/move-cursor screen @player-x @player-y))
  (s/redraw screen))


; Input/command handling ------------------------------------------------------
(defn parse-input
  "Get a key from the user and return what command they want (if any).

  The returned value is a vector of [command-type data], where data is any
  extra metadata that might be needed (like the direction for a :move command)."
  [screen]
  (let [k (s/get-key-blocking screen)]
    (case k
      \q [:quit nil]
      \? [:help nil]
      \h [:move :left]
      \j [:move :down]
      \k [:move :up]
      \l [:move :right]
      \r [:rake]
      [nil nil])))


(defmulti handle-command
  (fn [command screen data] command))


(defmethod handle-command nil [_ screen _]
  nil)

(defmethod handle-command :help [_ screen _]
  "Draw a help message on the screen and wait for the user to press a key."
  (draw-lines screen help-message)
  (s/get-key-blocking screen))

(defmethod handle-command :move [_ _ dir]
  "Move the player in the given direction.

  Does bounds checking and ensures the player doesn't walk through solid
  objects, so a player might not actually end up moving."
  (dosync
    (let [[x y] (calc-coords @player-x @player-y dir)
          x (max 0 x)
          x (min x (dec cols))
          y (max 0 y)
          y (min y (dec rows))]
      (when-not (solid? (:kind (@world [x y])))
        (ref-set player-x x)
        (ref-set player-y y)
        (alter world assoc [x y] (make-footprint))))))


(defn rake [dir style]
  (dosync
    (let [coords (calc-coords @player-x @player-y dir)
          target (@world coords)]
      (when (and target
                 (= :sand (:kind target)))
        (alter world assoc coords (make-sand style))))))

(defmethod handle-command :rake [_ screen _]
  (when-let [dir (prompt screen "Which direction [hjkl]?" dir-keys)]
    (when-let [style (prompt screen "Which style [1~ 2= 3≈]?" rake-keys)]
      (rake dir style))))


; World generation ------------------------------------------------------------
(defn rand-placement [item]
  (into {} (for [_ (range (+ 5 (rand-int 5)))]
             [[(rand-int cols) (rand-int rows)]
              item])))


(defn sand []
  (into {} (for [x (range cols)
                 y (range rows)]
             [[x y] (make-sand)])))

(defn rocks []
  (rand-placement (make-rock)))

(defn shrubs []
  (rand-placement (make-shrub)))


(defn generate-world []
  (let [new-world (-> (merge (sand) (rocks) (shrubs))
                    (assoc [0 0] (make-footprint)))]
    (dosync (ref-set world new-world))))


; Main ------------------------------------------------------------------------
(defn intro [screen]
  (draw-lines screen welcome-message)
  (s/get-key-blocking screen))

(defn game-loop [screen]
  (render screen)
  (let [[command data] (parse-input screen)]
    (if (= command :quit)
      (s/stop screen)
      (do
        (handle-command command screen data)
        (recur screen)))))

(defn handle-resize [rows cols]
  (dosync
    (ref-set canvas-rows rows)
    (ref-set canvas-cols cols)))

(defn go []
  (let [screen (get-screen cols (inc rows) handle-resize)]
    (generate-world)
    (intro screen)
    (game-loop screen)))


(defn -main [& args]
  (go))
