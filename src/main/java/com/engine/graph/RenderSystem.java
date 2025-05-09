package com.engine.graph;

import java.awt.Graphics2D;
import java.awt.Color;
import java.awt.Font;
import java.awt.BasicStroke;
import java.awt.Toolkit;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferStrategy;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Logger;
import java.util.ArrayDeque;
import java.util.Deque;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.jbox2d.common.Vec2;

import com.engine.components.CameraComponent;
import com.engine.components.PhysicsBodyComponent;
import com.engine.components.RenderableComponent;
import com.engine.components.SpriteComponent;
import com.engine.components.Transform;
import com.engine.components.UIComponent;
import com.engine.components.GameObjectComponent;
import com.engine.core.CameraSystem;
import com.engine.core.GameFrame;
import com.engine.editor.Editor;
import com.engine.events.EventSystem;
import com.engine.events.EventTypes;
import com.engine.physics.BoxCollider;
import com.engine.physics.CircleCollider;
import com.engine.physics.PolygonCollider;

import dev.dominion.ecs.api.Dominion;
import dev.dominion.ecs.api.Entity;

@Singleton
public class RenderSystem implements RenderingSystem {
  private final GameFrame window;
  private final Dominion world;
  private final CameraSystem cameraSystem;
  private final EventSystem eventSystem;
  private static final Logger LOGGER = Logger.getLogger(RenderSystem.class.getName());

  // Debug visualization flags
  private boolean debugPhysics = false;
  private boolean debugColliders = false;
  private boolean showGrid = true;

  // Rendering layers and components
  private OverlayRenderer overlayRenderer;
  private Editor editor;
  private boolean editorActive = false;

  // Rendering statistics
  private int lastFrameEntityCount = 0;
  private int lastFrameUICount = 0;

  // Performance optimization - Graphics context pool
  private final Deque<Graphics2D> graphicsPool = new ArrayDeque<>();

  // List of custom renderers
  private final List<CustomRenderer> customRenderers = new ArrayList<>();

  // Optimized debug renderer
  private final DebugRenderer debugRenderer;

  @Inject
  public RenderSystem(GameFrame window, Dominion world, CameraSystem cameraSystem,
      EventSystem eventSystem, DebugRenderer debugRenderer) {
    this.cameraSystem = cameraSystem;
    this.window = window;
    this.world = world;
    this.eventSystem = eventSystem;
    this.debugRenderer = debugRenderer;

    // Subscribe to relevant rendering events
    subscribeToEvents();
  }

  /**
   * Subscribe to events that affect rendering
   */
  private void subscribeToEvents() {
    // Listen for debug visualization changes
    eventSystem.addEventListener(EventTypes.RENDER_DEBUG_CHANGED, event -> {
      boolean showPhysics = event.getData("showPhysics", debugPhysics);
      boolean showColliders = event.getData("showColliders", debugColliders);
      boolean showWorldGrid = event.getData("showGrid", showGrid);
      setDebugOptions(showPhysics, showColliders, showWorldGrid);
      return;
    });

    // Listen for editor state changes
    eventSystem.addEventListener(EventTypes.EDITOR_STATE_CHANGED, event -> {
      editorActive = event.getData("active", false);
      return;
    });
  }

  /**
   * Set the overlay renderer (used for console, etc)
   */
  public void setOverlayRenderer(OverlayRenderer overlayRenderer) {
    this.overlayRenderer = overlayRenderer;
  }

  /**
   * Configure debug visualization options
   */
  public void setDebugOptions(boolean showPhysics, boolean showColliders, boolean showGrid) {
    this.debugPhysics = showPhysics;
    this.debugColliders = showColliders;
    this.showGrid = showGrid;

    // Update debug renderer settings
    debugRenderer.setDebugOptions(showPhysics, showColliders);
  }

  /**
   * Main render method - orchestrates the entire rendering pipeline
   */
  @Override
  public void render() {
    // Create buffer strategy if needed
    if (window.getBufferStrategy() == null) {
      try {
        window.createBufferStrategy(2);
        return; // Skip this frame, buffer strategy will be ready next time
      } catch (IllegalStateException e) {
        LOGGER.warning("Failed to create buffer strategy: " + e.getMessage());
        return;
      }
    }

    BufferStrategy bs = window.getBufferStrategy();
    if (bs == null)
      return;

    // Main graphics context for the frame
    Graphics2D g = (Graphics2D) bs.getDrawGraphics();

    try {
      // Clear the screen
      g.clearRect(0, 0, window.getWidth(), window.getHeight());

      // === RENDER WORLD ===
      renderWorld(g);

      // === RENDER UI ===
      renderUI(g);

      // === RENDER EDITOR ===
      if (editorActive && editor != null) {
        renderEditor(g);
      }
      // === RENDER OVERLAY ===
      if (overlayRenderer != null) {
        overlayRenderer.renderOverlays(g);
      }

      // Fire render complete event with stats
      eventSystem.fireEvent(EventTypes.RENDER_FRAME_COMPLETE,
          "entityCount", lastFrameEntityCount,
          "uiCount", lastFrameUICount);

    } finally {
      g.dispose();
      bs.show();
      Toolkit.getDefaultToolkit().sync();
    }
  }

