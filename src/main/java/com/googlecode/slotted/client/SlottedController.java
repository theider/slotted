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
package com.googlecode.slotted.client;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.google.gwt.activity.shared.Activity;
import com.google.gwt.activity.shared.ActivityMapper;
import com.google.gwt.core.client.Callback;
import com.google.gwt.core.client.GWT;
import com.google.gwt.dom.client.Document;
import com.google.gwt.dom.client.NativeEvent;
import com.google.gwt.event.logical.shared.ValueChangeEvent;
import com.google.gwt.event.logical.shared.ValueChangeHandler;
import com.google.gwt.event.shared.HandlerRegistration;
import com.google.gwt.place.shared.Place;
import com.google.gwt.place.shared.PlaceHistoryMapper;
import com.google.gwt.user.client.Event;
import com.google.gwt.user.client.Event.NativePreviewEvent;
import com.google.gwt.user.client.History;
import com.google.gwt.user.client.Window;
import com.google.gwt.user.client.Window.ClosingEvent;
import com.google.gwt.user.client.Window.ClosingHandler;
import com.google.gwt.user.client.ui.AcceptsOneWidget;
import com.google.gwt.user.client.ui.PopupPanel;
import com.google.web.bindery.event.shared.EventBus;

/**
 * Controls display of the RootSlot and all nested slots.
 */
public class SlottedController {
    /**
     * RootSlot instance that should be used to define {@link SlottedPlace}s that should be
     * displayed in the root slot.
     */
    public static final RootSlotImpl RootSlot = new RootSlotImpl();
    public static SlottedController instance;

    public static class RootSlotImpl extends Slot {
        public RootSlotImpl() {
            super(null, null);
        }

        public RootSlotImpl(SlottedPlace defaultPlace) {
            super(null, defaultPlace);
        }
    }

    /**
     * Default implementation of {@link Delegate}, based on {@link Window}.
     */
    public static class DefaultDelegate implements Delegate {
        public HandlerRegistration addWindowClosingHandler(ClosingHandler handler) {
            return Window.addWindowClosingHandler(handler);
        }

        public boolean confirm(String[] messages) {
            return Window.confirm(messages[0]);
        }
    }

    /**
     * Optional delegate in charge of Window-related events. Provides nice isolation for unit
     * testing, and allows customization of confirmation handling.
     */
    public interface Delegate {
        /**
         * Adds a {@link ClosingHandler} to the Delegate.
         *
         * @param handler a {@link ClosingHandler} instance
         * @return a {@link HandlerRegistration} instance
         */
        HandlerRegistration addWindowClosingHandler(ClosingHandler handler);

        /**
         * Called to confirm a window closing event.
         *
         * @param messages an array of warning messages
         * @return true to allow the window closing
         */
        boolean confirm(String[] messages);
    }

    protected static final Logger log = Logger.getLogger(SlottedController.class.getName());

    private final EventBus eventBus;
    private final HistoryMapper historyMapper;
    private ActivityMapper legacyActivityMapper;
    private ActivityCache activityCache = new ActivityCache();
    protected HashMap<Class, CodeSplitMapper> codeSplitMap = new HashMap<Class, CodeSplitMapper>();

    private boolean processingGoTo;
    private boolean processingSync;
    private boolean tokenDone;
    private SlottedPlace mainGoToPlace;
    protected List<Callback<Activity, Throwable>> asyncActivities = new LinkedList<Callback<Activity, Throwable>>();
    private SlottedPlace nextGoToPlace;
    @SuppressWarnings("FieldCanBeLocal")
    private SlottedPlace[] nextGoToNonDefaultPlaces;
    @SuppressWarnings("FieldCanBeLocal")
    private boolean nextGoToReloadAll;
    private List<SlottedPlace> currentHierarchyList;
    private List<SlottedPlace> possibleParentPlaces;
    private final Delegate delegate;
    private boolean reloadAll = false;
    private boolean useExistingChildren = false;
    private ActiveSlot root;
    private PlaceParameters currentParameters;
    private NavigationOverride navigationOverride;
    private String goToList;
    private String referringToken;
    private String currentToken;
    private boolean openNewTab;
    private boolean openNewWindow;
    private String openWindowFeatures = "directories=yes,location=yes,menubar=yes,status=yes,titlebar=yes,toolbar=yes";
    protected boolean isMainController;

