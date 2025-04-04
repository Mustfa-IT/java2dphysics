package com.engine.ui;

import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.logging.Logger;

import javax.inject.Inject;

import java.util.function.Consumer;

import com.engine.components.Transform;
import com.engine.components.UIComponent;
import com.engine.core.GameWindow;
import com.engine.entity.EntityRegistrar;
import com.engine.events.EventSystem;
import com.engine.events.EventTypes;

import dev.dominion.ecs.api.Dominion;
import dev.dominion.ecs.api.Entity;

/**
 * System for creating and managing UI elements.
 * Acts as a factory for UI elements and handles their events.
 *
 * <p>
 * This class provides methods to create various UI components such as buttons,
 * labels, panels, sliders, and debug overlays. It also handles mouse input
 * events
 * for these components and manages UI element states such as hover, focus, and
 * drag.
 * </p>
 *
 * <p>
 * Usage example:
 * </p>
 *
 * <pre>
 * UISystem uiSystem = new UISystem(gameWindow, ecsInstance);
 * Entity button = uiSystem.createButton("Click Me", 100, 100, 200, 50);
 * </pre>
 */
public class UISystem {
  private static final Logger LOGGER = Logger.getLogger(UISystem.class.getName());
  private final GameWindow window;
  private final Dominion ecs;
  private final EventSystem eventSystem;
  private EntityRegistrar currentRegistrar;
  private UIElement hoveredElement;
  private UIElement focusedElement;
  private UIElement draggedElement;

  @Inject
  public UISystem(GameWindow window, Dominion ecs, EventSystem eventSystem) {
    this.window = window;
    this.ecs = ecs;
    this.eventSystem = eventSystem;
    setupEventHandling();
  }

  /**
   * Set the current entity registrar
   */
  public void setCurrentRegistrar(EntityRegistrar registrar) {
    this.currentRegistrar = registrar;
  }

  private void setupEventHandling() {
    MouseAdapter mouseAdapter = new MouseAdapter() {
      @Override
      public void mouseClicked(MouseEvent e) {
        handleMouseClick(e.getX(), e.getY());
      }

      @Override
      public void mouseMoved(MouseEvent e) {
        handleMouseMove(e.getX(), e.getY());
      }

      @Override
      public void mousePressed(MouseEvent e) {
        handleMousePress(e.getX(), e.getY());
      }

      @Override
      public void mouseReleased(MouseEvent e) {
        handleMouseRelease(e.getX(), e.getY());
      }

      @Override
      public void mouseDragged(MouseEvent e) {
        handleMouseDrag(e.getX(), e.getY());
      }
    };

    window.addMouseListener(mouseAdapter);
    window.addMouseMotionListener(mouseAdapter);
  }

  /**
   * Register created UI entity with current registrar
   */
  private Entity registerWithRegistrar(Entity entity) {
    if (entity == null) {
      LOGGER.warning("Attempted to register null entity with registrar");
      return null;
    }

    if (currentRegistrar != null) {
      try {
        Entity registered = currentRegistrar.registerEntity(entity);
        LOGGER.fine("UI entity registered with scene: " + entity);
        return registered;
      } catch (Exception e) {
        LOGGER.warning("Failed to register entity with registrar: " + e.getMessage());
      }
    } else {
      LOGGER.fine("No current registrar available for UI entity: " + entity);
    }
    return entity;
  }

  /**
   * Factory method to create a button
   */
  public Entity createButton(String text, float x, float y, float width, float height) {
    Button button = new Button(text, x, y, width, height);
    Entity entity = ecs.createEntity(
        "ui_button_" + text,
        new UIComponent(button),
        new Transform(x, y, 0, 1, 1));
    return registerWithRegistrar(entity);
  }

  /**
   * Factory method to create a label
   */
  public Entity createLabel(String text, float x, float y) {
    Label label = new Label(text, x, y);
    Entity entity = ecs.createEntity(
        "ui_label_" + text,
        new UIComponent(label),
        new Transform(x, y, 0, 1, 1));
    return registerWithRegistrar(entity);
  }

  /**
   * Factory method to create a panel
   */
  public Entity createPanel(float x, float y, float width, float height) {
    Panel panel = new Panel(x, y, width, height);
    Entity entity = ecs.createEntity(
        "ui_panel",
        new UIComponent(panel),
        new Transform(x, y, 0, 1, 1));
    return registerWithRegistrar(entity);
  }

  /**
   * Factory method to create a slider
   */
  public Entity createSlider(String label, float x, float y, float width, float height,
      float minValue, float maxValue, float initialValue) {
    Slider slider = new Slider(label, x, y, width, height, minValue, maxValue, initialValue);
    Entity entity = ecs.createEntity(
        "ui_slider_" + label,
        new UIComponent(slider),
        new Transform(x, y, 0, 1, 1));
    return registerWithRegistrar(entity);
  }

  /**
   * Set a callback for when a slider's value changes.
   *
   * @param sliderEntity The entity containing the slider UI component
   * @param callback     A consumer function that will be called with the new
   *                     value when the slider changes
   * @throws NullPointerException if the callback is null
   */
  public void setSliderCallback(Entity sliderEntity, Consumer<Float> callback) {
    if (sliderEntity == null)
      return;

    if (callback == null) {
      throw new NullPointerException("Callback cannot be null");
    }

    UIComponent uiComp = sliderEntity.get(UIComponent.class);
    if (uiComp != null && uiComp.getUi() instanceof Slider) {
      Slider slider = (Slider) uiComp.getUi();

      // Wrap the callback to also fire an event
      slider.setOnValueChanged(value -> {
        // Fire value changed event
        eventSystem.fireEvent(EventTypes.UI_VALUE_CHANGED,
            "element", slider,
            "entity", sliderEntity,
            "value", value);

        // Call the original callback
        callback.accept(value);
      });
    }
  }

