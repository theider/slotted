package com.googlecode.slotted.client;

/**
 * CodeSplitMapper is a generator class, which finds all the Places which have the
 * {@link CodeSplit} and {@link PlaceActivity} annotations provides the
 * logic for constructing the Activity via default constructor.
 *
 * More information on CodeSplitMapper can be found on the wiki here:
 * https://code.google.com/p/slotted/wiki/CodeSplitting
 */
public interface CodeSplitGinMapper<D> extends CodeSplitMapper {
    D getGinjector();
}