    /**
     * Create a new SlottedController with a {@link DefaultDelegate}. The DefaultDelegate is created
     * via a call to GWT.create(), so an alternative default implementation can be provided through
     * &lt;replace-with&gt; rules in a {@code .gwt.xml} file.
     *
     * @param historyMapper the {@link HistoryMapper}
     * @param eventBus the {@link EventBus}
     */
    public SlottedController(HistoryMapper historyMapper, EventBus eventBus) {
        this(historyMapper, eventBus, (Delegate) GWT.create(DefaultDelegate.class));
    }

    /**
     * Create a new SlottedController.
     *
     * @param historyMapper the {@link HistoryMapper}
     * @param eventBus the {@link EventBus}
     * @param delegate the {@link Delegate} in charge of Window-related events
     */
    public SlottedController(final HistoryMapper historyMapper, EventBus eventBus, Delegate delegate) {
        this(historyMapper, eventBus, delegate, true);
    }

    /**
     * Create a new SlottedController.
     *
     * @param historyMapper the {@link HistoryMapper}
     * @param eventBus the {@link EventBus}
     * @param delegate the {@link Delegate} in charge of Window-related events
     * @param isMainController false if this controller shouldn't handle History, WindowClosingEvent, and OpenWindow events.
     */
    protected SlottedController(final HistoryMapper historyMapper, EventBus eventBus, Delegate delegate, boolean isMainController) {
        this.eventBus = eventBus;
        this.historyMapper = historyMapper;
        this.delegate = delegate;
        this.isMainController = isMainController;

        if (isMainController) {
            instance = this;
            History.addValueChangeHandler(new ValueChangeHandler<String>() {
                @Override public void onValueChange(ValueChangeEvent<String> event) {
                    historyMapper.handleHistory(event.getValue(), false, SlottedController.this);
                }
            });

            delegate.addWindowClosingHandler(new ClosingHandler() {
                public void onWindowClosing(ClosingEvent event) {
                    if (root != null) {
                        ArrayList<String> warnings = new ArrayList<String>();
                        root.maybeGoTo(new ArrayList<SlottedPlace>(), true, warnings);
                        if (!warnings.isEmpty()) {
                            event.setMessage(warnings.get(0));
                        }
                    }
                }
            });

            Event.addNativePreviewHandler(new Event.NativePreviewHandler() {
                public void onPreviewNativeEvent(NativePreviewEvent event) {
                    NativeEvent ne = event.getNativeEvent();
                    String type = ne.getType();
                    if (type.equals("mousedown") || type.equals("mouseup") || type.equals("click")) {
                        openNewWindow = ne.getShiftKey();
                        openNewTab = ne.getMetaKey() || ne.getCtrlKey();
                    } else {
                        openNewWindow = false;
                        openNewTab = false;
                    }
                }
            });
        }
    }

    /**
     * Sets the GWT Activity and Places controller objects so that Slotted can run legacy code without changes.
     *
     * @param legacyActivityMapper The A&amp;P ActivityMapper that is used to create an Activity from a Place.
     * @param legacyHistoryMapper The A&amp;P HistoryMapper that will be used to parse the URL if Slotted have those places.
     * @param defaultPlace The Place to be navigated to in the URL token is blank.
     */
    public void setLegacyMappers(ActivityMapper legacyActivityMapper,
            PlaceHistoryMapper legacyHistoryMapper, Place defaultPlace)
    {
        this.legacyActivityMapper = legacyActivityMapper;
        historyMapper.setLegacyActivityMapper(legacyActivityMapper);
        historyMapper.setLegacyHistoryMapper(legacyHistoryMapper);
        if (historyMapper.getDefaultPlace() == null) {
            historyMapper.setDefaultPlace(
                    new WrappedPlace(defaultPlace, legacyActivityMapper));
        }
    }

    /**
     * Sets an ActivityMapper that is responsible for creating the Activities.  This is used in conjunction with
     * {@link MappedSlottedPlace} when you don't want to use {@link SlottedPlace#getActivity()} or if you are dealing
     * legacy around the creation of Activities.
     *
     * @param activityMapper The A&amp;P ActivityMapper this will be used to create Activities.
     */
    public void setActivityMapper(ActivityMapper activityMapper) {
        this.legacyActivityMapper = activityMapper;
        historyMapper.setLegacyActivityMapper(activityMapper);
    }

    /**
     * Sets the SlottedPlace that should be displayed when the History token is empty.
     *
     * @param defaultPlace The place with correct parameters to display.
     */
    public void setDefaultPlace(Place defaultPlace) {
        SlottedPlace slottedPlace;
        if (defaultPlace instanceof SlottedPlace) {
            slottedPlace = (SlottedPlace) defaultPlace;
        } else {
            if (legacyActivityMapper == null) {
                throw new IllegalStateException(
                        "Must use SlottedPlace unless LegacyActivityMapper is set");
            }

            slottedPlace = new WrappedPlace(defaultPlace, legacyActivityMapper);
        }

        historyMapper.setDefaultPlace(slottedPlace);
    }

