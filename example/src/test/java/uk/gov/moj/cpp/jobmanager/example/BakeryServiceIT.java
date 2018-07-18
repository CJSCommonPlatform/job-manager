package uk.gov.moj.cpp.jobmanager.example;


import static uk.gov.moj.cpp.jobmanager.example.util.OpenEjbConfigurationBuilder.createOpenEjbConfigurationBuilder;


//import static uk.gov.justice.services.core.h2.OpenEjbConfigurationBuilder.createOpenEjbConfigurationBuilder;

import uk.gov.justice.services.common.configuration.JndiBasedServiceContextNameProvider;
import uk.gov.justice.services.common.converter.JsonObjectToObjectConverter;
import uk.gov.justice.services.common.converter.ObjectToJsonObjectConverter;
import uk.gov.justice.services.common.converter.jackson.ObjectMapperProducer;
import uk.gov.justice.services.core.cdi.LoggerProducer;
import uk.gov.justice.services.jdbc.persistence.JdbcRepositoryHelper;
import uk.gov.moj.cpp.jobmanager.example.task.BakeCakeTask;
import uk.gov.moj.cpp.jobmanager.example.task.FillCakeTinTask;
import uk.gov.moj.cpp.jobmanager.example.task.GetIngredientsTask;
import uk.gov.moj.cpp.jobmanager.example.task.GetUtensilsTask;
import uk.gov.moj.cpp.jobmanager.example.task.JobUtil;
import uk.gov.moj.cpp.jobmanager.example.task.MixIngredientsTask;
import uk.gov.moj.cpp.jobmanager.example.task.SliceAndEatCakeTask;
import uk.gov.moj.cpp.jobmanager.example.task.SwitchOvenOnTask;
import uk.gov.moj.cpp.jobmanager.example.util.OpenEjbJobJdbcRepository;
import uk.gov.moj.cpp.jobmanager.example.util.PropertiesFileValueProducer;
import uk.gov.moj.cpp.jobstore.api.JobService;
import uk.gov.moj.cpp.jobstore.api.task.ExecutableTask;
import uk.gov.moj.cpp.jobstore.persistence.AnsiJobSqlProvider;
import uk.gov.moj.cpp.jobstore.persistence.InitialContextProducer;
import uk.gov.moj.cpp.jobstore.persistence.JdbcJobStoreDataSourceProvider;
import uk.gov.moj.cpp.jobstore.persistence.JobRepository;
import uk.gov.moj.cpp.jobstore.persistence.JobSqlProvider;
import uk.gov.moj.cpp.task.execution.JobExecutor;
import uk.gov.moj.cpp.task.extension.TaskRegistry;

import java.util.Properties;

import javax.annotation.Resource;
import javax.inject.Inject;
import javax.naming.InitialContext;
import javax.sql.DataSource;

import liquibase.Liquibase;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.apache.commons.logging.LogFactory;
import org.apache.openejb.jee.WebApp;
import org.apache.openejb.jee.jpa.unit.Persistence;
import org.apache.openejb.jee.jpa.unit.PersistenceUnit;
import org.apache.openejb.junit.ApplicationComposer;
import org.apache.openejb.testing.Application;
import org.apache.openejb.testing.Classes;
import org.apache.openejb.testing.Configuration;
import org.apache.openejb.testing.Module;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(ApplicationComposer.class)
public class BakeryServiceIT {

    @Inject
    private OpenEjbJobJdbcRepository repository;

    @Inject
    private BakeryService bakeryService;

    @Resource(name = "openejb/Resource/jobStore")
    private DataSource dataSource;

    @Module
    @Classes(cdi = true, value = {
            JobService.class,
            JobRepository.class,
            TaskRegistry.class,
            JobExecutor.class,
            SwitchOvenOnTask.class,
            JobUtil.class,
            ExecutableTask.class,
            GetIngredientsTask.class,
            GetUtensilsTask.class,
            MixIngredientsTask.class,
            FillCakeTinTask.class,
            BakeCakeTask.class,
            SliceAndEatCakeTask.class,
            JdbcJobStoreDataSourceProvider.class,
            JdbcRepositoryHelper.class,
            JobSqlProvider.class,
            LoggerProducer.class,
            OpenEjbJobJdbcRepository.class,
            JndiBasedServiceContextNameProvider.class,
            PropertiesFileValueProducer.class,
            BakeryService.class,
            ObjectToJsonObjectConverter.class,
            JsonObjectToObjectConverter.class,
            LogFactory.class,
            Persistence.class,
            PersistenceUnit.class,
            ObjectMapperProducer.class,
            InitialContextProducer.class
    },
            cdiAlternatives = {AnsiJobSqlProvider.class}
    )

    public WebApp war() {
        return new WebApp()
                .contextRoot("bakeryservice-test")
                .addServlet("ServiceApp", Application.class.getName());
    }


    @Configuration
    public Properties configuration() {
        Properties props =  createOpenEjbConfigurationBuilder()
                .addInitialContext()
                .addHttpEjbPort(8080)
                .addh2ViewStore()
                .build();

        return props;

    }

    @Before
    public void setup() throws Exception {
        final InitialContext initialContext = new InitialContext();
        initialContext.bind("java:/app/BakeryServiceIT/DS.jobstore", dataSource);
        initJobStoreDatabase();
    }

    public void initJobStoreDatabase() throws Exception {

        final Liquibase eventStoreLiquibase = new Liquibase("liquibase/jobstore-db-changelog.xml",
                new ClassLoaderResourceAccessor(), new JdbcConnection(dataSource.getConnection()));

        eventStoreLiquibase.update("");

    }

    @Test
    public void shouldMakeACake() {

        bakeryService.makeCake();
        repository.waitForAllJobsToBeProcessed();
    }


}