package com.wuwei.see.runer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.Timer;
import java.util.TimerTask;

@Component
public class DosomethingRunner implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(DosomethingRunner.class);

    @Override
    public void run(ApplicationArguments applicationArguments) throws Exception {

        logger.info("=============do something=================");

        doSomethingTimer();
    }

    public static void doSomethingTimer(){

        Timer timer = new Timer();
        timer.schedule(new TimerTask() {
            int counter = 0;
            @Override
            public void run() {
                logger.info("=============just do it =================" + counter++);
            }
        }, 0, 3000);
    }
}