    /**
     * Registers a CodeSplitMapper to be used retrieve Activities from a Code Split call.
     *
     * @param mapperClass The class represents this CodeSplitMapper.  Used because the CodeSplitMapper
     *                    might be generated.
     * @param codeSplitMapper The mapper that should be used to retrieve Activities for specified Places
     */
    public void registerCodeSplitMapper(Class<? extends CodeSplitMapper> mapperClass, CodeSplitMapper codeSplitMapper) {
        codeSplitMap.put(mapperClass, codeSplitMapper);
    }

    /**
     * Internal call to get the CodeSplitMapper during goTo Activity construction.
     *
     * @param codeSplitMapperClass The mapperClass specified in the @CodeSplitMapperClass
     */
    protected CodeSplitMapper getCodeSplitMapper(Class codeSplitMapperClass) {
        return codeSplitMap.get(codeSplitMapperClass);
    }

    /**
     * Sets the SlottedPlace that should be displayed when there is an error parsing the History
     * token.  If this is not set, the default place is used instead.
     *
     * @param errorPlace The place with correct parameters to display.
     */
    public void setErrorPlace(SlottedErrorPlace errorPlace) {
        historyMapper.setErrorPlace(errorPlace);
    }

    /**
     * Gets the ErrorPlace that was set or null if none was set.
     *
     * @see #setErrorPlace
     */
    public SlottedErrorPlace getErrorPlace() {
        return historyMapper.getErrorPlace();
    }

    /**
     * Sets the widget that displays the root slot content.  This must be set before any content can
     * be displayed.
     *
     * @param display an instance of AcceptsOneWidget
     */
    public void setDisplay(AcceptsOneWidget display) {
        Slot rootSlot = new RootSlotImpl(null);
        //noinspection deprecation
        rootSlot.setDisplay(display);
        root = new ActiveSlot(null, rootSlot, eventBus, this);

        if (isMainController) {
            History.fireCurrentHistoryState();
        }
    }

    /**
     * Sets reloadAll (defaults false).  If the reloadAll is true, then every Activity in the
     * hierarchy will be stopped and started again on every navigation.  If false, Places/Activities
     * that are already in the hierarchy will not be stopped, but Activities not in navigation
     * will be stopped and new Places will have Activities started.
     *
     * It is possible to override the RefreshAll default value by calling {@link #goTo(SlottedPlace, SlottedPlace[], boolean)}.
     *
     * @param reloadAll The new default value to use on goTo() calls and URL changes.
     */
    public void setReloadAll(boolean reloadAll) {
        this.reloadAll = reloadAll;
    }

    /**
     * Sets useExistingChildren (normally false).  If useExistingChildren is set to false (Slotted 0.3+), which means when navigating to a
     * parent place, Slotted will use the parents default children, even if another child exists in the hierarchy.  This change was made,
     * because it is more natural to have the goTo Place navigate to the same location every time.
     *
     * If useExistingChildren is set to true (Slotted 0.2 functionality), then when navigating to the parent place, if the parent
     * already exists, the current children will be used.  This can create the feeling that the navigation failed, because no places
     * will change.
     *
     * @param useExistingChildren The new default value to use on goTo() calls and URL changes.
     */
    public void setUseExistingChildren(boolean useExistingChildren) {
        this.useExistingChildren = useExistingChildren;
    }

    /**
     * Allows for a NavigationOverride object to evaluate the Places before Slotted creates the Activities.
     *
     * @param navigationOverride That will called before on every goTo().
     * @see NavigationOverride for examples
     */
    public void setNavigationOverride(NavigationOverride navigationOverride) {
        this.navigationOverride = navigationOverride;
    }

    /**
     * Gets the current NavigationOverride that has been set.
     */
    public NavigationOverride getNavigationOverride() {
        return navigationOverride;
    }

    /**
     * Set the features to pass to {@link Window#open(String, String, String)} when the SHIFT key is
     * pressed.
     */
    public void setOpenWindowFeature(String features) {
        openWindowFeatures = features;
    }

    /**
     * Used by the HistoryMapper to display the default place.  This method works as if you called
     * {@link #goTo(Place)} with the default place.
     */
    protected void goToDefaultPlace() {
        if (isMainController) {
            History.newItem("", true);
        } else {
            goTo(historyMapper.getDefaultPlace());
        }
    }

