package com.googlecode.slotted.client;

/**
 *
 * @author theider
 */

public class Debug {
    private static boolean isEnabled_ = false;
    public static void enable() { isEnabled_ = true; }
    public static void setEnabled( final boolean isEnabled )
    { isEnabled_ = isEnabled; }

    public static void log( final String s )
    { if( isEnabled_ ) nativeConsoleLog( s ); }

    private static native void nativeConsoleLog( String s )
    /*-{ console.log( s ); }-*/;
}
