package org.sputnikdev.bluetooth.manager.impl;

/*-
 * #%L
 * org.sputnikdev:bluetooth-manager
 * %%
 * Copyright (C) 2017 Sputnik Dev
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sputnikdev.bluetooth.URL;
import org.sputnikdev.bluetooth.manager.GovernorListener;
import org.sputnikdev.bluetooth.manager.GovernorState;
import org.sputnikdev.bluetooth.manager.NotReadyException;
import org.sputnikdev.bluetooth.manager.transport.BluetoothObject;

import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * A root class for all governors in the system. Defines lifecycle and error handling/recovery processes for governors.
 *
 * <p>The lifecycle is divided on the following stages:
 *
 * <p><ul>
 *     <li>Native object acquisition</li>
 *     <li>Error handling and recovery</li>
 *     <li>Governing/maintaining state of the bluetooth object</li>
 * </ul>
 *
 * <p>All the stages above are handled in {@link #update()} method. When invoked, this method tries to acquire
 * a native object for corresponding device first. When acquired, {@link #init(BluetoothObject)} method is called
 * to perform initialisation (setting initial state, subscribing to the object events), then all registered governor
 * listeners are notified that the governor is "ready" by triggering {@link GovernorListener#ready(boolean)} method.
 * Once initialised, the {@link #update()} method switches to the next stage - maintaining state of the bluetooth
 * object by invoking {@link #update(BluetoothObject)} method, this is where main manipulations with
 * the bluetooth object are happening. If any exception occurs in the {@link #update(BluetoothObject)} method,
 * the Error handling stage begins by triggering {@link #reset(BluetoothObject)} method, which must revert
 * internal state of the governor to the initial state - the Native object acquisition.
 *
 * <p>In short this looks like that:
 *
 * <p>The update method is called outside of the governor (by a separate thread):
 * governor.update();
 *
 * <p>then the following happens:
 *
 * {@link #init(BluetoothObject)}
 * {@link GovernorListener#ready(boolean)} with argument - true
 * {@link #update(BluetoothObject)}
 *
 * <p>if the {@link #update(BluetoothObject)} method throws any exception, then
 * {@link #reset(BluetoothObject)}
 * {@link GovernorListener#ready(boolean)} with argument - false
 * are invoked, which brings the governor to its initial state, where everything begins from the start.
 *
 * <p>In order to help to release resources (native objects) {@link #reset(BluetoothObject)} method is used,
 * which must release any acquired resources, disconnect and unsubscribe from notifications.
 * It is recommended to call this method when, for example, a program exists or in any similar cases.
 *
 * @author Vlad Kolotov
 */
abstract class AbstractBluetoothObjectGovernor<T extends BluetoothObject> implements BluetoothObjectGovernor {

    private Logger logger = LoggerFactory.getLogger(AbstractBluetoothObjectGovernor.class);

    protected final BluetoothManagerImpl bluetoothManager;
    protected final URL url;
    private T bluetoothObject;
    private String transport;
    private Date lastActivity;
    private Date lastActivityNotified;
    private final List<GovernorListener> governorListeners = new CopyOnWriteArrayList<>();
    private GovernorState state = GovernorState.NEW;

    private final ReentrantLock updateLock = new ReentrantLock();

    AbstractBluetoothObjectGovernor(BluetoothManagerImpl bluetoothManager, URL url) {
        this.bluetoothManager = bluetoothManager;
        this.url = url;
    }

    @Override
    public URL getURL() {
        return url;
    }

    @Override
    public boolean isReady() {
        return bluetoothObject != null;
    }

    @Override
    public void addGovernorListener(GovernorListener listener) {
        governorListeners.add(listener);
    }

    @Override
    public void removeGovernorListener(GovernorListener listener) {
        governorListeners.remove(listener);
    }