    /**
     * Convenience method for navigating to the ErrorPage.
     *
     * @param exception that caused the error.
     */
    public void goToErrorPlace(Throwable exception) {
        SlottedErrorPlace errorPlace = getErrorPlace();
        if (errorPlace != null) {
            errorPlace.setException(exception);
            goTo(errorPlace);
        } else {
            throw new IllegalStateException("ErrorPlace hasn't been set properly");
        }
    }

    /**
     * Returns the History Token for the referring page (the page that triggered the goTo()).
     */
    public String getReferringToken() {
        if (processingGoTo && !tokenDone) {
            return currentToken;
        } else {
            return referringToken;
        }
    }

    /**
     * Request a change to a historyToken string.  This is used with {@link #getReferringToken()} to
     * save a location to jump to.  This will add a new Item to the History list, so the browser
     * back button with show the page that called the goTo().
     *
     * @param historyToken Just the part of the url following the #.  Don't send complete URLs.
     */
    public void goTo(String historyToken) {
        historyMapper.handleHistory(historyToken, true, this);
    }

    /**
     * Request a change to a new place. It is not a given that we'll actually get there. First all
     * active {@link SlottedActivity#mayStop()} will called. If any activities return a warning
     * message, it will be presented to the user via {@link Delegate#confirm(String[])} (which is
     * typically a call to {@link Window#confirm(String)}) with the first warning message. If she
     * cancels, the current location will not change. Otherwise, the location changes and the new
     * place and all dependent places are created.
     *
     * @param newPlace a {@link SlottedPlace} instance to navigate to.  This place is used to create the hierarchy.
     */
    public void goTo(Place newPlace) {
        SlottedPlace slottedPlace;
        if (newPlace instanceof SlottedPlace) {
            slottedPlace = (SlottedPlace) newPlace;
        } else {
            if (legacyActivityMapper == null) {
                throw new IllegalStateException(
                        "Must use SlottedPlace unless LegacyActivityMapper is set");
            }

            slottedPlace = new WrappedPlace(newPlace, legacyActivityMapper);
        }
        goTo(slottedPlace, new SlottedPlace[0]);
    }

    /**
     * Same as {@link #goTo(Place)} except adds the ability to override default places for
     * any of the slots that will be created by the newPlace.
     *
     * @param nonDefaultPlaces array of {@link SlottedPlace}s that should be used instead of the
     * default places defined for the slots.
     */
    public void goTo(SlottedPlace newPlace, SlottedPlace... nonDefaultPlaces) {
        goTo(newPlace, nonDefaultPlaces, reloadAll);
    }

    /**
     * Same as {@link #goTo(SlottedPlace, SlottedPlace...)} except adds the ability to override
     * whether the existing {@link SlottedActivity}s should be refreshed.  The default is that all
     * pages are refreshed, and this method should only be used if you don't want to refresh
     * existing activities.
     *
     * @param reloadAll true if existing activities should be refreshed.
     */
    public void goTo(SlottedPlace newPlace, SlottedPlace[] nonDefaultPlaces, boolean reloadAll) {
	    goToList = "GoTo: " + newPlace;
	    for (SlottedPlace place : nonDefaultPlaces) {
		    goToList += "/" + place;
	    }
	    log.info(goToList);

	    if (root == null) {
		    String token = historyMapper.createToken(newPlace, nonDefaultPlaces);
		    History.newItem(token, false);
	    } else {
		    _goTo(newPlace, nonDefaultPlaces, reloadAll);
	    }
    }

