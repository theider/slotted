package com.googlecode.slotted.gap_example.client;

import com.google.web.bindery.event.shared.EventBus;
import com.googlecode.slotted.client.SlottedController;

public interface ClientFactory {
    EventBus getEventBus();
    SlottedController getPlaceController();
    HelloView getHelloView();
    GoodbyeView getGoodbyeView();
}