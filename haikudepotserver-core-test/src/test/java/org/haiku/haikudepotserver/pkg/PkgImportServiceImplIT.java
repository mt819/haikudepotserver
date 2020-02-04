/*
 * Copyright 2018-2020, Andrew Lindesay
 * Distributed under the terms of the MIT License.
 */

package org.haiku.haikudepotserver.pkg;

import com.google.common.io.Files;
import com.google.common.io.Resources;
import org.apache.cayenne.ObjectContext;
import org.apache.cayenne.ObjectId;
import org.fest.assertions.Assertions;
import org.haiku.haikudepotserver.AbstractIntegrationTest;
import org.haiku.haikudepotserver.IntegrationTestSupportService;
import org.haiku.haikudepotserver.config.TestConfig;
import org.haiku.haikudepotserver.dataobjects.*;
import org.haiku.haikudepotserver.pkg.model.PkgIconService;
import org.haiku.haikudepotserver.pkg.model.PkgImportService;
import org.haiku.haikudepotserver.pkg.model.PkgLocalizationService;
import org.haiku.haikudepotserver.pkg.model.PkgService;
import org.haiku.haikudepotserver.support.ExposureType;
import org.haiku.haikudepotserver.support.FileHelper;
import org.haiku.pkg.model.Pkg;
import org.haiku.pkg.model.PkgArchitecture;
import org.haiku.pkg.model.PkgVersion;
import org.junit.Test;
import org.springframework.test.context.ContextConfiguration;

import javax.annotation.Resource;
import java.io.File;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import java.util.Random;

@ContextConfiguration(classes = TestConfig.class)
public class PkgImportServiceImplIT extends AbstractIntegrationTest {

    @Resource
    private PkgImportService pkgImportService;

    @Resource
    private PkgLocalizationService pkgLocalizationService;

    @Resource
    private PkgIconService pkgIconService;

    @Resource
    private PkgService pkgService;

    @Resource
    private IntegrationTestSupportService integrationTestSupportService;

    private Pkg createPkg(String minor) {
        return new Pkg(
                "testpkg",
                new PkgVersion("1", minor, "3", "4", 5),
                PkgArchitecture.X86_64,
                null,
                Collections.emptyList(),
                Collections.emptyList(),
                "test-summary-en",
                "test-description-en",
                null);
    }

    /**
     * <p>When a "_devel" package is imported there is a special behaviour that the localization and the
     * icons are copied from the main package over to the "_devel" package.</p>
     */