	private void _goTo(SlottedPlace newPlace, SlottedPlace[] nonDefaultPlaces, boolean reloadAll) {
        try {
            Exception maybeGoToException = null;
            if (openNewTab) {
                Window.open(createUrl(newPlace), "_blank", "");
                openNewTab = false;
                openNewWindow = false;

            }else if (openNewWindow) {
                Window.open(createUrl(newPlace), "_blank", openWindowFeatures);
                openNewWindow = false;
                openNewTab = false;

            } else {
                if (processingGoTo) {
                    nextGoToPlace = newPlace;
                    nextGoToNonDefaultPlaces = nonDefaultPlaces;
                    nextGoToReloadAll = reloadAll;

                } else {
                    processingGoTo = true;
                    processingSync = true;
                    mainGoToPlace = newPlace;
                    tokenDone = false;
                    nextGoToPlace = null;
                    nextGoToNonDefaultPlaces = null;
                    nextGoToReloadAll = false;

                    List<SlottedPlace> nonDefaultPlacesList = Arrays.asList(nonDefaultPlaces);
                    indexMultiParentPlaces(newPlace, nonDefaultPlacesList);
                    List<SlottedPlace> hierarchyList = createHierarchyList(newPlace, nonDefaultPlacesList);
                    currentParameters = historyMapper.extractParameters(hierarchyList);

                    if (navigationOverride != null) {
                        List<SlottedPlace> override = navigationOverride.checkOverrides(this, hierarchyList);
                        newPlace = override.get(0);
                        hierarchyList = createHierarchyList(newPlace, Arrays.asList(nonDefaultPlaces));
                        currentParameters = historyMapper.extractParameters(hierarchyList);

                    }

                    ArrayList<String> warnings = new ArrayList<String>();
                    try {
                        root.maybeGoTo(hierarchyList, reloadAll, warnings);
                    } catch (Exception e) {
                        maybeGoToException = e;
                    }

                    boolean constructedCleanup = false;
                    if (warnings.isEmpty() || delegate.confirm(warnings.toArray(new String[warnings.size()]))) {
                        currentHierarchyList = hierarchyList;
                        root.constructStopStart(currentParameters, hierarchyList, reloadAll);
                        constructedCleanup = true;
                    }

                    processingSync = false;
                    asyncGoToCleanup(constructedCleanup);
                }
            }
            if (maybeGoToException != null) {
                throw maybeGoToException;
            }
        } catch (Exception e) {
            handleGoToException(e);
        }
    }

    /**
     * Handles exceptions for GoTo for synchronous and asynchronous calls.
     *
     * @param e The exception that needs to be handled.
     */
    protected void handleGoToException(Throwable e) {
        processingGoTo = false;
        asyncActivities.clear();
        log.log(Level.SEVERE, "Problem while goTo:" + goToList, e);
        SlottedErrorPlace errorPlace = historyMapper.getErrorPlace();
        if (errorPlace != null && !(mainGoToPlace instanceof SlottedErrorPlace)) {
            errorPlace.setException(e);
            goTo(errorPlace);
        } else if (e instanceof SlottedInitException) {
            ((SlottedInitException) e).setGoToList(goToList);
            throw ((SlottedInitException) e);
        } else {
            throw SlottedException.wrap(e);
        }
    }

    /**
     * Cleans up the goTo processing only after all the async Activity request finish.
     *
     * @param constructedCleanup True when new Activities were created.
     */
    protected void asyncGoToCleanup(boolean constructedCleanup) {
        if (!processingSync && asyncActivities.isEmpty()) {
            if (constructedCleanup) {
                LinkedList<SlottedPlace> places = new LinkedList<SlottedPlace>();
                fillPlaces(root, places);

                referringToken = currentToken;
                currentToken = historyMapper.createToken(this);
                tokenDone = true;
                activityCache.clearUnused();
                eventBus.fireEventFromSource(new NewPlacesEvent(places, this), SlottedController.this);
            }

            processingGoTo = false;

            if (!attemptShowViews()) {
                eventBus.fireEventFromSource(new LoadingEvent(true), SlottedController.this);
            }

            if (nextGoToPlace != null) {
                goTo(nextGoToPlace, nextGoToNonDefaultPlaces, nextGoToReloadAll);
            }
        }
    }

    public SlottedDialogController createSlottedDialog(PopupPanel popupPanel, AcceptsOneWidget display) {
        return new SlottedDialogController(this, popupPanel, display);
    }

    private void indexMultiParentPlaces(SlottedPlace newPlace, List<SlottedPlace> nonDefaults) {
        possibleParentPlaces = new LinkedList<SlottedPlace>();
        possibleParentPlaces.add(newPlace);
        possibleParentPlaces.addAll(nonDefaults);
        if (currentHierarchyList != null) {
            possibleParentPlaces.addAll(currentHierarchyList);
        }

        if (newPlace instanceof MultiParentPlace) {
            ((MultiParentPlace) newPlace).indexParentPlace(possibleParentPlaces, false);
        }
        for (SlottedPlace place: nonDefaults) {
            if (place instanceof MultiParentPlace) {
                ((MultiParentPlace) place).indexParentPlace(possibleParentPlaces, false);
            }
        }
    }