  /**
   * Render the game world (entities, GameObjects, sprites)
   */
  private void renderWorld(Graphics2D baseG) {
    // Only draw grid if flag is enabled
    if (showGrid) {
      drawWorldGrid(baseG);
    }

    // Apply camera transformation for world rendering
    Graphics2D g = cameraSystem.applyActiveCamera((Graphics2D) baseG.create());

    try {

      // Clear graphics pool before rendering frame
      graphicsPool.clear();

      // Render game entities in the world
      renderEntities(g);
      renderGameObjects(g);
      renderSprites(g);

      // Render any custom renderers in order of priority
      renderCustom(g);

      // Use optimized debug renderer
      if (debugPhysics || debugColliders) {
        debugRenderer.render(g);
      }
    } finally {
      g.dispose();
    }
  }

  /**
   * Get a graphics context from pool or create new one
   */
  private Graphics2D getGraphics(Graphics2D source) {
    Graphics2D g = graphicsPool.poll();
    if (g == null) {
      g = (Graphics2D) source.create();
    }
    return g;
  }

  /**
   * Return graphics context to pool for reuse
   */
  private void recycleGraphics(Graphics2D g) {
    if (graphicsPool.size() < 20) { // Limit pool size
      graphicsPool.offer(g);
    } else {
      g.dispose();
    }
  }

  /**
   * Add a custom renderer
   *
   * @param renderer The renderer to add
   */
  public void addCustomRenderer(CustomRenderer renderer) {
    customRenderers.add(renderer);
    customRenderers.sort(Comparator.comparingInt(CustomRenderer::getPriority));
  }

  /**
   * Remove a custom renderer
   *
   * @param renderer The renderer to remove
   */
  public void removeCustomRenderer(CustomRenderer renderer) {
    customRenderers.remove(renderer);
  }

  /**
   * Render all active custom renderers
   */
  private void renderCustom(Graphics2D g) {
    for (CustomRenderer renderer : customRenderers) {

      renderer.render(g);

    }
  }

  /**
   * Render UI elements
   */
  private void renderUI(Graphics2D baseG) {
    // Create a separate graphics context for UI (not affected by camera)
    Graphics2D g = (Graphics2D) baseG.create();

    try {
      int uiCount = 0;

      // Render all UI components
      for (var result : world.findEntitiesWith(UIComponent.class)) {
        UIComponent com = result.comp();
        if (com.isVisible()) {
          com.render(g);
          uiCount++;
        }
      }

      lastFrameUICount = uiCount;
    } finally {
      g.dispose();
    }
  }

  /**
   * Render the editor UI
   */
  private void renderEditor(Graphics2D g) {
    if (editor != null) {
      editor.render(g);
    }
  }

