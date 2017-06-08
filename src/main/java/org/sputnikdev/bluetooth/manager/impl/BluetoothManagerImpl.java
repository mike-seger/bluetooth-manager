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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sputnikdev.bluetooth.URL;
import org.sputnikdev.bluetooth.manager.AdapterDiscoveryListener;
import org.sputnikdev.bluetooth.manager.BluetoothManager;
import org.sputnikdev.bluetooth.manager.DeviceDiscoveryListener;
import org.sputnikdev.bluetooth.manager.DiscoveredAdapter;
import org.sputnikdev.bluetooth.manager.DiscoveredDevice;


/**
 *
 * @author Vlad Kolotov
 */
class BluetoothManagerImpl implements BluetoothManager {

    private static final int DISCOVERY_RATE_SEC = 10;

    private Logger logger = LoggerFactory.getLogger(BluetoothManagerImpl.class);

    private final ScheduledExecutorService singleThreadScheduler = Executors.newSingleThreadScheduledExecutor();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(5);
    private final Set<DeviceDiscoveryListener> deviceDiscoveryListeners = new HashSet<>();
    private final Set<AdapterDiscoveryListener> adapterDiscoveryListeners = new HashSet<>();

    private final Map<URL, BluetoothObjectGovernor> governors = new HashMap<>();

    private ScheduledFuture discoveryFuture;
    private final Map<URL, ScheduledFuture> governorFutures = new HashMap<>();

    private final Set<DiscoveredDevice> discoveredDevices = new HashSet<>();
    private final Set<DiscoveredAdapter> discoveredAdapters = new HashSet<>();


    private boolean startDiscovering;
    private int discoveryRate = DISCOVERY_RATE_SEC;
    private boolean rediscover = false;

    @Override
    public synchronized void start(boolean startDiscovering) {
        if (discoveryFuture == null) {
            this.startDiscovering = startDiscovering;
            discoveryFuture = singleThreadScheduler.scheduleAtFixedRate(
                    new DiscoveryJob(), 0, discoveryRate, TimeUnit.SECONDS);
        }
    }

    @Override
    public synchronized void stop() {
        if (discoveryFuture != null) {
            discoveryFuture.cancel(true);
        }
    }

    @Override
    public void addDeviceDiscoveryListener(DeviceDiscoveryListener deviceDiscoveryListener) {
        deviceDiscoveryListeners.add(deviceDiscoveryListener);
    }

    @Override
    public void removeDeviceDiscoveryListener(DeviceDiscoveryListener deviceDiscoveryListener) {
        deviceDiscoveryListeners.remove(deviceDiscoveryListener);
    }

    @Override
    public void addApterDiscoveryListener(AdapterDiscoveryListener adapterDiscoveryListener) {
        adapterDiscoveryListeners.add(adapterDiscoveryListener);
    }

    @Override
    public void removeAdapterDiscoveryListener(AdapterDiscoveryListener adapterDiscoveryListener) {
        adapterDiscoveryListeners.remove(adapterDiscoveryListener);
    }

    @Override
    public void disposeGovernor(URL url) {
        synchronized (governors) {
            if (governors.containsKey(url)) {
                governors.get(url).dispose();
                synchronized (governorFutures) {
                    if (governorFutures.containsKey(url)) {
                        governorFutures.get(url).cancel(true);
                        governorFutures.remove(url);
                    }
                }
                governors.remove(url);
            }
        }
    }

    @Override
    public DeviceGovernorImpl getDeviceGovernor(URL url) {
        return (DeviceGovernorImpl) getGovernor(url.getDeviceURL());
    }
    @Override
    public AdapterGovernorImpl getAdapterGovernor(URL url) {
        return (AdapterGovernorImpl) getGovernor(url.getAdapterURL());
    }
    @Override
    public CharacteristicGovernorImpl getCharacteristicGovernor(URL url) {
        return (CharacteristicGovernorImpl) getGovernor(url.getCharacteristicURL());
    }

