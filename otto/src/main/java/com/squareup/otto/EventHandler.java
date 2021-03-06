/*
 * Copyright (C) 2016 Sergey Solovyev
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.squareup.otto;

/**
 * Encapsulates information about event receiver, it's up to {@link HandlerFinder} to
 * decide what implementation to use
 *
 * @author Sergey Solovyev
 */
interface EventHandler {
  /**
   * @return current state of this {@link EventHandler}
   * @see {@link #invalidate()}
   */
  boolean isValid();

  /**
   * If invalidated, will subsequently refuse to handle events.
   * <p/>
   * Should be called when the wrapped object is unregistered from the Bus.
   */
  void invalidate();

  /**
   * Delivers event to a subscriber
   *
   * @param event event to be consumed
   * @throws RuntimeException thrown if error occurs while subscriber is processing the event
   */
  void handleEvent(Object event);
}
