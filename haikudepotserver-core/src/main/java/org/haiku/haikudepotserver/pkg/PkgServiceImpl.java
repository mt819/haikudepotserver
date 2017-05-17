/*
 * Copyright 2013-2016, Andrew Lindesay
 * Distributed under the terms of the MIT License.
 */

package org.haiku.haikudepotserver.pkg;

import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.Uninterruptibles;
import org.apache.cayenne.DataRow;
import org.apache.cayenne.ObjectContext;
import org.apache.cayenne.ObjectId;
import org.apache.cayenne.access.OptimisticLockException;
import org.apache.cayenne.configuration.server.ServerRuntime;
import org.apache.cayenne.exp.Expression;
import org.apache.cayenne.exp.ExpressionFactory;
import org.apache.cayenne.query.*;
import org.haiku.haikudepotserver.dataobjects.*;
import org.haiku.haikudepotserver.pkg.model.PkgSearchSpecification;
import org.haiku.haikudepotserver.pkg.model.PkgService;
import org.haiku.haikudepotserver.support.SingleCollector;
import org.haiku.haikudepotserver.support.StoppableConsumer;
import org.haiku.haikudepotserver.support.VersionCoordinatesComparator;
import org.haiku.haikudepotserver.support.cayenne.ExpressionHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.URL;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * <p>This service undertakes non-trivial operations on packages.</p>
 */

@Service
public class PkgServiceImpl implements PkgService {

    protected static Logger LOGGER = LoggerFactory.getLogger(PkgServiceImpl.class);

    // TODO; should be injected as a pattern because this should not know about paths for the controller.
    public final static String URL_SEGMENT_PKGDOWNLOAD = "__pkgdownload";

    @Value("${architecture.default.code}")
    private String defaultArchitectureCode;

    // ------------------------------
    // QUERY

    /**
     * <p>This method will return the latest version for a package in any architecture.</p>
     */

    @Override
    public Optional<PkgVersion> getLatestPkgVersionForPkg(
            ObjectContext context,
            Pkg pkg,
            Repository repository) {

        Preconditions.checkArgument(null != context, "a context must be provided");
        Preconditions.checkArgument(null != pkg, "a package must be provided");

        Optional<PkgVersion> pkgVersionOptional = getLatestPkgVersionForPkg(
                context,
                pkg,
                repository,
                Collections.singletonList(Architecture.getByCode(context, defaultArchitectureCode).get()));

        if(!pkgVersionOptional.isPresent()) {
            List<Architecture> architectures = Architecture.getAllExceptByCode(
                    context,
                    ImmutableList.of(Architecture.CODE_SOURCE, defaultArchitectureCode));

            for (int i = 0; i < architectures.size() && !pkgVersionOptional.isPresent(); i++) {
                pkgVersionOptional = getLatestPkgVersionForPkg(
                        context,
                        pkg,
                        repository,
                        Collections.singletonList(architectures.get(i)));
            }
        }

        return pkgVersionOptional;
    }

    /**
     * <p>This method will return the latest PkgVersion for the supplied package.</p>
     */

    @Override
    public Optional<PkgVersion> getLatestPkgVersionForPkg(
            ObjectContext context,
            Pkg pkg,
            Repository repository,
            final List<Architecture> architectures) {

        Preconditions.checkArgument(null != context, "the context must be provided");
        Preconditions.checkArgument(null != pkg, "the pkg must must be provided");
        Preconditions.checkArgument(null != architectures && !architectures.isEmpty(), "the architectures must be provided and must not be empty");
        Preconditions.checkArgument(null != repository, "the repository must be provided");

        List<Expression> expressions = new ArrayList<>();
        expressions.add(ExpressionFactory.matchExp(PkgVersion.PKG_PROPERTY, pkg));
        expressions.add(ExpressionFactory.matchExp(PkgVersion.ACTIVE_PROPERTY, Boolean.TRUE));
        expressions.add(ExpressionFactory.matchExp(PkgVersion.IS_LATEST_PROPERTY, Boolean.TRUE));
        expressions.add(ExpressionFactory.inExp(PkgVersion.ARCHITECTURE_PROPERTY, architectures));
        expressions.add(ExpressionFactory.matchExp(
                PkgVersion.REPOSITORY_SOURCE_PROPERTY + "." + RepositorySource.REPOSITORY_PROPERTY,
                repository));

        SelectQuery query = new SelectQuery(PkgVersion.class, ExpressionHelper.andAll(expressions));
        return ((List<PkgVersion>) context.performQuery(query)).stream().collect(SingleCollector.optional());
    }

