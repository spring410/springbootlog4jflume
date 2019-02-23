package com.wuwei.see.runer;

import com.wuwei.see.appender.FindAsyncLog4j2Appender;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.AppenderRef;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;



@Component
public class FindLog4jInitializer implements ApplicationRunner {

    Logger logger = LoggerFactory.getLogger(FindLog4jInitializer.class);

    //only for test
    String flumeHost = "10.211.55.5:8888";
    String pattern = "%d [${app}] [%class{1.}.%method:%line] [%level] - %m%n";
    String loggerName = "to-flume-test";
    boolean bOutAppLog = true;

    @Override
    public void run(ApplicationArguments applicationArguments) throws Exception {

        logger.info("===============run to config log flume append=================");

        String appenderName = "async_flume_test";
        LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        Configuration config = ctx.getConfiguration();

        Layout layout = PatternLayout.newBuilder().withConfiguration(config).withPattern(pattern).build();


        FindAsyncLog4j2Appender appender = FindAsyncLog4j2Appender
                .createAppender( false, 0, 1024*10, appenderName, flumeHost,
                         6000L, null,"testApp", layout);
        appender.start();
        config.addAppender(appender);
        AppenderRef ref = AppenderRef.createAppenderRef(appenderName, null, null);
        AppenderRef[] refs = new AppenderRef[]{ref};

        LoggerConfig loggerConfig = LoggerConfig.createLogger(false, Level.INFO, loggerName,
                "true", refs, null, config, null);
        loggerConfig.addAppender(appender, null, null);
        config.addLogger(loggerName, loggerConfig);

        if (bOutAppLog) {
            LoggerConfig rootLoggerConfig = ctx.getRootLogger().get();
            rootLoggerConfig.addAppender(appender, null, null);
        }
        ctx.updateLoggers();
        logger.info("completed to init FlumeAppender");
    }
}