    @Override
    public void dispose() {
        logger.info("Disposing Bluetooth manager");

        singleThreadScheduler.shutdown();
        scheduler.shutdown();
        if (discoveryFuture != null) {
            discoveryFuture.cancel(true);
        }
        for (ScheduledFuture future : Sets.newHashSet(governorFutures.values())) {
            future.cancel(true);
        }
        deviceDiscoveryListeners.clear();
        adapterDiscoveryListeners.clear();

        synchronized (governors) {
            for (BluetoothObjectGovernor governor : governors.values()) {
                try {
                    governor.dispose();
                } catch (Exception ex) {
                    logger.error("Could not dispose governor: " + governor.getURL());
                }
            }
            governors.clear();
        }
        logger.info("Bluetooth service has been disposed");
    }

    @Override
    public Set<DiscoveredDevice> getDiscoveredDevices() {
        synchronized (discoveredDevices) {
            return Collections.unmodifiableSet(discoveredDevices);
        }
    }

    @Override
    public Set<DiscoveredAdapter> getDiscoveredAdapters() {
        synchronized (discoveredAdapters) {
            return Collections.unmodifiableSet(discoveredAdapters);
        }
    }

    @Override
    public BluetoothObjectGovernor getGovernor(URL url) {
        synchronized (governors) {
            if (!governors.containsKey(url)) {
                BluetoothObjectGovernor governor = createGovernor(url);

                update(governor);

                governors.put(url, governor);
                governorFutures.put(url,
                        scheduler.scheduleAtFixedRate((Runnable) () -> update(governor), 5, 5, TimeUnit.SECONDS));

                return governor;
            }
            return governors.get(url);
        }
    }

    @Override
    public void setDiscoveryRate(int seconds) {
        this.discoveryRate = seconds;
    }

    @Override
    public void setRediscover(boolean rediscover) {
        this.rediscover = rediscover;
    }

    List<BluetoothObjectGovernor> getGovernors(List<? extends BluetoothObject> objects) {
        List<BluetoothObjectGovernor> result = new ArrayList<>(objects.size());
        synchronized (governors) {
            for (BluetoothObject object : objects) {
                result.add(getGovernor(object.getURL()));
            }
        }
        return Collections.unmodifiableList(result);
    }

    void updateDescendants(URL parent) {
        synchronized (governors) {
            for (BluetoothObjectGovernor governor : governors.values()) {
                if (governor.getURL().isDescendant(parent)) {
                    update(governor);
                }
            }
        }
    }

    void resetDescendants(URL parent) {
        synchronized (governors) {
            for (BluetoothObjectGovernor governor : governors.values()) {
                if (governor.getURL().isDescendant(parent)) {
                    governor.reset();
                }
            }
        }
    }


    private BluetoothObjectGovernor createGovernor(URL url) {
        if (url.isAdapter()) {
            return new AdapterGovernorImpl(this, url);
        } else if (url.isDevice()) {
            return new DeviceGovernorImpl(this, url);
        } else if (url.isCharacteristic()) {
            return new CharacteristicGovernorImpl(this, url);
        }
        throw new IllegalStateException("Unknown url");
    }

    private void notifyDeviceDiscovered(DiscoveredDevice device) {
        if (this.discoveredDevices.contains(device) && !this.rediscover) {
            return;
        }
        for (DeviceDiscoveryListener deviceDiscoveryListener : Lists.newArrayList(deviceDiscoveryListeners)) {
            try {
                deviceDiscoveryListener.discovered(device);
            } catch (Exception ex) {
                logger.error("Discovery listener error (device)", ex);
            }
        }
    }

    private void notifyAdapterDiscovered(DiscoveredAdapter adapter) {
        if (this.discoveredAdapters.contains(adapter) && !this.rediscover) {
            return;
        }
        for (AdapterDiscoveryListener adapterDiscoveryListener : Lists.newArrayList(adapterDiscoveryListeners)) {
            try {
                adapterDiscoveryListener.discovered(adapter);
            } catch (Exception ex) {
                logger.error("Discovery listener error (adapter)", ex);
            }
        }
    }

