/*
 * Copyright 2012 Jeffrey Kleiss
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.web.bindery.event.shared;

/**
 * Helper needed by SlottedEventBus to work properly, because the Event API has protected methods.
 */
public class EventHelper {
    /**
     * Calls the protected setSource() method on the passed Event.
     * @param event
     * @param source
     */
    public static void setSource(Event event, Object source) {
        event.setSource(source);
    }

    /**
     * Calls the protected dispatch() method on the passed Event.
     * @param <H>
     * @param event
     * @param handler
     */
    public static <H> void dispatch(Event<H> event, H handler) {
        event.dispatch(handler);
    }
}
