(ns editor.curve-view
  (:require [clojure.set :as set]
            [dynamo.graph :as g]
            [editor.background :as background]
            [editor.camera :as c]
            [editor.scene-selection :as selection]
            [editor.colors :as colors]
            [editor.core :as core]
            [editor.geom :as geom]
            [editor.gl :as gl]
            [editor.gl.shader :as shader]
            [editor.gl.vertex :as vtx]
            [editor.grid :as grid]
            [editor.input :as i]
            [editor.math :as math]
            [editor.defold-project :as project]
            [util.profiler :as profiler]
            [editor.scene-cache :as scene-cache]
            [editor.scene-tools :as scene-tools]
            [editor.types :as types]
            [editor.ui :as ui]
            [editor.handler :as handler]
            [editor.workspace :as workspace]
            [editor.gl.pass :as pass]
            [editor.ui :as ui]
            [editor.scene :as scene]
            [editor.properties :as properties]
            [editor.camera :as camera]
            [util.id-vec :as iv]
            [service.log :as log])
  (:import [com.defold.editor Start UIUtil]
           [com.jogamp.opengl.util GLPixelStorageModes]
           [com.jogamp.opengl.util.awt TextRenderer]
           [editor.types Camera AABB Region Rect]
           [java.awt Font]
           [java.awt.image BufferedImage DataBufferByte DataBufferInt]
           [javafx.animation AnimationTimer]
           [javafx.application Platform]
           [javafx.beans.value ChangeListener]
           [javafx.collections FXCollections ObservableList]
           [javafx.embed.swing SwingFXUtils]
           [javafx.event ActionEvent EventHandler]
           [javafx.geometry BoundingBox Pos VPos HPos]
           [javafx.scene Scene Group Node Parent]
           [javafx.scene.control Tab Button]
           [javafx.scene.image Image ImageView WritableImage PixelWriter]
           [javafx.scene.input MouseEvent]
           [javafx.scene.layout AnchorPane Pane StackPane]
           [java.lang Runnable Math]
           [java.nio IntBuffer ByteBuffer ByteOrder]
           [javax.media.opengl GL GL2 GL2GL3 GLContext GLProfile GLAutoDrawable GLOffscreenAutoDrawable GLDrawableFactory GLCapabilities]
           [javax.media.opengl.glu GLU]
           [javax.vecmath Point2i Point3d Quat4d Matrix4d Vector4d Matrix3d Vector3d]
           [sun.awt.image IntegerComponentRaster]
           [java.util.concurrent Executors]
           [com.defold.editor AsyncCopier]))

(set! *warn-on-reflection* true)

; Line shader

(vtx/defvertex color-vtx
  (vec3 position)
  (vec4 color))

(shader/defshader line-vertex-shader
  (attribute vec4 position)
  (attribute vec4 color)
  (varying vec4 var_color)
  (defn void main []
    (setq gl_Position (* gl_ModelViewProjectionMatrix position))
    (setq var_color color)))

(shader/defshader line-fragment-shader
  (varying vec4 var_color)
  (defn void main []
    (setq gl_FragColor var_color)))

(def line-shader (shader/make-shader ::line-shader line-vertex-shader line-fragment-shader))

(defn gl-viewport [^GL2 gl viewport]
  (.glViewport gl (:left viewport) (:top viewport) (- (:right viewport) (:left viewport)) (- (:bottom viewport) (:top viewport))))

(defn render-curves [^GL2 gl render-args renderables rcount]
  (let [camera (:camera render-args)
        viewport (:viewport render-args)]
    (doseq [renderable renderables
            :let [screen-tris (get-in renderable [:user-data :screen-tris])
                  world-lines (get-in renderable [:user-data :world-lines])]]
      (when world-lines
        (let [vcount (count world-lines)
              vertex-binding (vtx/use-with ::lines world-lines line-shader)]
          (gl/with-gl-bindings gl render-args [line-shader vertex-binding]
            (gl/gl-draw-arrays gl GL/GL_LINES 0 vcount))))
      (when screen-tris
        (let [vcount (count screen-tris)
              vertex-binding (vtx/use-with ::tris screen-tris line-shader)]
          (gl/with-gl-bindings gl render-args [line-shader vertex-binding]
            (gl/gl-draw-arrays gl GL/GL_TRIANGLES 0 vcount)))))))

