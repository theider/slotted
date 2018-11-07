package com.googlecode.slotted.client;

/**
 *
 * @author theider
 */

public class Debug {
   
    public static native void debugMessage(String message) /*-{
        console.log(message);
    }-*/;
}