    /**
     * Creates a list of all the Places that will be displayed.
     *
     * @param newPlace The place that is being navigated to.
     * @param nonDefaults The list of places that should be used instead of the default places.
     * @return List all/only the Places that will be displayed.
     */
    private List<SlottedPlace> createHierarchyList(SlottedPlace newPlace, List<SlottedPlace> nonDefaults) {
        LinkedList<SlottedPlace> hierarchyList = new LinkedList<SlottedPlace>();

        hierarchyList.add(newPlace);

        addChildPlaces(newPlace, nonDefaults, useExistingChildren, null, hierarchyList);

        // Adding parent Places
        Slot childSlot = newPlace.getParentSlot();
        Slot parentSlot = getActualParentSlot(childSlot);
        while (parentSlot != null) {
            SlottedPlace parentPlace = getPlaceForSlot(parentSlot, nonDefaults, childSlot.getOwnerPlace(), true);
            if (parentPlace != null) {
                hierarchyList.add(parentPlace);
                addChildPlaces(parentPlace, nonDefaults, true, childSlot, hierarchyList);
                childSlot = parentSlot;
                parentSlot = getActualParentSlot(parentSlot);
            } else {
                parentSlot = null;
            }
        }

        return hierarchyList;
    }

    private Slot getActualParentSlot(Slot slot) {
        SlottedPlace actualParentPlace = slot.getOwnerPlace();
        if (actualParentPlace != null) {
            if (actualParentPlace instanceof MultiParentPlace) {
                ((MultiParentPlace) actualParentPlace).indexParentPlace(possibleParentPlaces, true);
            }
            return actualParentPlace.getParentSlot();
        }
        return null;
    }

    private void addChildPlaces(SlottedPlace parentPlace, List<SlottedPlace> nonDefaults,
            boolean useExisting, Slot excludeSlot, List<SlottedPlace> hierarchyList)
    {
        // This makes sure all children Places are reset if the parent not equal to existing
        if (useExisting) {
            ActiveSlot activeSlot = root.findSlot(parentPlace.getParentSlot());
            if (activeSlot == null || !parentPlace.equals(activeSlot.getPlace())) {
                useExisting = false;
            }
        }

        Slot[] slots = parentPlace.getChildSlots();
        if (slots != null) {
            for (Slot childSlot: slots) {
                if (excludeSlot == null || !excludeSlot.equals(childSlot)) {
                    SlottedPlace place = getPlaceForSlot(childSlot, nonDefaults, null, useExisting);
                    if (place instanceof MultiParentPlace) {
                        ((MultiParentPlace) place).setParentSlotIndex(childSlot);
                    }
                    hierarchyList.add(place);
                    addChildPlaces(place, nonDefaults, useExisting, null, hierarchyList);
                }
            }
        }
    }

    /**
     * Gets the SlottedPlace for the passed slot, by first checking the nonDefaults, then the existing ActiveSlots, and
     * finally the Slot's defaultChildPlace.
     *
     * @param slot The slot to get the Place for.
     * @param nonDefaults The list of Places that should be used before existing Places or default Place.
     * @param defaultPlace The defaultPlace that should be used, or null if the slots defaultPlace should be used.
     *                     This is needed when child is known but the parent is unknown, because child's defaultParentPlace,
     *                     might be different then Slot's defaultPlace.
     * @param useExisting if false, the existing place will be skipped and the default will be used.
     */
    private SlottedPlace getPlaceForSlot(Slot slot, List<SlottedPlace> nonDefaults, SlottedPlace defaultPlace,
            boolean useExisting)
    {
        for (SlottedPlace place: nonDefaults) {
            if (defaultPlace != null && place.getClass().equals(defaultPlace.getClass())) {
                //This check is made encase the nonDefaults contains a Place that cause the newPlace not to be displayed.
                return place;
            } else if (slot.equals(place.getParentSlot())) {
                return place;
            }
        }

        if (useExisting) {
            ActiveSlot activeSlot = root.findSlot(slot);
            if (activeSlot != null) {
                SlottedPlace existingPlace = activeSlot.getPlace();
                if (existingPlace != null && (defaultPlace == null || existingPlace.getClass().equals(defaultPlace.getClass()))) {
                    return existingPlace;
                }
            }
        }

        if (defaultPlace != null) {
            return defaultPlace;
        }

        return slot.getDefaultPlace();
    }


    private void fillPlaces(ActiveSlot slot, LinkedList<SlottedPlace> places) {
        places.add(slot.getPlace());
        for (ActiveSlot childSlot : slot.getChildren()) {
            fillPlaces(childSlot, places);
        }
    }

    protected boolean shouldStartActivity() {
        return nextGoToPlace == null;
    }

    /**
     * Returns true if Slotted is processing a goTo() request or the {@link SlottedActivity#setLoadingStarted(Object...)} and the
     * {@link SlottedActivity#setLoadingComplete(Object...)} ()} hasn't been called.
     */
    public boolean isLoading() {
        return root.getFirstBlockingSlot() != null;
    }