(defn- render! [^Region viewport ^GLAutoDrawable drawable camera renderables ^GLContext context ^GL2 gl]
  (let [render-args (scene/generic-render-args viewport camera)]
    (gl/gl-clear gl 0.0 0.0 0.0 1)
    (.glColor4f gl 1.0 1.0 1.0 1.0)
    (gl-viewport gl viewport)
    (doseq [pass pass/render-passes
            :let [render-args (assoc render-args :pass pass)]]
      (scene/setup-pass context gl pass camera viewport)
      (scene/batch-render gl render-args (get renderables pass) false :batch-key))))

(g/defnk produce-async-frame [^Region viewport ^GLAutoDrawable drawable camera renderables ^AsyncCopier async-copier curve-renderables cp-renderables tool-renderables]
  (when async-copier
    (profiler/profile "render-curves" -1
                      (when-let [^GLContext context (.getContext drawable)]
                        (let [gl ^GL2 (.getGL context)]
                          (.beginFrame async-copier gl)
                          (render! viewport drawable camera (reduce (partial merge-with into) renderables (into [curve-renderables cp-renderables] tool-renderables)) context gl)
                          (scene-cache/prune-object-caches! gl)
                          (.endFrame async-copier gl)
                          :ok)))))

(defn- curve? [[_ p]]
  (let [v (:value p)]
    (when (satisfies? types/GeomCloud v)
      (< 1 (count (properties/curve-vals v))))))

