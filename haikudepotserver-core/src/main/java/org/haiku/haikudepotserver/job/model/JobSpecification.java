/*
 * Copyright 2014-2016, Andrew Lindesay
 * Distributed under the terms of the MIT License.
 */

package org.haiku.haikudepotserver.job.model;

import java.util.Collection;
import java.util.Optional;

/**
 * <p>Concrete implementations of this interface are able to be used as specifications for a job to be run.  It does
 * not know what {@link JobRunner} should be used to run the job, but can
 * specify the data that a {@link JobRunner} might need in order to be able
 * to operate.</p>
 */

public interface JobSpecification {

    /**
     * <p>This is a unique identifier for the job specification.</p>
     */

    String getGuid();

    void setGuid(String value);

    String getOwnerUserNickname();

    /**
     * <p>This code is able to identify the type of the job.  The same code associated with an instance of
     * {@link JobRunner} allows an instance of
     * {@link JobService} to find a runner which is
     * able to run a given specification.</p>
     */

    String getJobTypeCode();

    /**
     * <p>Once a job has completed, this value (in milliseconds) defines how long it should remain available
     * in the {@link JobService}.</p>
     */

    Optional<Long> tryGetTimeToLiveMillis();

    Collection<String> getSuppliedDataGuids();

    /**
     * <p>When two job specifications are submitted to the
     * {@link JobService}, it is possible to coalesce
     * them in order to avoid two of the same job running at the same time.  This method is called in order
     * to know if two jobs are equivalent before knowing if it should enqueue the submitted one.</p>
     *
     * <p>This does not imply equality of the <em>instance</em> of the job specification, but equivalence
     * of the parameters of the job.</p>
     */

    boolean isEquivalent(JobSpecification other);

}