    /**
     * <p>For the given architecture and package, re-establish what is the latest package and correct it.
     * This may be necessary after, for example, adjusting the active flag on a pkg version.</p>
     * @return the updated latest package version or an empty option if there is none.
     */

    @Override
    public Optional<PkgVersion> adjustLatest(
            ObjectContext context,
            Pkg pkg,
            Architecture architecture) {

        Preconditions.checkArgument(null != context, "a context is required");
        Preconditions.checkArgument(null != pkg, "the package must be supplied");
        Preconditions.checkArgument(null != architecture, "the architecture must be supplied");

        List<PkgVersion> pkgVersions = (List<PkgVersion>) context.performQuery(new SelectQuery(
                PkgVersion.class,
                ExpressionHelper.andAll(ImmutableList.of(
                        ExpressionFactory.matchExp(PkgVersion.PKG_PROPERTY, pkg),
                        ExpressionFactory.matchExp(PkgVersion.ARCHITECTURE_PROPERTY, architecture)
                ))
        ));

        if(!pkgVersions.isEmpty()) {

            final VersionCoordinatesComparator comparator = new VersionCoordinatesComparator();

            Optional<PkgVersion> pkgVersionOptional = pkgVersions
                    .stream()
                    .filter(PkgVersion::getActive)
                    .sorted((pv1, pv2) -> comparator.compare(pv2.toVersionCoordinates(), pv1.toVersionCoordinates()))
                    .findFirst();

            if(pkgVersionOptional.isPresent()) {
                pkgVersionOptional.get().setIsLatest(true);
            }

            for (PkgVersion pkgVersion : pkgVersions) {
                if (pkgVersion.getIsLatest() &&
                        (!pkgVersionOptional.isPresent() ||
                                !pkgVersion.equals(pkgVersionOptional.get())
                        )
                        ) {
                    pkgVersion.setIsLatest(false);
                }
            }

            return pkgVersionOptional;
        }

        return Optional.empty();
    }

    /**
     * <p>Given a {@link PkgVersion}, see if there is a corresponding source package.</p>
     */

    @Override
    public Optional<PkgVersion> getCorrespondingSourcePkgVersion(
            ObjectContext context,
            PkgVersion pkgVersion) {

        Preconditions.checkArgument(null != context, "a context is required");
        Preconditions.checkArgument(null != pkgVersion, "a pkg version is required");

        Optional<Pkg> pkgSourceOptional = Pkg.tryGetByName(context, pkgVersion.getPkg().getName() + "_source");

        if(pkgSourceOptional.isPresent()) {

            Architecture sourceArchitecture = Architecture.getByCode(
                    context,
                    Architecture.CODE_SOURCE).get();

            SelectQuery query = new SelectQuery(
                    PkgVersion.class,
                    ExpressionHelper.andAll(ImmutableList.of(
                            ExpressionFactory.matchExp(PkgVersion.PKG_PROPERTY, pkgSourceOptional.get()),
                            ExpressionFactory.matchExp(
                                    PkgVersion.REPOSITORY_SOURCE_PROPERTY + "." + RepositorySource.REPOSITORY_PROPERTY,
                                    pkgVersion.getRepositorySource().getRepository()),
                            ExpressionFactory.matchExp(PkgVersion.ACTIVE_PROPERTY, Boolean.TRUE),
                            ExpressionFactory.matchExp(PkgVersion.ARCHITECTURE_PROPERTY, sourceArchitecture),
                            ExpressionHelper.toExpression(pkgVersion.toVersionCoordinates(), null)
                    ))
            );

            return ((List<PkgVersion>) context.performQuery(query)).stream().collect(SingleCollector.optional());
        }

        return Optional.empty();
    }

