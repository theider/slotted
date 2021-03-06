package com.googlecode.slotted.gin_codesplitting.client;

import com.google.gwt.core.client.GWT;
import com.google.gwt.inject.client.GinModules;
import com.google.gwt.inject.client.Ginjector;
import com.googlecode.slotted.client.SlottedController;

@GinModules({AppGinModule.class, SingletonModule.class})
public interface AppGinjector extends Ginjector {
    public static final AppGinjector INSTANCE = GWT.create(AppGinjector.class);

    SlottedController getSlottedController();

    BaseActivity getBaseActivity();
}