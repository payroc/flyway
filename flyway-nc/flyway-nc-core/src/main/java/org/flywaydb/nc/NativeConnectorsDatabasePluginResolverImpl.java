/*-
 * ========================LICENSE_START=================================
 * flyway-nc-core
 * ========================================================================
 * Copyright (C) 2010 - 2026 Red Gate Software Ltd
 * ========================================================================
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
 * =========================LICENSE_END==================================
 */
package org.flywaydb.nc;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.configuration.Configuration;
import org.flywaydb.core.internal.jdbc.JdbcUtils;
import org.flywaydb.core.internal.nc.NativeConnectorsDatabase;
import org.flywaydb.core.internal.plugin.PluginRegister;
import org.flywaydb.core.internal.util.Pair;

public class NativeConnectorsDatabasePluginResolverImpl implements NativeConnectorsDatabasePluginResolver {

    private final PluginRegister pluginRegister;

    public NativeConnectorsDatabasePluginResolverImpl(final PluginRegister pluginRegister) {
        this.pluginRegister = pluginRegister;
    }

    @Override
    public Optional<NativeConnectorsDatabase> resolve(final Configuration configuration) {
        return resolve(configuration.getUrl()).stream().findFirst();
    }

    @Override
    public Optional<NativeConnectorsDatabase> resolveAndVerify(final Configuration configuration) {
        final List<NativeConnectorsDatabase> databases = resolve(configuration.getUrl());

        if (databases.isEmpty()) {
            return Optional.empty();
        }

        final Lazy<Connection> connection = new Lazy<>(() -> JdbcUtils.openConnection(configuration.getDataSource(),
            configuration.getConnectRetries(),
            configuration.getConnectRetriesInterval()));
        final Optional<NativeConnectorsDatabase> result = databases.stream()
            .filter(database -> handlesConnection(database, connection))
            .findFirst();

        if (connection.isInitialized()) {
            JdbcUtils.closeConnection(connection.get());
        }

        return result;
    }

    private List<NativeConnectorsDatabase> resolve(final String url) {
        return pluginRegister.getInstancesOf(NativeConnectorsDatabase.class)
            .stream()
            .map(p -> Pair.of(p.supportsUrl(url), p))
            .filter(p -> p.getLeft().isSupported())
            .sorted(Comparator.comparing(x -> x.getLeft().priority(), Comparator.reverseOrder()))
            .map(Pair::getRight)
            .toList();
    }

    private static boolean handlesConnection(final NativeConnectorsDatabase database,
        final Lazy<? extends Connection> connection) {
        if (!(database instanceof final NativeConnectorsJdbc jdbcDatabase)) {
            return true;
        }

        try {
            final String databaseProductName = connection.get().getMetaData().getDatabaseProductName();
            if (jdbcDatabase.handlesProductName(connection.get(), databaseProductName)) {
                return true;
            }
        } catch (final SQLException e) {
            throw new FlywayException(e);
        }

        return false;
    }

    private static class Lazy<T> {
        private T value;
        private final Supplier<? extends T> supplier;

        Lazy(final Supplier<? extends T> supplier) {
            this.supplier = supplier;
        }

        T get() {
            if (!isInitialized()) {
                try {
                    value = supplier.get();
                } catch (final Exception e) {
                    throw new FlywayException("Failed to initialize lazy value", e);
                }
            }
            return value;
        }

        boolean isInitialized() {
            return value != null;
        }
    }
}
