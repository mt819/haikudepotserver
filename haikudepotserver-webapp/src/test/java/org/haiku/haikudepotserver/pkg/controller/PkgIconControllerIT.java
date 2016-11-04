/*
 * Copyright 2014-2016, Andrew Lindesay
 * Distributed under the terms of the MIT License.
 */

package org.haiku.haikudepotserver.pkg.controller;

import com.google.common.net.MediaType;
import org.fest.assertions.Assertions;
import org.haiku.haikudepotserver.AbstractIntegrationTest;
import org.haiku.haikudepotserver.IntegrationTestSupportService;
import org.junit.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.web.WebAppConfiguration;

import javax.annotation.Resource;
import java.io.IOException;

@ContextConfiguration({
        "classpath:/spring/test-servlet-context.xml",
        "classpath:/spring/test-application-context.xml"
})
@WebAppConfiguration
public class PkgIconControllerIT extends AbstractIntegrationTest {

    @Resource
    private PkgIconController pkgIconController;

    @Resource
    private IntegrationTestSupportService integrationTestSupportService;

    private byte[] getIconData() throws IOException {
        return getResourceData("/sample-32x32.png");
    }

    /**
     * <p>This test works knowing that the test package pkg1 will have a PNG image pre-loaded for it.</p>
     */

    @Test
    public void testGet() throws Exception {

        integrationTestSupportService.createStandardTestData();
        byte[] imageData = getIconData();

        MockHttpServletResponse response = new MockHttpServletResponse();

        // ------------------------------------
        pkgIconController.handleGet(
                response,
                32,
                "png",
                "pkg1",
                true);
        // -----------------------------------

        Assertions.assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
        Assertions.assertThat(response.getContentType()).isEqualTo(MediaType.PNG.toString());

        byte[] responseBytes = response.getContentAsByteArray();

        Assertions.assertThat(responseBytes.length).isEqualTo(imageData.length);
        Assertions.assertThat(responseBytes).isEqualTo(imageData);

    }

}