    private DiscoveredDevice getDiscoveredDevice(Device device) {
        URL url = device.getURL();
        return new DiscoveredDevice(new URL(url.getAdapterAddress(), url.getDeviceAddress()),
                device.getName(), device.getAlias(), device.getRSSI(),
                device.getBluetoothClass());
    }

    private DiscoveredAdapter getDiscoveredAdapter(Adapter adapter) {
        return new DiscoveredAdapter(new URL(adapter.getURL().getAdapterAddress(), null),
                adapter.getName(), adapter.getAlias());
    }

    private void handleDeviceLost(URL url) {
        logger.info("Device has been lost: " + url);
        for (DeviceDiscoveryListener deviceDiscoveryListener : Lists.newArrayList(deviceDiscoveryListeners)) {
            try {
                deviceDiscoveryListener.lost(url);
            } catch (Throwable ex) {
                logger.error("Device listener error", ex);
            }
        }
        try {
            getDeviceGovernor(url).reset();
        } catch (Throwable ex) {
            logger.warn("Could not reset device governor", ex);
        }
    }

    private void handleAdapterLost(URL url) {
        logger.info("Adapter has been lost: " + url);
        for (AdapterDiscoveryListener adapterDiscoveryListener : Lists.newArrayList(adapterDiscoveryListeners)) {
            try {
                adapterDiscoveryListener.lost(url);
            } catch (Throwable ex) {
                logger.error("Adapter listener error", ex);
            }
        }
        try {
            getAdapterGovernor(url).reset();
        } catch (Throwable ex) {
            logger.warn("Could not reset adapter governor", ex);
        }
    }

    private void update(BluetoothObjectGovernor governor) {
        try {
            governor.update();
        } catch (Throwable ex) {
            logger.error("Could not update governor: " + governor.getURL(), ex);
        }
    }

    private class DiscoveryJob implements Runnable {

        @Override
        public void run() {
            discoverAdapters();
            discoverDevices();
        }

        private void discoverDevices() {
            try {
                synchronized (discoveredDevices) {
                    List<Device> list = BluetoothObjectFactory.getDefault().getDiscoveredDevices();
                    if (list == null) {
                        return;
                    }

                    Set<DiscoveredDevice> newDiscovery = new HashSet<>();
                    for (Device device : list) {
                        short rssi = device.getRSSI();
                        if (rssi == 0) {
                            continue;
                        }
                        DiscoveredDevice discoveredDevice = getDiscoveredDevice(device);
                        notifyDeviceDiscovered(discoveredDevice);
                        newDiscovery.add(discoveredDevice);
                    }
                    for (DiscoveredDevice lost : Sets.difference(discoveredDevices, newDiscovery)) {
                        handleDeviceLost(lost.getURL());
                    }
                    discoveredDevices.clear();
                    discoveredDevices.addAll(newDiscovery);
                }
            } catch (Exception ex) {
                logger.error("Device discovery job error", ex);
            }
        }

        private void discoverAdapters() {
            try {
                synchronized (discoveredAdapters) {
                    Set<DiscoveredAdapter> newDiscovery = new HashSet<>();
                    for (Adapter adapter : BluetoothObjectFactory.getDefault().getDiscoveredAdapters()) {
                        DiscoveredAdapter discoveredAdapter = getDiscoveredAdapter(adapter);
                        notifyAdapterDiscovered(discoveredAdapter);
                        newDiscovery.add(discoveredAdapter);
                        if (startDiscovering) {
                            // create (if not created before) adapter governor which will trigger its discovering status
                            // (by default when it is created "discovering" flag is set to true)
                            getAdapterGovernor(adapter.getURL());
                        }
                    }
                    for (DiscoveredAdapter lost : Sets.difference(discoveredAdapters, newDiscovery)) {
                        handleAdapterLost(lost.getURL());
                    }
                    discoveredAdapters.clear();
                    discoveredAdapters.addAll(newDiscovery);
                }
            } catch (Exception ex) {
                logger.error("Adapter discovery job error", ex);
            }
        }
    }

}