    // ------------------------------
    // SEARCH

    @Override
    public List<PkgVersion> search(
            ObjectContext context,
            PkgSearchSpecification search) {

        Preconditions.checkNotNull(context);
        Preconditions.checkNotNull(search);
        Preconditions.checkNotNull(search.getNaturalLanguage());
        Preconditions.checkState(search.getOffset() >= 0);
        Preconditions.checkState(search.getLimit() > 0);

        SQLTemplate sqlTemplate = (SQLTemplate) context.getEntityResolver().getQuery("SearchPkgVersions");
        Query query = sqlTemplate.createQuery(ImmutableMap.of(
                "search", search,
                "isTotal", false,
                "englishNaturalLanguage", NaturalLanguage.getEnglish(context)
        ));

        return (List<PkgVersion>) context.performQuery(query);
    }

    /**
     * <p>This method will provide a total of the package versions.</p>
     */

    @Override
    public long total(
            ObjectContext context,
            PkgSearchSpecification search) {

        SQLTemplate sqlTemplate = (SQLTemplate) context.getEntityResolver().getQuery("SearchPkgVersions");
        SQLTemplate query = (SQLTemplate) sqlTemplate.createQuery(ImmutableMap.of(
                "search", search,
                "isTotal", true,
                "englishNaturalLanguage", NaturalLanguage.getEnglish(context)
        ));
        query.setFetchingDataRows(true);

        DataRow dataRow = (DataRow) (context.performQuery(query)).get(0);
        Number newTotal = (Number) dataRow.get("total");

        return newTotal.longValue();
    }

    // ------------------------------
    // EACH PACKAGE

    private void appendEjbqlAllPkgsWhere(
            Appendable ejbql,
            List<Object> parameterList,
            ObjectContext context,
            boolean allowSourceOnly) throws IOException {

        ejbql.append("p.active=true\n");

        if(!allowSourceOnly) {
            ejbql.append("AND EXISTS(");
            ejbql.append("SELECT pv FROM PkgVersion pv WHERE pv.pkg=p AND pv.active=true AND pv.architecture <> ?");
            parameterList.add(Architecture.getByCode(context,Architecture.CODE_SOURCE).get());
            ejbql.append(Integer.toString(parameterList.size()));
            ejbql.append(")");
        }

    }

    /**
     * <p>This method will provide a total of the packages.</p>
     */

    @Override
    public long totalPkg(
            ObjectContext context,
            boolean allowSourceOnly) {
        Preconditions.checkArgument(null!=context, "the object context must be provided");

        StringBuilder ejbql = new StringBuilder();
        List<Object> parameterList = new ArrayList<>();

        ejbql.append("SELECT COUNT(p) FROM Pkg p WHERE \n");

        try {
            appendEjbqlAllPkgsWhere(ejbql, parameterList, context, allowSourceOnly);
        }
        catch(IOException ioe) {
            throw new IllegalStateException("it was not possible to render the ejbql to get the packages", ioe);
        }

        EJBQLQuery query = new EJBQLQuery(ejbql.toString());

        for(int i=0;i<parameterList.size();i++) {
            query.setParameter(i+1, parameterList.get(i));
        }

        List<Object> result = (List<Object>) context.performQuery(query);

        if(1==result.size()) {
            return ((Number) result.get(0)).longValue();
        }

        throw new IllegalStateException("expecting one result with the total record count");
    }

    /**
     * <p>This will be called for each package in the system.</p>
     * @param c is the callback to invoke.
     * @param allowSourceOnly when true implies that a package can be processed which only has versions that are for
     *                        the source architecture.
     * @return the quantity of packages processed.
     */