(g/defnk produce-curve-renderables [curves]
  (let [splines (mapv (fn [{:keys [node-id property curve]}] (->> curve
                                                               (mapv second)
                                                               (properties/->spline))) curves)
        steps 128
        scount (count splines)
        colors (map-indexed (fn [i s] (let [h (* (+ i 0.5) (/ 360.0 scount))
                                            s 1.0
                                            l 0.7]
                                        (colors/hsl->rgba h s l)))
                    splines)
        xs (->> (range steps)
             (map #(/ % (- steps 1)))
             (partition 2 1)
             (apply concat))
        curve-vs (mapcat (fn [spline color](let [[r g b a] color]
                                             (mapv #(let [[x y] (properties/spline-cp spline %)]
                                                      [x y 0.0 r g b a]) xs))) splines colors)
        curve-vcount (count curve-vs)
        world-lines (when (< 0 curve-vcount)
                      (let [vb (->color-vtx curve-vcount)]
                        (doseq [v curve-vs]
                          (conj! vb v))
                        (persistent! vb)))
        renderables [{:render-fn render-curves
                      :batch-key nil
                      :user-data {:world-lines world-lines}}]]
    (into {} (map #(do [% renderables]) [pass/transparent]))))

(g/defnk produce-cp-renderables [curves viewport camera sub-selection]
  (let [sub-sel (reduce (fn [sel [nid prop idx]] (update sel [nid prop] (fn [v] (conj (or v #{}) idx)))) {} sub-selection)
        scale (camera/scale-factor camera viewport)
        splines (mapv (fn [{:keys [node-id property curve]}]
                        (let [sel (get sub-sel [node-id property])]
                          [(->> curve
                             (mapv second)
                             (properties/->spline)) sel])) curves)
        scount (count splines)
        color-hues (map-indexed (fn [i _] (* (+ i 0.5) (/ 360.0 scount)))
                        splines)
        cp-r 4.0
        quad (let [[v0 v1 v2 v3] (vec (for [x [(- cp-r) cp-r]
                                            y [(- cp-r) cp-r]]
                                        [x y 0.0]))]
               (geom/scale scale [v0 v1 v2 v2 v1 v3]))
        cp-vs (mapcat (fn [[spline sel] hue]
                        (let [s 0.7
                              l 0.5]
                          (->> spline
                            (map-indexed (fn [i [x y tx ty]]
                                           (let [v (geom/transl [x y 0.0] quad)
                                                 selected? (contains? sel (inc i))
                                                 s (if selected? 1.0 s)
                                                 l (if selected? 0.9 l)
                                                 c (colors/hsl->rgba hue s l)]
                                             (mapv (fn [v] (reduce conj v c)) v))))
                            (mapcat identity))))
                      splines color-hues)
        cp-vcount (count cp-vs)
        screen-tris (when (< 0 cp-vcount)
                      (let [vb (->color-vtx cp-vcount)]
                        (doseq [v cp-vs]
                          (conj! vb v))
                        (persistent! vb)))
        renderables [{:render-fn render-curves
                     :batch-key nil
                     :user-data {:screen-tris screen-tris}}]]
    (into {} (map #(do [% renderables]) [pass/transparent]))))

(g/defnk produce-curves [selected-node-properties]
  (let [curves (mapcat (fn [p] (->> (:properties p)
                                 (filter curve?)
                                 (map (fn [[k p]] {:node-id (:node-id p)
                                                   :property k
                                                   :curve (iv/iv-mapv identity (:points (:value p)))}))))
                       selected-node-properties)
        ccount (count curves)
        hue-f (/ 360.0 ccount)
        curves (map-indexed (fn [i c] (assoc c :hue (* (+ i 0.5) hue-f))) curves)]
    curves))

(defn- aabb-contains? [^AABB aabb ^Point3d p]
  (let [min-p (types/min-p aabb)
        max-p (types/max-p aabb)]
    (and (<= (.x min-p) (.x p) (.x max-p))
         (<= (.y min-p) (.y p) (.y max-p)))))

(defn handle-input [self action user-data]
  (let [^Point3d start      (g/node-value self :start)
        ^Point3d current    (g/node-value self :current)
        op-seq     (g/node-value self :op-seq)
        command    (g/node-value self :command)
        sub-selection (g/node-value self :sub-selection)
        ^Point3d cursor-pos (:world-pos action)]
    (case (:type action)
      :mouse-pressed (let [basis (g/now)
                           op-seq (gensym)
                           [command data] (g/node-value self :curve-handle)
                           sel-mods? (some #(get action %) selection/toggle-modifiers)]
                       (if (and (some? command) (not sel-mods?))
                         (let [sub-selection (if (not (contains? (set sub-selection) data))
                                               (let [select-fn (g/node-value self :select-fn)
                                                     sub-selection [data]]
                                                 (select-fn sub-selection op-seq)
                                                 sub-selection)
                                               sub-selection)]
                           (g/transact
                             (concat
                               (g/operation-sequence op-seq)
                               (g/set-property self :op-seq op-seq)
                               (g/set-property self :start cursor-pos)
                               (g/set-property self :current cursor-pos)
                               (g/set-property self :command command)
                               (g/set-property self :_basis (atom basis))))
                           nil)
                         action))
      :mouse-released (do
                        (g/transact
                          (concat
                            (g/operation-sequence op-seq)
                            (g/set-property self :start nil)
                            (g/set-property self :current nil)
                            (g/set-property self :op-seq nil)
                            (g/set-property self :command nil)
                            (g/set-property self :_basis nil)))
                        (if command
                          nil
                          action))
      :mouse-moved (case command
                     :move (let [basis @(g/node-value self :_basis)
                                 delta (doto (Vector3d.)
                                         (.sub cursor-pos start))
                                 trans (doto (Matrix4d.)
                                         (.set delta))
                                 ids (reduce (fn [ids [nid prop idx]]
                                               (update ids [nid prop] (fn [v] (conj (or v []) idx))))
                                             {} sub-selection)]
                             (g/transact
                               (concat
                                 (g/operation-sequence op-seq)
                                 (g/set-property self :current cursor-pos)
                                 (for [[[nid prop] ids] ids
                                       :let [curve (g/node-value nid prop :basis basis)]]
                                   (g/set-property nid prop (types/geom-transform curve ids trans)))))
                             nil)
                     action)
      action)))

(g/defnode CurveController
  (property command g/Keyword)
  (property start Point3d)
  (property current Point3d)
  (property op-seq g/Any)
  (property _basis g/Any)
  (property select-fn Runnable)
  (input sub-selection g/Any)
  (input curve-handle g/Any)
  (output input-handler Runnable :cached (g/always handle-input)))

(defn- pick-control-points [curves picking-rect camera viewport]
  (let [aabb (geom/rect->aabb picking-rect)]
    (->> curves
      (mapcat (fn [c]
                (->> (:curve c)
                  (filterv (fn [[idx cp]]
                             (let [[x y] cp
                                   p (doto (->> (Point3d. x y 0.0)
                                             (camera/camera-project camera viewport))
                                       (.setZ 0.0))]
                               (aabb-contains? aabb p))))
                  (mapv (fn [[idx _]] [(:node-id c) (:property c) idx])))))
      (keep identity))))

(g/defnk produce-picking-selection [curves picking-rect camera viewport]
  (pick-control-points curves picking-rect camera viewport))

(g/defnode CurveView
  (inherits scene/SceneRenderer)

  (property image-view ImageView)
  (property viewport Region (default (types/->Region 0 0 0 0)))
  (property drawable GLAutoDrawable)
  (property async-copier AsyncCopier)
  (property tool-picking-rect Rect)

  (input camera-id g/NodeID :cascade-delete)
  (input grid-id g/NodeID :cascade-delete)
  (input background-id g/NodeID :cascade-delete)
  (input input-handlers Runnable :array)
  (input selected-node-properties g/Any)
  (input tool-renderables pass/RenderData :array)
  (input picking-rect Rect)
  (input sub-selection g/Any)

  (output async-frame g/Keyword :cached produce-async-frame)
  (output curves g/Any :cached produce-curves)
  (output curve-renderables g/Any :cached produce-curve-renderables)
  (output cp-renderables g/Any :cached produce-cp-renderables)
  (output picking-selection g/Any produce-picking-selection)
  (output selected-tool-renderables g/Any :cached (g/fnk [] {}))
  (output curve-handle g/Any :cached (g/fnk [curves tool-picking-rect camera viewport]
                                            (if-let [cp (first (pick-control-points curves tool-picking-rect camera viewport))]
                                              [:move cp]
                                              nil))))

(defonce view-state (atom nil))

(defn- update-image-view! [^ImageView image-view dt]
  (let [view-id (ui/user-data image-view ::view-id)
        view-graph (g/node-id->graph-id view-id)]
    (try
      (g/node-value view-id :async-frame)
      (catch Exception e
        (.setImage image-view nil)
        (throw e)))))

(defn frame-selection [view animate?]
  (let [graph (g/node-id->graph-id view)
        camera (g/graph-value graph :camera)
        aabb (or (g/node-value view :selected-aabb)
                 (-> (geom/null-aabb)
                   (geom/aabb-incorporate 0.0 0.0 0.0)
                   (geom/aabb-incorporate 1.0 1.0 0.0)))
        viewport (g/node-value view :viewport)
        local-cam (g/node-value camera :local-camera)
        end-camera (c/camera-orthographic-frame-aabb local-cam viewport aabb)]
    (if animate?
      (let [duration 0.5]
        (ui/anim! duration
                  (fn [t] (let [t (- (* t t 3) (* t t t 2))
                                cam (c/interpolate local-cam end-camera t)]
                            (g/transact
                              (g/set-property camera :local-camera cam))))
                  (fn [])))
      (g/transact (g/set-property camera :local-camera end-camera)))))

(defn make-gl-pane [view-id parent opts]
  (let [image-view (doto (ImageView.)
                     (.setScaleY -1.0))
        pane (proxy [com.defold.control.Region] []
               (layoutChildren []
                 (let [this ^com.defold.control.Region this
                       w (.getWidth this)
                       h (.getHeight this)]
                   (.setFitWidth image-view w)
                   (.setFitHeight image-view h)
                   (proxy-super layoutInArea ^Node image-view 0.0 0.0 w h 0.0 HPos/CENTER VPos/CENTER)
                   (when (and (> w 0) (> h 0))
                     (let [viewport (types/->Region 0 w 0 h)]
                       (g/transact (g/set-property view-id :viewport viewport))
                       (if-let [view-id (ui/user-data image-view ::view-id)]
                         (let [drawable ^GLOffscreenAutoDrawable (g/node-value view-id :drawable)]
                           (doto drawable
                             (.setSize w h))
                           (let [context (scene/make-current drawable)]
                             (doto ^AsyncCopier (g/node-value view-id :async-copier)
                               (.setSize ^GL2 (.getGL context) w h))
                             (.release context)))
                         (do
                           (scene/register-event-handler! parent view-id)
                           (ui/user-data! image-view ::view-id view-id)
                           (let [^Tab tab      (:tab opts)
                                 repainter     (ui/->timer "refresh-curve-view"
                                                (fn [dt]
                                                  (when (.isSelected tab)
                                                    (update-image-view! image-view dt))))]
                             (ui/user-data! parent ::repainter repainter)
                             (ui/on-close tab
                                          (fn [e]
                                            (ui/timer-stop! repainter)))
                             (ui/timer-start! repainter))
                           (let [drawable (scene/make-drawable w h)]
                             (g/transact
                              (concat
                               (g/set-property view-id :drawable drawable)
                               (g/set-property view-id :async-copier (scene/make-copier image-view drawable viewport)))))
                           (frame-selection view-id false)))))
                   (proxy-super layoutChildren))))]
    (.add (.getChildren pane) image-view)
    (g/set-property! view-id :image-view image-view)
    pane))

(defn destroy-view! [parent]
  (when-let [repainter (ui/user-data parent ::repainter)]
    (ui/timer-stop! repainter)
    (ui/user-data! parent ::repainter nil))
  (when-let [node-id (ui/user-data parent ::node-id)]
    (when-let [scene (g/node-by-id node-id)]
      (when-let [^GLAutoDrawable drawable (g/node-value node-id :drawable)]
        (let [gl (.getGL drawable)]
          (when-let [^AsyncCopier copier (g/node-value node-id :async-copier)]
            (.dispose copier gl))
          (scene-cache/drop-context! gl false)
          (.destroy drawable))))
    (g/transact (g/delete-node node-id))
    (ui/user-data! parent ::node-id nil)
    (ui/children! parent [])))

(defn- camera-filter-fn [camera]
  (let [^Point3d p (:position camera)
        y (.y p)
        z (.z p)]
    (assoc camera
           :position (Point3d. 0.5 y z)
           :focus-point (Vector4d. 0.5 y z 1.0)
           :fov-x 1.2)))

(defn make-view!
  ([project graph ^Parent parent opts]
    (reset! view-state {:project project :graph graph :parent parent :opts opts})
    (make-view! project graph parent opts false))
  ([project graph ^Parent parent opts reloading?]
    (let [[node-id] (g/tx-nodes-added
                      (g/transact (g/make-nodes graph [view-id    CurveView
                                                       controller [CurveController :select-fn (fn [selection op-seq] (project/sub-select! project selection op-seq))]
                                                       selection  [selection/SelectionController :select-fn (fn [selection op-seq] (project/sub-select! project selection op-seq))]
                                                       background background/Gradient
                                                       camera     [c/CameraController :local-camera (or (:camera opts) (c/make-camera :orthographic camera-filter-fn))]
                                                       grid       grid/Grid]
                                                (g/update-property camera :movements-enabled disj :tumble) ; TODO - pass in to constructor
                                                (g/set-graph-value graph :camera camera)

                                                (g/connect camera :_node-id view-id :camera-id)
                                                (g/connect grid :_node-id view-id :grid-id)
                                                (g/connect camera :camera view-id :camera)
                                                (g/connect camera :camera grid :camera)
                                                (g/connect camera :input-handler view-id :input-handlers)
                                                (g/connect view-id :viewport camera :viewport)
                                                (g/connect grid :renderable view-id :aux-renderables)
                                                (g/connect background :_node-id view-id :background-id)
                                                (g/connect background :renderable view-id :aux-renderables)

                                                (g/connect project :selected-node-properties view-id :selected-node-properties)
                                                (g/connect project :sub-selection view-id :sub-selection)

                                                (g/connect view-id :curve-handle controller :curve-handle)
                                                (g/connect project :sub-selection controller :sub-selection)
                                                (g/connect controller :input-handler view-id :input-handlers)

                                                (g/connect selection            :renderable                view-id          :tool-renderables)
                                                (g/connect selection            :input-handler             view-id          :input-handlers)
                                                (g/connect selection            :picking-rect              view-id          :picking-rect)
                                                (g/connect view-id              :picking-selection         selection        :picking-selection)
                                                (g/connect project              :sub-selection             selection        :selection))))]
      (when parent
        (let [^Node pane (make-gl-pane node-id parent opts)]
          (ui/fill-control pane)
          (ui/children! parent [pane])
          (ui/user-data! parent ::node-id node-id)))
      node-id)))

(defn- reload-curve-view []
  (when @view-state
    (let [{:keys [project graph ^Parent parent opts]} @view-state]
      (ui/run-now
        (destroy-view! parent)
        (make-view! project graph parent opts true)))))
