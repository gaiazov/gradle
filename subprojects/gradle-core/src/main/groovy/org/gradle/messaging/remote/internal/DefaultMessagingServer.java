/*
 * Copyright 2010 the original author or authors.
 *
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
 */
package org.gradle.messaging.remote.internal;

import org.gradle.messaging.concurrent.AsyncStoppable;
import org.gradle.messaging.concurrent.CompositeStoppable;
import org.gradle.messaging.remote.MessagingServer;
import org.gradle.messaging.remote.ObjectConnection;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicReference;

public class DefaultMessagingServer implements MessagingServer {
    private final MultiChannelConnector connector;
    private final ClassLoader classLoader;
    private final Set<ObjectConnection> connections = new CopyOnWriteArraySet<ObjectConnection>();

    public DefaultMessagingServer(MultiChannelConnector connector, ClassLoader classLoader) {
        this.connector = connector;
        this.classLoader = classLoader;
    }

    public ObjectConnection createUnicastConnection() {
        final MultiChannelConnection<Message> messageConnection = connector.listen();
        IncomingMethodInvocationHandler incoming = new IncomingMethodInvocationHandler(classLoader, messageConnection);
        OutgoingMethodInvocationHandler outgoing = new OutgoingMethodInvocationHandler(messageConnection);
        final AtomicReference<ObjectConnection> connectionRef = new AtomicReference<ObjectConnection>();
        AsyncStoppable stopControl = new AsyncStoppable() {
            public void requestStop() {
                messageConnection.requestStop();
            }

            public void stop() {
                try {
                    messageConnection.stop();
                } finally {
                    connections.remove(connectionRef.get());
                }
            }
        };

        DefaultObjectConnection connection = new DefaultObjectConnection(messageConnection, stopControl, outgoing,
                incoming);
        connectionRef.set(connection);
        connections.add(connection);
        return connection;
    }

    public void stop() {
        for (ObjectConnection connection : connections) {
            connection.requestStop();
        }
        try {
            new CompositeStoppable(connections).stop();
        } finally {
            connections.clear();
        }
    }
}