    @Override
    public long eachPkg(
            ObjectContext context,
            boolean allowSourceOnly,
            StoppableConsumer<Pkg> c) {
        Preconditions.checkArgument(null!=c, "the callback should be provided to run for each package");
        Preconditions.checkArgument(null!=context, "the object context must be provided");

        int offset = 0;

        StringBuilder ejbql = new StringBuilder();
        List<Object> parameterList = new ArrayList<>();

        ejbql.append("SELECT p FROM Pkg p WHERE \n");

        try {
            appendEjbqlAllPkgsWhere(ejbql, parameterList, context, allowSourceOnly);
        }
        catch(IOException ioe) {
            throw new IllegalStateException("it was not possible to render the ejbql to get the packages", ioe);
        }

        ejbql.append("\nORDER BY p.name ASC");

        EJBQLQuery query = new EJBQLQuery(ejbql.toString());

        for(int i=0;i<parameterList.size();i++) {
            query.setParameter(i+1, parameterList.get(i));
        }

        query.setFetchLimit(100);

        while(true) {

            query.setFetchOffset(offset);

            List<Pkg> pkgs = (List<Pkg>) context.performQuery(query);

            if(pkgs.isEmpty()) {
                return offset; // stop
            }
            else {
                for(Pkg pkg : pkgs) {

                    offset++;

                    if(!c.accept(pkg)) {
                        return offset;
                    }
                }
            }
        }
    }

    // ------------------------------
    // CHANGE LOG

    /**
     * <p>Performs necessary modifications to the package so that the changelog is updated
     * with the new content supplied.</p>
     */

    @Override
    public void updatePkgChangelog(
            ObjectContext context,
            Pkg pkg,
            String newContent) {

        Preconditions.checkArgument(null!=context, "the context is not supplied");
        Preconditions.checkArgument(null!=pkg, "the pkg is not supplied");

        Optional<PkgChangelog> pkgChangelogOptional = pkg.getPkgChangelog();

        if(null!=newContent) {
            newContent = newContent.trim().replace("\r\n", "\n"); // windows to unix newline.
        }

        if(pkgChangelogOptional.isPresent()) {
            if(null==newContent) {
                context.deleteObject(pkgChangelogOptional.get());
                LOGGER.info("did remove the changelog for; {}", pkg);
            }
            else {
                pkgChangelogOptional.get().setContent(newContent);
                LOGGER.info("did update the changelog for; {}",pkg);
            }
        }
        else {
            if(null!=newContent) {
                PkgChangelog pkgChangelog = context.newObject(PkgChangelog.class);
                pkgChangelog.setPkg(pkg);
                pkgChangelog.setContent(newContent);
                LOGGER.info("did add a new changelog for; {}", pkg);
            }
        }
    }

    /**
     * <p>This method will deactivate package versions for a package where the package version is related to the
     * supplied repository.  This is used in the situation where a package was once part of a repository, but has
     * been removed.</p>
     * @return the quantity of package versions that were deactivated.
     */

    @Override
    public int deactivatePkgVersionsForPkgAssociatedWithRepositorySource(
            ObjectContext context,
            Pkg pkg,
            final RepositorySource repositorySource) {

        Preconditions.checkArgument(null != context, "the context must be provided");
        Preconditions.checkArgument(null != pkg, "the pkg must be provided");
        Preconditions.checkArgument(null != repositorySource, "the repository source must be provided");

        int count = 0;

        for(PkgVersion pkgVersion : PkgVersion.getForPkg(context, pkg, repositorySource, false)) { // active only
            if(pkgVersion.getRepositorySource().equals(repositorySource)) {
                if(pkgVersion.getActive()) {
                    pkgVersion.setActive(false);
                    count++;
                }
            }
        }

        return count;
    }

    /**
     * <p>This method will return all of the package names that have package versions that are related to a
     * repository.</p>
     */

