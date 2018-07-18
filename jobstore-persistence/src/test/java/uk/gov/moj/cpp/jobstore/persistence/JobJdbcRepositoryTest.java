package uk.gov.moj.cpp.jobstore.persistence;

import static java.lang.String.format;
import static java.time.ZonedDateTime.now;
import static java.util.Optional.empty;
import static java.util.Optional.of;
import static java.util.UUID.randomUUID;
import static java.util.stream.Collectors.toList;
import static javax.json.Json.createReader;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static uk.gov.justice.services.common.converter.ZonedDateTimes.toSqlTimestamp;

import uk.gov.justice.services.common.util.UtcClock;
import uk.gov.justice.services.jdbc.persistence.JdbcRepositoryException;
import uk.gov.justice.services.jdbc.persistence.JdbcRepositoryHelper;
import uk.gov.justice.services.jdbc.persistence.PreparedStatementWrapper;
import uk.gov.justice.services.test.utils.core.messaging.Poller;
import uk.gov.justice.services.test.utils.persistence.TestDataSourceFactory;

import java.io.StringReader;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import javax.json.JsonObject;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;

public class JobJdbcRepositoryTest {

    private static final String LIQUIBASE_JOB_STORE_DB_CHANGELOG_XML = "liquibase/jobstore-db-changelog.xml";

    private final JobJdbcRepository jdbcRepository = new JobJdbcRepository();

    private static final String JOBS_COUNT = "SELECT COUNT(*) FROM job";

    private static final String JOB_DATA_JSON = "{\"some\": \"json\"}";

    @Before
    public void initialize() {
        try {
            jdbcRepository.dataSource = new TestDataSourceFactory(LIQUIBASE_JOB_STORE_DB_CHANGELOG_XML).createDataSource();
            jdbcRepository.logger = mock(Logger.class);
            jdbcRepository.jdbcRepositoryHelper = new JdbcRepositoryHelper();
            jdbcRepository.jobSqlProvider = new AnsiJobSqlProvider();
            checkIfReady();
        } catch (final Exception e) {
            e.printStackTrace();
            fail("Job store construction failed");
        }
    }

    private void checkIfReady() {
        final Poller poller = new Poller();

        poller.pollUntilFound(() -> {
            try {
                jdbcRepository.dataSource.getConnection().prepareStatement(JOBS_COUNT).execute();
                return of("Success");
            } catch (SQLException e) {
                e.printStackTrace();
                fail("Job store construction failed");
                return empty();
            }
        });
    }

    @After
    public void after() throws SQLException {
        jdbcRepository.dataSource.getConnection().close();
    }

    @Test
    public void shouldAddEmailNotificationWithMandatoryDataOnly() {

        final Job job = new Job(randomUUID(), jobData(JOB_DATA_JSON), "nextTask", now(), empty(), empty(), empty());

        jdbcRepository.createJob(job);

        final int jobsCount = jobsCount();
        assertThat(jobsCount, is(1));
    }


    @Test
    public void shouldAddEmailNotificationWithMandatoryAndOptionalData() {
        final UUID jobId1 = randomUUID();
        final UUID jobId2 = randomUUID();

        final Job job1 = new Job(jobId1, jobData(JOB_DATA_JSON), "nextTask", now(), of(randomUUID()), of(now()), empty());

        jdbcRepository.createJob(job1);

        final Job job2 = new Job(jobId2, jobData(JOB_DATA_JSON), "nextTask", now(), of(randomUUID()), of(now()), empty());
        jdbcRepository.createJob(job2);

        final int jobsCount = jobsCount();
        assertThat(jobsCount, is(2));

    }

    @Test
    public void shouldUpdateJobData() {

        final UUID jobId = randomUUID();
        final String jobDataBeforeUpdate = "{\"some\": \"json before update\"}";
        final String jobDataAfterUpdate = "{\"some\": \"json after update\"}";
        final UUID workerId = randomUUID();
        final Job job1 = new Job(jobId, jobData(jobDataBeforeUpdate), "nextTask", now(), of(workerId), of(now()), empty());

        jdbcRepository.createJob(job1);
        jdbcRepository.updateJobData(jobId, jobData(jobDataAfterUpdate));

        final List<Job> jobs = jdbcRepository.findJobsLockedTo(workerId).collect(toList());
        assertThat(jobs.size(), is(1));
        assertThat(jobs.get(0).getJobData(), is(jobData(jobDataAfterUpdate)));

    }