  @SuppressWarnings("null")
  public void removeSliderCallBacks(Entity sliderEntity) {
    if (sliderEntity == null)
      return;
    UIComponent uiComp = sliderEntity.get(UIComponent.class);
    if (uiComp != null)
      throw new NullPointerException("UIComponent for the Slider can't be found ");

    Slider slider = (Slider) uiComp.getUi();
    slider.removeSliderCallBacks();
  }

  @SuppressWarnings("null")
  public void removeSliderCallBack(Entity sliderEntity, Consumer<Float> callBack) {
    if (sliderEntity == null)
      return;
    UIComponent uiComp = sliderEntity.get(UIComponent.class);
    if (uiComp != null)
      throw new NullPointerException("UIComponent for the Slider can't be found ");
    Slider slider = (Slider) uiComp.getUi();
    slider.removeSliderCallBack(callBack);
  }

  /**
   * Factory method to create a debug overlay for displaying performance metrics
   * and debug information.
   *
   * @param x The x-coordinate of the top-left corner of the overlay
   * @param y The y-coordinate of the top-left corner of the overlay
   * @return An entity containing the debug overlay UI component, or null if
   *         registration fails
   * @see DebugOverlay
   */
  public Entity createDebugOverlay(float x, float y) {
    int overlayWidth = (int) (window.getWidth() * 0.2); // 20% of the window width
    int overlayHeight = (int) (window.getHeight() * 0.20); // 15% of the window height
    DebugOverlay overlay = new DebugOverlay(x, y, overlayWidth, overlayHeight);
    Entity entity = ecs.createEntity(
        "ui_debug_overlay",
        new UIComponent(overlay),
        new Transform(x, y, 0, 1, 1));
    return registerWithRegistrar(entity);
  }

  /**
   * Add a UI element to an existing panel
   */
  public void addToPanel(Entity panelEntity, Entity elementEntity) {
    if (panelEntity == null || elementEntity == null) {
      return;
    }

    UIComponent panelComp = panelEntity.get(UIComponent.class);
    UIComponent elementComp = elementEntity.get(UIComponent.class);

    if (panelComp == null || elementComp == null) {
      return;
    }

    UIElement panelElement = panelComp.getUi();
    UIElement childElement = elementComp.getUi();

    if (panelElement instanceof Panel && childElement != null) {
      ((Panel) panelElement).addElement(childElement);
    }
  }

  /**
   * Handle mouse movement to update hover states
   */
  private void handleMouseMove(int x, int y) {
    // Reset previous hover state
    if (hoveredElement instanceof Button) {
      ((Button) hoveredElement).setHovered(false);

      // Fire hover end event
      eventSystem.fireEvent(EventTypes.UI_HOVER_END,
          "element", hoveredElement,
          "x", x,
          "y", y);
    }

    // Find element under cursor
    hoveredElement = findElementAt(x, y);

    // Update hover state
    if (hoveredElement instanceof Button) {
      ((Button) hoveredElement).setHovered(true);

      // Fire hover begin event
      eventSystem.fireEvent(EventTypes.UI_HOVER_BEGIN,
          "element", hoveredElement,
          "x", x,
          "y", y);
    }
  }

  /**
   * Handle mouse click events
   */
  private void handleMouseClick(int x, int y) {
    UIElement clickedElement = findElementAt(x, y);

    if (clickedElement instanceof Button) {
      ((Button) clickedElement).click();

      // Fire click event
      eventSystem.fireEvent(EventTypes.UI_CLICK,
          "element", clickedElement,
          "x", x,
          "y", y);
    }

    focusedElement = clickedElement;
  }

  /**
   * Handle mouse press events
   */
  private void handleMousePress(int x, int y) {
    UIElement clickedElement = findElementAt(x, y);

    if (clickedElement instanceof Slider) {
      if (((Slider) clickedElement).startDrag(x, y)) {
        draggedElement = clickedElement;
      }
    }

    // Could be used for drag operations or visual feedback
  }

  /**
   * Handle mouse release events
   */
  private void handleMouseRelease(int x, int y) {
    if (draggedElement instanceof Slider) {
      ((Slider) draggedElement).stopDrag();
    }
    draggedElement = null;
  }

  /**
   * Handle mouse drag events
   */
  private void handleMouseDrag(int x, int y) {
    if (draggedElement instanceof Slider) {
      ((Slider) draggedElement).handleMouseDrag(x, y);
    }
  }

  /**
   * Find the UI element at the specified screen coordinates
   */
  private UIElement findElementAt(int x, int y) {
    UIElement result = null;

    // Instead of looping through a local list, query all entities with UIComponent
    var entities = ecs.findEntitiesWith(UIComponent.class);

    // Check each UI component to see if it contains the point
    for (var entityResult : entities) {
      UIComponent uiComponent = entityResult.comp();

      if (uiComponent != null && uiComponent.isVisible() &&
          uiComponent.contains(x, y)) {
        result = uiComponent.getUi();
        break;
      }
    }

    return result;
  }

  /**
   * Update all UI elements
   */
  public void update(double deltaTime) {
    ecs.findEntitiesWith(UIComponent.class).forEach(result -> {
      UIComponent uiComponent = result.comp();
      if (uiComponent.getUi() instanceof AbstractUIElement) {
        ((AbstractUIElement) uiComponent.getUi()).update(deltaTime);
      }
    });
  }
}