  /**
   * Draw a reference grid in world space for debugging
   */
  private void drawWorldGrid(Graphics2D g) {
    Entity camera = cameraSystem.getActiveCamera();
    if (camera == null) {
      LOGGER.warning("No active camera found for grid drawing");
      return;
    }

    CameraComponent camComponent = camera.get(CameraComponent.class);
    if (camComponent == null) {
      LOGGER.warning("Camera entity has no CameraComponent");
      return;
    }

    // Get screen dimensions
    int screenWidth = window.getWidth();
    int screenHeight = window.getHeight();

    // Convert screen edges to world coordinates
    float[] topLeft = cameraSystem.screenToWorld(0, 0);
    float[] bottomRight = cameraSystem.screenToWorld(screenWidth, screenHeight);

    // Draw coordinate axes with transformed graphics
    Graphics2D g2d = cameraSystem.applyActiveCamera((Graphics2D) g.create());

    // Set up line style
    g2d.setColor(new Color(0, 0, 255, 128)); // Semi-transparent blue
    g2d.setStroke(new BasicStroke(0.5f));

    try {
      // Draw grid lines
      float gridSize = 50; // Size of each grid cell

      // Calculate grid bounds based on screen edges in world space
      float startX = (float) (Math.floor(topLeft[0] / gridSize) * gridSize);
      float endX = (float) (Math.ceil(bottomRight[0] / gridSize) * gridSize);
      float startY = (float) (Math.floor(topLeft[1] / gridSize) * gridSize);
      float endY = (float) (Math.ceil(bottomRight[1] / gridSize) * gridSize);

      // Draw vertical grid lines
      for (float x = startX; x <= endX; x += gridSize) {
        g2d.drawLine((int) x, (int) startY, (int) x, (int) endY);
      }

      // Draw horizontal grid lines
      for (float y = startY; y <= endY; y += gridSize) {
        g2d.drawLine((int) startX, (int) y, (int) endX, (int) y);
      }

      // Draw the main axes with stronger lines
      g2d.setStroke(new BasicStroke(2.0f));
      g2d.setColor(Color.RED);
      g2d.drawLine(-1000, 0, 1000, 0); // X-axis

      g2d.setColor(Color.GREEN);
      g2d.drawLine(0, -1000, 0, 1000); // Y-axis (now points up)

      // Draw origin marker
      g2d.setColor(Color.WHITE);
      g2d.fillOval(-5, -5, 10, 10);
    } catch (Exception e) {
      LOGGER.warning("Error drawing grid: " + e.getMessage());
    } finally {
      g2d.dispose();
    }
  }

  /**
   * Renders entities using the camera-transformed graphics context
   */
  private void renderEntities(Graphics2D g) {
    int entityCount = 0;

    for (var result : world.findEntitiesWith(Transform.class, RenderableComponent.class)) {
      Transform transform = result.comp1();
      RenderableComponent renderable = result.comp2();

      // Only render if the component is visible
      if (renderable.isVisible()) {
        // Create a copy of the camera-transformed graphics for this entity
        Graphics2D entityG = (Graphics2D) g.create();

        try {
          // Apply entity's local transformations
          entityG.translate(transform.getX(), transform.getY());
          entityG.rotate(transform.getRotation());
          entityG.scale(transform.getScaleX(), transform.getScaleY());

          // Render the entity with the combined transformations
          renderable.render(entityG);
          entityCount++;
        } finally {
          entityG.dispose();
        }
      }
    }

    lastFrameEntityCount += entityCount;
  }

  /**
   * Renders custom GameObjects with culling optimization
   */
  private void renderGameObjects(Graphics2D g) {
    world.findEntitiesWith(Transform.class, GameObjectComponent.class).forEach(result -> {
      Transform transform = result.comp1();
      GameObjectComponent gameObjectComp = result.comp2();
      if(gameObjectComp == null && transform == null) return;
      // Skip if the GameObject has been destroyed
      if (gameObjectComp.isDestroyed()) {
        return;
      }

      // Reuse graphics context from pool
      Graphics2D entityG = getGraphics(g);

      // Reset transformation to match the parent graphics context before applying new
      // transforms
      entityG.setTransform(g.getTransform());

      // Apply entity's local transformations
      entityG.translate(transform.getX(), transform.getY());
      entityG.rotate(transform.getRotation());
      entityG.scale(transform.getScaleX(), transform.getScaleY());

      // Let the GameObject render itself
      gameObjectComp.getGameObject().render(entityG);

      // Return graphics context to pool instead of disposing
      recycleGraphics(entityG);
    });
  }

  /**
   * Render sprite components
   */
  private void renderSprites(Graphics2D g) {
    int renderedCount = 0;

    for (var result : world.findEntitiesWith(Transform.class, SpriteComponent.class)) {
      Transform transform = result.comp1();
      SpriteComponent sprite = result.comp2();

      // Skip if sprite is not visible or has no image
      if (!sprite.isVisible() || sprite.getImage() == null) {
        continue;
      }

      // Create a copy of the camera-transformed graphics for this entity
      Graphics2D entityG = (Graphics2D) g.create();

      // Apply entity's local transformations
      entityG.translate(transform.getX(), transform.getY());
      entityG.rotate(transform.getRotation());

      // Fix: Apply scale with Y-component negated to correct the sprite orientation
      entityG.scale(transform.getScaleX(), -transform.getScaleY());

      // Render the sprite
      sprite.render(entityG);
      entityG.dispose();

      renderedCount++;
    }

    if (renderedCount > 0) {
      LOGGER.fine("Rendered " + renderedCount + " sprites");
    }
  }
}