    protected void showLoading() {
        ActiveSlot blockingSlot = root.getFirstBlockingSlot();
        if (blockingSlot != null) {
            log.info("Place loading:" + blockingSlot);
            eventBus.fireEventFromSource(new LoadingEvent(true), SlottedController.this);
        }
    }

    /**
     * Called internally to show all the activities if none of them are loading.  Prints warning of the first Activity that
     * is loading.
     *
     * @return Return true if the pages were shown, or false if a loading page is blocking.
     */
    protected boolean attemptShowViews() {
        if (!processingGoTo) {
            ActiveSlot blockingSlot = root.getFirstBlockingSlot();
            if (blockingSlot == null) {
                root.showViews();
                eventBus.fireEventFromSource(new LoadingEvent(false), SlottedController.this);
                return true;
            } else if (blockingSlot.isLoading()) {
                log.info("Waiting for loading Activity:" + blockingSlot.getActivity().getClass());
            }
        }
        return false;
    }

    /**
     * Clones the passed place by converting it to a token, and then parsing the token.  This means any data not tokenized
     * will be lost in the cloning process.
     *
     * @param place The SlottedPlace to clone.
     * @return A new instance of the Place that can be changed without effecting existing hierarchy.
     */
    public <T extends SlottedPlace> T clonePlace(T place) {
        String token = createToken(place);
        SlottedPlace[] places = historyMapper.parseToken(token);
        //noinspection unchecked
        return (T) places[0];
    }

    /**
     * Updates the History with a token as if a goTo() was called, but without processing the goTo().
     *
     * @param newPlace a {@link SlottedPlace} instance to navigate.
     * @param nonDefaultPlaces array of {@link SlottedPlace}s that should be used instead of the
     * default places defined for the slots.
     */
    public void updateToken(SlottedPlace newPlace, SlottedPlace... nonDefaultPlaces) {
        String token = createToken(newPlace, nonDefaultPlaces);
        if (isMainController) {
            History.newItem(token, false);
        }
        referringToken = currentToken;
        currentToken = token;
    }

    /**
     * Creates a full URL which can be used to navigate from anywhere.
     *
     * @param newPlace a {@link SlottedPlace} instance to navigate.
     * @param nonDefaultPlaces array of {@link SlottedPlace}s that should be used instead of the
     * default places defined for the slots.
     */
    public String createUrl(SlottedPlace newPlace, SlottedPlace... nonDefaultPlaces) {
        String url = Document.get().getURL();
        String[] splitUrl = url.split("#");
        String token = createToken(newPlace, nonDefaultPlaces);

        return splitUrl[0] + "#" + token;
    }

    /**
     * Creates a full URL which can be used to navigate from anywhere, but only contains the passed
     * places.
     *
     * Using this token to navigate might cause current non default places
     * to get replaced on refresh, or forward/back navigation.
     *
     * @param newPlace a {@link SlottedPlace} instance to navigate.
     * @param nonDefaultPlaces array of {@link SlottedPlace}s that should be used instead of the
     * default places defined for the slots.
     */
    public String createSimpleUrl(SlottedPlace newPlace, SlottedPlace... nonDefaultPlaces) {
        String url = Document.get().getURL();
        String[] splitUrl = url.split("#");
        String token = createSimpleToken(newPlace, nonDefaultPlaces);

        return splitUrl[0] + "#" + token;
    }

    /**
     * Creates a token with just the passed Places.  This is just the portion after the '#' mark.
     *
     * Using this token to navigate might cause current non default places
     * to get replaced on refresh, or forward/back navigation.
     *
     * @param newPlace a {@link SlottedPlace} instance to navigate.
     * @param nonDefaultPlaces array of {@link SlottedPlace}s that should be used instead of the
     * default places defined for the slots.
     */
    public String createSimpleToken(SlottedPlace newPlace, SlottedPlace... nonDefaultPlaces) {
        PlaceParameters placeParameters = new PlaceParameters();
        newPlace.extractParameters(placeParameters);
        String token = historyMapper.createToken(newPlace, nonDefaultPlaces);
        return token;
    }

    /**
     * Creates a token that would result from a goTo() call, but without doing the navigation.
     *
     * @param newPlace a {@link SlottedPlace} instance to navigate.
     * @param nonDefaultPlaces array of {@link SlottedPlace}s that should be used instead of the
     * default places defined for the slots.
     */
    public String createToken(SlottedPlace newPlace, SlottedPlace... nonDefaultPlaces) {
        List<SlottedPlace> hierarchyList = createHierarchyList(newPlace, Arrays.asList(nonDefaultPlaces));
        hierarchyList.remove(0);

        String token = historyMapper.createToken(newPlace, hierarchyList.toArray(new SlottedPlace[hierarchyList.size()]));
        return token;
    }