    @Override
    public Set<String> fetchPkgNamesWithAnyPkgVersionAssociatedWithRepositorySource(
            ObjectContext context,
            RepositorySource repositorySource) {

        Preconditions.checkArgument(null != context, "the context must be provided");
        Preconditions.checkArgument(null != repositorySource, "the repository soures must be provided");

        StringBuilder queryBuilder = new StringBuilder();
        queryBuilder.append("SELECT p.name FROM ");
        queryBuilder.append(Pkg.class.getSimpleName());
        queryBuilder.append(" p WHERE EXISTS(SELECT pv FROM ");
        queryBuilder.append(PkgVersion.class.getSimpleName());
        queryBuilder.append(" pv WHERE pv.");
        queryBuilder.append(PkgVersion.PKG_PROPERTY);
        queryBuilder.append("=p AND pv.");
        queryBuilder.append(PkgVersion.REPOSITORY_SOURCE_PROPERTY);
        queryBuilder.append("=:repositorySource)");

        EJBQLQuery query = new EJBQLQuery(queryBuilder.toString());
        query.setParameter("repositorySource", repositorySource);

        return ImmutableSet.copyOf(context.performQuery(query));
    }

    /**
     * <p>This method will either find the existing pkg prominence with respect to the
     * repository or will create one and return it.</p>
     */

    @Override
    public PkgProminence ensurePkgProminence(
            ObjectContext objectContext,
            Pkg pkg,
            Repository repository) {
        return ensurePkgProminence(objectContext, pkg, repository, Prominence.ORDERING_LAST);
    }

    @Override
    public PkgProminence ensurePkgProminence(
            ObjectContext objectContext,
            Pkg pkg,
            Repository repository,
            Integer ordering) {
        Preconditions.checkArgument(null!=ordering && ordering > 0, "an ordering must be suppied");
        return ensurePkgProminence(
                objectContext, pkg, repository,
                Prominence.getByOrdering(objectContext, ordering).get());
    }

    @Override
    public PkgProminence ensurePkgProminence(
            ObjectContext objectContext,
            Pkg pkg,
            Repository repository,
            Prominence prominence) {
        Preconditions.checkArgument(null != prominence, "the prominence must be provided");
        Preconditions.checkArgument(null != repository, "the repository must be provided");
        Preconditions.checkArgument(null != pkg, "the pkg must be provided");
        Optional<PkgProminence> pkgProminenceOptional = pkg.getPkgProminence(repository);

        if(!pkgProminenceOptional.isPresent()) {
            PkgProminence pkgProminence = objectContext.newObject(PkgProminence.class);
            pkg.addToManyTarget(Pkg.PKG_PROMINENCES_PROPERTY, pkgProminence, true);
            pkgProminence.setRepository(repository);
            pkgProminence.setProminence(prominence);
            return pkgProminence;
        }

        return pkgProminenceOptional.get();
    }

    /**
     * <p>If this is a development package that has a parent then return it.</p>
     */

    @Override
    public Optional<Pkg> tryGetDevelMainPkg(ObjectContext objectContext, String develPkgName) {
        Preconditions.checkArgument(null != objectContext, "the object context must be provided");
        Preconditions.checkArgument(!Strings.isNullOrEmpty(develPkgName), "the pkg must be provided");

        if (develPkgName.endsWith(SUFFIX_PKG_DEVELOPMENT)) {
            String mainPkgName = develPkgName.substring(0, develPkgName.length() - PkgServiceImpl.SUFFIX_PKG_DEVELOPMENT.length());
            return Pkg.tryGetByName(objectContext, mainPkgName);
        }

        return Optional.empty();
    }

    /**
     * <p>If there exists a development package for this package then return it.</p>
     */

    @Override
    public Optional<Pkg> tryGetDevelPkg(
            ObjectContext objectContext,
            String mainPkgName) {

        Preconditions.checkArgument(null != objectContext, "the object context must be provided");
        Preconditions.checkArgument(!Strings.isNullOrEmpty(mainPkgName), "the pkg must be provided");

        if(!mainPkgName.endsWith(SUFFIX_PKG_DEVELOPMENT)) {
            return Pkg.tryGetByName(objectContext, mainPkgName + SUFFIX_PKG_DEVELOPMENT);
        }

        return Optional.empty();
    }

    @Override
    public String createHpkgDownloadUrl(PkgVersion pkgVersion) {
        URL packagesBaseUrl = pkgVersion.getRepositorySource().getPackagesBaseURL();

        if(ImmutableSet.of("http","https").contains(packagesBaseUrl.getProtocol())) {
            return pkgVersion.getHpkgURL().toString();
        }

        UriComponentsBuilder builder = UriComponentsBuilder.fromPath(URL_SEGMENT_PKGDOWNLOAD);
        pkgVersion.appendPathSegments(builder);
        builder.path("package.hpkg");
        return builder.build().toUriString();
    }

