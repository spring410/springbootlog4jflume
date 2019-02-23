package com.wuwei.see.web.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Created by user .
 */

@RestController
@RequestMapping(value = "/v1/debug")
public class TestController {

    private static final Logger logger = LoggerFactory.getLogger(TestController.class);


    @RequestMapping(value = "/test")
    public String justSayhi(@RequestParam(value = "marketId", required = false) String marketId) {

        logger.info("just to say hi marketId=" + marketId);

        return ("ok");
    }



}