    /**
     * Returns the root {@link ActiveSlot}.
     *
     * @return a {@link ActiveSlot} instance.
     */
    public ActiveSlot getRoot() {
        return root;
    }

    /**
     * Gets the Place for the specified Slot.
     *
     * @param slot The Slot returned by the getParentSlot() method of the Place.
     * @param <T> Used to prevent casting.
     * @return The Place requested, or null if that type is not in the hierarchy.
     */
    @SuppressWarnings("unchecked")
    public <T extends Place> T getCurrentPlace(Slot slot) {
        for (SlottedPlace place: currentHierarchyList) {
            if (place.getParentSlot() == slot) {
                return (T) place;
            }
        }
        return null;
    }

	/**
	 * Gets the current place hierarchy list.
	 *
	 * @return The all the Places currently shown.
	 */
	public List<SlottedPlace> getCurrentPlaces() {
		return Collections.unmodifiableList(currentHierarchyList);
	}

    /**
     * Gets a Place of the specified type in the hierarchy.  This can be used to get a parent Place, child Place, or
     * any Place that might be displayed.
     *
     * @param placeType The Class object of the Place you want to get.
     * @param <T> Used to prevent casting.
     * @return The Place requested, or null if that type is not in the hierarchy.
     */
    @SuppressWarnings("unchecked")
    public <T extends Place> T getCurrentPlace(Class<T> placeType) {
        for (SlottedPlace place: currentHierarchyList) {
            if (place.instanceOf(placeType)) {
                return (T) place;
            }
        }
        return null;
    }

    /**
     * Gets a the Activity for the specified Slot.
     * WARNING: This can't be used to get the child activity during the start().  The child
     * activity hasn't been created yet.
     *
     * @param slot The Slot object of the Place.
     * @return The Activity requested, or null if that type is not in the hierarchy.
     */
    @SuppressWarnings("unchecked")
    public Activity getCurrentActivity(Slot slot) {
        Place place = getCurrentPlace(slot);
        if (place != null  && place instanceof SlottedPlace) {
            Class placeType = place.getClass();
            List<Activity> activities = activityCache.get(placeType);
            if (activities.isEmpty()) {
                return null;
            } else {
                return activities.get(0);
            }
        }
        return null;
    }

    /**
     * Gets a the Activity of the specified type in the hierarchy.
     * WARNING: This can't be used to get the child activity during the start().  The child
     * activity hasn't been created yet.
     *
     * @param type The Class object of the Activity you want to get.
     * @param <T> Used to prevent casting.
     * @return The Activity requested, or null if that type is not in the hierarchy.
     */
    @SuppressWarnings("unchecked")
    public <T extends Activity> T getCurrentActivity(Class<T> type) {
        return (T) activityCache.getByActivity(type);
    }

    /**
     * Gets a the active Activity for the specified Place type in the hierarchy.
     * WARNING: This can't be used to get the child activity during the start().  The child
     * activity hasn't been created yet.
     *
     * @param placeType The Class object of the Activity you want to get.
     * @return The Activity requested, or null if that type is not in the hierarchy.
     */
    @SuppressWarnings("unchecked")
    public Activity getCurrentActivityByPlace(Class<? extends SlottedPlace> placeType) {
        List<Activity> activities = activityCache.get(placeType);
        if (activities.isEmpty()) {
            return null;
        } else {
            return activities.get(0);
        }
    }

    /**
     * Returns the {@link PlaceParameters} that used to display the current state.
     *
     * @return a {@link PlaceParameters} instance.
     */
    public PlaceParameters getCurrentParameters() {
        return currentParameters;
    }

    /**
     * Returns the EventBus used for all events in the slotted framework.
     */
    public EventBus getEventBus() {
        return eventBus;
    }

    /**
     * Returns the HistoryMapper used for processing history tokens.
     */
    public HistoryMapper getHistoryMapper() {
        return historyMapper;
    }

    /**
     * Gets the Delegate used when navigation is stopped by mayStop().
     */
    public Delegate getDelegate() {
        return delegate;
    }

    /**
     * Returns the GWT's ActivityMapper used to create Activities, or null if none was set.
     */
    public ActivityMapper getLegacyActivityMapper() {
        return legacyActivityMapper;
    }

    protected ActivityCache getActivityCache() {
        return activityCache;
    }
}