    /**
     * <p>This method will update the {@link PkgCategory} set in the
     * nominated {@link Pkg} such that the supplied set are the
     * categories for the package.  It will do this by adding and removing relationships between the package
     * and the categories.</p>
     * @return true if a change was made.
     */

    @Override
    public boolean updatePkgCategories(ObjectContext context, Pkg pkg, List<PkgCategory> pkgCategories) {

        Preconditions.checkArgument(null != context, "the context must be provided");
        Preconditions.checkArgument(null != pkg, "the pkg must be provided");
        Preconditions.checkArgument(null != pkgCategories, "the pkg categories must be provided");

        pkgCategories = new ArrayList<>(pkgCategories);
        boolean didChange = false;

        // now go through and delete any of those pkg relationships to packages that are already present
        // and which are no longer required.  Also remove those that we already have from the list.

        for(PkgPkgCategory pkgPkgCategory : ImmutableList.copyOf(pkg.getPkgPkgCategories())) {
            if(!pkgCategories.contains(pkgPkgCategory.getPkgCategory())) {
                pkg.removeToManyTarget(Pkg.PKG_PKG_CATEGORIES_PROPERTY, pkgPkgCategory, true);
                context.deleteObjects(pkgPkgCategory);
                didChange = true;
            }
            else {
                pkgCategories.remove(pkgPkgCategory.getPkgCategory());
            }
        }

        // now any remaining in the pkgCategories will need to be added to the pkg.

        for(PkgCategory pkgCategory : pkgCategories) {
            PkgPkgCategory pkgPkgCategory = context.newObject(PkgPkgCategory.class);
            pkgPkgCategory.setPkgCategory(pkgCategory);
            pkg.addToManyTarget(Pkg.PKG_PKG_CATEGORIES_PROPERTY, pkgPkgCategory, true);
            didChange = true;
        }

        // now save and finish.

        if(didChange) {
            pkg.setModifyTimestamp();
        }

        return didChange;
    }

    /**
     * <p>This method will increment the view counter on a package version.  If it encounters an optimistic
     * locking problem then it will pause and it will try again in a moment.  It will attempt this a few
     * times and then fail with a runtime exception.</p>
     */

    @Override
    public void incrementViewCounter(ServerRuntime serverRuntime, ObjectId pkgVersionOid) {

        Preconditions.checkArgument(null != serverRuntime, "the server runtime must be provided");
        Preconditions.checkArgument(null != pkgVersionOid, "the pkg version oid must be provided");
        Preconditions.checkArgument(pkgVersionOid.getEntityName().equals(PkgVersion.class.getSimpleName()), "the oid must reference PkgVersion");

        int attempts = 3;

        while(true) {
            ObjectContext contextEdit = serverRuntime.getContext();
            PkgVersion pkgVersionEdit = ((List<PkgVersion>) contextEdit.performQuery(new ObjectIdQuery(pkgVersionOid)))
                    .stream()
                    .collect(SingleCollector.single());
            pkgVersionEdit.incrementViewCounter();

            try {
                contextEdit.commitChanges();
                LOGGER.info("did increment the view counter for '{}'", pkgVersionEdit.getPkg().toString());
                return;
            } catch (OptimisticLockException ole) {
                contextEdit.invalidateObjects(pkgVersionEdit);

                attempts--;

                if (0 == attempts) {
                    throw new RuntimeException("unable to increment the view counter for '"+pkgVersionEdit.getPkg().toString()+"' because of an optimistic locking failure; have exhausted attempts", ole);
                } else {
                    LOGGER.error("unable to increment the view counter for '{}' because of an optimistic locking failure; will try again...", pkgVersionEdit.getPkg().toString());
                    Uninterruptibles.sleepUninterruptibly(250 + (System.currentTimeMillis() % 250), TimeUnit.MILLISECONDS);
                }
            }
        }
    }

}