    @Test
    public void testImport_develPkgHandling() throws Exception {

        ObjectId respositorySourceObjectId;

        {
            integrationTestSupportService.createStandardTestData();

            ObjectContext context = serverRuntime.newContext();
            RepositorySource repositorySource = RepositorySource.tryGetByCode(context, "testreposrc_xyz").get();
            respositorySourceObjectId = repositorySource.getObjectId();
        }

        // setup the base package.

        {
            Pkg importPkg = createPkg("2");

            {
                ObjectContext importObjectContext = serverRuntime.newContext();
                pkgImportService.importFrom(importObjectContext, respositorySourceObjectId, importPkg, false);
                importObjectContext.commitChanges();
            }

            // now add some localization to the imported package.

            {
                ObjectContext setupObjectContext = serverRuntime.newContext();
                org.haiku.haikudepotserver.dataobjects.Pkg persistedPkg =
                        org.haiku.haikudepotserver.dataobjects.Pkg.getByName(setupObjectContext, importPkg.getName());
                setupObjectContext.commitChanges();

                pkgLocalizationService.updatePkgLocalization(
                        setupObjectContext,
                        persistedPkg.getPkgSupplement(),
                        NaturalLanguage.getByCode(setupObjectContext, NaturalLanguage.CODE_GERMAN),
                        "title_kingston_black",
                        "summary_kingston_black",
                        "description_kingston_black");
                setupObjectContext.commitChanges();


                try (InputStream inputStream = Resources.asByteSource(Resources.getResource("sample-32x32.png")).openStream()) {
                    pkgIconService.storePkgIconImage(
                            inputStream,
                            MediaType.getByCode(setupObjectContext, com.google.common.net.MediaType.PNG.toString()),
                            32,
                            setupObjectContext,
                            persistedPkg.getPkgSupplement());
                }
                setupObjectContext.commitChanges();
            }
        }

        // setup the devel package.

        Pkg importDevelPkg = new Pkg(
                "testpkg" + PkgServiceImpl.SUFFIX_PKG_DEVELOPMENT,
                new PkgVersion("1", "2", "3", "4", 5),
                PkgArchitecture.X86_64,
                null,
                Collections.emptyList(),
                Collections.emptyList(),
                "test-summary-en",
                "test-description-en",
                null);

        // ---------------------------------
        {
            ObjectContext importObjectContext = serverRuntime.newContext();
            pkgImportService.importFrom(importObjectContext, respositorySourceObjectId, importDevelPkg, false);
            importObjectContext.commitChanges();
        }
        // ---------------------------------

        // check it has the icon and the localization.

        {
            ObjectContext context = serverRuntime.newContext();

            org.haiku.haikudepotserver.dataobjects.Pkg persistedDevelPkg =
                    org.haiku.haikudepotserver.dataobjects.Pkg.getByName(context, importDevelPkg.getName());
            PkgSupplement persistedDevelPkgSupplement = persistedDevelPkg.getPkgSupplement();

            Assertions.assertThat(persistedDevelPkgSupplement.getPkgIcons().size()).isEqualTo(1);
            Assertions.assertThat(persistedDevelPkgSupplement.getPkgIcons().get(0).getSize()).isEqualTo(32);

            PkgLocalization pkgLocalization = persistedDevelPkgSupplement.getPkgLocalization(NaturalLanguage.CODE_GERMAN).get();

            Assertions.assertThat(pkgLocalization.getTitle()).isEqualTo("title_kingston_black");
            Assertions.assertThat(pkgLocalization.getSummary()).isEqualTo("summary_kingston_black");
            Assertions.assertThat(pkgLocalization.getDescription()).isEqualTo("description_kingston_black");
        }

    }

    /**
     * <P>During the import process, it is possible that the system is able to check the length of the
     * package.  This test will check that this mechanism is working.</P>
     */

    @Test
    public void testImport_payloadLength() throws Exception {

        File repositoryDirectory = null;
        int expectedPayloadLength;

        try {
            // create the repository

            integrationTestSupportService.createStandardTestData();
            Pkg inputPackage = createPkg("3");

            // setup a test repository

            {
                ObjectContext context = serverRuntime.newContext();
                RepositorySource repositorySource = RepositorySource.tryGetByCode(context, "testreposrc_xyz").get();
                repositoryDirectory = new File(repositorySource.tryGetPackagesBaseURL(ExposureType.INTERNAL_FACING).get().getPath());

                if (!repositoryDirectory.mkdirs()) {
                    throw new IllegalStateException("unable to create the on-disk repository");
                }

                Random random = new Random(System.currentTimeMillis());
                File fileF = new File(repositoryDirectory, "testpkg-1.3.3~4-5-x86_64.hpkg");
                byte[] buffer = new byte[1000 + (Math.abs(random.nextInt()) % 10*1000)];
                Files.write(buffer,fileF);
                expectedPayloadLength = buffer.length;
            }

            // now load the next package version in

            {
                ObjectContext context = serverRuntime.newContext();
                RepositorySource repositorySource = RepositorySource.tryGetByCode(context, "testreposrc_xyz").get();

                // ---------------------------------

                pkgImportService.importFrom(
                        context,
                        repositorySource.getObjectId(),
                        inputPackage,
                        true); // <--- NOTE

                // ---------------------------------

                context.commitChanges();
            }

            // check the length on that package is there and is correct.

            {
                ObjectContext context = serverRuntime.newContext();
                org.haiku.haikudepotserver.dataobjects.Pkg pkg = org.haiku.haikudepotserver.dataobjects.Pkg.getByName(context, "testpkg");
                org.haiku.haikudepotserver.dataobjects.PkgVersion pkgVersion = pkgService.getLatestPkgVersionForPkg(
                        context,
                        pkg,
                        Repository.tryGetByCode(context, "testrepo").get(),
                        Collections.singletonList(
                                Architecture.tryGetByCode(context, "x86_64").get()
                        )).get();

                Assertions.assertThat(pkgVersion.getPayloadLength()).isEqualTo(expectedPayloadLength);
            }
        }
        finally {
            if(null!=repositoryDirectory) {
                FileHelper.delete(repositoryDirectory);
            }
        }
    }