    @Test
    public void shouldUpdateNextTask() {

        final UUID jobId = randomUUID();
        final String nextTaskBeforeUpdate = "Next Task Before Update";
        final String nextTaskAfterUpdate = "Next Task After Update";

        final ZonedDateTime nextTaskStartTimeBeforeUpdate = new UtcClock().now().minusHours(2);
        final ZonedDateTime nextTaskStartTimeAfterUpdate = new UtcClock().now();

        final Optional<UUID> workerId = of(randomUUID());
        final Job job1 = new Job(jobId, jobData(JOB_DATA_JSON), nextTaskBeforeUpdate, nextTaskStartTimeBeforeUpdate, workerId, of(now()), empty());

        jdbcRepository.createJob(job1);
        jdbcRepository.updateNextTaskDetails(jobId, nextTaskAfterUpdate, toSqlTimestamp(nextTaskStartTimeAfterUpdate));

        final List<Job> jobs = jdbcRepository.findJobsLockedTo(workerId.get()).collect(Collectors.toList());
        assertThat(jobs.size(), is(1));
        assertThat(jobs.get(0).getNextTask(), is(nextTaskAfterUpdate));
        assertThat(jobs.get(0).getNextTaskStartTime(), is(nextTaskStartTimeAfterUpdate));
    }

    @Test
    public void shouldLockJobsToWorker() throws SQLException {
        createJobs(10);
        final UUID workerId = randomUUID();

        jdbcRepository.lockJobsFor(workerId, 4);

        final List<Job> jobs = jdbcRepository.findJobsLockedTo(workerId).collect(toList());
        assertThat(jobs.size(), is(4));
    }

    @Test
    public void shouldFindLockedJobsToWorker() {
        final UUID jobId = randomUUID();
        final Optional<UUID> worker = of(randomUUID());

        final Job job = new Job(jobId, jobData(JOB_DATA_JSON), "nextTask", now(), worker, of(now()), empty());

        jdbcRepository.createJob(job);

        final UUID jobId2 = randomUUID();

        final Job job2 = new Job(jobId2, jobData(JOB_DATA_JSON), "nextTask", now(), empty(), empty(), empty());

        jdbcRepository.createJob(job2);

        jdbcRepository.lockJobsFor(worker.get(), 10);

        final List<Job> jobs = jdbcRepository.findJobsLockedTo(worker.get()).collect(toList());
        assertThat(jobs.size(), is(2));

        assertThat(jobs.get(0).getWorkerId(), is(worker));

        assertThat(jobs.get(1).getWorkerId(), is(worker));
    }

    @Test
    public void shouldReleaseJob() {
        final UUID jobId1 = randomUUID();
        final Optional<UUID> workerId = of(randomUUID());

        final Job job1 = new Job(jobId1, jobData(JOB_DATA_JSON), "nextTask", now(), workerId, of(now()), empty());
        jdbcRepository.createJob(job1);
        final UUID jobId2 = randomUUID();

        final Job job2 = new Job(jobId2, jobData(JOB_DATA_JSON), "nextTask", now(), workerId, of(now()), empty());
        jdbcRepository.createJob(job2);
        jdbcRepository.releaseJob(jobId1);

        final List<Job> jobs = jdbcRepository.findJobsLockedTo(workerId.get()).collect(toList());
        assertThat(jobs.size(), is(1));

        assertThat(jobs.get(0).getWorkerId(), is(workerId));
        assertThat(jobs.get(0).getJobId(), is(jobId2));

        final int jobsCount = jobsCount();
        assertThat(jobsCount, is(2));
    }

    @Test
    public void shouldDeleteJob() {

        final UUID jobId1 = randomUUID();
        final Optional<UUID> workerId = of(randomUUID());
        final Job job1 = new Job(jobId1, jobData(JOB_DATA_JSON), "nextTask", now(), workerId, of(now()), empty());

        jdbcRepository.createJob(job1);

        final UUID jobId2 = randomUUID();
        final Job job2 = new Job(jobId2, jobData(JOB_DATA_JSON), "nextTask", now(), workerId, of(now()), empty());
        jdbcRepository.createJob(job2);
        jdbcRepository.deleteJob(jobId1);

        final List<Job> jobs = jdbcRepository.findJobsLockedTo(workerId.get()).collect(toList());
        assertThat(jobs.size(), is(1));
        assertThat(jobs.get(0).getWorkerId().get(), is(workerId.get()));
        assertThat(jobs.get(0).getJobId(), is(jobId2));

        final int jobsCount = jobsCount();
        assertThat(jobsCount, is(1));
    }

