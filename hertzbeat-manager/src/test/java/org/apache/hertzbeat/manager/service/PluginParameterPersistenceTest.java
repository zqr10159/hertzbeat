/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hertzbeat.manager.service;

import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.hertzbeat.common.entity.job.Configmap;
import org.apache.hertzbeat.manager.dao.PluginItemDao;
import org.apache.hertzbeat.manager.dao.PluginMetadataDao;
import org.apache.hertzbeat.manager.dao.PluginParamDao;
import org.apache.hertzbeat.manager.pojo.dto.PluginParam;
import org.apache.hertzbeat.manager.service.impl.PluginServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactoryBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

/** Exercises parameter replacement and rollback with the production entity/repository and real H2 transactions. */
class PluginParameterPersistenceTest {

    private LocalContainerEntityManagerFactoryBean entityManagerFactory;
    private DriverManagerDataSource dataSource;
    private TransactionTemplate transaction;
    private PluginParamDao parameters;
    private EntityManager entityManager;
    private PluginServiceImpl service;
    private Map<Long, List<Configmap>> cacheBefore;

    @BeforeEach
    void setUp() {
        cacheBefore = new HashMap<>(parameterCache());
        dataSource = new DriverManagerDataSource("jdbc:h2:mem:plugin_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        entityManagerFactory = new LocalContainerEntityManagerFactoryBean();
        entityManagerFactory.setDataSource(dataSource);
        entityManagerFactory.setManagedTypes(PersistenceManagedTypes.of(PluginParam.class.getName()));
        entityManagerFactory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        entityManagerFactory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-drop"));
        entityManagerFactory.afterPropertiesSet();
        var factory = entityManagerFactory.getObject();
        var manager = new JpaTransactionManager(factory);
        transaction = new TransactionTemplate(manager);
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("transactionManager", manager);
        var repository = new JpaRepositoryFactoryBean<PluginParamDao, PluginParam, Long>(PluginParamDao.class);
        repository.setBeanFactory(beans);
        entityManager = SharedEntityManagerCreator.createSharedEntityManager(factory);
        repository.setEntityManager(entityManager);
        repository.afterPropertiesSet();
        parameters = repository.getObject();
        service = new PluginServiceImpl(mock(PluginMetadataDao.class), mock(PluginItemDao.class), parameters);
    }

    @AfterEach
    void tearDown() {
        if (entityManagerFactory != null) {
            entityManagerFactory.destroy();
        }
        if (dataSource != null) {
            new JdbcTemplate(dataSource).execute("SHUTDOWN");
        }
        parameterCache().clear();
        parameterCache().putAll(cacheBefore);
    }

    @Test
    void editingAnExistingFieldReplacesItsCommittedValue() {
        save(List.of(parameter(901L, "normal", "old")));
        save(List.of(parameter(902L, "normal", "unrelated")));

        assertDoesNotThrow(() -> save(List.of(parameter(901L, "normal", "new"))));

        assertEquals(List.of("new"), values(901L));
        assertEquals(List.of("unrelated"), values(902L));
        assertEquals(List.of(new Configmap("normal", "new", (byte) 1)), parameterCache().get(901L));
    }

    @Test
    void failedInsertionRollsBackDeletionAndPreservesPreviousValues() {
        save(List.of(parameter(901L, "normal", "old")));
        Map<Long, List<Configmap>> beforeFailure = new HashMap<>(parameterCache());

        var failure = assertThrows(ConstraintViolationException.class, () -> save(List.of(
                parameter(901L, "normal", "new"), parameter(901L, "normal", "duplicate"))));
        assertEquals("23505", failure.getSQLState());

        assertEquals(List.of("old"), values(901L));
        assertEquals(beforeFailure, parameterCache());
    }

    @Test
    void failureAfterServiceReturnsRollsBackDatabaseWithoutPublishingParameters() {
        save(List.of(parameter(901L, "normal", "old")));
        IllegalStateException outerFailure = new IllegalStateException("Outer transaction failed");

        assertSame(outerFailure, assertThrows(IllegalStateException.class,
                () -> transaction.executeWithoutResult(status -> {
                    service.savePluginParam(List.of(parameter(901L, "normal", "new")));
                    assertEquals(List.of("new"), values(901L));
                    throw outerFailure;
                })));

        assertEquals(List.of("old"), values(901L));
        assertEquals(List.of(new Configmap("normal", "old", (byte) 1)), parameterCache().get(901L));
    }

    @Test
    void successfulTransactionPublishesParametersOnlyAfterCommit() {
        save(List.of(parameter(901L, "normal", "old")));

        transaction.executeWithoutResult(status -> {
            service.savePluginParam(List.of(parameter(901L, "normal", "new")));
            assertEquals(List.of("new"), values(901L));
            assertEquals(List.of(new Configmap("normal", "old", (byte) 1)), parameterCache().get(901L));
        });

        assertEquals(List.of("new"), values(901L));
        assertEquals(List.of(new Configmap("normal", "new", (byte) 1)), parameterCache().get(901L));
    }

    @Test
    void publicationUsesDetachedValuesWhenCallerChangesTheInputAfterSave() {
        PluginParam param = parameter(901L, "normal", "new");
        List<PluginParam> input = new ArrayList<>(List.of(param));

        transaction.executeWithoutResult(status -> {
            service.savePluginParam(input);
            entityManager.detach(param);
            param.setField("changed");
            param.setParamValue("changed");
            param.setType((byte) 2);
            input.clear();
        });

        assertEquals(List.of("new"), values(901L));
        assertEquals(List.of(new Configmap("normal", "new", (byte) 1)), parameterCache().get(901L));
    }

    @Test
    void twoSavesInOneTransactionPublishTheLastCommittedParameters() {
        save(List.of(parameter(901L, "normal", "old")));

        transaction.executeWithoutResult(status -> {
            service.savePluginParam(List.of(parameter(901L, "normal", "first")));
            service.savePluginParam(List.of(parameter(901L, "normal", "latest")));
            assertEquals(List.of("latest"), values(901L));
            assertEquals(List.of(new Configmap("normal", "old", (byte) 1)), parameterCache().get(901L));
        });

        assertEquals(List.of("latest"), values(901L));
        assertEquals(List.of(new Configmap("normal", "latest", (byte) 1)), parameterCache().get(901L));
    }

    private void save(List<PluginParam> params) {
        // Matches the service's @Transactional boundary while sharing the real repository persistence context.
        transaction.executeWithoutResult(status -> service.savePluginParam(params));
    }

    private List<String> values(Long pluginId) {
        return parameters.findParamsByPluginMetadataId(pluginId).stream().map(PluginParam::getParamValue).toList();
    }

    private PluginParam parameter(Long pluginId, String field, String value) {
        return PluginParam.builder().pluginMetadataId(pluginId).field(field).paramValue(value).type((byte) 1).build();
    }

    @SuppressWarnings("unchecked")
    private Map<Long, List<Configmap>> parameterCache() {
        return (Map<Long, List<Configmap>>) ReflectionTestUtils.getField(PluginServiceImpl.class, "PARAMS_MAP");
    }
}
