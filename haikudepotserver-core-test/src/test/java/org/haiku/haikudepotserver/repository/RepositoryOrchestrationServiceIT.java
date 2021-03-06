/*
 * Copyright 2018, Andrew Lindesay
 * Distributed under the terms of the MIT License.
 */

package org.haiku.haikudepotserver.repository;

import org.apache.cayenne.ObjectContext;
import org.apache.cayenne.configuration.server.ServerRuntime;
import org.fest.assertions.Assertions;
import org.haiku.haikudepotserver.AbstractIntegrationTest;
import org.haiku.haikudepotserver.IntegrationTestSupportService;
import org.haiku.haikudepotserver.config.TestConfig;
import org.haiku.haikudepotserver.dataobjects.Repository;
import org.haiku.haikudepotserver.dataobjects.RepositorySource;
import org.haiku.haikudepotserver.repository.model.RepositorySearchSpecification;
import org.haiku.haikudepotserver.repository.model.RepositoryService;
import org.junit.Test;
import org.springframework.test.context.ContextConfiguration;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;

@ContextConfiguration(classes = TestConfig.class)
public class RepositoryOrchestrationServiceIT extends AbstractIntegrationTest {

    @Resource
    ServerRuntime serverRuntime;

    @Resource
    IntegrationTestSupportService integrationTestSupportService;

    @Resource
    RepositoryService repositoryService;

    public void setup(ObjectContext context) {
        integrationTestSupportService.createStandardTestData();

        RepositorySource repositorySource = context.newObject(RepositorySource.class);
        repositorySource.setCode("zigzag_x86_64");
        repositorySource.setUrl("http://example.com/zigzag");
        repositorySource.setRepository(Repository.tryGetByCode(context, "testrepo").get());
        context.commitChanges();
    }

    public void testSearchForRepositorySourceSearchUrls_ok() {
        ObjectContext context = serverRuntime.newContext();
        setup(context);

        RepositorySearchSpecification specification = new RepositorySearchSpecification();
        specification.setRepositorySourceSearchUrls(Collections.singletonList("https://example.com/zigzag/"));

        // ---------------------------------
        List<Repository> repos = repositoryService.search(context, specification);
        // ---------------------------------

        Assertions.assertThat(repos.size()).isEqualTo(1);
        Assertions.assertThat(repos.get(0).getCode()).isEqualTo("zigzag_x86_64");
    }

    @Test
    public void testGetRepositoriesForRepositorySourceSearchUrls_notFound() {
        ObjectContext context = serverRuntime.newContext();
        setup(context);

        RepositorySearchSpecification specification = new RepositorySearchSpecification();
        specification.setRepositorySourceSearchUrls(Collections.singletonList("http://example.com/notfound"));
        specification.setOffset(0);
        specification.setLimit(2);

        // ---------------------------------
        List<Repository> repos = repositoryService.search(context, specification);
        // ---------------------------------

        Assertions.assertThat(repos.size()).isEqualTo(0);
    }

}
