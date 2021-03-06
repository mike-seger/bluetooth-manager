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

package org.sputnikdev.bluetooth.manager.impl;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongUnaryOperator;

/**
 * A utility class that can accommodate 63 boolean flags. It is similar to {@link java.util.BitSet}
 * but synchronized and provides some "atomic" utility methods for tracking changes.
 * @author Vlad Kolotov
 */
class ConcurrentBitMap {

    private final AtomicLong bits = new AtomicLong();

    /**
     * Sets a new cumulative state for the bitmap field.
     * @param index index of the new state
     * @param newState value of the new state
     */
    void cumulativeSet(int index, boolean newState) {
        cumulativeSet(index, newState, null, null);
    }

    /**
     * Sets a new cumulative state for the bitmap field.
     * @param index index of the new state
     * @param newState value of the new state
     * @param changed triggered if the overall state changes
     */
    void cumulativeSet(int index, boolean newState, Runnable changed) {
        cumulativeSet(index, newState, changed, null);
    }

    /**
     * Sets a new exclusive state for the bitmap field.
     * @param index index of the new state
     * @param newState value of the new state
     */
    void exclusiveSet(int index, boolean newState) {
        exclusiveSet(index, newState, null, null);
    }

    /**
     * Sets a new exclusive state for the bitmap field.
     * @param index index of the new state
     * @param newState value of the new state
     * @param changed triggered if the overall state changes
     */
    void exclusiveSet(int index, boolean newState, Runnable changed) {
        exclusiveSet(index, newState, changed, null);
    }

    /**
     * Sets a new cumulative state for the bitmap field.
     * @param index index of the new state
     * @param newState value of the new state
     * @param changed triggered if the overall state changes
     * @param notChanged triggered if the overall state does not change
     */
    void cumulativeSet(int index, boolean newState, Runnable changed, Runnable notChanged) {
        if (index < 0 || index > 63) {
            throw new IllegalStateException("Invalid index, must be between 0 and 63: " + index);
        }
        set(current -> {
            return newState ? current | (1 << index) : current & ~(1 << index);
        }, changed, notChanged);
    }

    /**
     * Sets a new exclusive state for the bitmap field.
     * @param index index of the new state
     * @param newState value of the new state
     * @param changed triggered if the overall state changes
     * @param notChanged triggered if the overall state does not change
     */
    void exclusiveSet(int index, boolean newState, Runnable changed, Runnable notChanged) {
        if (index < 0 || index > 63) {
            throw new IllegalStateException("Invalid index, must be between 0 and 63: " + index);
        }
        set(current -> {
            return newState ? (1 << index) : current & ~(1 << index);
        }, changed, notChanged);
    }

    /**
     * Returns cumulative value (if any of bits is set to 1).
     * @return true if any of bits is set to 1, false otherwise
     */
    boolean get() {
        return bits.get() > 0;
    }

    /**
     * Returns the one bit index. If multiple one bits found, an IllegalStateException is thrown.
     * If no one bits found, -1 returned.
     * @return one bit index
     */
    int getUniqueIndex() {
        long state = bits.get();
        if (Long.bitCount(state) > 1) {
            throw new IllegalStateException("Multiple one bits found");
        }
        return Long.numberOfTrailingZeros(state);
    }

    private void set(LongUnaryOperator operator, Runnable changed, Runnable notChanged) {
        synchronized (bits) {
            long oldState = bits.getAndUpdate(operator);
            if (bits.get() > 0 && oldState == 0 || bits.get() == 0 && oldState > 0) {
                if (changed != null) {
                    changed.run();
                }
            } else if (notChanged != null) {
                notChanged.run();
            }
        }
    }

}
