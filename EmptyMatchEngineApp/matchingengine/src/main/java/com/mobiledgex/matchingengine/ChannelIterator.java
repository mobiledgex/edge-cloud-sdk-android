/**
 * Copyright 2018-2020 MobiledgeX, Inc. All rights and licenses reserved.
 * MobiledgeX, Inc. 156 2nd Street #408, San Francisco, CA 94105
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.mobiledgex.matchingengine;

import java.util.Iterator;

import io.grpc.ManagedChannel;

/**
 * Simple Iterator wrapper that keeps a GRPC channel reference alive to read data from that channel.
 * This holds a channel resource until no longer referenced.
 * @param <T>
 */
public class ChannelIterator<T> implements Iterator<T> {

    private ManagedChannel mManagedChannel;
    private Iterator<T> mIterator;

    public ChannelIterator (ManagedChannel channel, Iterator<T> iterator) {
        mManagedChannel = channel;
        mIterator = iterator;
    }

    @Override
    public boolean hasNext() {
        return mIterator.hasNext();
    }

    @Override
    public T next() {
        return mIterator.next();
    }

    @Override
    public void remove() {
        mIterator.remove();
    }

    /**
     * Shutdown the channel.
     */
    public void shutdown() {
        mManagedChannel.shutdown();
    }
}
