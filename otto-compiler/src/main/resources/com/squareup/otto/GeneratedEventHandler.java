package com.squareup.otto;

/**
 * Base class for generated {@link EventHandler}s.
 * Contains {@link #equals(Object)} and {@link #hashCode()} methods which assume that implementors of this class are
 * anonymous classes, one per subscriber's method.
 *
 * @author Sergey Solovyev
 */
abstract class GeneratedEventHandler extends BaseEventHandler {

  protected final Object listener;

  protected GeneratedEventHandler(Object listener) {
    this.listener = listener;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    // each anonymous class has its own unique class name, thus, checking class name should be enough to distinguish
    // one handler from another
    if (o == null || getClass() != o.getClass()) return false;

    // but there might be several subscribers with the same class names (f.e. a fragment might be instantiated several
    // times), thus, subscriber should als be checked
    final GeneratedEventHandler that = (GeneratedEventHandler) o;
    return listener.equals(that.listener);

  }

  @Override
  public int hashCode() {
    return listener.hashCode();
  }
}