    @Override
    public Date getLastActivity() {
        return lastActivity;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof AbstractBluetoothObjectGovernor)) {
            return false;
        }
        AbstractBluetoothObjectGovernor<?> that = (AbstractBluetoothObjectGovernor<?>) o;
        return Objects.equals(url, that.url);
    }

    @Override
    public int hashCode() {
        return Objects.hash(url);
    }

    public String getTransport() {
        return transport;
    }

    @Override
    public void init() {
        update();
    }

    @Override
    public void update() {
        if (state != GovernorState.DISPOSED) {
            logger.debug("Updating governor. Trying to acquire lock: {}", url);
            boolean updated = false;
            T object = null;
            if (updateLock.tryLock()) {
                try {
                    logger.trace("Lock acquired. Getting a native object: {}", url);
                    object = getOrFindBluetoothObject();
                    if (object == null) {
                        logger.trace("Native object is not available: {}", url);
                        return;
                    }
                    logger.trace("Performing update with the native object: {} / {}",
                            url, Integer.toHexString(object.hashCode()));
                    update(object);
                    logger.debug("Governor has been updated: {}", url);
                    updated = true;
                } catch (Exception ex) {
                    logger.warn("Error occurred while updating governor: {} / {} : {}",
                            url, object != null ? Integer.toHexString(object.hashCode()) : null, ex.getMessage());
                    reset();
                } finally {
                    logger.trace("Unlocking update (update) lock: {}", url);
                    updateLock.unlock();
                }
                if (updated) {
                    notifyLastChanged();
                }
            } else {
                // looks like the bluetooth manager is performing an update of this governor already,
                // therefore no need to run another update, let's wait until the bluetooth manager finishes its update
                logger.debug("Lock could not be acquired (governor is being updated). Skipping the update.");
                updateLock.lock();
                updateLock.unlock();
            }
        }
    }

    public void reset() {
        if (state != GovernorState.RESET && state != GovernorState.DISPOSED) {
            state = GovernorState.RESET;
            logger.debug("Resetting governor. Descendants first: {}", url);
            bluetoothManager.resetDescendants(url);
            try {
                if (bluetoothObject != null) {
                    forceReset(bluetoothObject);
                }
                bluetoothObject = null;
                logger.debug("Governor has been reset: {}", url);
            } catch (Exception ex) {
                logger.debug("Error occurred while resetting governor {}: {}", url, ex.getMessage());
            }
        }
    }

    @Override
    public void dispose() {
        if (state != GovernorState.DISPOSED) {
            logger.debug("Disposing governor: {}", url);
            reset();
            state = GovernorState.DISPOSED;
            governorListeners.clear();
        }
    }

    protected void scheduleUpdate() {
        bluetoothManager.scheduleUpdate(this);
    }

    protected <R> R interact(String name, Function<T, R> delegate) {
        try {
            T object = getBluetoothObject();
            logger.trace("Interacting with native object ({}): {} / {}",
                    name, url, Integer.toHexString(object.hashCode()));
            R result = delegate.apply(object);
            logger.trace("Interaction completed ({}): {} / {}", name, url, Integer.toHexString(object.hashCode()));
            updateLastChanged();
            return result;
        } catch (Exception ex) {
            boolean locked = updateLock.isLocked();
            logger.warn("Error occurred while interacting ({}) with native object: {} : {}", name, url, locked);
            // no need to reset if it is locked in the update or reset method
            //TODO decide if it is needed
            //if (!locked) {
            reset();
            //}
            throw ex;
        }
    }

    protected void interact(String name, Consumer<T> delegate) {
        interact(name, (Function<T, Void>) object -> {
            delegate.accept(object);
            return null;
        });
    }

    private T getBluetoothObject() throws NotReadyException {
        logger.trace("Getting native object. Checking if governor is ready: {}", url);
        if (!isReady()) {
            logger.trace("Governor is not ready. Trying to perform an explicit update: {}", url);
            // the governor is not ready, trying to update it
            update();
            logger.trace("Checking if governor is ready after the explicit update: {}", url);
            if (!isReady()) {
                // still not ready even after the update?
                throw new NotReadyException("Bluetooth object is not ready: " + url);
            }
        }
        logger.trace("Returning native object: {} / {}", url,
                bluetoothObject != null ? Integer.toHexString(bluetoothObject.hashCode()) : null);
        return bluetoothObject;
    }

    abstract void init(T object);

    abstract void update(T object);

    abstract void reset(T object);

    void updateLastChanged() {
        lastActivity = new Date();
    }

    void notifyReady(boolean ready) {
        BluetoothManagerUtils.safeForEachError(governorListeners, listener -> listener.ready(ready), logger,
                "Execution error of a governor listener: ready");
        bluetoothManager.notifyGovernorReady(this, ready);
    }

    void notifyLastChanged() {
        Date lastChanged = lastActivity;
        if (lastChanged != null && !lastChanged.equals(lastActivityNotified)) {
            BluetoothManagerUtils.safeForEachError(governorListeners, listener -> listener
                            .lastUpdatedChanged(lastChanged), logger,
                    "Execution error of a governor listener: last changed");
            lastActivityNotified = lastChanged;
        }
    }

    private T getOrFindBluetoothObject() {
        logger.trace("Acquiring native object: {}", url);
        if (bluetoothObject == null) {
            logger.trace("Native object is null. Trying to get a new native object from manager: {}", url);
            bluetoothObject = bluetoothManager.getBluetoothObject(
                    transport != null ? url.copyWithProtocol(transport) : url);
            if (bluetoothObject != null) {
                logger.debug("A new native object has been acquired: {}", url);
                // update internal cache so that next time acquiring "native" object will be faster
                transport = bluetoothObject.getURL().getProtocol();
                try {
                    logger.debug("Initializing governor with the new native object: {}", url);
                    init(bluetoothObject);
                    logger.trace("Initialization succeeded: {}", url);
                    state = GovernorState.READY;
                    notifyReady(true);
                } catch (Exception ex) {
                    logger.warn("Error occurred while initializing governor with a new native object: {} : {}",
                            url, ex.getMessage());
                    throw ex;
                }
            }
        }
        logger.trace("Returning native object: {}", url);
        return bluetoothObject;
    }

    private void forceReset(T bluetoothObject) {
        try {
            logger.trace("Resetting native object: {} / {}", url, Integer.toHexString(bluetoothObject.hashCode()));
            reset(bluetoothObject);
        } catch (Exception ex) {
            logger.trace("Could not reset bluetooth object {}: {}", url, ex.getMessage());
        }
        notifyReady(false);
        try {
            logger.trace("Disposing native object: {} / {}", url, Integer.toHexString(bluetoothObject.hashCode()));
            bluetoothObject.dispose();
        } catch (Exception ex) {
            logger.trace("Could not dispose bluetooth object {}: {}", url, ex.getMessage());
        }
    }

}