    @Test(expected = JdbcRepositoryException.class)
    public void shouldThrowJdbcRepositoryExceptionWhenCreating() throws SQLException {
        final JdbcRepositoryHelper mockDatasource = mock(JdbcRepositoryHelper.class);
        when(mockDatasource.preparedStatementWrapperOf(any(), any())).thenThrow(SQLException.class);
        jdbcRepository.jdbcRepositoryHelper = mockDatasource;
        jdbcRepository.createJob(mock(Job.class));
    }

    @Test(expected = JdbcRepositoryException.class)
    public void shouldThrowJdbcRepositoryExceptionWhenDeleting() throws SQLException {
        final JdbcRepositoryHelper mockDatasource = mock(JdbcRepositoryHelper.class);
        when(mockDatasource.preparedStatementWrapperOf(any(), any())).thenThrow(SQLException.class);
        jdbcRepository.jdbcRepositoryHelper = mockDatasource;
        jdbcRepository.deleteJob(randomUUID());
    }

    @Test(expected = JdbcRepositoryException.class)
    public void shouldThrowJdbcRepositoryExceptionWhenUpdatingJobData() throws SQLException {
        final JdbcRepositoryHelper mockDatasource = mock(JdbcRepositoryHelper.class);
        when(mockDatasource.preparedStatementWrapperOf(any(), any())).thenThrow(SQLException.class);
        jdbcRepository.jdbcRepositoryHelper = mockDatasource;
        jdbcRepository.updateJobData(randomUUID(), mock(JsonObject.class));
    }

    @Test(expected = JdbcRepositoryException.class)
    public void shouldThrowJdbcRepositoryExceptionWhenUpdatingNextTaskDetails() throws SQLException {
        final JdbcRepositoryHelper mockDatasource = mock(JdbcRepositoryHelper.class);
        when(mockDatasource.preparedStatementWrapperOf(any(), any())).thenThrow(SQLException.class);
        jdbcRepository.jdbcRepositoryHelper = mockDatasource;
        jdbcRepository.updateNextTaskDetails(randomUUID(), "string", mock(Timestamp.class));
    }

    @Test(expected = JdbcRepositoryException.class)
    public void shouldThrowJdbcRepositoryExceptionWhenLocingJobs() throws SQLException {
        final JdbcRepositoryHelper mockDatasource = mock(JdbcRepositoryHelper.class);
        when(mockDatasource.preparedStatementWrapperOf(any(), any())).thenThrow(SQLException.class);
        jdbcRepository.jdbcRepositoryHelper = mockDatasource;
        jdbcRepository.lockJobsFor(randomUUID(), 2);
    }

    @Test(expected = JdbcRepositoryException.class)
    public void shouldThrowJdbcRepositoryExceptionWheoFindingJobsLockedTo() throws SQLException {
        final JdbcRepositoryHelper mockDatasource = mock(JdbcRepositoryHelper.class);
        when(mockDatasource.preparedStatementWrapperOf(any(), any())).thenThrow(SQLException.class);
        jdbcRepository.jdbcRepositoryHelper = mockDatasource;
        jdbcRepository.findJobsLockedTo(randomUUID());
    }

    @Test(expected = JdbcRepositoryException.class)
    public void shouldThrowJdbcRepositoryExceptionWhenReleaseingJob() throws SQLException {
        final JdbcRepositoryHelper mockDatasource = mock(JdbcRepositoryHelper.class);
        when(mockDatasource.preparedStatementWrapperOf(any(), any())).thenThrow(SQLException.class);
        jdbcRepository.jdbcRepositoryHelper = mockDatasource;
        jdbcRepository.releaseJob(randomUUID());
    }

    private void createJobs(final int count) {
        int i = 0;
        while (i < count) {

            final Job job = new Job(randomUUID(), jobData(JOB_DATA_JSON), "nextTask", now(), empty(), empty(), empty());
            jdbcRepository.createJob(job);
            i++;

        }
    }

    private int jobsCount() {
        int jobsCount = 0;
        try {
            final PreparedStatementWrapper ps = jdbcRepository.jdbcRepositoryHelper.preparedStatementWrapperOf(jdbcRepository.dataSource, JOBS_COUNT);
            final ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                jobsCount = rs.getInt(1);
            }
        } catch (SQLException e) {
            throw new JdbcRepositoryException(format("Exception while retrieving jobs count"), e);
        }
        return jobsCount;
    }

    private JsonObject jobData(final String json) {
        return createReader(new StringReader(json)).readObject();
    }
}