    /**
     * <p>If there is a series of packages and suddenly there is an older version come in then the future versions
     * should be deactivated.</p>
     */

    @Test
    public void testImport_versionRegressionDeactivatesNewerVersions() {

        integrationTestSupportService.createStandardTestData();

        int highestMinor= 6;

        {
            ObjectContext context = serverRuntime.newContext();
            org.haiku.haikudepotserver.dataobjects.Pkg pkg = integrationTestSupportService.createPkg(context, "pkgx");

            for (int i = 1; i <= 6; i++) {
                org.haiku.haikudepotserver.dataobjects.PkgVersion pkgVersion = context.newObject(
                        org.haiku.haikudepotserver.dataobjects.PkgVersion.class);
                pkgVersion.setPkg(pkg);
                pkgVersion.setIsLatest(i == highestMinor);
                pkgVersion.setArchitecture(org.haiku.haikudepotserver.dataobjects.Architecture.getByCode(
                        context, Architecture.CODE_X86_64));
                pkgVersion.setMajor("1");
                pkgVersion.setMinor(Integer.toString(i));
                pkgVersion.setRepositorySource(RepositorySource.getByCode(context, "testreposrc_xyz"));
            }

            context.commitChanges();
        }

        // now there are a string of pkg versions in place, import an older one.

        Pkg inputPackage = new Pkg(
                "pkgx",
                new PkgVersion("1", "4", null, null, null), // <-- version in middle of pkg versions
                PkgArchitecture.X86_64,
                null,
                Collections.emptyList(),
                Collections.emptyList(),
                "test-summary-en",
                "test-description-en",
                null);

        {
            ObjectContext context = serverRuntime.newContext();
            ObjectId repositorySourceObjectId = RepositorySource.getByCode(context, "testreposrc_xyz").getObjectId();

            // ---------------------------------

            pkgImportService.importFrom(
                    context,
                    repositorySourceObjectId,
                    inputPackage,
                    false);

            // ---------------------------------

            context.commitChanges();
        }

        // now check to see that the right outcomes have been achieved.

        {
            ObjectContext context = serverRuntime.newContext();
            List<org.haiku.haikudepotserver.dataobjects.PkgVersion> pkgVersions = org.haiku.haikudepotserver.dataobjects.PkgVersion.getForPkg(
                    context,
                    org.haiku.haikudepotserver.dataobjects.Pkg.getByName(context, "pkgx"),
                    true);

            Assertions.assertThat(pkgVersions.size()).isEqualTo(6);

            for(org.haiku.haikudepotserver.dataobjects.PkgVersion pkgVersion : pkgVersions) {
                switch(Integer.parseInt(pkgVersion.getMinor())) {

                    case 1:
                    case 2:
                    case 3:
                        Assertions.assertThat(pkgVersion.getActive()).isTrue();
                        Assertions.assertThat(pkgVersion.getIsLatest()).isFalse();
                        break;

                    case 4:
                        Assertions.assertThat(pkgVersion.getActive()).isTrue();
                        Assertions.assertThat(pkgVersion.getIsLatest()).isTrue();
                        break;

                    case 5:
                    case 6:
                        Assertions.assertThat(pkgVersion.getActive()).isFalse();
                        Assertions.assertThat(pkgVersion.getIsLatest()).isFalse();
                        break;

                    default:
                        throw new IllegalStateException("unknown pkg version; " + pkgVersion.toVersionCoordinates().toString());
                }
            }

        }

    }

}
