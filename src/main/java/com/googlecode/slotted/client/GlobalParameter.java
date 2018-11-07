package com.googlecode.slotted.client;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Indicates that the field is to be tokenized by the AutoTokenizer into the global parameters. The
 * global parameters will appear at the end of the history token similar to URL parameters
 * (i.e. foo?key1=value&key2=anotherValue).
 *
 * The useInEquals parameter determines if the field should be included in the equals() that is
 * generated by the AutoTokenizer.
 *
 * More information on AutoTokenizer and AutoHistoryMapper can be found on the wiki here:
 * https://code.google.com/p/slotted/wiki/AutoTokenizerAutoHistoryMapper
 */
@Target({ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
public @interface GlobalParameter {
    boolean useInEquals() default true;
}